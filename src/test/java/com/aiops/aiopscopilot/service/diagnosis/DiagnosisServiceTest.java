package com.aiops.aiopscopilot.service.diagnosis;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.OpsAlertReporter;
import com.aiops.aiopscopilot.service.SnapshotCollector;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DiagnosisService 纯单元测试：不启动 Spring 容器；
 * worker 通过 start()/stop() 手动控制，ChatClient 用动态代理伪造整条 fluent 链。
 */
class DiagnosisServiceTest {

    private DiagnosisService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    private DiagnosisService newService(ChatClient chatClient, SnapshotCollector collector) {
        AuditLogger audit = new AuditLogger();
        return new DiagnosisService(chatClient, collector, new OpsAlertReporter(audit), audit);
    }

    private DiagnosisService newService(ChatClient chatClient, SnapshotCollector collector,
                                        Duration workerTimeout) {
        AuditLogger audit = new AuditLogger();
        return new DiagnosisService(chatClient, collector, new OpsAlertReporter(audit), audit, workerTimeout);
    }

    /** 同指纹自动升级：PENDING 在途去重，第二次被拒 */
    @Test
    void escalatedSameFingerprintIsDeduplicated() {
        service = newService(null, null);
        Map<String, Object> snap = Map.of("cpuUsage", 0.95);

        DiagnosisTask first = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "CRITICAL|DEADLOCK;", snap, "first");
        DiagnosisTask second = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "CRITICAL|DEADLOCK;", snap, "second");

        assertNotNull(first);
        assertEquals(DiagnosisTask.Status.PENDING, first.status());
        assertNull(second, "同指纹有 PENDING 在途任务，必须拒绝重复升级");
    }

    /** G1：RUNNING 在途同样去重（isDeduplicated 对 PENDING/RUNNING 两个状态都要拦截） */
    @Test
    void escalatedWhileSameFingerprintRunningIsDeduplicated() throws Exception {
        service = newService(null, null);
        Map<String, DiagnosisTask> store = taskStore();
        DiagnosisTask running = DiagnosisTask
                .pending("run1", DiagnosisTask.Trigger.PERSISTENT, "CRITICAL|DEADLOCK;", Map.of(), null)
                .withRunning();
        store.put("run1", running);

        DiagnosisTask rejected = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "CRITICAL|DEADLOCK;", Map.of(), null);
        assertNull(rejected, "同指纹任务 RUNNING 中也必须拒绝");
    }

    /** 不同指纹不受防抖影响 */
    @Test
    void escalatedDifferentFingerprintsAccepted() {
        service = newService(null, null);
        assertNotNull(service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|A;", Map.of(), "a"));
        assertNotNull(service.submitEscalated(
                DiagnosisTask.Trigger.LOW_CONFIDENCE, "FP|B;", Map.of(), "b"));
    }

    /** 队列满（容量 20 且 worker 不启动）：第 21 个被拒 */
    @Test
    void queueFullRejectsNewTask() throws Exception {
        service = newService(null, null);
        int accepted = 0;
        for (int i = 0; i < DiagnosisService.QUEUE_CAPACITY; i++) {
            DiagnosisTask t = service.submitEscalated(
                    DiagnosisTask.Trigger.PERSISTENT, "FP|" + i + ";", Map.of(), null);
            if (t != null) accepted++;
        }
        DiagnosisTask overflow = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|OVERFLOW;", Map.of(), null);

        assertEquals(DiagnosisService.QUEUE_CAPACITY, accepted);
        assertNull(overflow, "有界队列满了必须拒绝，不能让 r1 任务无限堆积");
        // offer 失败必须回滚 tasks.put，被拒任务不能在 Map 里留下永不出队的幽灵条目
        assertEquals(DiagnosisService.QUEUE_CAPACITY, taskStore().size());
    }

    /** G5：手动提交绕过防抖，但绕不过有界队列——队列满时同样拒绝（防止手动接口打满 r1 队列） */
    @Test
    void manualSubmitAlsoRejectedWhenQueueFull() {
        SnapshotCollector collector = mock(SnapshotCollector.class);
        when(collector.collect()).thenReturn(Map.of("cpuUsage", 0.1));
        service = newService(null, collector);

        for (int i = 0; i < DiagnosisService.QUEUE_CAPACITY; i++) {
            assertNotNull(service.submitEscalated(
                    DiagnosisTask.Trigger.PERSISTENT, "FP|Q" + i + ";", Map.of(), null));
        }
        assertNull(service.submitManual("队列已满时手动也应被拒"));
    }

    /** get 查询：存在返回任务，不存在返回 null */
    @Test
    void getReturnsTaskOrNull() {
        service = newService(null, null);
        DiagnosisTask t = service.submitEscalated(
                DiagnosisTask.Trigger.MANUAL, "manual", Map.of(), null);
        assertSame(t, service.get(t.taskId()));
        assertNull(service.get("not-exist"));
    }

    /** worker 成功路径：PENDING → RUNNING → SUCCEEDED，报告内容为 r1 输出，Prompt 带齐上下文（G4） */
    @Test
    void workerRunsTaskToSucceededWithFullPromptContext() throws Exception {
        List<String> capturedPrompts = new ArrayList<>();
        ChatClient fakeClient = fakeChatClient(capturedPrompts,
                new String[]{"# 深度报告\n根因：测试用例"}, null);
        service = newService(fakeClient, null);
        service.start();

        DiagnosisTask task = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|OK;",
                Map.of("cpuUsage", 0.99, "blockedThreads", 2.0), null);

        awaitStatus(task.taskId(), DiagnosisTask.Status.SUCCEEDED, 5000);
        DiagnosisTask done = service.get(task.taskId());
        assertEquals(DiagnosisTask.Status.SUCCEEDED, done.status());
        assertTrue(done.report().contains("测试用例"));
        assertNotNull(done.startedAt());
        assertNotNull(done.finishedAt());

        // G4：Prompt 必须包含快照、trigger 中文标签、空 note 占位符、不可信数据声明、五段式要求
        assertEquals(1, capturedPrompts.size());
        String prompt = capturedPrompts.get(0);
        assertTrue(prompt.contains("\"cpuUsage\":0.99"), "快照应序列化进 Prompt: " + prompt);
        assertTrue(prompt.contains("同一故障持续多个巡检周期未消除"), "PERSISTENT 标签缺失");
        assertTrue(prompt.contains("（无附加说明）"), "note=null 时应有占位符");
        assertTrue(prompt.contains("不可信数据"));
        assertTrue(prompt.contains("【需人工审批】"));
    }

    /** worker 失败路径：模型抛异常 → FAILED 终态且 error 有内容 */
    @Test
    void workerFailureMarksFailed() throws Exception {
        ChatClient fakeClient = fakeChatClient(new ArrayList<>(),
                null, new RuntimeException[]{new RuntimeException("simulated ollama down")});
        service = newService(fakeClient, null);
        service.start();

        DiagnosisTask task = service.submitEscalated(
                DiagnosisTask.Trigger.PARSE_FAIL, "PARSE_FAIL", Map.of(), null);
        awaitStatus(task.taskId(), DiagnosisTask.Status.FAILED, 5000);

        DiagnosisTask done = service.get(task.taskId());
        assertEquals(DiagnosisTask.Status.FAILED, done.status());
        assertTrue(done.error().contains("simulated ollama down"));
    }

    /** G3：前一个任务失败后 worker 不崩，继续消费后续任务（真正验证 keepsRunning） */
    @Test
    void workerContinuesConsumingAfterFailure() throws Exception {
        List<String> prompts = new ArrayList<>();
        Deque<Object> results = new ArrayDeque<>(List.of(
                new RuntimeException("first task must fail"),
                "# 第二个任务的报告"));
        ChatClient fakeClient = fakeChatClient(prompts, results);
        service = newService(fakeClient, null);
        service.start();

        DiagnosisTask first = service.submitEscalated(
                DiagnosisTask.Trigger.PARSE_FAIL, "FP|FAIL;", Map.of(), null);
        DiagnosisTask second = service.submitEscalated(
                DiagnosisTask.Trigger.LOW_CONFIDENCE, "FP|NEXT;", Map.of(), null);

        awaitStatus(first.taskId(), DiagnosisTask.Status.FAILED, 5000);
        awaitStatus(second.taskId(), DiagnosisTask.Status.SUCCEEDED, 5000);
        assertEquals("# 第二个任务的报告", service.get(second.taskId()).report());
    }

    /** 硬超时：模型调用永久挂死时按注入阈值快速 FAILED，不能等默认 15 分钟 */
    @Test
    void workerTimeoutMarksFailedAtConfiguredThreshold() throws Exception {
        Deque<Object> results = new ArrayDeque<>(List.of(new BlockingCall(10_000)));
        ChatClient hangingClient = fakeChatClient(new ArrayList<>(), results);
        service = newService(hangingClient, null, Duration.ofMillis(200));
        service.start();

        long t0 = System.currentTimeMillis();
        DiagnosisTask task = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|HANG;", Map.of(), null);
        awaitStatus(task.taskId(), DiagnosisTask.Status.FAILED, 5000);
        long elapsedMs = System.currentTimeMillis() - t0;

        DiagnosisTask done = service.get(task.taskId());
        assertTrue(done.error().contains("硬超时"), "error 应标明硬超时: " + done.error());
        assertTrue(elapsedMs < 3000,
                "必须按注入的 200ms 阈值超时，实际等了 " + elapsedMs + "ms（说明阈值未生效）");
    }

    /** 超时触发 future.cancel(true) 后 worker 不崩，继续消费队列里的下一个任务并成功 */
    @Test
    void workerContinuesConsumingAfterTimeout() throws Exception {
        Deque<Object> results = new ArrayDeque<>(List.of(
                new BlockingCall(10_000),
                "# 超时后的第二个任务报告"));
        ChatClient fakeClient = fakeChatClient(new ArrayList<>(), results);
        service = newService(fakeClient, null, Duration.ofMillis(200));
        service.start();

        DiagnosisTask first = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|HANG;", Map.of(), null);
        DiagnosisTask second = service.submitEscalated(
                DiagnosisTask.Trigger.LOW_CONFIDENCE, "FP|NEXT;", Map.of(), null);

        awaitStatus(first.taskId(), DiagnosisTask.Status.FAILED, 5000);
        awaitStatus(second.taskId(), DiagnosisTask.Status.SUCCEEDED, 5000);
        assertEquals("# 超时后的第二个任务报告", service.get(second.taskId()).report());
    }

    /** 手动提交不走防抖：同指纹（manual）可连续提交 */
    @Test
    void manualSubmitBypassesDedup() {
        SnapshotCollector collector = mock(SnapshotCollector.class);
        when(collector.collect()).thenReturn(Map.of("cpuUsage", 0.1));
        service = newService(null, collector);

        DiagnosisTask m1 = service.submitManual("第一次");
        DiagnosisTask m2 = service.submitManual("第二次");
        assertNotNull(m1);
        assertNotNull(m2);
        assertNotEquals(m1.taskId(), m2.taskId());
    }

    /** G2：任务完成后 10 分钟窗口内同指纹仍被拦截；窗口过期后放行（FINISHED 不算在途，时间窗兜底） */
    @Test
    void dedupWindowBlocksAfterFinishThenAllowsAfterExpiry() throws Exception {
        ChatClient fakeClient = fakeChatClient(new ArrayList<>(),
                new String[]{"报告"}, null);
        service = newService(fakeClient, null);
        service.start();

        DiagnosisTask first = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|WINDOW;", Map.of(), null);
        awaitStatus(first.taskId(), DiagnosisTask.Status.SUCCEEDED, 5000);

        // 已 FINISHED 但仍在窗口内：拒绝
        assertNull(service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|WINDOW;", Map.of(), null),
                "FINISHED 后 10 分钟窗口内应继续防抖");

        // 把窗口时间拨到 11 分钟前：放行（旧的终态任务仍在 store，但不是在途状态）
        Map<String, Instant> window = lastEscalatedMap();
        window.put("FP|WINDOW;", Instant.now().minusSeconds(11 * 60));
        DiagnosisTask retry = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|WINDOW;", Map.of(), null);
        assertNotNull(retry, "防抖窗口过期后应允许重新升级");
    }

    /** 终态任务超过上限时淘汰一批，回到上限以内 */
    @Test
    @SuppressWarnings("unchecked")
    void finishedTasksArePrunedBeyondLimit() throws Exception {
        service = newService(null, null);
        Map<String, DiagnosisTask> store = taskStore();
        for (int i = 0; i < DiagnosisService.MAX_FINISHED_TASKS + 1; i++) {
            store.put("old" + i, DiagnosisTask
                    .pending("old" + i, DiagnosisTask.Trigger.MANUAL, "manual", Map.of(), null)
                    .withSuccess("report " + i));
        }

        DiagnosisTask fresh = service.submitEscalated(
                DiagnosisTask.Trigger.PERSISTENT, "FP|FRESH;", Map.of(), null);

        assertNotNull(fresh);
        long finishedCount = store.values().stream()
                .filter(t -> t.finishedAt() != null).count();
        assertEquals(DiagnosisService.MAX_FINISHED_TASKS, finishedCount,
                "终态任务应被淘汰到上限以内");
        assertTrue(store.containsKey(fresh.taskId()));
    }

    /** G6：prune 只淘汰终态任务，绝不能误删在途（PENDING/RUNNING）任务 */
    @Test
    @SuppressWarnings("unchecked")
    void pruneNeverEvictsInFlightTasks() throws Exception {
        service = newService(null, null);
        Map<String, DiagnosisTask> store = taskStore();
        for (int i = 0; i < DiagnosisService.MAX_FINISHED_TASKS + 5; i++) {
            store.put("old" + i, DiagnosisTask
                    .pending("old" + i, DiagnosisTask.Trigger.MANUAL, "manual", Map.of(), null)
                    .withSuccess("report " + i));
        }
        // 直接塞两个在途任务到 Map（不入队，避免占用队列容量），prune 后必须仍在
        store.put("p1", DiagnosisTask.pending("p1", DiagnosisTask.Trigger.MANUAL, "FP|P;", Map.of(), null));
        store.put("r1", DiagnosisTask.pending("r1", DiagnosisTask.Trigger.MANUAL, "FP|R;", Map.of(), null).withRunning());

        service.submitEscalated(DiagnosisTask.Trigger.PERSISTENT, "FP|FRESH;", Map.of(), null);

        assertNotNull(store.get("p1"), "PENDING 在途任务不得被淘汰");
        assertEquals(DiagnosisTask.Status.PENDING, store.get("p1").status());
        assertNotNull(store.get("r1"), "RUNNING 在途任务不得被淘汰");
        assertEquals(DiagnosisTask.Status.RUNNING, store.get("r1").status());
    }

    /** start()/stop() 生命周期：worker 正常终止，不挂起调用线程 */
    @Test
    void startStopLifecycleTerminatesWorker() throws Exception {
        service = newService(fakeChatClient(new ArrayList<>(), new String[]{"x"}, null), null);
        service.start();
        service.stop();
        ExecutorService executor = (ExecutorService) field("worker").get(service);
        assertTrue(executor.isTerminated(), "stop 后 worker 线程池应在 5 秒内终止");
    }

    // ==================== 辅助方法 ====================

    private void awaitStatus(String taskId, DiagnosisTask.Status expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DiagnosisTask t = service.get(taskId);
            if (t != null && t.status() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        DiagnosisTask current = service.get(taskId);
        fail("任务 " + taskId + " 未在 " + timeoutMs + "ms 内达到 " + expected
                + "，当前状态=" + (current == null ? "null" : current.status()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, DiagnosisTask> taskStore() throws Exception {
        return (Map<String, DiagnosisTask>) field("tasks").get(service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Instant> lastEscalatedMap() throws Exception {
        return (Map<String, Instant>) field("lastEscalatedAt").get(service);
    }

    private Field field(String name) throws NoSuchFieldException {
        Field f = DiagnosisService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** 固定结果（每次调用返回同一报告 / 抛同一异常）的伪造客户端 */
    private ChatClient fakeChatClient(List<String> capturedPrompts, String[] reports,
                                      RuntimeException[] failures) {
        Deque<Object> results = new ArrayDeque<>();
        if (failures != null) {
            for (RuntimeException f : failures) results.add(f);
        }
        if (reports != null) {
            for (String r : reports) results.add(r);
        }
        return fakeChatClient(capturedPrompts, results);
    }

    /**
     * 伪造 ChatClient fluent 链：prompt()/options()/call() 返回同层代理；
     * user(args) 捕获 Prompt 文本；content() 按序消费 results（String=报告，异常=抛出）。
     */
    private static ChatClient fakeChatClient(List<String> capturedPrompts, Deque<Object> results) {
        FakeChainHandler handler = new FakeChainHandler(capturedPrompts, results);
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(), new Class<?>[]{ChatClient.class}, handler);
    }

    /** content() 结果哨兵：阻塞 sleep 指定毫秒，模拟模型调用挂死 */
    private record BlockingCall(long sleepMillis) {
    }

    private static final class FakeChainHandler implements InvocationHandler {
        private final List<String> capturedPrompts;
        private final Deque<Object> results;

        private FakeChainHandler(List<String> capturedPrompts, Deque<Object> results) {
            this.capturedPrompts = capturedPrompts;
            this.results = results;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "fakeChatClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> true;
                };
            }
            if ("user".equals(method.getName()) && args != null && args.length == 1
                    && args[0] instanceof String prompt) {
                capturedPrompts.add(prompt);
            }
            if ("content".equals(method.getName()) && method.getReturnType() == String.class) {
                Object next = results.isEmpty() ? "" : results.pollFirst();
                if (next instanceof BlockingCall block) {
                    // 模拟 Ollama HTTP 永久挂死；sleep 响应中断（cancel(true)），不会泄漏成僵尸线程
                    try {
                        Thread.sleep(block.sleepMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("挂死调用被中断", ie);
                    }
                    return "woke-up";
                }
                if (next instanceof RuntimeException re) {
                    throw re;
                }
                return next;
            }
            Class<?> rt = method.getReturnType();
            if (rt.isInterface()) {
                return Proxy.newProxyInstance(rt.getClassLoader(), new Class<?>[]{rt}, this);
            }
            return null;
        }
    }
}

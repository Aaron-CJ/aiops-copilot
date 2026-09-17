package com.aiops.aiopscopilot.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入调试接口：AIOps 自治诊断链路的评估测试夹具，全部仅 dev profile 装配。
 * <p>
 * 端点与白皮书 §三故障注入评估体系一一对应（用例、判定关键词与度量口径见
 * docs/aiops-fault-injection-eval.md）：
 * <ul>
 *   <li>{@code /api/debug/deadlock/a} 与 {@code /api/debug/deadlock/b} — 相反锁序的一对业务接口，
 *       并发调用时两个平台 worker 交叉持锁死锁、对应请求永久挂起（真实生产形态：
 *       请求下放 worker 池 + 接口挂起 + BLOCKED 可累积，只能重启恢复）</li>
 *   <li>{@code /api/debug/memory-leak} 与 {@code /api/debug/memory-leak/release} —
 *       保留 N MB 不释放模拟内存泄漏，可释放以观察事件 RESOLVED 流转</li>
 *   <li>{@code /api/debug/slow-request} — 请求内 sleep 模拟慢接口/外部依赖卡死</li>
 *   <li>{@code /api/debug/cpu-spike} — 秒级 CPU 毛刺，验证瞬时尖峰抑制</li>
 *   <li>{@code /api/debug/reset} — 释放可代码恢复的注入（死锁除外）</li>
 * </ul>
 * <p>
 * 评估定位：本组接口验证的是"自动发现 → 自动诊断 → 给处置建议"链路，
 * <b>不包含</b>自动修复/自动重启——重启等写操作按治理层设计必须人工审批执行。
 * <p>
 * 注入后 OpsScheduler 下一轮巡检（最长 60 秒）会发现对应指标异常并给出分级报告。
 * <p>
 * 安全隔离：带 {@link Profile @Profile("dev")}，仅 dev profile 装配此 Bean；
 * 生产（prod）启动时 /api/debug/** 直接 404，杜绝生产环境被注入故障。
 * 若配置了 AIOPS_API_TOKEN，这些端点同其余 /api/** 一样需要鉴权。
 */
@RestController
@Profile("dev")
@RequestMapping("/api/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    /** 两把静态锁：模拟两个业务接口对同一组资源以相反顺序加锁——生产死锁的最典型形态 */
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    /** 拿到第一把锁后的持锁窗口：给对向请求留出获取其第一把锁的时间，并发调用时交叉持锁必然成形 */
    private static final long LOCK_HOLD_MILLIS = 500;

    private static final AtomicLong WORKER_SEQ = new AtomicLong();

    /**
     * 死锁工作线程池（平台线程）：锁竞争在 worker 上执行，请求线程等待 worker 结果——
     * 对应真实系统"请求下放给 @Async/批处理/自定义 executor 工作池"的常见形态。
     * <p>
     * 刻意用平台线程而非虚拟线程：JDK 21 实测，虚拟线程等待 monitor/ReentrantLock 时
     * {@code ThreadMXBean.findDeadlockedThreads()} 不检出、{@code Thread.getAllStackTraces()}
     * （Micrometer 线程状态指标数据源）也不包含虚拟线程——虚拟请求线程上的死锁对
     * blockedThreads 指标与二级诊断均不可观测。平台 worker 上的死锁才是当前 JVM 工具链
     * 能看见的真实场景。daemon 线程保证应用可正常停机（死锁 worker 本身无法恢复，只能重启）。
     */
    private static final ExecutorService DEADLOCK_WORKERS = Executors.newFixedThreadPool(4, r -> {
        Thread t = Thread.ofPlatform().name("deadlock-worker-" + WORKER_SEQ.incrementAndGet()).unstarted(r);
        t.setDaemon(true);
        return t;
    });

    /** 模拟内存泄漏的保留区：分配后持有引用不释放，堆（主要是 Old 区）持续增长 */
    private static final List<byte[]> retainedMemory = new ArrayList<>();

    /** CPU 毛刺代数：每次新注入或 reset 递增，使上一批自旋线程感知代数变化后退出 */
    private static final AtomicLong cpuSpikeGeneration = new AtomicLong();

    /**
     * GET /api/debug/deadlock/a：业务接口形态 A——以 LOCK_A → LOCK_B 顺序加锁（锁竞争在平台 worker 上执行）。
     * <p>
     * 单独调用不会死锁（持锁窗口后正常返回）；与 /deadlock/b 在持锁窗口内
     * <b>并发</b>调用时，两个 worker 线程互相持有对方需要的锁，形成循环等待，
     * 各自的请求线程永远等不到 worker 结果：
     * <pre>
     * workerA（服务请求a）：持有 LOCK_A → 等待 LOCK_B
     * workerB（服务请求b）：持有 LOCK_B → 等待 LOCK_A
     * </pre>
     * 与真实生产场景的对应关系（刻意不做幂等保护——生产代码里不存在这种东西）：
     * <ul>
     *   <li>接口挂起永不返回——生产死锁的第一症状，调用方直观可见</li>
     *   <li>死锁对持锁不放，后续并发调用会耗尽 worker 池剩余线程并阻塞在锁入口，
     *       BLOCKED 线程数持续累积——模拟故障代码被流量反复踩中</li>
     *   <li>恢复只能重启应用（死锁线程无法代码释放，/api/debug/reset 同样无能为力）</li>
     * </ul>
     * 注入方式：并发调用 /deadlock/a 与 /deadlock/b（curl 加 --max-time 避免客户端挂等）。
     * 评估时只注入一对：重复注入会让 blockedThreads 计数漂移产生不同指纹（BLOCKED=N 分桶），
     * 被识别为多个事件。
     */
    @GetMapping("/deadlock/a")
    public String lockOrderA() throws InterruptedException, ExecutionException {
        Future<String> worker = DEADLOCK_WORKERS.submit(() -> {
            synchronized (LOCK_A) {
                log.info("[deadlock/a] {} 已持有 LOCK_A，{}ms 后尝试获取 LOCK_B...",
                        Thread.currentThread().getName(), LOCK_HOLD_MILLIS);
                Thread.sleep(LOCK_HOLD_MILLIS);
                synchronized (LOCK_B) {
                    // 与 /deadlock/b 并发时，上面的锁获取会永久阻塞，根本走不到这里
                    return "完成（未形成死锁）";
                }
            }
        });
        // worker 死锁时请求线程永久等待——真实症状：接口挂起
        return worker.get() + "（未与 /deadlock/b 并发）。并发调用 /deadlock/a 与 /deadlock/b 即注入双锁交叉死锁。";
    }

    /**
     * GET /api/debug/deadlock/b：业务接口形态 B——以 LOCK_B → LOCK_A 顺序加锁（与 /deadlock/a 相反）。
     * 注入语义、真实场景对应关系与恢复方式见 {@link #lockOrderA}。
     */
    @GetMapping("/deadlock/b")
    public String lockOrderB() throws InterruptedException, ExecutionException {
        Future<String> worker = DEADLOCK_WORKERS.submit(() -> {
            synchronized (LOCK_B) {
                log.info("[deadlock/b] {} 已持有 LOCK_B，{}ms 后尝试获取 LOCK_A...",
                        Thread.currentThread().getName(), LOCK_HOLD_MILLIS);
                Thread.sleep(LOCK_HOLD_MILLIS);
                synchronized (LOCK_A) {
                    // 与 /deadlock/a 并发时，上面的锁获取会永久阻塞，根本走不到这里
                    return "完成（未形成死锁）";
                }
            }
        });
        // worker 死锁时请求线程永久等待——真实症状：接口挂起
        return worker.get() + "（未与 /deadlock/a 并发）。并发调用 /deadlock/a 与 /deadlock/b 即注入双锁交叉死锁。";
    }

    /**
     * GET /api/debug/memory-leak?mb=100：分配并保留 N MB 堆内存（模拟内存泄漏）。
     * <p>
     * 可重复调用逐步加压。预期信号链：heapUsage 与 Old 区持续上涨 →
     * gcCountLast5m 上升 → 堆超过 0.95 后巡检报 critical（含代码级兜底）。
     * 用 /api/debug/memory-leak/release 释放后，连续 3 轮 normal 自动 RESOLVED，
     * 无需重启即可完整观察事件生命周期。
     *
     * @param mb 单次保留的 MB 数（1..1024，超出堆上限会分配失败并提示）
     */
    @GetMapping("/memory-leak")
    public String memoryLeak(@RequestParam(defaultValue = "100") int mb) {
        int safeMb = Math.max(1, Math.min(mb, 1024));
        try {
            byte[] chunk = new byte[safeMb * 1024 * 1024];
            synchronized (retainedMemory) {
                retainedMemory.add(chunk);
            }
        } catch (OutOfMemoryError oom) {
            return "分配 " + safeMb + "MB 失败：已超过堆上限，请用更小的 mb 值逐步加压"
                    + "（当前已保留 " + retainedMb() + "MB）。";
        }
        return "已保留 " + safeMb + "MB（累计 " + retainedMb() + "MB）。"
                + "堆使用率与 GC 次数将随轮次上涨，观察结束后调用 "
                + "/api/debug/memory-leak/release 释放。";
    }

    /** GET /api/debug/memory-leak/release：释放全部模拟泄漏内存，用于观察事件 RESOLVED 流转。 */
    @GetMapping("/memory-leak/release")
    public String releaseMemoryLeak() {
        long freedMb;
        synchronized (retainedMemory) {
            freedMb = retainedMb();
            retainedMemory.clear();
        }
        return "已释放 " + freedMb + "MB 模拟泄漏内存。堆使用率将随 GC 回落，"
                + "连续 3 轮巡检 normal 后对应事件自动 RESOLVED。";
    }

    private static long retainedMb() {
        synchronized (retainedMemory) {
            return retainedMemory.stream().mapToLong(b -> b.length).sum() / 1024 / 1024;
        }
    }

    /**
     * GET /api/debug/slow-request?seconds=45：请求内 sleep N 秒（模拟慢接口/外部依赖卡死）。
     * <p>
     * 信号：请求完成后 http_server_requests_seconds_max（TimeWindow 约 2 分钟）被推高；
     * 并发挂起多笔时窗口内完成请求数骤减，QPS 随之下跌。纯延迟信号无硬阈值兜底
     * （设计边界：延迟属语义信号，由 LLM 层关联 maxRequestSeconds 与 QPS 趋势判断），
     * 这正是评估 LLM 语义判断能力的用例。
     *
     * @param seconds 挂起秒数（1..300）
     */
    @GetMapping("/slow-request")
    public String slowRequest(@RequestParam(defaultValue = "45") int seconds) throws InterruptedException {
        int safeSeconds = Math.max(1, Math.min(seconds, 300));
        Thread.sleep(safeSeconds * 1000L);
        return "慢请求完成，耗时 " + safeSeconds + " 秒。maxRequestSeconds 已被推高"
                + "（约维持 2 分钟），巡检应在窗口内识别慢接口信号。";
    }

    /**
     * GET /api/debug/cpu-spike?seconds=5&threads=0：瞬时 CPU 毛刺（threads=0 时取全部处理器核数）。
     * <p>
     * 验证尖峰抑制：毛刺若整体落在两次巡检之间（60 秒间隔），不会被捕获——这是期望行为；
     * 若被单轮巡检捕获（NEW），毛刺消失后连续 3 轮 normal 自动 RESOLVED，不产生持续告警。
     * 重复注入会使上一批自旋线程因代数失效而退出。
     *
     * @param seconds 毛刺持续秒数（1..60）
     * @param threads 自旋线程数（0=处理器核数，上限 64）
     */
    @GetMapping("/cpu-spike")
    public String cpuSpike(@RequestParam(defaultValue = "5") int seconds,
                           @RequestParam(defaultValue = "0") int threads) {
        int safeSeconds = Math.max(1, Math.min(seconds, 60));
        int n = threads <= 0 ? Runtime.getRuntime().availableProcessors() : Math.min(threads, 64);
        long generation = cpuSpikeGeneration.incrementAndGet();
        long deadline = System.nanoTime() + safeSeconds * 1_000_000_000L;
        for (int i = 1; i <= n; i++) {
            Thread.ofVirtual().name("cpu-spike-" + i).start(() -> {
                // 纯自旋占满 ForkJoinPool 载体线程，推高 process_cpu_usage
                while (cpuSpikeGeneration.get() == generation && System.nanoTime() < deadline) {
                }
            });
        }
        return "已启动 " + n + " 个自旋线程，持续 " + safeSeconds + " 秒。"
                + "毛刺落在巡检间隔内则不告警（尖峰抑制）；被单轮捕获则随后自动 RESOLVED。";
    }

    /**
     * GET /api/debug/reset：释放所有可代码恢复的注入（模拟泄漏内存、CPU 毛刺）。
     * 死锁线程无法代码释放——那正是死锁的本质，注入过死锁需重启应用。
     */
    @GetMapping("/reset")
    public String resetInjections() {
        cpuSpikeGeneration.incrementAndGet();
        long freedMb;
        synchronized (retainedMemory) {
            freedMb = retainedMb();
            retainedMemory.clear();
        }
        return "已停止 CPU 毛刺、释放 " + freedMb + "MB 模拟泄漏内存。"
                + "死锁线程不可恢复，注入过死锁需重启应用。";
    }
}

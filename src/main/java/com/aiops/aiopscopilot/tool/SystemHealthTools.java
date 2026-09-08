package com.aiops.aiopscopilot.tool;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.sun.management.OperatingSystemMXBean;

/**
 * 服务器健康检查工具：通过 Spring AI 的 Function Calling 机制
 * 暴露给大模型，由模型在对话中自主决定何时调用。
 * <p>
 * 注意：Spring AI 1.0 早期使用的 {@code @Description} 注解
 * 在 2.0 GA 中已移除，统一改为 {@link Tool @Tool(description = "...")}。
 */
@Component
public class SystemHealthTools {

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * 获取服务器 CPU 占用率与内存剩余。
     * 由 opsAgentClient（/api/agent/ops 交互问答）注册为可调用工具，
     * 用户提问"服务器健康"等运维问题时模型自动调用本方法获取真实指标，避免幻觉。
     * 注意：巡检用的 qwenChatClient 刻意不挂工具——巡检数据由调度器预拉后直接塞 Prompt。
     */
    @Tool(description = "获取当前服务器的 CPU 占用率（百分比）和剩余可用内存（字节与 MB）。"
            + "当用户询问服务器健康状态、CPU 使用率、内存剩余等运维问题时调用此工具。")
    public Map<String, Object> getServerHealth() {
        // getCpuLoad() 在 JVM 启动后首次调用可能返回 -1（尚未完成采样），按 0 处理避免误导模型
        double cpuLoad = osBean.getCpuLoad();
        if (cpuLoad < 0) {
            cpuLoad = 0;
        }
        long freeBytes = osBean.getFreeMemorySize();
        long totalBytes = osBean.getTotalMemorySize();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cpuUsagePercent", Math.round(cpuLoad * 1000) / 10.0);  // 保留 1 位小数
        result.put("freeMemoryBytes", freeBytes);
        result.put("totalMemoryBytes", totalBytes);
        result.put("freeMemoryMB", freeBytes / 1024 / 1024);
        result.put("totalMemoryMB", totalBytes / 1024 / 1024);
        return result;
    }

    /**
     * 死锁检测：基于 {@link ThreadMXBean#findDeadlockedThreads()} 检测 JVM 内死锁。
     * <p>
     * 选型决策：用 {@code ThreadMXBean} 而非外部 {@code jstack} 进程——
     * <ul>
     *   <li>纯 Java API，零外部依赖、零进程 spawn，跨平台无差别</li>
     *   <li>JVM 内调用微秒级，对巡检链路零延迟影响</li>
     *   <li>{@code findDeadlockedThreads()} 同时覆盖 {@code synchronized} 块死锁和
     *       {@link java.util.concurrent.locks.ReentrantLock} 死锁，
     *       比 {@code findMonitorDeadlockedThreads()}（仅 synchronized）覆盖更广</li>
     *   <li>项目自身的死锁测试（{@code /api/debug/deadlock}）使用 synchronized 块，本方法完美命中</li>
     * </ul>
     * <p>
     * 双重身份：
     * <ul>
     *   <li>被动：{@link Tool @Tool} 注解让 {@code opsAgentClient} 在交互问答时自主调用</li>
     *   <li>主动：{@link com.aiops.aiopscopilot.service.OpsScheduler} 在 BLOCKED 线程数 &gt; 0 时直接调用本方法
     *       作为"二级诊断"，把死锁线程详情塞进 Prompt，让模型能精准定位死锁而非泛泛说"可能存在资源竞争"</li>
     * </ul>
     */
    @Tool(description = "检测当前 JVM 是否存在死锁。当巡检发现 BLOCKED 线程数 > 0 或用户询问死锁、线程阻塞、应用假死等问题时调用。"
            + "返回死锁检测结果：deadlockDetected (boolean)、deadlockedThreadCount (int)、deadlockedThreads (列表，含线程名/状态/等待的锁/锁持有者/栈帧)。")
    public Map<String, Object> detectDeadlock() {
        ThreadMXBean tmb = ManagementFactory.getThreadMXBean();
        // findDeadlockedThreads() Java 6+ 引入，覆盖 synchronized + ReentrantLock；
        // 返回 null 表示无死锁检测能力（极少见），空数组表示无死锁
        long[] ids = tmb.findDeadlockedThreads();

        Map<String, Object> result = new LinkedHashMap<>();
        if (ids == null || ids.length == 0) {
            result.put("deadlockDetected", false);
            result.put("deadlockedThreadCount", 0);
            return result;
        }

        result.put("deadlockDetected", true);
        result.put("deadlockedThreadCount", ids.length);

        List<Map<String, Object>> threads = new ArrayList<>(ids.length);
        for (long id : ids) {
            // 第二个参数 maxDepth：取栈顶 8 帧，足以定位 Controller/Service 方法，又不会让 Prompt 膨胀
            ThreadInfo info = tmb.getThreadInfo(id, 8);
            if (info == null) continue;

            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("threadId", id);
            thread.put("threadName", info.getThreadName());
            thread.put("threadState", info.getThreadState().name());
            thread.put("waitingOnLock", info.getLockName());
            thread.put("lockOwnerId", info.getLockOwnerId());
            thread.put("lockOwnerName", info.getLockOwnerName());
            thread.put("topStackFrames", formatStack(info.getStackTrace()));
            threads.add(thread);
        }
        result.put("deadlockedThreads", threads);
        return result;
    }

    /** 格式化栈帧：取栈顶 5 帧，用 {@code <-} 连接（最顶在前，表示阻塞点） */
    private String formatStack(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) return "N/A";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(stack.length, 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(stack[i].toString());
        }
        return sb.toString();
    }
}

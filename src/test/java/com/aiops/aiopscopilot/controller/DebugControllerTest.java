package com.aiops.aiopscopilot.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DebugController} 注入端点的行为测试（直接 new，不起 Spring 容器）。
 * <p>
 * 与 {@link DebugControllerWiringTest} 互补：后者锁"dev profile + 显式开关"的装配门控，
 * 本类锁端点自身的可观测行为。故意不测 deadlock/slow-request：前者会永久挂起真实线程、
 * 后者按设计 sleep 数十秒，属于演练手册里的人工验证项，不适合进秒级单测集。
 */
class DebugControllerTest {

    /**
     * cpu-spike 的入参钳制与代数终止：seconds 超上限钳到 60、threads 精确取 1；
     * 自旋循环体（含 Thread.onSpinWait 提示）真正跑起来后，reset 递增代数让线程退出，
     * 避免测试 JVM 留下 60 秒的后台自旋线程。
     */
    @Test
    void cpuSpikeClampsSecondsAndResetStopsSpinningThreads() throws InterruptedException {
        DebugController controller = new DebugController();
        String reply = controller.cpuSpike(999, 1);
        try {
            assertTrue(reply.contains("已启动 1 个自旋线程"), reply);
            assertTrue(reply.contains("持续 60 秒"), "seconds 超上限应钳到 60: " + reply);
            // 给虚拟线程一点时间进入 while 自旋，确保循环体被执行到（不依赖具体调度结果）
            Thread.sleep(50);
        } finally {
            String reset = controller.resetInjections();
            assertTrue(reset.contains("已停止 CPU 毛刺"), reset);
        }
    }
}

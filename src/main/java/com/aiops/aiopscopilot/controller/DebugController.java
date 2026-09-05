package com.aiops.aiopscopilot.controller;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入调试接口：故意制造一个经典的"双锁交叉持有"死锁，用于练习 Arthas 线程诊断。
 * <p>
 * 死锁原理（教科书级 Case）：
 * <pre>
 * 线程1：持有 LOCK_A → 等待 LOCK_B
 * 线程2：持有 LOCK_B → 等待 LOCK_A
 * </pre>
 * 两线程互相等待对方释放锁，形成循环等待（Circular Wait），永远阻塞。
 * <p>
 * 诊断方式：Arthas 挂载后执行 thread 命令，输出末尾会自动列出
 * "Found one Java-level deadlock" 及互相等待的线程对。
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    /** 两把静态锁：跨请求复用，避免每次调用产生新的死锁线程 */
    private static final Object LOCK_A = new Object();
    private static final Object LOCK_B = new Object();

    /** 幂等标记：死锁线程已存在时，重复调用接口不再制造第二对死锁 */
    private volatile boolean deadlockCreated = false;

    /**
     * GET /api/debug/deadlock：触发后立即返回，
     * 两个后台线程（deadlock-thread-1/2）进入死锁状态，可随时用 Arthas 诊断。
     */
    @GetMapping("/deadlock")
    public String createDeadlock() throws InterruptedException {
        if (deadlockCreated) {
            return "死锁已存在，无需重复触发，直接用 Arthas thread 命令诊断。";
        }
        deadlockCreated = true;

        // 发令枪：保证两个线程同时起步，让"交叉持锁"时序 100% 确定性复现
        CountDownLatch startGate = new CountDownLatch(1);

        Thread thread1 = new Thread(() -> {
            try {
                startGate.await();
                synchronized (LOCK_A) {
                    log.warn("[死锁线程1] 已持有 LOCK_A，休眠 500ms 后尝试获取 LOCK_B...");
                    Thread.sleep(500); // 给线程2足够时间先持有 LOCK_B，形成交叉
                    synchronized (LOCK_B) {
                        log.warn("[死锁线程1] 不可能走到这里");
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "deadlock-thread-1");

        Thread thread2 = new Thread(() -> {
            try {
                startGate.await();
                synchronized (LOCK_B) {
                    log.warn("[死锁线程2] 已持有 LOCK_B，休眠 500ms 后尝试获取 LOCK_A...");
                    Thread.sleep(500);
                    synchronized (LOCK_A) {
                        log.warn("[死锁线程2] 不可能走到这里");
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "deadlock-thread-2");

        thread1.start();
        thread2.start();
        startGate.countDown();

        // 最多等 1.5 秒，确保死锁已形成后再返回
        thread1.join(1500);
        thread2.join(1500);

        return "已制造 2 个死锁线程：deadlock-thread-1 与 deadlock-thread-2，"
                + "现在可以用 Arthas 的 thread 命令抓取。";
    }
}

package com.aiops.aiopscopilot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对比虚拟线程与传统固定大小线程池在处理大量 I/O 阻塞任务时的性能差异。
 */
class VirtualThreadVsThreadPoolBenchmarkTest {

	private static final int TASK_COUNT = 10_000;
	private static final int SLEEP_MILLIS = 100;
	/** 传统线程池大小，模拟生产环境中有限的平台线程数 */
	private static final int FIXED_POOL_SIZE = 200;

	@Test
	void compareVirtualThreadsAndTraditionalThreadPool() throws InterruptedException {
		long virtualThreadTimeMs = runWithVirtualThreads();
		long threadPoolTimeMs = runWithFixedThreadPool();

		System.out.println("========== 并发任务耗时对比 ==========");
		System.out.printf("任务数量: %d, 每个任务模拟 I/O 阻塞: %dms%n", TASK_COUNT, SLEEP_MILLIS);
		System.out.printf("传统线程池大小: %d%n", FIXED_POOL_SIZE);
		System.out.printf("虚拟线程总耗时: %d ms%n", virtualThreadTimeMs);
		System.out.printf("传统线程池总耗时: %d ms%n", threadPoolTimeMs);
		System.out.printf("虚拟线程相对传统线程池加速比: %.2fx%n",
				(double) threadPoolTimeMs / virtualThreadTimeMs);
		System.out.println("======================================");

		assertTrue(virtualThreadTimeMs < threadPoolTimeMs,
				"虚拟线程处理 I/O 密集型任务应显著快于有限大小的传统线程池");
	}

	private long runWithVirtualThreads() throws InterruptedException {
		long start = System.nanoTime();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			runTasks(executor);
		}
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private long runWithFixedThreadPool() throws InterruptedException {
		long start = System.nanoTime();
		try (ExecutorService executor = Executors.newFixedThreadPool(FIXED_POOL_SIZE)) {
			runTasks(executor);
		}
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private void runTasks(ExecutorService executor) throws InterruptedException {
		List<Future<?>> futures = new ArrayList<>(TASK_COUNT);
		for (int i = 0; i < TASK_COUNT; i++) {
			futures.add(executor.submit(() -> {
				try {
					Thread.sleep(SLEEP_MILLIS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}));
		}
		for (Future<?> future : futures) {
			try {
				future.get();
			}
			catch (ExecutionException e) {
				throw new RuntimeException(e);
			}
		}
	}

}

package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    /**
     * 多线程并发写入：容量预留与水位判断原子完成，
     * 最终使用量不得超过危险水位，且接受/拒绝计数守恒。
     */
    @Test
    void concurrentWritesNeverExceedCriticalLevel() throws Exception {
        WaterLevelThresholds thresholds =
                WaterLevelThresholds.ofBytes(500, 800, 1000, 10);
        CapacityGuard guard = new CapacityGuard(thresholds);
        int threads = 16;
        int writesPerThread = 200;
        long writeSize = 10;
        AtomicLong accepted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int w = 0; w < writesPerThread; w++) {
                        if (guard.tryWrite(writeSize).isAccepted()) {
                            accepted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 核心不变量：使用量绝不突破危险水位
        assertThat(guard.currentUsageBytes())
                .isLessThanOrEqualTo(thresholds.criticalBytes());
        // 每次写入 10 字节：接受数 * 10 == 最终使用量（无丢失、无重复计费）
        assertThat(accepted.get() * writeSize).isEqualTo(guard.currentUsageBytes());
        // 写满到只读水位后其余全部被明确拒绝
        assertThat(guard.currentUsageBytes()).isEqualTo(thresholds.readOnlyBytes());
        assertThat(rejected.get()).isPositive();
        assertThat(guard.stats().rejectedWriteCount()).isEqualTo(rejected.get());
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
    }

    /**
     * 大粒度写入并发竞争：多个线程同时请求大块容量，
     * 只有不越过危险水位的请求被接受，且接受结果互不超卖。
     */
    @Test
    void concurrentLargeWritesAreNotOversold() throws Exception {
        WaterLevelThresholds thresholds =
                WaterLevelThresholds.ofBytes(100, 200, 300, 10);
        CapacityGuard guard = new CapacityGuard(thresholds);
        int threads = 8;
        AtomicLong accepted = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (guard.tryWrite(250).isAccepted()) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 250+250 > 300：至多一个线程成功，绝不突破危险水位
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(guard.currentUsageBytes()).isEqualTo(250);
        assertThat(guard.currentUsageBytes())
                .isLessThanOrEqualTo(thresholds.criticalBytes());
    }

    /**
     * 读写混合并发：写入与释放同时进行，使用量始终保持在合法区间。
     */
    @Test
    void concurrentWritesAndFreesStayWithinBounds() throws Exception {
        WaterLevelThresholds thresholds =
                WaterLevelThresholds.ofBytes(500, 800, 1000, 10);
        CapacityGuard guard = new CapacityGuard(thresholds);
        int threads = 8;
        int iterations = 500;

        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int w = 0; w < iterations; w++) {
                        guard.tryWrite(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int w = 0; w < iterations; w++) {
                        guard.free(10);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(guard.currentUsageBytes()).isBetween(0L, thresholds.criticalBytes());
    }
}

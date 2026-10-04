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

/** 并发写入：无论多少线程竞争，总用量永不突破危险水位硬上限。 */
class ConcurrencyTest {

    private static final long CAPACITY = 1_000_000;
    private static final long CRITICAL_BYTES = 950_000; // 0.95 * CAPACITY

    private static CapacityConfig config() {
        return new CapacityConfig(CAPACITY, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void concurrentWritesNeverExceedCriticalWatermark() throws Exception {
        CapacityGuard guard = new CapacityGuard(config());
        int threads = 32;
        int writesPerThread = 2_000;
        long writeSize = 100; // 总需求 6.4MB，远超容量，必然触发降级与硬上限

        AtomicLong accepted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        AtomicLong maxObserved = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        try {
                            guard.write(writeSize);
                            accepted.incrementAndGet();
                        } catch (StorageDegradedException e) {
                            rejected.incrementAndGet();
                        }
                        long used = guard.usedBytes();
                        maxObserved.accumulateAndGet(used, Math::max);
                        assertThat(used).isLessThanOrEqualTo(CRITICAL_BYTES);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 核心不变量：任何线程在任何时刻观察到的用量都不超过危险水位。
        assertThat(maxObserved.get()).isLessThanOrEqualTo(CRITICAL_BYTES);
        assertThat(guard.usedBytes()).isLessThanOrEqualTo(CRITICAL_BYTES);

        // 每次写入要么成功要么被明确拒绝，账目必须完全对得上。
        assertThat(accepted.get() + rejected.get()).isEqualTo((long) threads * writesPerThread);
        assertThat(guard.usedBytes()).isEqualTo(accepted.get() * writeSize);
        assertThat(guard.stats().rejectedWrites()).isEqualTo(rejected.get());

        // 容量被打满到危险水位附近，组件处于降级态而不是崩溃。
        assertThat(guard.currentLevel()).isIn(WaterLevel.READ_ONLY, WaterLevel.CRITICAL);
        assertThat(guard.usedBytes()).isGreaterThanOrEqualTo(850_000);
    }

    @Test
    void concurrentWriteAndFreeStayConsistent() throws Exception {
        CapacityGuard guard = new CapacityGuard(config());
        int threads = 16;
        int iterations = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            boolean writer = t % 2 == 0;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        if (writer) {
                            guard.tryWrite(64);
                        } else {
                            // 只释放自己确认存在的容量：保守地小步释放。
                            if (guard.usedBytes() >= 64) {
                                guard.free(64);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(guard.usedBytes()).isBetween(0L, CRITICAL_BYTES);
        // 统计快照自身一致。
        assertThat(guard.stats().usedBytes()).isEqualTo(guard.usedBytes());
    }
}

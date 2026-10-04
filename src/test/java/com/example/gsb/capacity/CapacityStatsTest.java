package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 统计：各水位持续时间、拒绝次数、回收次数与当前使用量。 */
class CapacityStatsTest {

    private static CapacityConfig config() {
        return new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void tracksDurationPerLevelRejectionsAndReclaims() {
        MutableClock clock = new MutableClock(1_000_000);
        CapacityGuard guard = new CapacityGuard(config(), clock);

        clock.advanceMillis(1_000); // NORMAL 停留 1000ms

        guard.write(700);
        clock.advanceMillis(2_000); // WARNING 停留 2000ms

        guard.write(150);
        clock.advanceMillis(3_000); // READ_ONLY 停留 3000ms
        try {
            guard.write(1);
        } catch (StorageDegradedException ignored) {
        }
        try {
            guard.write(1);
        } catch (StorageDegradedException ignored) {
        }

        guard.free(850);
        clock.advanceMillis(0);
        guard.write(950);
        clock.advanceMillis(500); // CRITICAL 停留 500ms

        CapacityStats stats = guard.stats();

        assertThat(stats.levelDurations().get(WaterLevel.NORMAL)).isEqualTo(Duration.ofMillis(1_000));
        assertThat(stats.levelDurations().get(WaterLevel.WARNING)).isEqualTo(Duration.ofMillis(2_000));
        assertThat(stats.levelDurations().get(WaterLevel.READ_ONLY)).isEqualTo(Duration.ofMillis(3_000));
        assertThat(stats.levelDurations().get(WaterLevel.CRITICAL)).isEqualTo(Duration.ofMillis(500));
        assertThat(stats.rejectedWrites()).isEqualTo(2);
        assertThat(stats.reclaimTriggers()).isEqualTo(1);
        assertThat(stats.reclaimFailures()).isZero();
        assertThat(stats.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(stats.usedBytes()).isEqualTo(950);
        assertThat(stats.capacityBytes()).isEqualTo(1000);
    }

    @Test
    void accumulatesRepeatedVisitsToSameLevel() {
        MutableClock clock = new MutableClock(0);
        CapacityGuard guard = new CapacityGuard(config(), clock);

        guard.write(850);            // -> READ_ONLY
        clock.advanceMillis(100);
        guard.free(850);             // -> NORMAL
        clock.advanceMillis(200);
        guard.write(850);            // -> READ_ONLY
        clock.advanceMillis(300);

        CapacityStats stats = guard.stats();
        assertThat(stats.levelDurations().get(WaterLevel.READ_ONLY)).isEqualTo(Duration.ofMillis(400));
        assertThat(stats.levelDurations().get(WaterLevel.NORMAL)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void snapshotIsImmutableAndCurrentUsageStaysFresh() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(700);

        CapacityStats snapshot = guard.stats();
        assertThat(snapshot.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThat(snapshot.usedBytes()).isEqualTo(700);

        guard.free(700);
        CapacityStats next = guard.stats();
        assertThat(next.currentLevel()).isEqualTo(WaterLevel.NORMAL);
        assertThat(snapshot.currentLevel()).isEqualTo(WaterLevel.WARNING); // 旧快照不受影响
    }
}

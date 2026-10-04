package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CapacityStatsTest {

    @Test
    void tracksTimeSpentInEachLevel() {
        MutableTicker ticker = new MutableTicker();
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10), ticker, null);

        ticker.advanceMillis(1000);
        guard.recordExternalUsage(150);

        ticker.advanceMillis(500);
        guard.recordExternalUsage(100);

        ticker.advanceMillis(250);
        guard.recordExternalUsage(100);

        ticker.advanceMillis(100);
        CapacityStats stats = guard.stats();

        assertThat(stats.timeIn(WaterLevel.NORMAL)).isEqualTo(Duration.ofSeconds(1));
        assertThat(stats.timeIn(WaterLevel.WARNING))
                .isEqualTo(Duration.ofMillis(500));
        assertThat(stats.timeIn(WaterLevel.READ_ONLY))
                .isEqualTo(Duration.ofMillis(250));
        // 当前水位的进行时时长也计入
        assertThat(stats.timeIn(WaterLevel.CRITICAL))
                .isEqualTo(Duration.ofMillis(100));
        assertThat(stats.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(stats.currentUsageBytes()).isEqualTo(350);
    }

    @Test
    void accumulatesDurationsAcrossMultipleVisits() {
        MutableTicker ticker = new MutableTicker();
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10), ticker, null);

        guard.recordExternalUsage(150);
        ticker.advanceMillis(300);
        guard.free(100);
        ticker.advanceMillis(700);
        guard.recordExternalUsage(150);
        ticker.advanceMillis(200);

        CapacityStats stats = guard.stats();
        assertThat(stats.timeIn(WaterLevel.WARNING))
                .isEqualTo(Duration.ofMillis(300));
        assertThat(stats.timeIn(WaterLevel.NORMAL))
                .isEqualTo(Duration.ofMillis(700));
        assertThat(stats.timeIn(WaterLevel.READ_ONLY))
                .isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void tracksRejectionsCleanupsAndUsage() {
        MutableTicker ticker = new MutableTicker();
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10), ticker, () -> 0L);

        guard.recordExternalUsage(350);
        guard.tryWrite(1);
        guard.tryWrite(2);

        CapacityStats stats = guard.stats();
        assertThat(stats.rejectedWriteCount()).isEqualTo(2);
        assertThat(stats.cleanupTriggerCount()).isEqualTo(1);
        assertThat(stats.currentUsageBytes()).isEqualTo(350);
        assertThat(stats.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(stats.timePerLevel()).containsKeys(WaterLevel.values());
    }
}

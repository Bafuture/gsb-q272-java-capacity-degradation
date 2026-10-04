package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CleanupCallbackTest {

    private static WaterLevelThresholds thresholds() {
        return WaterLevelThresholds.ofBytes(100, 200, 300, 10);
    }

    @Test
    void triggersCleanupOnEnteringCriticalAndAppliesFreedBytes() {
        AtomicInteger invocations = new AtomicInteger();
        CapacityGuard guard = new CapacityGuard(thresholds(), Ticker.system(), () -> {
            invocations.incrementAndGet();
            return 120;
        });

        guard.recordExternalUsage(320);

        assertThat(invocations).hasValue(1);
        assertThat(guard.currentUsageBytes()).isEqualTo(200);
        assertThat(guard.stats().cleanupTriggerCount()).isEqualTo(1);
        // 释放 120 后用量 200，低于危险恢复阈值 290，但高于只读恢复阈值 190
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
    }

    @Test
    void cleanupFailureDoesNotAffectDegradationState() {
        CapacityGuard guard = new CapacityGuard(thresholds(), Ticker.system(), () -> {
            throw new RuntimeException("boom");
        });

        guard.recordExternalUsage(350);

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(guard.currentUsageBytes()).isEqualTo(350);
        assertThat(guard.stats().cleanupTriggerCount()).isEqualTo(1);

        WriteResult result = guard.tryWrite(1);
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.level()).isEqualTo(WaterLevel.CRITICAL);
    }

    @Test
    void cleanupTriggeredOncePerCriticalEntry() {
        AtomicInteger invocations = new AtomicInteger();
        CapacityGuard guard = new CapacityGuard(thresholds(), Ticker.system(),
                invocations::incrementAndGet);

        guard.recordExternalUsage(300);
        guard.recordExternalUsage(50);
        guard.recordExternalUsage(50);
        assertThat(invocations).hasValue(1);

        guard.free(200);
        guard.recordExternalUsage(300);
        assertThat(invocations).hasValue(2);
        assertThat(guard.stats().cleanupTriggerCount()).isEqualTo(2);
    }

    @Test
    void negativeFreedBytesFromCallbackAreIgnored() {
        CapacityGuard guard = new CapacityGuard(thresholds(), Ticker.system(), () -> -50);

        guard.recordExternalUsage(320);

        assertThat(guard.currentUsageBytes()).isEqualTo(320);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
    }
}

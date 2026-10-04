package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadOnlyRejectionTest {

    @Test
    void rejectsWritesWithLevelAndReasonWhenReadOnly() {
        CapacityGuard guard =
                new CapacityGuard(WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        guard.tryWrite(200);

        WriteResult result = guard.tryWrite(10);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.level()).isEqualTo(WaterLevel.READ_ONLY);
        assertThat(result.reason()).hasValueSatisfying(
                reason -> assertThat(reason).contains("READ_ONLY"));
        assertThat(result.currentUsageBytes()).isEqualTo(200);
        assertThat(guard.currentUsageBytes()).isEqualTo(200);
        assertThat(guard.stats().rejectedWriteCount()).isEqualTo(1);
    }

    @Test
    void rejectsWritesWhenCritical() {
        CapacityGuard guard =
                new CapacityGuard(WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        guard.recordExternalUsage(350);

        WriteResult result = guard.tryWrite(1);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.level()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(result.reason()).isPresent();
    }

    @Test
    void rejectsWriteThatWouldExceedCriticalEvenBelowReadOnly() {
        CapacityGuard guard =
                new CapacityGuard(WaterLevelThresholds.ofBytes(100, 200, 300, 10));

        WriteResult result = guard.tryWrite(400);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.reason()).hasValueSatisfying(
                reason -> assertThat(reason).contains("critical"));
        assertThat(guard.currentUsageBytes()).isZero();
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);
    }

    @Test
    void acceptedWriteReportsLevelAndUsage() {
        CapacityGuard guard =
                new CapacityGuard(WaterLevelThresholds.ofBytes(100, 200, 300, 10));

        WriteResult result = guard.tryWrite(150);

        assertThat(result.isAccepted()).isTrue();
        assertThat(result.level()).isEqualTo(WaterLevel.WARNING);
        assertThat(result.currentUsageBytes()).isEqualTo(150);
        assertThat(result.reason()).isEmpty();
    }
}

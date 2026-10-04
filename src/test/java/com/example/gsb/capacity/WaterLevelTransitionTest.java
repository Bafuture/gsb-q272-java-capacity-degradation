package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class WaterLevelTransitionTest {

    private static WaterLevelThresholds thresholds() {
        return WaterLevelThresholds.ofBytes(100, 200, 300, 10);
    }

    @Test
    void escalatesThroughAllLevelsAsUsageGrows() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);

        guard.tryWrite(99);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);

        guard.tryWrite(1);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.tryWrite(99);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.tryWrite(1);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
    }

    @Test
    void externalUsageCanJumpDirectlyToCritical() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.recordExternalUsage(350);

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).from()).isEqualTo(WaterLevel.NORMAL);
        assertThat(events.get(0).to()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(events.get(0).isEscalation()).isTrue();
    }

    @Test
    void emitsOneEventPerTransitionInOrder() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.tryWrite(100);
        guard.tryWrite(100);
        guard.recordExternalUsage(100);

        assertThat(events).extracting(WaterLevelChangeEvent::to)
                .containsExactly(WaterLevel.WARNING, WaterLevel.READ_ONLY,
                        WaterLevel.CRITICAL);
        assertThat(events).extracting(WaterLevelChangeEvent::from)
                .containsExactly(WaterLevel.NORMAL, WaterLevel.WARNING,
                        WaterLevel.READ_ONLY);
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThatThrownBy(() -> WaterLevelThresholds.ofBytes(200, 100, 300, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaterLevelThresholds.ofBytes(100, 200, 200, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaterLevelThresholds.ofBytes(100, 200, 300, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WaterLevelThresholds.ofBytes(100, 200, 300, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fractionBasedThresholdsAreConvertedToBytes() {
        WaterLevelThresholds t =
                WaterLevelThresholds.ofFractions(1000, 0.5, 0.8, 0.95, 0.05);
        assertThat(t.warningBytes()).isEqualTo(500);
        assertThat(t.readOnlyBytes()).isEqualTo(800);
        assertThat(t.criticalBytes()).isEqualTo(950);
        assertThat(t.hysteresisBytes()).isEqualTo(50);
    }
}

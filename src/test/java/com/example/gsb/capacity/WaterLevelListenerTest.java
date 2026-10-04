package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class WaterLevelListenerTest {

    @Test
    void notifiesListenersOnEveryLevelChange() {
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.recordExternalUsage(150);
        guard.recordExternalUsage(100);
        guard.free(200);

        assertThat(events).extracting(WaterLevelChangeEvent::to)
                .containsExactly(WaterLevel.WARNING, WaterLevel.READ_ONLY,
                        WaterLevel.NORMAL);
        assertThat(events.get(0).usageBytes()).isEqualTo(150);
        assertThat(events.get(2).isEscalation()).isFalse();
    }

    @Test
    void noEventWhenLevelDoesNotChange() {
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.tryWrite(10);
        guard.tryWrite(10);
        guard.free(5);

        assertThat(events).isEmpty();
    }

    @Test
    void throwingListenerDoesNotAffectMainFlowOrOtherListeners() {
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(event -> {
            throw new RuntimeException("listener boom");
        });
        guard.addListener(events::add);

        WriteResult result = guard.tryWrite(150);

        assertThat(result.isAccepted()).isTrue();
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).to()).isEqualTo(WaterLevel.WARNING);
    }

    @Test
    void removedListenerStopsReceivingEvents() {
        CapacityGuard guard = new CapacityGuard(
                WaterLevelThresholds.ofBytes(100, 200, 300, 10));
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        WaterLevelListener listener = events::add;
        guard.addListener(listener);
        guard.recordExternalUsage(150);
        guard.removeListener(listener);
        guard.recordExternalUsage(100);

        assertThat(events).hasSize(1);
    }
}

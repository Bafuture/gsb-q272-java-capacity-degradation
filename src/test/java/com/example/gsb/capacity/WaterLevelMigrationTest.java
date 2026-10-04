package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** 水位迁移与事件通知。 */
class WaterLevelMigrationTest {

    /** 容量 1000：警告 700 / 只读 850 / 危险 950 / 滞回 50。 */
    private static CapacityConfig config() {
        return new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void escalatesThroughAllLevelsAsUsageGrows() {
        CapacityGuard guard = new CapacityGuard(config());

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);

        guard.write(700);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.write(150);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        // 只读状态下无法再写，直接释放后重填到危险水位。
        guard.free(850);
        guard.write(950);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
    }

    @Test
    void notifiesListenersInOrderOnEveryTransition() {
        CapacityGuard guard = new CapacityGuard(config());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.write(700);   // NORMAL -> WARNING
        guard.write(150);   // WARNING -> READ_ONLY
        guard.free(850);    // READ_ONLY -> NORMAL（回落也是一次迁移事件）
        guard.write(950);   // NORMAL -> CRITICAL

        assertThat(events).extracting(WaterLevelChangeEvent::previous)
                .containsExactly(WaterLevel.NORMAL, WaterLevel.WARNING,
                        WaterLevel.READ_ONLY, WaterLevel.NORMAL);
        assertThat(events).extracting(WaterLevelChangeEvent::current)
                .containsExactly(WaterLevel.WARNING, WaterLevel.READ_ONLY,
                        WaterLevel.NORMAL, WaterLevel.CRITICAL);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.capacityBytes()).isEqualTo(1000);
            assertThat(e.occurredAt()).isNotNull();
        });
        assertThat(events.get(2).isEscalation()).isFalse();
        assertThat(events.get(3).isEscalation()).isTrue();
    }

    @Test
    void listenerExceptionDoesNotAffectMainFlowOrOtherListeners() {
        CapacityGuard guard = new CapacityGuard(config());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(e -> {
            throw new RuntimeException("boom from listener");
        });
        guard.addListener(events::add);

        guard.write(700);

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).current()).isEqualTo(WaterLevel.WARNING);
    }

    @Test
    void rejectsInvalidConfig() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CapacityConfig(1000, 0.9, 0.8, 0.95, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CapacityConfig(0, 0.7, 0.85, 0.95, 0.05))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 回落恢复与抖动抑制：水位下降采用滞回（hysteresis）恢复——
 * 使用量必须跌破“当前等级阈值 − 滞回区间”才降级，避免阈值附近
 * 小幅波动导致状态在可写/只读间来回翻转。
 *
 * <p>配置：容量 1000，只读阈值 850，滞回 50 → 恢复点为 800。
 */
class RecoveryHysteresisTest {

    private static CapacityConfig config() {
        return new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void recoversToWritableAfterUsageDropsBelowRecoveryPoint() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(850);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        assertThatThrownBy(() -> guard.write(1)).isInstanceOf(StorageDegradedException.class);

        guard.free(100); // 750 <= 800，跌破恢复点
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.write(50); // 已恢复可写
        assertThat(guard.usedBytes()).isEqualTo(800);
    }

    @Test
    void hysteresisSuppressesFlappingAroundThreshold() {
        CapacityGuard guard = new CapacityGuard(config());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.write(850); // -> READ_ONLY（1 次迁移）
        // 在滞回区间内反复小幅释放：849..801 均 > 恢复点 800，状态必须保持 READ_ONLY。
        for (int i = 0; i < 10; i++) {
            guard.free(1);
        }
        assertThat(guard.usedBytes()).isEqualTo(840);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        assertThatThrownBy(() -> guard.write(1)).isInstanceOf(StorageDegradedException.class);

        // 若无滞回，840 < 850 早已恢复可写；滞回将其牢牢钉在 READ_ONLY。
        // 整个抖动过程只有最初一次迁移事件。
        assertThat(events).hasSize(1);
        assertThat(events.get(0).current()).isEqualTo(WaterLevel.READ_ONLY);

        // 跌破恢复点后一次性恢复。
        guard.free(41); // 799 <= 800
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThat(events).hasSize(2);
    }

    @Test
    void oscillationAcrossThresholdWithoutHysteresisWouldFlap() {
        // 对照组：滞回为 0 时，阈值附近 ±1 的波动会导致状态来回翻转。
        CapacityGuard noHysteresis = new CapacityGuard(
                new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.0));
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        noHysteresis.addListener(events::add);

        noHysteresis.write(850); // -> READ_ONLY
        noHysteresis.free(1);    // 849 -> 立即恢复 WARNING
        noHysteresis.write(1);   // 850 -> 又回 READ_ONLY
        noHysteresis.free(1);    // 849 -> 又恢复

        assertThat(events).hasSize(4); // 明显抖动
    }

    @Test
    void recoveryFromCriticalAlsoUsesHysteresis() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(950);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);

        guard.free(30); // 920 > 900（恢复点），仍为 CRITICAL
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);

        guard.free(30); // 890 <= 900，恢复；levelFor(890)=READ_ONLY
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        guard.free(100); // 790 <= 800，恢复；levelFor(790)=WARNING
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);
    }

    @Test
    void freeNeverDrivesUsageNegative() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(100);
        guard.free(1000); // 超额释放按 0 兜底
        assertThat(guard.usedBytes()).isZero();
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);
    }
}

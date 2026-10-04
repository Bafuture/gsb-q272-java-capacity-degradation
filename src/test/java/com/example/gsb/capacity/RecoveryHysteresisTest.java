package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class RecoveryHysteresisTest {

    private static WaterLevelThresholds thresholds() {
        return WaterLevelThresholds.ofBytes(100, 200, 300, 20);
    }

    @Test
    void recoveryRequiresDroppingBelowThresholdMinusHysteresis() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        guard.recordExternalUsage(250);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        guard.free(50);
        assertThat(guard.currentUsageBytes()).isEqualTo(200);
        // 200 未跌破 200-20=180，仍保持只读
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        guard.free(30);
        assertThat(guard.currentUsageBytes()).isEqualTo(170);
        // 170 < 180 回落；但 170 >= 100-20=80，停留在警告
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.free(80);
        assertThat(guard.currentUsageBytes()).isEqualTo(90);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.free(20);
        assertThat(guard.currentUsageBytes()).isEqualTo(70);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);
    }

    @Test
    void oscillationWithinHysteresisBandCausesNoFlapping() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        List<WaterLevelChangeEvent> events = new CopyOnWriteArrayList<>();
        guard.addListener(events::add);

        guard.recordExternalUsage(210);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        int eventsAfterEscalation = events.size();

        // 用量在只读阈值上下抖动，但始终在滞回带内：不得反复切换状态
        guard.free(10);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        guard.recordExternalUsage(10);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        guard.free(19);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);
        guard.recordExternalUsage(19);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        assertThat(events).hasSize(eventsAfterEscalation);

        // 真正跌破恢复阈值后才平滑恢复，且恢复后立即可以写入
        guard.free(40);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        WriteResult result = guard.tryWrite(5);
        assertThat(result.isAccepted()).isTrue();
    }

    @Test
    void recoversToWritableAfterFreeAndStaysThere() {
        CapacityGuard guard = new CapacityGuard(thresholds());
        guard.recordExternalUsage(350);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);

        guard.free(300);

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.NORMAL);
        WriteResult result = guard.tryWrite(10);
        assertThat(result.isAccepted()).isTrue();
    }
}

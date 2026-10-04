package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 只读降级：写入被明确拒绝，携带水位与原因，绝不静默丢弃。 */
class ReadOnlyRejectionTest {

    private static CapacityConfig config() {
        return new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void rejectsWritesWithLevelAndReasonWhenReadOnly() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(850); // 到达只读水位
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        assertThatThrownBy(() -> guard.write(10))
                .isInstanceOfSatisfying(StorageDegradedException.class, e -> {
                    assertThat(e.level()).isEqualTo(WaterLevel.READ_ONLY);
                    assertThat(e.usedBytes()).isEqualTo(850);
                    assertThat(e.capacityBytes()).isEqualTo(1000);
                    assertThat(e.getMessage()).contains("read-only");
                });

        // 写入未被静默丢弃：用量不变，拒绝计数 +1。
        assertThat(guard.usedBytes()).isEqualTo(850);
        assertThat(guard.stats().rejectedWrites()).isEqualTo(1);
    }

    @Test
    void rejectsWritesThatWouldExceedCriticalHardCap() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(900); // WARNING..READ_ONLY 之间？900 >= 850 -> READ_ONLY

        // 已只读，先验证状态；再构造“状态未及迁移但会越界”的场景：
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.READ_ONLY);

        CapacityGuard fresh = new CapacityGuard(config());
        fresh.write(900); // 900 < 950，成功，但进入 READ_ONLY
        // 直接验证硬上限：释放到 800 恢复可写后，一次写 200 会突破 950。
        fresh.free(200);  // 700 -> WARNING 滞回内？700 <= 850-50=800 -> 恢复；levelFor(700)=WARNING
        assertThat(fresh.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThatThrownBy(() -> fresh.write(300)) // 700+300=1000 > 950
                .isInstanceOfSatisfying(StorageDegradedException.class, e -> {
                    assertThat(e.level()).isEqualTo(WaterLevel.CRITICAL);
                    assertThat(e.getMessage()).contains("critical watermark");
                });
        assertThat(fresh.usedBytes()).isEqualTo(700);
    }

    @Test
    void tryWriteReturnsFalseInsteadOfThrowing() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(850);

        assertThat(guard.tryWrite(1)).isFalse();
        assertThat(guard.usedBytes()).isEqualTo(850);
        assertThat(guard.stats().rejectedWrites()).isEqualTo(1);
    }

    @Test
    void warningLevelStillAcceptsWrites() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.write(700);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);

        guard.write(100); // 警告水位不拦截写入
        assertThat(guard.usedBytes()).isEqualTo(800);
    }
}

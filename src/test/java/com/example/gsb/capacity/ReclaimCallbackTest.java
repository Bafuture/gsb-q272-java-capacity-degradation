package com.example.gsb.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 危险水位清理回调：触发、失败隔离、清理后自动恢复。 */
class ReclaimCallbackTest {

    private static CapacityConfig config() {
        return new CapacityConfig(1000, 0.70, 0.85, 0.95, 0.05);
    }

    @Test
    void triggersReclaimCallbackWhenEnteringCritical() {
        CapacityGuard guard = new CapacityGuard(config());
        AtomicInteger calls = new AtomicInteger();
        guard.addReclaimCallback(g -> calls.incrementAndGet());

        guard.write(949);
        assertThat(calls).hasValue(0); // 未达危险水位不触发

        // 只读状态下无法继续写，释放后重新填满到危险水位。
        guard.free(949);
        guard.write(950);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(calls).hasValue(1);
        assertThat(guard.stats().reclaimTriggers()).isEqualTo(1);
    }

    @Test
    void failingCallbackDoesNotAffectDegradationState() {
        CapacityGuard guard = new CapacityGuard(config());
        guard.addReclaimCallback(g -> {
            throw new RuntimeException("reclaim failed");
        });
        AtomicInteger good = new AtomicInteger();
        guard.addReclaimCallback(g -> good.incrementAndGet());

        guard.write(950);

        // 状态机不受回调异常影响，后续回调仍被执行，失败计入统计。
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(good).hasValue(1);
        assertThat(guard.stats().reclaimTriggers()).isEqualTo(1);
        assertThat(guard.stats().reclaimFailures()).isEqualTo(1);
    }

    @Test
    void reclaimCallbackFreesSpaceAndGuardRecoversAutomatically() {
        CapacityGuard guard = new CapacityGuard(config());
        // 模拟清理过期数据：进入危险水位时释放 200 字节。
        guard.addReclaimCallback(g -> g.free(200));

        guard.write(950); // 触发 CRITICAL -> 回调释放 200 -> 750，低于恢复点 900 -> 回落

        assertThat(guard.usedBytes()).isEqualTo(750);
        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.WARNING);
        assertThat(guard.stats().reclaimTriggers()).isEqualTo(1);

        // 已恢复可写。
        guard.write(50);
        assertThat(guard.usedBytes()).isEqualTo(800);
    }

    @Test
    void reclaimIsNotTriggeredAgainWhileStayingCritical() {
        CapacityGuard guard = new CapacityGuard(config());
        AtomicInteger calls = new AtomicInteger();
        guard.addReclaimCallback(g -> calls.incrementAndGet());

        guard.write(950);
        assertThat(guard.tryWrite(1)).isFalse(); // 已被拒绝（CRITICAL）
        guard.free(10); // 940，滞回区间内（恢复点 900），仍为 CRITICAL
        assertThat(guard.tryWrite(5)).isFalse(); // 仍被拒绝

        assertThat(guard.currentLevel()).isEqualTo(WaterLevel.CRITICAL);
        assertThat(calls).hasValue(1);
    }
}

package com.example.gsb.capacity;

import java.time.Duration;
import java.util.Map;

/**
 * 容量水位统计快照（不可变）。
 *
 * @param currentLevel    快照时的水位等级
 * @param usedBytes       快照时的已用字节数
 * @param capacityBytes   总容量字节数
 * @param levelDurations  各水位累计停留时长（含当前等级截至快照时刻的在途时长）
 * @param rejectedWrites  累计被拒绝的写入次数
 * @param reclaimTriggers 累计触发清理回调的次数（每次进入危险水位计一次）
 * @param reclaimFailures 清理回调抛出异常的次数
 */
public record CapacityStats(
        WaterLevel currentLevel,
        long usedBytes,
        long capacityBytes,
        Map<WaterLevel, Duration> levelDurations,
        long rejectedWrites,
        long reclaimTriggers,
        long reclaimFailures) {

    public CapacityStats {
        levelDurations = Map.copyOf(levelDurations);
    }
}

package com.example.gsb.capacity;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 容量水位统计快照。
 */
public final class CapacityStats {

    private final Map<WaterLevel, Duration> timePerLevel;
    private final long rejectedWriteCount;
    private final long cleanupTriggerCount;
    private final long currentUsageBytes;
    private final WaterLevel currentLevel;

    CapacityStats(Map<WaterLevel, Duration> timePerLevel,
                  long rejectedWriteCount,
                  long cleanupTriggerCount,
                  long currentUsageBytes,
                  WaterLevel currentLevel) {
        this.timePerLevel =
                Collections.unmodifiableMap(new EnumMap<>(timePerLevel));
        this.rejectedWriteCount = rejectedWriteCount;
        this.cleanupTriggerCount = cleanupTriggerCount;
        this.currentUsageBytes = currentUsageBytes;
        this.currentLevel = currentLevel;
    }

    /** 各水位累计持续时间（含当前水位正在进行的时长）。 */
    public Map<WaterLevel, Duration> timePerLevel() {
        return timePerLevel;
    }

    public Duration timeIn(WaterLevel level) {
        return timePerLevel.getOrDefault(level, Duration.ZERO);
    }

    public long rejectedWriteCount() {
        return rejectedWriteCount;
    }

    public long cleanupTriggerCount() {
        return cleanupTriggerCount;
    }

    public long currentUsageBytes() {
        return currentUsageBytes;
    }

    public WaterLevel currentLevel() {
        return currentLevel;
    }

    @Override
    public String toString() {
        return "CapacityStats{level=" + currentLevel
                + ", usage=" + currentUsageBytes
                + ", rejectedWrites=" + rejectedWriteCount
                + ", cleanupTriggers=" + cleanupTriggerCount
                + ", timePerLevel=" + timePerLevel + '}';
    }
}

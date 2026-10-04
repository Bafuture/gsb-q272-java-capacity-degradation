package com.example.gsb.capacity;

import java.util.Optional;

/**
 * 一次写入尝试的结果。被拒绝时不会静默丢弃：必须携带当前水位与拒绝原因。
 */
public final class WriteResult {

    private final boolean accepted;
    private final WaterLevel level;
    private final long currentUsageBytes;
    private final String reason;

    private WriteResult(boolean accepted, WaterLevel level,
                        long currentUsageBytes, String reason) {
        this.accepted = accepted;
        this.level = level;
        this.currentUsageBytes = currentUsageBytes;
        this.reason = reason;
    }

    static WriteResult accepted(WaterLevel level, long currentUsageBytes) {
        return new WriteResult(true, level, currentUsageBytes, null);
    }

    static WriteResult rejected(WaterLevel level, long currentUsageBytes, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("rejection reason must not be blank");
        }
        return new WriteResult(false, level, currentUsageBytes, reason);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public WaterLevel level() {
        return level;
    }

    public long currentUsageBytes() {
        return currentUsageBytes;
    }

    /** 拒绝原因；接受时为空。 */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    @Override
    public String toString() {
        return accepted
                ? "WriteResult{ACCEPTED, level=" + level + ", usage=" + currentUsageBytes + '}'
                : "WriteResult{REJECTED, level=" + level + ", usage=" + currentUsageBytes
                        + ", reason='" + reason + "'}";
    }
}

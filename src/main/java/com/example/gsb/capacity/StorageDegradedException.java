package com.example.gsb.capacity;

/**
 * 写入因水位降级被明确拒绝时抛出。异常携带拒绝时的水位、原因与当前用量，
 * 调用方必须显式处理，组件绝不静默丢弃写入。
 */
public class StorageDegradedException extends RuntimeException {

    private final WaterLevel level;
    private final long usedBytes;
    private final long capacityBytes;

    public StorageDegradedException(WaterLevel level, String reason, long usedBytes, long capacityBytes) {
        super(reason);
        this.level = level;
        this.usedBytes = usedBytes;
        this.capacityBytes = capacityBytes;
    }

    /** 拒绝写入时的水位等级。 */
    public WaterLevel level() {
        return level;
    }

    /** 拒绝写入时的已用字节数。 */
    public long usedBytes() {
        return usedBytes;
    }

    /** 总容量字节数。 */
    public long capacityBytes() {
        return capacityBytes;
    }
}

package com.example.gsb.capacity;

/**
 * 存储容量水位，按严重程度从低到高排列。
 */
public enum WaterLevel {
    /** 容量充足，读写正常。 */
    NORMAL,
    /** 接近上限，可写但建议关注。 */
    WARNING,
    /** 只读降级：新写入被明确拒绝。 */
    READ_ONLY,
    /** 危险水位：触发清理回调，持续拒绝写入。 */
    CRITICAL;

    public int severity() {
        return ordinal();
    }

    public boolean isWritable() {
        return ordinal() < READ_ONLY.ordinal();
    }
}

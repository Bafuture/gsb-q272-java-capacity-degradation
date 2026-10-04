package com.example.gsb.capacity;

/**
 * 容量水位等级，严重程度按 {@link #ordinal()} 递增。
 */
public enum WaterLevel {

    /** 容量充裕，写入不受限。 */
    NORMAL,

    /** 容量偏高，写入不受限，但应关注增长。 */
    WARNING,

    /** 只读降级：新写入被明确拒绝，等待容量回落。 */
    READ_ONLY,

    /** 危险：已接近物理容量上限，写入被拒绝并触发清理回调。 */
    CRITICAL
}

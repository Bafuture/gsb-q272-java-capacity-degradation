package com.example.gsb.capacity;

import java.time.Instant;

/**
 * 水位变化事件，在等级发生迁移时投递给监听器。
 */
public record WaterLevelChangeEvent(
        WaterLevel previous,
        WaterLevel current,
        long usedBytes,
        long capacityBytes,
        Instant occurredAt) {

    /** 是否为水位上升（降级加剧）。 */
    public boolean isEscalation() {
        return current.ordinal() > previous.ordinal();
    }
}

package com.example.gsb.capacity;

/**
 * 水位变化事件。
 *
 * @param from            变化前水位
 * @param to              变化后水位
 * @param usageBytes      变化发生时的使用量
 * @param timestampNanos  事件时间戳（来自注入的 {@link Ticker}）
 */
public record WaterLevelChangeEvent(WaterLevel from,
                                    WaterLevel to,
                                    long usageBytes,
                                    long timestampNanos) {

    public boolean isEscalation() {
        return to.severity() > from.severity();
    }
}

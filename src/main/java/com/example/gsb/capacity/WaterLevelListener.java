package com.example.gsb.capacity;

/**
 * 水位变化监听器。监听器抛出的异常会被 {@link CapacityGuard} 捕获并记录，
 * 不会影响主流程与其他监听器。
 */
@FunctionalInterface
public interface WaterLevelListener {

    void onWaterLevelChange(WaterLevelChangeEvent event);
}

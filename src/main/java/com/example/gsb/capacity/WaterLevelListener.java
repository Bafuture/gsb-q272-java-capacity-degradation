package com.example.gsb.capacity;

/**
 * 水位变化监听器。监听器抛出的任何异常都会被组件捕获并忽略，
 * 不会影响主流程与其他监听器。
 */
@FunctionalInterface
public interface WaterLevelListener {

    void onLevelChange(WaterLevelChangeEvent event);
}

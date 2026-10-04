package com.example.gsb.capacity;

/**
 * 危险水位清理回调。进入 {@link WaterLevel#CRITICAL} 时触发，
 * 典型实现是清理过期数据并通过 {@link CapacityGuard#free(long)} 释放容量。
 *
 * <p>回调抛出的任何异常都会被组件捕获并记录（计入统计），
 * 不会影响降级状态机与其他回调。
 */
@FunctionalInterface
public interface ReclaimCallback {

    void reclaim(CapacityGuard guard);
}

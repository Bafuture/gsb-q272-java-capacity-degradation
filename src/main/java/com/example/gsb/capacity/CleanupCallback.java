package com.example.gsb.capacity;

/**
 * 危险水位清理回调，例如清理过期数据。
 *
 * <p>实现返回本次清理释放的字节数（非负），该数值会被从当前使用量中扣除并
 * 重新评估水位。回调抛出的异常会被捕获，不影响降级状态。
 */
@FunctionalInterface
public interface CleanupCallback {

    /**
     * 执行清理。
     *
     * @return 释放的字节数（非负）
     */
    long cleanup() throws Exception;
}

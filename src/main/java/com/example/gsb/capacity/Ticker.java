package com.example.gsb.capacity;

/**
 * 可注入的时间源，方便测试统计水位持续时间。
 */
@FunctionalInterface
public interface Ticker {

    long nanos();

    static Ticker system() {
        return System::nanoTime;
    }
}

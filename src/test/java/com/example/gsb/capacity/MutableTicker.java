package com.example.gsb.capacity;

import java.util.concurrent.atomic.AtomicLong;

/** 测试用可手动推进的时间源。 */
final class MutableTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long nanos() {
        return nanos.get();
    }

    void advanceMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }
}

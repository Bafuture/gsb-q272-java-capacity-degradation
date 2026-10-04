package com.example.gsb.capacity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/** 测试用可手动推进的时钟。 */
final class MutableClock extends Clock {

    private final AtomicLong millis;

    MutableClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    void advanceMillis(long delta) {
        millis.addAndGet(delta);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}

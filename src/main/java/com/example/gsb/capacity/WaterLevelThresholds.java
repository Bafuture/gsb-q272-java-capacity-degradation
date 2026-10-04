package com.example.gsb.capacity;

import java.util.Objects;

/**
 * 多级水位阈值配置（单位：字节）。
 *
 * <p>四个等级与三个升档阈值：用量达到 {@code warningBytes} 进入警告，
 * 达到 {@code readOnlyBytes} 进入只读，达到 {@code criticalBytes} 进入危险。
 *
 * <p>{@code hysteresisBytes} 为抖动抑制滞回带：降级时用量必须跌破
 * “当前阈值 − hysteresisBytes”才允许回落（见 {@link CapacityGuard}）。
 */
public final class WaterLevelThresholds {

    private final long warningBytes;
    private final long readOnlyBytes;
    private final long criticalBytes;
    private final long hysteresisBytes;

    private WaterLevelThresholds(long warningBytes,
                                 long readOnlyBytes,
                                 long criticalBytes,
                                 long hysteresisBytes) {
        if (warningBytes <= 0 || readOnlyBytes <= warningBytes
                || criticalBytes <= readOnlyBytes) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 < warning < readOnly < critical: "
                            + warningBytes + ", " + readOnlyBytes + ", " + criticalBytes);
        }
        if (hysteresisBytes < 0 || hysteresisBytes >= warningBytes) {
            throw new IllegalArgumentException(
                    "hysteresis must satisfy 0 <= hysteresis < warning: " + hysteresisBytes);
        }
        this.warningBytes = warningBytes;
        this.readOnlyBytes = readOnlyBytes;
        this.criticalBytes = criticalBytes;
        this.hysteresisBytes = hysteresisBytes;
    }

    /**
     * 按绝对字节数配置阈值。
     */
    public static WaterLevelThresholds ofBytes(long warningBytes,
                                               long readOnlyBytes,
                                               long criticalBytes,
                                               long hysteresisBytes) {
        return new WaterLevelThresholds(warningBytes, readOnlyBytes,
                criticalBytes, hysteresisBytes);
    }

    /**
     * 按总容量比例配置阈值（hysteresisFraction 为占总容量的比例）。
     */
    public static WaterLevelThresholds ofFractions(long capacityBytes,
                                                   double warningFraction,
                                                   double readOnlyFraction,
                                                   double criticalFraction,
                                                   double hysteresisFraction) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive: " + capacityBytes);
        }
        double warning = warningFraction;
        if (!(warning > 0 && warning < readOnlyFraction
                && readOnlyFraction < criticalFraction && criticalFraction <= 1d)) {
            throw new IllegalArgumentException(
                    "fractions must satisfy 0 < warning < readOnly < critical <= 1");
        }
        if (!(hysteresisFraction >= 0 && hysteresisFraction < warning)) {
            throw new IllegalArgumentException(
                    "hysteresisFraction must satisfy 0 <= h < warning");
        }
        return ofBytes(
                Math.round(capacityBytes * warningFraction),
                Math.round(capacityBytes * readOnlyFraction),
                Math.round(capacityBytes * criticalFraction),
                Math.round(capacityBytes * hysteresisFraction));
    }

    public long warningBytes() {
        return warningBytes;
    }

    public long readOnlyBytes() {
        return readOnlyBytes;
    }

    public long criticalBytes() {
        return criticalBytes;
    }

    public long hysteresisBytes() {
        return hysteresisBytes;
    }

    /**
     * 升档阈值：用量达到该值即进入对应等级。
     */
    long escalateThreshold(WaterLevel level) {
        switch (level) {
            case WARNING:
                return warningBytes;
            case READ_ONLY:
                return readOnlyBytes;
            case CRITICAL:
                return criticalBytes;
            case NORMAL:
            default:
                throw new IllegalArgumentException("no escalate threshold for " + level);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WaterLevelThresholds that)) {
            return false;
        }
        return warningBytes == that.warningBytes
                && readOnlyBytes == that.readOnlyBytes
                && criticalBytes == that.criticalBytes
                && hysteresisBytes == that.hysteresisBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(warningBytes, readOnlyBytes, criticalBytes, hysteresisBytes);
    }

    @Override
    public String toString() {
        return "WaterLevelThresholds{warning=" + warningBytes
                + ", readOnly=" + readOnlyBytes
                + ", critical=" + criticalBytes
                + ", hysteresis=" + hysteresisBytes + '}';
    }
}

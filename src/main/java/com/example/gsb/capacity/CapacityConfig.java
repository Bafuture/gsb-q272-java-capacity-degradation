package com.example.gsb.capacity;

/**
 * 水位阈值配置。阈值以占总容量的比例表示，必须满足
 * {@code 0 <= warning <= readOnly <= critical <= 1}。
 *
 * <p>{@code hysteresisFraction} 为回落滞回区间：水位下降时，使用量必须低于
 * “当前等级阈值 − 滞回区间” 才会降级恢复，用于抑制用量在阈值附近抖动时
 * 水位状态的来回翻转（flapping）。
 */
public record CapacityConfig(
        long capacityBytes,
        double warningFraction,
        double readOnlyFraction,
        double criticalFraction,
        double hysteresisFraction) {

    public CapacityConfig {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive: " + capacityBytes);
        }
        if (!(0.0 <= warningFraction && warningFraction <= readOnlyFraction
                && readOnlyFraction <= criticalFraction && criticalFraction <= 1.0)) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 0 <= warning <= readOnly <= critical <= 1, got warning="
                            + warningFraction + ", readOnly=" + readOnlyFraction
                            + ", critical=" + criticalFraction);
        }
        if (hysteresisFraction < 0.0 || hysteresisFraction >= 1.0) {
            throw new IllegalArgumentException(
                    "hysteresisFraction must be in [0, 1): " + hysteresisFraction);
        }
    }

    /** 常用默认配置：70% 警告、85% 只读、95% 危险、5% 滞回区间。 */
    public static CapacityConfig ofCapacity(long capacityBytes) {
        return new CapacityConfig(capacityBytes, 0.70, 0.85, 0.95, 0.05);
    }

    long warningBytes() {
        return (long) (capacityBytes * warningFraction);
    }

    long readOnlyBytes() {
        return (long) (capacityBytes * readOnlyFraction);
    }

    long criticalBytes() {
        return (long) (capacityBytes * criticalFraction);
    }

    long hysteresisBytes() {
        return (long) (capacityBytes * hysteresisFraction);
    }
}

package com.example.gsb.capacity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 存储容量水位与只读降级组件（线程安全）。
 *
 * <h3>写入准入</h3>
 * <ul>
 *   <li>水位低于 {@link WaterLevel#READ_ONLY} 时允许写入；</li>
 *   <li>达到只读/危险水位时，{@link #write(long)} 抛出
 *       {@link StorageDegradedException}（携带水位与原因），绝不静默丢弃；</li>
 *   <li>无论状态机视图如何，写入通过 CAS 预留容量，承诺总用量
 *       永不突破危险水位阈值（硬上限）。</li>
 * </ul>
 *
 * <h3>抖动抑制（滞回）</h3>
 * 水位上升立即生效；水位下降采用滞回恢复：使用量必须降至
 * “当前等级阈值 − hysteresisBytes” 以下才会降级恢复，避免用量在阈值
 * 附近小幅波动时状态在可写/只读之间来回翻转。
 *
 * <h3>回调与监听器隔离</h3>
 * 清理回调与水位监听器均在状态锁之外同步执行，抛出的任何异常都会被
 * 捕获并计入统计，不影响降级状态机与主流程。
 */
public final class CapacityGuard {

    private final CapacityConfig config;
    private final Clock clock;

    private final AtomicLong usedBytes = new AtomicLong();
    private final AtomicLong rejectedWrites = new AtomicLong();
    private final AtomicLong reclaimTriggers = new AtomicLong();
    private final AtomicLong reclaimFailures = new AtomicLong();

    private final List<WaterLevelListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ReclaimCallback> reclaimCallbacks = new CopyOnWriteArrayList<>();

    /** 状态机（等级、各等级停留时长）由该锁串行化。 */
    private final Object stateLock = new Object();
    private WaterLevel level = WaterLevel.NORMAL;
    private long levelSinceMillis;
    private final EnumMap<WaterLevel, Long> levelDurationsMillis = new EnumMap<>(WaterLevel.class);

    public CapacityGuard(CapacityConfig config) {
        this(config, Clock.systemUTC());
    }

    public CapacityGuard(CapacityConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.levelSinceMillis = clock.millis();
        for (WaterLevel l : WaterLevel.values()) {
            levelDurationsMillis.put(l, 0L);
        }
    }

    // ------------------------------------------------------------------
    // 写入与释放
    // ------------------------------------------------------------------

    /**
     * 申请写入 {@code bytes} 字节。
     *
     * @throws StorageDegradedException 水位达到只读/危险，或本次写入会突破危险水位硬上限
     */
    public void write(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive: " + bytes);
        }
        while (true) {
            long used = usedBytes.get();
            WaterLevel current = level;
            if (current.ordinal() >= WaterLevel.READ_ONLY.ordinal()) {
                rejectedWrites.incrementAndGet();
                throw new StorageDegradedException(current,
                        "write rejected: store is " + current + " (degraded to read-only)",
                        used, config.capacityBytes());
            }
            long newUsed = used + bytes;
            if (newUsed > config.criticalBytes()) {
                // 硬上限：即使状态机尚未迁移，也绝不突破危险水位。
                rejectedWrites.incrementAndGet();
                throw new StorageDegradedException(WaterLevel.CRITICAL,
                        "write rejected: " + bytes + " bytes would exceed critical watermark ("
                                + config.criticalBytes() + " bytes)",
                        used, config.capacityBytes());
            }
            if (usedBytes.compareAndSet(used, newUsed)) {
                break;
            }
        }
        reevaluate();
    }

    /**
     * 尝试写入，成功返回 {@code true}，被拒绝返回 {@code false}（不抛异常）。
     * 拒绝原因可通过 {@link #currentLevel()} 与 {@link #stats()} 获取。
     */
    public boolean tryWrite(long bytes) {
        try {
            write(bytes);
            return true;
        } catch (StorageDegradedException e) {
            return false;
        }
    }

    /**
     * 释放 {@code bytes} 字节容量（删除/清理数据后调用），并触发水位重估。
     * 释放量超过当前用量时按 0 兜底，不会出现负用量。
     */
    public void free(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive: " + bytes);
        }
        usedBytes.updateAndGet(used -> Math.max(0, used - bytes));
        reevaluate();
    }

    // ------------------------------------------------------------------
    // 水位状态机
    // ------------------------------------------------------------------

    /** 当前水位等级。 */
    public WaterLevel currentLevel() {
        return level;
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public long capacityBytes() {
        return config.capacityBytes();
    }

    /** 按使用量直接映射的水位（不含滞回）。 */
    private WaterLevel levelFor(long used) {
        if (used >= config.criticalBytes()) {
            return WaterLevel.CRITICAL;
        }
        if (used >= config.readOnlyBytes()) {
            return WaterLevel.READ_ONLY;
        }
        if (used >= config.warningBytes()) {
            return WaterLevel.WARNING;
        }
        return WaterLevel.NORMAL;
    }

    /** 当前等级阈值对应的字节数；NORMAL 无阈值，返回 0。 */
    private long thresholdBytesOf(WaterLevel l) {
        return switch (l) {
            case NORMAL -> 0L;
            case WARNING -> config.warningBytes();
            case READ_ONLY -> config.readOnlyBytes();
            case CRITICAL -> config.criticalBytes();
        };
    }

    /**
     * 用量变化后重估水位。上升立即生效；下降需跌破
     * “当前等级阈值 − 滞回区间” 才恢复（抖动抑制）。
     */
    private void reevaluate() {
        WaterLevelChangeEvent event = null;
        boolean triggerReclaim = false;
        synchronized (stateLock) {
            long used = usedBytes.get();
            WaterLevel direct = levelFor(used);
            WaterLevel target = level;
            if (direct.ordinal() > level.ordinal()) {
                target = direct; // 上升立即生效
            } else if (direct.ordinal() < level.ordinal()) {
                long recoveryPoint = thresholdBytesOf(level) - config.hysteresisBytes();
                if (used <= recoveryPoint) {
                    target = direct; // 跌破恢复点才降级，抑制阈值附近抖动
                }
            }
            if (target != level) {
                long now = clock.millis();
                levelDurationsMillis.merge(level, now - levelSinceMillis, Long::sum);
                levelSinceMillis = now;
                WaterLevel previous = level;
                level = target;
                event = new WaterLevelChangeEvent(previous, target, used,
                        config.capacityBytes(), Instant.ofEpochMilli(now));
                triggerReclaim = target == WaterLevel.CRITICAL;
                if (triggerReclaim) {
                    reclaimTriggers.incrementAndGet();
                }
            }
        }
        // 回调与通知在状态锁之外执行，异常全部隔离。
        if (event != null) {
            notifyListeners(event);
        }
        if (triggerReclaim) {
            runReclaimCallbacks();
        }
    }

    private void notifyListeners(WaterLevelChangeEvent event) {
        for (WaterLevelListener listener : listeners) {
            try {
                listener.onLevelChange(event);
            } catch (Throwable ignored) {
                // 监听器异常不得影响主流程与其他监听器。
            }
        }
    }

    private void runReclaimCallbacks() {
        for (ReclaimCallback callback : reclaimCallbacks) {
            try {
                callback.reclaim(this);
            } catch (Throwable t) {
                // 清理失败不得影响降级状态；计入统计便于观测。
                reclaimFailures.incrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------
    // 注册与统计
    // ------------------------------------------------------------------

    public void addListener(WaterLevelListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addReclaimCallback(ReclaimCallback callback) {
        reclaimCallbacks.add(Objects.requireNonNull(callback, "callback"));
    }

    /** 当前统计快照。 */
    public CapacityStats stats() {
        EnumMap<WaterLevel, Duration> durations = new EnumMap<>(WaterLevel.class);
        WaterLevel current;
        synchronized (stateLock) {
            long now = clock.millis();
            for (Map.Entry<WaterLevel, Long> e : levelDurationsMillis.entrySet()) {
                long millis = e.getValue();
                if (e.getKey() == level) {
                    millis += now - levelSinceMillis; // 当前等级的在途时长
                }
                durations.put(e.getKey(), Duration.ofMillis(millis));
            }
            current = level;
        }
        return new CapacityStats(current, usedBytes.get(), config.capacityBytes(),
                durations, rejectedWrites.get(), reclaimTriggers.get(), reclaimFailures.get());
    }

    @Override
    public String toString() {
        return "CapacityGuard{level=" + currentLevel()
                + ", used=" + usedBytes.get() + "/" + config.capacityBytes() + '}';
    }
}

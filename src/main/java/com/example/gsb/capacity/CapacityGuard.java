package com.example.gsb.capacity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 存储容量水位与只读降级组件。
 *
 * <p>核心语义：
 * <ul>
 *   <li>用量达到阈值立即升档（正常 → 警告 → 只读 → 危险）；</li>
 *   <li>达到只读水位后，{@link #tryWrite(long)} 明确拒绝并返回当前水位与原因，
 *       绝不静默丢弃；</li>
 *   <li>进入危险水位时触发一次 {@link CleanupCallback}（如清理过期数据），
 *       回调异常被捕获，不影响降级状态；</li>
 *   <li>降级带滞回（hysteresis）抑制抖动：用量必须跌破
 *       “阈值 − hysteresisBytes”才回落，避免在阈值附近来回震荡导致
 *       只读状态频繁切换；</li>
 *   <li>容量预留与水位判断在同一把锁内原子完成，多线程并发写入
 *       不会突破危险水位；</li>
 *   <li>水位变化通知 {@link WaterLevelListener}，监听器异常被捕获，
 *       不影响主流程与其他监听器。</li>
 * </ul>
 *
 * <p>监听器通知与清理回调均在内部锁之外执行，避免回调重入或慢监听器
 * 阻塞写入路径。
 */
public final class CapacityGuard {

    private static final Logger LOGGER = Logger.getLogger(CapacityGuard.class.getName());

    private final WaterLevelThresholds thresholds;
    private final Ticker ticker;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<WaterLevelListener> listeners = new CopyOnWriteArrayList<>();

    private volatile CleanupCallback cleanupCallback;

    private long usageBytes;
    private WaterLevel level = WaterLevel.NORMAL;

    private long rejectedWriteCount;
    private long cleanupTriggerCount;
    private final EnumMap<WaterLevel, Long> levelNanos = new EnumMap<>(WaterLevel.class);
    private long levelEnteredAtNanos;

    public CapacityGuard(WaterLevelThresholds thresholds) {
        this(thresholds, Ticker.system(), null);
    }

    public CapacityGuard(WaterLevelThresholds thresholds, Ticker ticker,
                         CleanupCallback cleanupCallback) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.cleanupCallback = cleanupCallback;
        this.levelEnteredAtNanos = ticker.nanos();
        for (WaterLevel l : WaterLevel.values()) {
            levelNanos.put(l, 0L);
        }
    }

    public void setCleanupCallback(CleanupCallback cleanupCallback) {
        this.cleanupCallback = cleanupCallback;
    }

    public void addListener(WaterLevelListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(WaterLevelListener listener) {
        listeners.remove(listener);
    }

    /**
     * 尝试预留 {@code bytes} 字节的容量。
     *
     * <p>处于只读或危险水位时立即拒绝；否则若写入后会突破危险水位也拒绝。
     * 拒绝时返回当前水位与原因，使用量不变。
     */
    public WriteResult tryWrite(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive: " + bytes);
        }
        List<WaterLevelChangeEvent> events = new ArrayList<>();
        WriteResult result;
        lock.lock();
        try {
            if (!level.isWritable()) {
                rejectedWriteCount++;
                result = WriteResult.rejected(level, usageBytes,
                        "write rejected: store is " + level
                                + " (read-only degradation active)");
            } else if (usageBytes + bytes > thresholds.criticalBytes()) {
                rejectedWriteCount++;
                result = WriteResult.rejected(level, usageBytes,
                        "write of " + bytes + " bytes rejected: would exceed critical "
                                + "water level (" + thresholds.criticalBytes() + " bytes)");
            } else {
                usageBytes += bytes;
                transitionLocked(events);
                result = WriteResult.accepted(level, usageBytes);
            }
        } finally {
            lock.unlock();
        }
        afterTransition(events);
        return result;
    }

    /**
     * 释放 {@code bytes} 字节的容量（例如删除数据后调用）。
     * 用量回落并满足滞回条件时自动恢复到更低水位。
     */
    public void free(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative: " + bytes);
        }
        if (bytes == 0) {
            return;
        }
        List<WaterLevelChangeEvent> events = new ArrayList<>();
        lock.lock();
        try {
            usageBytes = Math.max(0, usageBytes - bytes);
            transitionLocked(events);
        } finally {
            lock.unlock();
        }
        afterTransition(events);
    }

    /**
     * 记录未经 {@link #tryWrite(long)} 的外部用量变化（可正可负），
     * 例如其他进程/分片的占用。可能触发升档、清理回调或回落恢复。
     */
    public void recordExternalUsage(long deltaBytes) {
        List<WaterLevelChangeEvent> events = new ArrayList<>();
        lock.lock();
        try {
            usageBytes = Math.max(0, usageBytes + deltaBytes);
            transitionLocked(events);
        } finally {
            lock.unlock();
        }
        afterTransition(events);
    }

    public WaterLevel currentLevel() {
        lock.lock();
        try {
            return level;
        } finally {
            lock.unlock();
        }
    }

    public long currentUsageBytes() {
        lock.lock();
        try {
            return usageBytes;
        } finally {
            lock.unlock();
        }
    }

    public WaterLevelThresholds thresholds() {
        return thresholds;
    }

    /**
     * 当前统计快照：各水位持续时间、拒绝写入次数、回收触发次数与当前使用量。
     */
    public CapacityStats stats() {
        lock.lock();
        try {
            EnumMap<WaterLevel, Duration> durations = new EnumMap<>(WaterLevel.class);
            long now = ticker.nanos();
            for (Map.Entry<WaterLevel, Long> entry : levelNanos.entrySet()) {
                long nanos = entry.getValue();
                if (entry.getKey() == level) {
                    nanos += now - levelEnteredAtNanos;
                }
                durations.put(entry.getKey(), Duration.ofNanos(nanos));
            }
            return new CapacityStats(durations, rejectedWriteCount,
                    cleanupTriggerCount, usageBytes, level);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 滞回式水位评估：升档立即生效；降档要求用量跌破
     * “当前等级阈值 − hysteresis”，以此抑制阈值附近的抖动。
     */
    private WaterLevel evaluateLocked(long usage) {
        WaterLevel evaluated = level;
        while (evaluated.severity() < WaterLevel.CRITICAL.severity()) {
            WaterLevel next = WaterLevel.values()[evaluated.severity() + 1];
            if (usage >= thresholds.escalateThreshold(next)) {
                evaluated = next;
            } else {
                break;
            }
        }
        while (evaluated.severity() > WaterLevel.NORMAL.severity()) {
            long recoveryThreshold =
                    thresholds.escalateThreshold(evaluated) - thresholds.hysteresisBytes();
            if (usage < recoveryThreshold) {
                evaluated = WaterLevel.values()[evaluated.severity() - 1];
            } else {
                break;
            }
        }
        return evaluated;
    }

    private void transitionLocked(List<WaterLevelChangeEvent> events) {
        WaterLevel target = evaluateLocked(usageBytes);
        if (target == level) {
            return;
        }
        long now = ticker.nanos();
        levelNanos.merge(level, now - levelEnteredAtNanos, Long::sum);
        WaterLevel from = level;
        level = target;
        levelEnteredAtNanos = now;
        events.add(new WaterLevelChangeEvent(from, target, usageBytes, now));
        if (target == WaterLevel.CRITICAL) {
            cleanupTriggerCount++;
        }
    }

    private void afterTransition(List<WaterLevelChangeEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        boolean enteredCritical = false;
        for (WaterLevelChangeEvent event : events) {
            if (event.to() == WaterLevel.CRITICAL) {
                enteredCritical = true;
            }
            notifyListeners(event);
        }
        if (enteredCritical) {
            runCleanup();
        }
    }

    private void notifyListeners(WaterLevelChangeEvent event) {
        for (WaterLevelListener listener : listeners) {
            try {
                listener.onWaterLevelChange(event);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING,
                        "water level listener threw; main flow unaffected", t);
            }
        }
    }

    private void runCleanup() {
        CleanupCallback callback = cleanupCallback;
        if (callback == null) {
            return;
        }
        try {
            long freed = callback.cleanup();
            if (freed < 0) {
                LOGGER.warning("cleanup callback returned negative freed bytes; ignored");
                return;
            }
            if (freed > 0) {
                free(freed);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                    "cleanup callback failed; degradation state unchanged", t);
        }
    }
}

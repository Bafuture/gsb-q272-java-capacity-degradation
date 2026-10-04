# 存储容量水位与只读降级组件

Pair-wise GSB 标注任务仓库（第 17 批 / 272）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 组件说明

核心类位于 `com.example.gsb.capacity`：

| 类 | 职责 |
|----|------|
| `CapacityGuard` | 核心组件：容量预留、水位评估、降级/恢复、事件与统计 |
| `WaterLevel` | 四级水位：`NORMAL` / `WARNING` / `READ_ONLY` / `CRITICAL` |
| `WaterLevelThresholds` | 阈值配置（字节数或容量比例），含滞回带 `hysteresis` |
| `WriteResult` | 写入结果：接受/拒绝 + 当前水位 + 拒绝原因，绝不静默丢弃 |
| `CleanupCallback` | 危险水位清理回调，返回释放字节数 |
| `WaterLevelListener` / `WaterLevelChangeEvent` | 水位变化事件通知 |
| `CapacityStats` | 统计快照：各水位持续时间、拒绝次数、回收触发次数、当前用量 |

### 关键设计

- **只读降级**：用量达到 `readOnly` 阈值后，`tryWrite` 一律拒绝并返回
  当前水位与原因；即使未到只读水位，单次写入会越过 `critical` 阈值也会被
  原子拒绝，保证任何情况下不突破危险水位。
- **抖动抑制（滞回 hysteresis）**：升档立即生效（用量 ≥ 阈值）；降档要求
  用量跌破「阈值 − hysteresisBytes」才回落。用量在阈值附近小幅波动时水位
  保持不变，避免只读状态频繁切换导致写入方反复成败交替。
- **危险水位清理**：每次进入 `CRITICAL` 触发一次清理回调（锁外执行），
  回调返回的释放字节数会重新评估水位；回调抛异常只记录日志，不影响
  降级状态。
- **并发安全**：容量预留与水位判断在同一把锁内原子完成，多线程并发写入
  不会超卖；监听器通知与清理回调均在锁外执行，慢监听器不阻塞写入路径。
- **事件与统计**：水位变化通知所有监听器，单个监听器异常被捕获、不影响
  主流程与其他监听器；统计含各水位累计持续时间（可注入 `Ticker` 便于
  测试）、拒绝写入次数、回收触发次数与当前使用量。

### 快速示例

```java
WaterLevelThresholds thresholds =
        WaterLevelThresholds.ofFractions(capacityBytes, 0.7, 0.85, 0.95, 0.05);
CapacityGuard guard = new CapacityGuard(thresholds);
guard.setCleanupCallback(() -> evictExpiredData());
guard.addListener(event -> log.info("water level {} -> {}", event.from(), event.to()));

WriteResult result = guard.tryWrite(recordBytes);
if (!result.isAccepted()) {
    // result.level() 当前水位，result.reason() 拒绝原因
    return rejected(result.level(), result.reason().orElse("unknown"));
}
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的存储快满时会出现写入失败甚至损坏数据，希望在水位越界时主动降级而不是崩溃。请从零实现一个存储容量水位与只读降级组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持定义多级水位（正常、警告、只读、危险），各级阈值可配置；2) 达到只读水位时新写入必须被明确拒绝并返回当前水位与原因，不得静默丢弃；3) 达到危险水位时可以触发清理回调（例如清理过期数据），回调失败不得影响降级状态；4) 支持水位回落后的自动恢复：容量下降后要能平滑回到可写状态，需说明抖动抑制方式；5) 支持并发读写下的水位判断：多个线程同时写入时不得突破危险水位（需有测试证明）；6) 支持事件通知：水位变化时通知监听器，监听器异常不得影响主流程；7) 提供统计：各水位持续时间、拒绝写入次数、回收触发次数与当前使用量；8) 测试覆盖水位迁移、只读拒绝、清理回调、回落恢复与抖动抑制、并发不越界与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

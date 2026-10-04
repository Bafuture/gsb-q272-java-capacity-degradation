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

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的存储快满时会出现写入失败甚至损坏数据，希望在水位越界时主动降级而不是崩溃。请从零实现一个存储容量水位与只读降级组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持定义多级水位（正常、警告、只读、危险），各级阈值可配置；2) 达到只读水位时新写入必须被明确拒绝并返回当前水位与原因，不得静默丢弃；3) 达到危险水位时可以触发清理回调（例如清理过期数据），回调失败不得影响降级状态；4) 支持水位回落后的自动恢复：容量下降后要能平滑回到可写状态，需说明抖动抑制方式；5) 支持并发读写下的水位判断：多个线程同时写入时不得突破危险水位（需有测试证明）；6) 支持事件通知：水位变化时通知监听器，监听器异常不得影响主流程；7) 提供统计：各水位持续时间、拒绝写入次数、回收触发次数与当前使用量；8) 测试覆盖水位迁移、只读拒绝、清理回调、回落恢复与抖动抑制、并发不越界与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

## 组件说明：存储容量水位与只读降级

包：`com.example.gsb.capacity`

### 核心类型

| 类型 | 职责 |
|------|------|
| `WaterLevel` | 四级水位：`NORMAL` / `WARNING` / `READ_ONLY` / `CRITICAL` |
| `CapacityConfig` | 各级阈值（占容量比例，可配）与滞回区间 `hysteresisFraction` |
| `CapacityGuard` | 水位状态机：`write / tryWrite / free`、回调注册、监听器注册、`stats()` |
| `StorageDegradedException` | 写入被拒绝时抛出，携带当前水位、原因、已用量与总容量 |
| `ReclaimCallback` | 进入危险水位时触发的清理回调（如清理过期数据） |
| `WaterLevelListener` / `WaterLevelChangeEvent` | 水位迁移事件通知 |
| `CapacityStats` | 各水位累计停留时长、拒绝写入次数、回收触发/失败次数、当前使用量 |

### 用法示例

```java
CapacityGuard guard = new CapacityGuard(
        new CapacityConfig(1_000_000L, 0.70, 0.85, 0.95, 0.05));

guard.addListener(event -> System.out.println(event.previous() + " -> " + event.current()));
guard.addReclaimCallback(g -> g.free(deleteExpiredFiles())); // 回调中释放容量

try {
    guard.write(1024);
} catch (StorageDegradedException e) {
    // 明确拒绝：e.level() / e.getMessage() 给出当前水位与原因，绝不静默丢弃
}
CapacityStats stats = guard.stats();
```

### 设计要点

- **只读拒绝**：`READ_ONLY` 及以上等级，`write` 必抛 `StorageDegradedException`（含水位+原因+用量），`rejectedWrites` 计数；另有 `tryWrite` 返回布尔。
- **硬上限保证**：写入通过 CAS 预留容量，提交前校验“当前用量 + 本次写入 ≤ 危险阈值”，即使状态机视图滞后也**绝不突破危险水位**（并发测试 32 线程×2000 次写入证明）。
- **抖动抑制（滞回恢复 / hysteresis）**：水位**上升立即生效**；**下降必须跌破“当前等级阈值 − 滞回区间”才恢复**。例如只读阈值 85%、滞回 5% 时，用量从 85% 降到 81% 仍保持只读，直到 ≤ 80% 才恢复可写，避免用量在阈值附近抖动时可写/只读频繁翻转。
- **自动恢复**：`free`（含清理回调内释放）后自动重估水位，平滑回到 `WARNING/NORMAL`。
- **回调与监听器隔离**：清理回调仅在“进入危险水位”这一迁移时触发一次，与监听器均在状态锁之外执行，任何异常被捕获（回调失败计入 `reclaimFailures`），不影响降级状态机与主流程。

### 测试覆盖

`./mvnw -q verify`，共 22 个用例：水位迁移与事件顺序、只读拒绝（携带水位/原因）、清理回调（触发/失败隔离/自动恢复）、回落恢复与滞回抖动抑制（含无滞回对照组）、32 线程并发不越界、统计时长/计数。

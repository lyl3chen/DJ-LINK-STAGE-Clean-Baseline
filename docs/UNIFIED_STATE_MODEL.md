# Unified State Model（统一状态模型）

Date: 2026-03-19
Scope: Phase A 架构收口（语义统一，不改主流程）

## 1) 字段总表（生产者/消费者/取值/语义）

| 字段 | 生产者 | 消费者 | 允许取值 | 语义 |
|---|---|---|---|---|
| sourceType | SyncOutputManager | WebUI, API 客户端 | `djlink` / `local` | 当前激活输入源类型 |
| sourcePlayer | SyncOutputManager | TimecodeCore, WebUI | `1..4`（local 固定 1） | 当前主播放源编号 |
| sourceState | SyncOutputManager | TimecodeCore, LTC/MTC, WebUI | `OFFLINE`/`STOPPED`/`PLAYING`/`PAUSED` | 当前激活源播放状态 |
| sourcePlaying | SyncOutputManager(derived) | 驱动与下游策略 | `true/false` | `sourceState == PLAYING` 的布尔语义 |
| sourceActive | SyncOutputManager(derived) | WebUI/策略 | `true/false` | 激活源是否在线可用 |
| positionMs | PlaybackEngine / PlayerState | LocalSourceInput, WebUI | `>=0` | 当前播放位置（毫秒） |
| durationMs | TrackInfo / PlaybackStatus | WebUI | `>=0` | 总时长（毫秒） |
| sourceBpm | SourceInput | SyncOutputManager, WebUI | `>=0` | 源 BPM（未知可为 0） |
| pitch | SourceInput / PlayerState | SyncOutputManager, WebUI | 百分比 | 速度/音高偏移 |
| currentTrackId | PlaybackStatus / PlayerState | WebUI/调试 | 字符串/空 | 当前曲目 ID |
| timecodeState | TimecodeCore | WebUI, 驱动状态观察 | `STOPPED`/`PLAYING`/`PAUSED` | 时间码核心状态 |
| currentFrame | TimecodeCore | LTC/MTC, WebUI | `>=0` | 当前时间码帧 |
| configuredDevice | BasicLocalPlaybackEngine | /api/local/status, WebUI | 设备名/`default` | 用户配置的目标设备 |
| actualOpenedDevice | BasicLocalPlaybackEngine | /api/local/status, WebUI | 设备名/`null` | 实际成功打开的设备 |
| lastError | BasicLocalPlaybackEngine | /api/local/status, WebUI | 错误字符串/`null` | 最近一次加载/播放相关错误 |

---

## 2) 状态流转（标准语义）

### 2.1 播放状态语义

- `STOPPED`
  - positionMs: `0`（或停止后归零）
  - timecodeState: `STOPPED`
  - currentFrame: `0`
- `PLAYING`
  - positionMs: 递增
  - timecodeState: `PLAYING`
  - currentFrame: 递增
- `PAUSED`
  - positionMs: 保持不变
  - timecodeState: `PAUSED`
  - currentFrame: 保持不变

### 2.2 标准流转

`STOPPED -> PLAYING -> PAUSED -> PLAYING -> STOPPED`

说明：
- Pause 不应清零位置；Stop 应回到起点语义。
- LTC/MTC 在 PAUSED 下应“保持帧”而非继续推进。

---

## 3) DJ Link 与 Local 的差异

### DJ Link（sourceType=djlink）
- sourcePlayer：来自主控/手动选择播放器
- 状态来源：CDJ 上报 players[]
- 时间来源：CDJ currentTimeMs

### Local（sourceType=local）
- sourcePlayer：固定 `1`
- 状态来源：LocalSourceInput -> PlaybackEngine.getStatus()
- 时间来源：PlaybackStatus.positionMs

共同点：
- 都由 SyncOutputManager 产出统一 derived state，交给 TimecodeCore。

---

## 4) Pause / Stop 标准语义（统一约束）

- Pause：
  - sourceState 必须为 `PAUSED`
  - positionMs 不变
  - TimecodeCore.currentFrame 不变
  - LTC/MTC 不应继续推进帧

- Stop：
  - sourceState 必须为 `STOPPED`
  - positionMs 归零（本地播放器语义）
  - TimecodeCore.currentFrame 回到 0

---

## 5) 已发现的重复/冲突字段（保留但标注）

1. `sourceState` vs `sourcePlaying`
- 关系：`sourcePlaying` 是 `sourceState == PLAYING` 的派生布尔。
- 风险：若两者被不同逻辑独立维护会产生分叉。
- 当前策略：以 `sourceState` 为主语义，`sourcePlaying` 仅派生使用。

2. `configuredDevice` vs `actualOpenedDevice`
- 关系：前者是“目标配置”，后者是“实际结果”。
- 风险：UI 若只显示一个字段会误导。
- 当前策略：状态 API 同时返回两者，并显示 `lastError`。

3. `timecodeState` vs `sourceState`
- 关系：通常一致，但传播存在短暂时序延迟。
- 风险：瞬时采样可能出现过渡窗口差异。
- 当前策略：以 TimecodeCore 状态为输出真实状态；UI 可接受短暂过渡。

---

## 6) 最小收口修改（本轮）

- 在 `SyncOutputManager` 为统一状态字段增加明确语义注释。
- 在 `JettyServer /api/local/status` 增加诊断字段语义注释。
- 新增本文档，统一字段定义、流转语义、冲突字段处理原则。

（未改 API 结构、未改 TimecodeCore 主流程、未改 WebUI 大逻辑）

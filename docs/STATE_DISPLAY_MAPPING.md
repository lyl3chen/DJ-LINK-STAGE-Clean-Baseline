# 状态显示映射表（WebUI / API / 后端来源）

Date: 2026-03-19  
Scope: Step 2（边界清理）

目标：确保“页面显示项 -> API 字段 -> 后端真实来源”一一对应，避免误导性状态。

---

## 1) I/O Setup 页面

| 页面位置 | 显示项 | 前端文案 | API 字段 | 后端来源 | 语义说明 | 风险/旧逻辑 | 修正状态 |
|---|---|---|---|---|---|---|---|
| I/O Setup / 播放源 | 输入源类型 | DJ Link / Local | `GET /api/sync/state.sourceType`（加载时） + `POST /api/source/switch` | `SyncOutputManager.activeSourceType` | 当前激活源类型 | 无 | ✅ |
| I/O Setup / 本地播放器音频 | Configured Device | `Configured Device=...` | `GET /api/config.sync.localPlayer.audioDevice` | `UserSettingsStore` 配置值 | 用户配置目标设备（非实际打开结果） | 旧文案曾容易被误读为“实际设备” | ✅ 已澄清 |
| I/O Setup / Realtime Sync Status | 原始状态 JSON | `syncStatus` | `GET /api/sync/state` 全量 | `SyncOutputManager.getStatus()` | 调试视图（真实字段透传） | 无 | ✅ |

---

## 2) 本地播放器页面

| 页面位置 | 显示项 | API 字段 | 后端来源 | 语义说明 | 风险/旧逻辑 | 修正状态 |
|---|---|---|---|---|---|---|
| 状态区 | Source Type | `/api/local/status.sourceType` | `LocalSourceInput.getType()` | local/djlink | 无 | ✅ |
| 状态区 | Current Track | `/api/local/status.currentTrack.title` | `BasicLocalPlaybackEngine.currentTrack` | 当前加载曲目 | 无 | ✅ |
| 状态区 | State | `/api/local/status.status.state` | `PlaybackStatus.state` | STOPPED/PLAYING/PAUSED | 无 | ✅ |
| 状态区 | Position | `/api/local/status.status.positionMs` | `PlaybackStatus.positionMs` | 当前播放位置 | 无 | ✅ |
| 状态区 | Duration | `/api/local/status.status.durationMs` | `PlaybackStatus.durationMs` | 曲目总时长 | 无 | ✅ |
| 状态区 | Source BPM | `/api/local/status.sourceBpm` | `LocalSourceInput.getSourceBpm()` | 源 BPM | 未做复杂分析时可为 0 | ✅ |
| 诊断区 | configuredDevice | `/api/local/status.configuredDevice` | `BasicLocalPlaybackEngine.deviceName` | 用户配置目标设备 | 无 | ✅ |
| 诊断区 | actualOpenedDevice | `/api/local/status.actualOpenedDevice` | `BasicLocalPlaybackEngine.actualOpenedDevice` | 实际成功打开设备 | 若 null 代表尚未成功打开 | ✅ |
| 诊断区 | lastError | `/api/local/status.lastError` | `BasicLocalPlaybackEngine.lastError` | 最近一次错误 | 无 | ✅ |
| 诊断区 | audioFormat | `/api/local/status.audioFormat` | `audioLine.getFormat()` | 实际音频格式 | 无 | ✅ |

---

## 3) Sync / Timecode 状态区

| 页面位置 | 显示项 | API 字段 | 后端来源 | 语义说明 | 修正状态 |
|---|---|---|---|---|---|
| I/O Setup / Realtime Sync Status | sourceType | `/api/sync/state.sourceType` | `SyncOutputManager.activeSourceType` | 当前输入源 | ✅ |
| I/O Setup / Realtime Sync Status | sourceState | `/api/sync/state.sourceState` | `SyncOutputManager.sourceState` | 源播放状态 | ✅ |
| I/O Setup / Realtime Sync Status | timecode.state | `/api/sync/state.timecode.state` | `TimecodeCore.currentState` | 时间码状态 | ✅ |
| I/O Setup / Realtime Sync Status | timecode.currentFrame | `/api/sync/state.timecode.currentFrame` | `TimecodeCore.getCurrentFrame()` | 当前时间码帧 | ✅ |
| I/O Setup / LTC 诊断 | transport/frame | `/api/sync/state.drivers.ltc.*` | `LtcDriver.status()` | LTC 输出状态 | ✅ |
| I/O Setup / MTC 诊断 | state/frame | `/api/sync/state.drivers.mtc.*` | `MtcDriver.status()` | MTC 输出状态 | ✅ |

---

## 4) 其他 source/device/state/frame 显示区域

| 区域 | 显示项 | 数据来源 | 备注 |
|---|---|---|---|
| Dashboard 顶栏 | master/source 在线统计 | `players/state` + `sync/state` | DJ Link 主看板 |
| 本地播放器 warning 条 | 当前源类型提示 | `/api/local/status.sourceType` | 非 local 时提示切换 |

---

## 5) 本轮清理的误导性显示

1. 已清理：I/O Setup 里“看起来像实时状态”的本地设备文案语义不清问题。  
   - 现在明确标注为 **Configured Device**（配置值）
   - 并注明“实际打开设备见本地播放器页 actualOpenedDevice”

2. 保留并强化：本地播放器页诊断区作为“真实状态单一口径”  
   - configuredDevice / actualOpenedDevice / lastError / audioFormat

3. 规则：前端不再“猜设备状态”，统一显示 API 返回值。

---

## 6) 仍需注意的边界风险（未改结构，仅标注）

1. `sourceState` 与 `timecode.state` 在状态传播瞬间可能出现短暂不一致（时序窗口）。
2. `configuredDevice` 与 `actualOpenedDevice` 语义不同，不可混用。
3. I/O Setup 属于“配置视图”；本地播放器页诊断区属于“运行时视图”。

---

## 7) 结论

- 当前页面显示项与后端字段已完成一一映射。
- 关键设备/播放/时间码状态均可追溯到明确 API 与后端对象。
- 已清理误导性口径，降低“页面文案与真实状态不一致”的回归风险。

# PROJECT_CONTEXT.md - 项目上下文

## 项目概述

**项目名称**: DJ LINK STAGE  
**项目定位**: 面向演出场景的播放状态中枢、同步输出平台与事件触发平台  
**GitHub 仓库**: https://github.com/lyl3chen/DJ-LINK-STAGE-Clean  
**当前 Commit**: `2f3041e`  
**项目根目录**: /home/shenlei/.openclaw/agents/dev/workspace/dj-link-stage/

## 项目架构

### 6 层架构

1. **输入源层** - 数据来源（DJ Link / Local Playback）
2. **统一播放状态核心** - 唯一播放状态真源
3. **连续同步核心** - 连续型同步输出
4. **事件触发核心** - 离散事件检测与触发
5. **输出适配层** - 外部系统输出
6. **表现与控制层** - WebUI / API

## 核心模块

### 设备输入与协议层

| 模块 | 职责 |
|------|------|
| DeviceManager | DJ Link 设备管理 |
| DbConnection | 数据库连接 |
| DbProtocol | 协议解析 |
| MetadataParser | 元数据解析 |
| SystemState | 系统状态管理 |

### 同步输出框架

| 模块 | 职责 |
|------|------|
| SyncOutputManager | 同步输出管理器 |
| OutputDriver | 输出驱动接口 |

### 当前输出模块

- **LtcDriver** - LTC (SMPTE Timecode) 音频输出
- **MtcDriver** - MTC (MIDI Timecode) MIDI 输出
- **AbletonLinkDriver** - Ableton Link 同步
- **TitanApiDriver** - Titan API 同步
- **Ma2BpmDriver** - MA2 Telnet 同步
- **ConsoleApiDriver** - Console API 同步

---

## 时间码模块 (TimecodeCore)

### 架构设计

```
┌─────────────────────────────────────────────────────────┐
│              SyncOutputManager                          │
│  ├─ sourcePlayer / sourceState ──► 显示/其他驱动         │
│  │                                                   │
│  └─ TimecodeCore ─────────────────► LTC/MTC 驱动       │
│       ├─ 独立 sourcePlayer (tcSourcePlayer)           │
│       ├─ 事件驱动的重锚逻辑                            │
│       └─ 本地单调时钟推进                               │
└─────────────────────────────────────────────────────────┘
```

### 关键设计原则

1. **完全独立**: TimecodeCore 与 SyncOutputManager 的播放器选择完全独立
2. **事件驱动重锚**: 只在明确事件发生时重置时间锚点
3. **本地时钟推进**: 使用单调时钟 (nanoTime) 线性推进，不实时贴合 CDJ
4. **共享核心**: LTC 和 MTC 共用同一个 TimecodeCore，确保时间一致

### API 状态字段

```json
{
  "sourceState": "PLAYING",      // SyncOutputManager 状态
  "sourcePlayer": 1,              // SyncOutputManager 选择的播放器
  "timecode": {
    "tcSourcePlayer": 2,          // TimecodeCore 选择的播放器（独立）
    "state": "PLAYING",           // TimecodeCore 状态
    "currentFrame": 2500,         // 当前帧号
    "anchorFrame": 2400,          // 锚点帧
    "manualTestMode": false       // 手动测试模式
  }
}
```

### 重锚触发条件

| 事件 | 阈值/条件 | 行为 |
|------|----------|------|
| PLAY_STARTED | STOPPED → PLAYING | 重置锚点到当前时间 |
| RESUMED | PAUSED → PLAYING | 重置锚点到当前时间 |
| PAUSED | PLAYING → PAUSED | 冻结在当前帧 |
| STOPPED | 任何 → STOPPED | 重置为 0 |
| TIME_JUMPED | 跳变 > 10帧 (0.4s) | 重置锚点 |
| TRACK_CHANGED | 切歌 | 重置锚点 |
| DRIFT_TOO_LARGE | 累积漂移 > 125帧 (5s) | 重置锚点 |

### 停止态策略

- **LTC**: `zeroLatch` 触发后发送静音（全0样本），而不是 00:00:00:00 帧
- **MTC**: 跟随 TimecodeCore 状态，停止时 Quarter Frame 序列暂停

### 配置项

```json
{
  "sync": {
    "masterPlayer": 1,              // SyncOutputManager 播放器
    "timecode": {
      "sourcePlayer": 2             // TimecodeCore 播放器（独立）
    },
    "ltc": {
      "enabled": true,
      "deviceName": "PCH [plughw:0,0]",
      "gainDb": -14,
      "frameRate": 25,
      "channelMode": "mono"           // mono / stereo-left / stereo-right / stereo-both
    },
    "mtc": {
      "enabled": true,
      "midiPort": "CH345 [hw:1,0,0]",
      "frameRate": 25
    }
  }
}
```

### 编码器实现

- **LtcFrameEncoder**: 80-bit LTC 帧编码（BCD LSB-first，sync word 0x3FFD）
- **LtcBmcEncoder**: BMC 调制（连续相位，bit=1 中点翻转）
- **MtcDriver**: Quarter Frame MIDI 消息（F1 xx）

### 诊断字段

LTC 驱动提供实时输出链路诊断：
- `writeInterval` - 写入间隔统计（min/max/avg ms）
- `bufferOccupancy` - 音频缓冲占用（min/max/avg bytes）
- `underrunCount` - 缓冲饥饿次数
- `frameDelta` - 帧推进差异统计

---

## 未来规划

### 本地播放模式

- 媒体库模块
- 音频分析模块
- 本地播放器模块
- 分析缓存模块

### 事件触发系统

- 播放开始/暂停/停止事件
- Hot Cue / 切歌事件
- 段落切换事件

---

**最后更新**: 2026-03-16 GMT+8

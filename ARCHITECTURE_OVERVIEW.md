# DJ LINK STAGE 项目架构与流程总览

## 一、整体架构（6层模型）

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 6. 表现与控制层 (Presentation & Control)                                   │
│    - WebUI (JettyServer + index.html)                                   │
│    - API 端点 (/api/sync/state, /api/timecode/manual-test, etc.)         │
│    - WebSocket 实时状态推送                                              │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↕
┌─────────────────────────────────────────────────────────────────────────┐
│ 5. 输出适配层 (Output Adapters)                                          │
│    ├─ LtcDriver ─────────────────┐                                      │
│    ├─ MtcDriver ─────────────────┤ 共用 TimecodeCore                    │
│    ├─ AbletonLinkDriver ─────────┤                                      │
│    ├─ TitanApiDriver ────────────┤                                      │
│    ├─ Ma2BpmDriver ──────────────┤                                      │
│    └─ ConsoleApiDriver ──────────┘                                      │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↕
┌─────────────────────────────────────────────────────────────────────────┐
│ 4. 连续同步核心 (Continuous Sync Core)                                   │
│    - TimecodeCore：本地单调时钟驱动，25fps 均匀输出                       │
│    - 事件驱动重锚（PLAY_STARTED/PAUSED/STOPPED/TIME_JUMPED...）           │
│    - 状态通知机制（TimecodeStateListener）                               │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↕
┌─────────────────────────────────────────────────────────────────────────┐
│ 3. 事件触发核心 (Event Trigger Core)                                     │
│    - PlayerEventDetector：检测播放状态变化、切歌、时间跳变                 │
│    - 阈值控制（跳变>10帧/漂移>125帧才触发重锚）                           │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↕
┌─────────────────────────────────────────────────────────────────────────┐
│ 2. 统一播放状态核心 (Unified Playback State Core)                         │
│    - SyncOutputManager：派生统一播放状态                                 │
│    - 播放源选择（masterPlayer / manual mode）                             │
│    - 状态广播（broadcastState）                                          │
└─────────────────────────────────────────────────────────────────────────┘
                                    ↕
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 输入源层 (Input Sources)                                              │
│    ├─ CDJ 设备（DJ Link 协议）                                           │
│    └─ 手动测试模式（脱离硬件，固定线性推进）                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心数据流

### 2.1 正常播放模式（CDJ输入）

```
CDJ 设备 ──DJ Link──┐
                    ├──┐
设备扫描/发现 ──────┘  │
                       ▼
              DeviceManager
                       │
                       ▼
              DbProtocol（协议解析）
                       │
                       ▼
              MetadataParser（元数据提取）
                       │
                       ▼
              onPlayersState(Map playersState)
                       │
                       ▼
              SyncOutputManager
              ├─ 选择播放源（masterPlayer）
              ├─ 构建派生状态（derived）
              └─ broadcastState(derived)
                       │
                       ├──────────────┐
                       ▼              ▼
              TimecodeCore    其他驱动（Link/Titan/MA2）
              ├─ update(derived)     │
              ├─ 事件检测            │
              ├─ 重锚判断            │
              └─ onFrame(frame)      │
                       │              │
                       ▼              │
              LtcDriver/MtcDriver     │
              （状态机 + 连续输出）    │
                       │              │
                       ▼              ▼
              音频/MIDI输出      网络输出
```

### 2.2 手动测试模式

```
WebUI 点击「开启测试」
         │
         ▼
POST /api/timecode/manual-test {enabled: true}
         │
         ▼
SyncOutputManager.setTimecodeManualTestMode(true)
         │
         ▼
TimecodeCore.setManualTestMode(true)
  ├─ currentState = "PLAYING"
  ├─ anchorFrame = 0
  ├─ anchorTimeNs = now()
  └─ 通知状态变化 ──▶ LtcDriver
         │
         ▼
TimecodeCore.run() ──25fps──▶ onFrame(frame++)
         │
         ▼
LtcDriver.onFrame(frame) ──▶ transportState = PLAYING
         │
         ▼
audioLoop() ──连续帧流──▶ 音频输出
```

---

## 三、关键模块详解

### 3.1 TimecodeCore（时间码核心）

**职责**：
- 本地单调时钟（System.nanoTime）驱动 25fps 均匀输出
- 播放器事件检测（PlayerEventDetector）
- 时间码重锚（re-anchor）决策
- 向消费者（LtcDriver/MtcDriver）输出当前帧

**核心机制**：
```
anchorFrame + elapsedFrames = currentFrame
    │              │
    │              └─ (nowNs - anchorTimeNs) * 25 / 1e9
    └─ 上次重锚时的帧号

重锚触发条件：
- PLAY_STARTED：停止→播放
- PAUSED：播放→暂停
- STOPPED：任何→停止
- TIME_JUMPED：跳变>10帧（0.4s）
- TRACK_CHANGED：切歌
- DRIFT_TOO_LARGE：累积漂移>125帧（5s）
```

**状态通知**：
- `TimecodeStateListener` 接口
- 状态变化时通知驱动（PLAYING/PAUSED/STOPPED）

### 3.2 LtcDriver（LTC 输出驱动）

**重构要点**：

**旧模型**（已废弃）：
```
onFrame(frame) ──▶ latestFrame = frame
                        │
audioLoop() ◀───────────┘
  │ 每轮读取 latestFrame
  │ 现编码现发送
  └─▶ 问题：追帧模式，不严格连续
```

**新模型**（当前）：
```
TransportState 状态机
  ├─ STOPPED：输出固定 00:00:00:00
  ├─ PAUSED：冻结 heldFrame
  └─ PLAYING：nextFrameToWrite 严格递增

audioLoop()
  │ 按缓冲区余量驱动
  │ framesToWrite = available / BYTES_PER_FRAME
  │ 批量写入（当前实现）
  └─▶ 待优化：写入节奏不均匀可能导致信号起伏
```

**编码流程**：
```
frame number
     │
     ▼
LtcFrameEncoder.buildFrame(frame)
  ├─ 80-bit LTC 帧
  ├─ BCD 编码（LSB-first）
  └─ sync word 0x3FFD
     │
     ▼
LtcBmcEncoder.encodeFrame(bits, gain)
  ├─ BMC 调制
  ├─ bit start 必翻转
  ├─ bit=1 中点翻转
  └─ 连续相位
     │
     ▼
applyChannelMode(monoPcm)
  ├─ mono：直接输出
  ├─ stereo-left：左=PCM，右=0
  ├─ stereo-right：左=0，右=PCM
  └─ stereo-both：左右=PCM
     │
     ▼
audioLine.write(outputPcm)
```

### 3.3 MtcDriver（MTC 输出驱动）

**与 LTC 区别**：
- **输出介质**：MIDI 端口 vs 音频设备
- **编码格式**：Quarter Frame (F1 xx) vs BMC 调制音频
- **帧率**：同样是 25fps，但每帧分 8 个 Quarter Frame 发送
- **时间码解析**：接收端从 Quarter Frame 序列重建完整时间码

### 3.4 PlayerEventDetector（播放器事件检测）

**检测逻辑**：
```
输入：PlayerState（state, playing, timeSec, trackId）
          │
          ▼
normalizeState(state, playing, timeSec)
  ├─ state=null + playing=false + timeSec>0.05 → PAUSED
  ├─ state=null + playing=true → PLAYING
  └─ ...
          │
          ▼
detect() ──▶ PlayerEvent
  ├─ 状态变化检测
  ├─ 切歌检测
  ├─ 时间跳变检测（>10帧）
  └─ 累积漂移检测（>125帧）
```

### 3.5 SyncOutputManager（同步输出管理器）

**双角色**：
1. **播放源选择器**：根据 masterPlayer / manual mode 选择 CDJ
2. **状态广播器**：将派生状态广播给所有驱动

**状态分离设计**：
```
SyncOutputManager              TimecodeCore
     │                              │
     ├─ sourcePlayer: 1             ├─ tcSourcePlayer: 2
     ├─ sourceState: PLAYING        ├─ state: PLAYING
     │                              │
     └─ 用于显示/Link/Titan/MA2     └─ 仅用于 LTC/MTC

两者独立运行，通过配置同步
```

---

## 四、配置结构

```json
{
  "sync": {
    "sourceMode": "manual",
    "masterPlayer": 1,
    "timecode": {
      "sourcePlayer": 2
    },
    "ltc": {
      "enabled": true,
      "deviceName": "PCH [hw:0,0]",
      "gainDb": -8,
      "frameRate": 25,
      "channelMode": "mono"
    },
    "mtc": {
      "enabled": true,
      "midiPort": "CH345 [hw:1,0,0]",
      "frameRate": 25
    }
  }
}
```

---

## 五、当前已知限制与问题

### 5.1 LTC 信号强度波浪形起伏 ⏸️
- **现象**：Resolume 观测到信号强度周期性起伏
- **关键发现**：标准 LTC 文件在本机播放也出现同样起伏
- **归因**：项目本机音频输出链路（ALSA/PulseAudio/驱动）
- **状态**：暂停修改代码，后续单独排查

### 5.2 MTC 视觉跳帧 ⏸️
- **状态**：待后续详细测试

### 5.3 其他限制
- 停止态 LTC 输出 00:00:00:00 帧（非静音，但接收端可能识别为异常）
- 音频设备选择仅显示检测到的物理设备

---

## 六、关键时序与性能

| 参数 | 值 |
|------|-----|
| LTC 帧率 | 25 fps |
| 每帧时长 | 40 ms |
| 采样率 | 48000 Hz |
| 每帧采样数 | 1920 samples |
| 每帧字节数 | 3840 bytes (16-bit mono) |
| bits per second | 2000 (25 * 80) |
| samples per bit | 24 |

---

**文档生成时间**: 2026-03-16 23:41 GMT+8  
**当前版本**: `1b8a066`

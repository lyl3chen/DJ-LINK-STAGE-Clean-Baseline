# DJ LINK STAGE - 真实架构总览

## 一、项目结构

```
dj-link-stage/
├── djlink-service/          # beat-link 包装服务（独立模块）
│   └── src/main/java/djlink/
│       ├── DeviceManager.java      # 使用 beat-link 连接 CDJ
│       ├── MetadataWarmupService.java
│       └── trigger/
│           └── TriggerEngine.java
│
└── dbclient/                # 主项目
    └── src/main/java/dbclient/
        ├── Main.java                 # 入口
        ├── websocket/
        │   └── JettyServer.java      # 反射调用 djlink-service
        ├── sync/
        │   ├── SyncOutputManager.java    # 状态派生
        │   ├── timecode/
        │   │   ├── TimecodeCore.java     # 时间码核心
        │   │   └── PlayerEventDetector.java
        │   └── drivers/
        │       ├── LtcDriver.java        # LTC 输出
        │       ├── MtcDriver.java        # MTC 输出
        │       ├── AbletonLinkDriver.java
        │       ├── TitanApiDriver.java
        │       ├── Ma2BpmDriver.java
        │       └── ConsoleApiDriver.java
        └── core/
            └── DeviceManager.java      # ❌ 未使用（旧实现）
```

## 二、真实数据流

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CDJ 设备 (DJ Link 协议)                               │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    djlink-service (beat-link)                               │
│                                                                              │
│  DeviceManager                                                               │
│  ├── DeviceFinder.getInstance().start()          # 发现设备                  │
│  ├── VirtualCdj.getInstance().start()            # 虚拟CDJ，接收状态         │
│  ├── BeatFinder.getInstance().start()            # 节拍检测                  │
│  ├── MetadataFinder.getInstance().start()        # 曲目元数据                │
│  ├── BeatGridFinder.getInstance().start()        # 节拍网格                  │
│  ├── WaveformFinder.getInstance().start()        # 波形数据                  │
│  └── ArtFinder.getInstance().start()             # 封面图片                  │
│                                                                              │
│  virtualCdj.addUpdateListener((CdjStatus status) -> {                        │
│      // 播放器状态更新回调                                                    │
│  });                                                                         │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    │ 反射调用（Java Reflection）
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         dbclient (主项目)                                    │
│                                                                              │
│  JettyServer                                                                 │
│  Class<?> dmClass = Class.forName("djlink.DeviceManager");                   │
│  Object dm = dmClass.getMethod("getInstance").invoke(null);                  │
│  Map result = dmClass.getMethod("getPlayersState").invoke(dm);               │
│                                                                              │
│  syncOutputManager.onPlayersState(result);                                   │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SyncOutputManager                                       │
│                                                                              │
│  onPlayersState(Map playersState)                                            │
│  ├── 选择播放源（masterPlayer / manual mode）                                 │
│  ├── 构建派生状态（sourceState, sourcePlayer, masterBpm...）                  │
│  └── broadcastState(derived)                                                │
│                                                                              │
│  timecodeCore.update(derived)                                               │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       TimecodeCore                                           │
│                                                                              │
│  update(Map state)                                                           │
│  ├── extractPlayerState(state, sourcePlayer)                                 │
│  ├── PlayerEventDetector.detect()                                            │
│  │   └── 事件：PLAY_STARTED/PAUSED/STOPPED/TIME_JUMPED/TRACK_CHANGED...       │
│  ├── handleEvent(event)                                                      │
│  │   └── 重锚：anchorFrame = newFrame, anchorTimeNs = now()                  │
│  └── onFrame(frame)  ────────────┬───────────┐                              │
│                                  │           │                               │
└──────────────────────────────────┼───────────┼───────────────────────────────┘
                                   │           │
                                   ▼           ▼
┌───────────────────────────────┐  ┌───────────────────────────────┐
│        LtcDriver              │  │        MtcDriver              │
│  ─────────────────────────────│  │  ─────────────────────────────│
│  onFrame(frame)               │  │  onFrame(frame)               │
│  │                            │  │  │                            │
│  ▼                            │  │  ▼                            │
│  TransportState.PLAYING       │  │  transportState = PLAYING     │
│  nextFrameToWrite++           │  │  sendQuarterFrame()           │
│  │                            │  │  │                            │
│  ▼                            │  │  ▼                            │
│  audioLoop()                  │  │  MIDI 输出                     │
│  ├── 按缓冲区余量驱动           │  │                              │
│  ├── encodeFrameToBuffer()    │  │                              │
│  └── audioLine.write()        │  │                              │
│                               │  │                              │
│  音频输出 → 声卡              │  │  MIDI 输出 → USB MIDI        │
└───────────────────────────────┘  └───────────────────────────────┘
```

## 三、关键组件详解

### 1. djlink-service（beat-link 包装）

**位置**: `djlink-service/src/main/java/djlink/DeviceManager.java`

**核心功能**:
- 使用 beat-link 库连接 CDJ 设备
- 启动所有 beat-link 组件
- 提供播放器状态查询 API
- 通过反射被 dbclient 调用

**beat-link 组件**:
```java
DeviceFinder      // 发现网络中的 CDJ 设备
VirtualCdj        // 虚拟 CDJ，接收播放器状态更新
BeatFinder        // 节拍检测
MetadataFinder    // 曲目元数据（标题、艺术家、BPM）
BeatGridFinder    // 节拍网格数据
WaveformFinder    // 波形数据（预览/详细）
AnalysisTagFinder // 分析标签（段落结构）
ArtFinder         // 封面图片
```

**数据监听**:
```java
virtualCdj.addUpdateListener(new DeviceUpdateListener() {
    @Override
    public void received(DeviceUpdate update) {
        if (update instanceof CdjStatus) {
            CdjStatus status = (CdjStatus) update;
            // 更新播放器状态
            updatePlayerState(status.getDeviceNumber(), status);
        }
    }
});
```

### 2. dbclient 主项目

**入口**: `dbclient/src/main/java/dbclient/Main.java`

**启动流程**:
1. 启动 JettyServer（HTTP + WebSocket）
2. 等待手动触发扫描（不自动启动 DeviceManager）
3. 注册事件监听器
4. 启动状态广播（WebSocket）

### 3. JettyServer（反射桥接层）

**位置**: `dbclient/src/main/java/dbclient/websocket/JettyServer.java`

**反射调用 djlink-service**:
```java
// 获取 DeviceManager 实例
Class<?> dmClass = Class.forName("djlink.DeviceManager");
Object dm = dmClass.getMethod("getInstance").invoke(null);

// 调用 getPlayersState()
Map result = (Map) dmClass.getMethod("getPlayersState").invoke(dm);

// 推送给 SyncOutputManager
syncOutputManager.onPlayersState(result);
```

**API 端点**:
- `GET /api/players/state` - 播放器完整状态
- `GET /api/djlink/track` - 曲目信息
- `GET /api/djlink/beatgrid` - 节拍网格
- `GET /api/djlink/cues` - Cue 点
- `GET /api/djlink/waveform` - 波形数据
- `GET /api/djlink/sections` - 段落结构
- `GET /api/djlink/artwork` - 封面图片

### 4. SyncOutputManager（状态派生）

**位置**: `dbclient/src/main/java/dbclient/sync/SyncOutputManager.java`

**核心功能**:
- 接收 beat-link 原始数据
- 选择播放源（masterPlayer）
- 派生统一状态（sourceState/sourcePlayer/masterBpm）
- 广播给所有输出驱动

**状态判断**:
```java
String st = !active ? "OFFLINE" : 
            (playing ? "PLAYING" : 
             (beat < 0 || nowSec <= 0.05 ? "STOPPED" : "PAUSED"));
```

### 5. TimecodeCore（时间码核心）

**位置**: `dbclient/src/main/java/dbclient/sync/timecode/TimecodeCore.java`

**核心功能**:
- 接收播放器状态
- 事件检测（PlayerEventDetector）
- 本地单调时钟推进（25fps）
- 重锚决策（anchorFrame/anchorTimeNs）
- 向 LTC/MTC 驱动输出帧

**状态机**:
- `PLAYING`: 线性推进，elapsedFrames = (now - anchorTime) * 25 / 1e9
- `PAUSED`: 冻结在 anchorFrame
- `STOPPED`: 重置为 0

### 6. LtcDriver（LTC 输出驱动）

**位置**: `dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java`

**重构后架构**:
```java
TransportState 状态机
  ├─ STOPPED: 输出固定 00:00:00:00
  ├─ PAUSED: 冻结 heldFrame
  └─ PLAYING: nextFrameToWrite 严格递增

audioLoop()
  ├─ 按缓冲区余量驱动
  ├─ encodeFrameToBuffer(frame)
  │   ├─ LtcFrameEncoder.buildFrame(frame)  // 80-bit LTC 帧
  │   └─ BMC 调制
  └─ audioLine.write()
```

**关键参数**:
- 采样率: 48000 Hz
- 帧率: 25 fps
- 每帧采样数: 1920 samples
- 每帧字节数: 3840 bytes (16-bit mono)

### 7. MtcDriver（MTC 输出驱动）

**位置**: `dbclient/src/main/java/dbclient/sync/drivers/MtcDriver.java`

**与 LTC 区别**:
- 输出介质: MIDI 端口 vs 音频设备
- 编码格式: Quarter Frame (F1 xx) vs BMC 调制音频
- 同样使用 TimecodeCore 作为时间源

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

## 五、模块依赖关系

```
djlink-service
    │
    ├─ beat-link 8.1.0-SNAPSHOT (外部依赖)
    │   ├─ DeviceFinder
    │   ├─ VirtualCdj
    │   ├─ BeatFinder
    │   ├─ MetadataFinder
    │   ├─ BeatGridFinder
    │   ├─ WaveformFinder
    │   ├─ AnalysisTagFinder
    │   └─ ArtFinder
    │
    └─ 通过反射被 dbclient 调用
            │
            ▼
dbclient
    │
    ├─ JettyServer (HTTP/WebSocket)
    ├─ SyncOutputManager
    ├─ TimecodeCore
    │       └─ PlayerEventDetector
    │
    └─ Output Drivers
        ├─ LtcDriver
        │   ├─ LtcFrameEncoder
        │   └─ LtcBmcEncoder
        ├─ MtcDriver
        ├─ AbletonLinkDriver
        ├─ TitanApiDriver
        ├─ Ma2BpmDriver
        └─ ConsoleApiDriver
```

## 六、重要澄清

### ❌ 不是真实架构的部分

1. **dbclient.core.DeviceManager** - 旧实现，未使用
   - 基于自定义 TCP 连接和协议解析
   - 实际项目使用 beat-link (djlink-service)

2. **dbclient.protocol.DbProtocol** - 旧实现，未使用
   - 自定义 DJ Link 协议解析
   - 实际使用 beat-link 的标准协议实现

### ✅ 真实架构的关键点

1. **所有 CDJ 数据来自 beat-link** (djlink-service)
2. **dbclient 通过反射调用** djlink-service
3. **SyncOutputManager 做状态派生**（选择播放源）
4. **TimecodeCore 做时间码生成**（本地时钟 + 重锚）
5. **LTC/MTC 驱动做输出**（共用同一时间源）

---

**文档创建时间**: 2026-03-17 00:08 GMT+8  
**当前版本**: `12b4640`

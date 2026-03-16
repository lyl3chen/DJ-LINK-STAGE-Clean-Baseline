# CDJ 数据获取流程详解

## 一、整体数据流

```
┌─────────────┐     TCP      ┌─────────────┐     解析      ┌─────────────┐
│   CDJ 设备   │ ═══════════▶ │  DeviceManager │ ═════════▶ │  JettyServer  │
│  (DJ Link)   │   12523/48304 │  (协议层)      │           │  (HTTP/WebSocket)│
└─────────────┘              └─────────────┘           └─────────────┘
                                                                  │
                                                                  ▼
                                                        ┌─────────────┐
                                                        │ SyncOutputManager│
                                                        │  (状态派生)   │
                                                        └─────────────┘
                                                                  │
                                                                  ▼
                                                        ┌─────────────┐
                                                        │ TimecodeCore   │
                                                        │ LtcDriver      │
                                                        │ MtcDriver      │
                                                        └─────────────┘
```

---

## 二、详细步骤

### Step 1: 设备发现与连接（DeviceManager）

**入口**: `dbclient/core/DeviceManager.java`

```java
// 1. 连接 CDJ 主端口
DbConnection conn = new DbConnection("192.168.100.132", 12523);
conn.connect();

// 2. 发送 GREETING 握手
DbProtocol protocol = new DbProtocol(conn);
protocol.handshake(3);  // 以 Player 3 身份握手

// 3. 切换到 DB 端口 48304（数据端口）
DbConnection dbConn = new DbConnection(host, 48304);
dbConn.connect();

// 4. 持续接收播放器数据
protocol.requestTrackMetadata(...);
```

**协议流程**:
1. **GREETING** (0x11) - 初始握手
2. **SETUP** (0x10 0x02 0x00) - 设置请求
3. **TRACK_REQUEST** - 请求曲目元数据

---

### Step 2: 协议解析（DbProtocol）

**文件**: `dbclient/protocol/DbProtocol.java`

```java
// 发送 GREETING
byte[] greeting = PacketBuilder.buildGreeting();
conn.send(greeting);

// 接收响应
byte[] response = conn.receiveAll();

// 解析响应（原始字节流）
// 响应格式: MSG_START(5) + type(3) + length(3) + args
```

**关键数据包类型**:
| 类型 | 说明 |
|------|------|
| 0x4000 | 菜单可用 |
| 0x4700 | 播放器状态 |
| 0x1002 | 设置响应 |

---

### Step 3: HTTP API 暴露（JettyServer）

**文件**: `dbclient/websocket/JettyServer.java`

```java
// 当收到 /api/players/state 请求时
if (path.equals("/api/players/state")) {
    // 从 DeviceManager 获取播放器状态
    result = dm.getMethod("getPlayersState").invoke(dm);
    
    // 关键：推送给 SyncOutputManager
    syncOutputManager.onPlayersState(result);
}
```

**API 端点**:
- `GET /api/players/state` - 播放器完整状态
- `GET /api/djlink/track` - 曲目信息
- `GET /api/djlink/beatgrid` - 节拍网格
- `GET /api/djlink/cues` - Cue 点
- `GET /api/djlink/waveform` - 波形数据

---

### Step 4: 状态派生（SyncOutputManager）

**文件**: `dbclient/sync/SyncOutputManager.java`

```java
public synchronized void onPlayersState(Map<String, Object> playersState) {
    // 1. 保存播放器列表
    this.lastPlayersList = playersState.get("players");
    
    // 2. 选择播放源（masterPlayer / manual mode）
    Map<String, Object> chosen = selectPlayer(playersList);
    
    // 3. 构建派生状态
    Map<String, Object> derived = new HashMap<>();
    derived.put("sourceState", determineState(chosen));  // PLAYING/PAUSED/STOPPED
    derived.put("sourcePlayer", chosen.get("number"));
    derived.put("masterBpm", chosen.get("bpm"));
    derived.put("sourcePlaying", chosen.get("playing"));
    
    // 4. 广播给所有驱动
    broadcastState(derived);
}
```

**状态判断逻辑**:
```java
String st = !active ? "OFFLINE" : 
            (playing ? "PLAYING" : 
             (beat < 0 || nowSec <= 0.05 ? "STOPPED" : "PAUSED"));
```

---

### Step 5: 时间码核心处理（TimecodeCore）

**文件**: `dbclient/sync/timecode/TimecodeCore.java`

```java
public void update(Map<String, Object> state) {
    // 1. 提取选定播放器状态
    PlayerState ps = extractPlayerState(state, sourcePlayer);
    
    // 2. 计算本地预期帧（线性推进）
    long expectedFrame = anchorFrame;
    if ("PLAYING".equals(currentState)) {
        long elapsedNs = System.nanoTime() - anchorTimeNs;
        long elapsedFrames = elapsedNs * 25 / 1_000_000_000;
        expectedFrame = anchorFrame + elapsedFrames;
    }
    
    // 3. 事件检测
    PlayerEvent event = detector.detect(ps, expectedFrame, lastTrackId);
    
    // 4. 处理事件（重锚）
    if (event != PlayerEvent.NONE) {
        handleEvent(event, ps);
    }
}
```

---

### Step 6: WebSocket 实时推送（EventPusher）

**文件**: `dbclient/websocket/EventPusher.java`

```java
// 每 100ms 广播一次 STATE
stateScheduler.scheduleAtFixedRate(() -> {
    Map<String, Object> state = buildState();
    broadcast(gson.toJson(state));
}, 0, 100, TimeUnit.MILLISECONDS);
```

**WebSocket 端点**: `ws://192.168.100.200:8080/ws`

---

## 三、关键数据结构

### CDJ 原始数据（来自 DeviceManager）

```json
{
  "players": [
    {
      "number": 1,
      "playing": true,
      "active": true,
      "state": null,
      "currentTimeMs": 123456,
      "bpm": 128.5,
      "beat": 32,
      "trackId": "abc123",
      "onAir": true
    }
  ],
  "online": 2,
  "metadataWarmupCacheSize": 3
}
```

### SyncOutputManager 派生状态

```json
{
  "sourceState": "PLAYING",
  "sourcePlayer": 1,
  "masterBpm": 128.5,
  "sourcePlaying": true,
  "currentTimeMs": 123456
}
```

### TimecodeCore 输出

```json
{
  "tcSourcePlayer": 1,
  "state": "PLAYING",
  "currentFrame": 3086,
  "anchorFrame": 3000,
  "manualTestMode": false
}
```

---

## 四、数据来源对比

| 来源 | 数据类型 | 更新频率 | 用途 |
|------|---------|---------|------|
| **CDJ 设备** | 原始播放状态 | ~30Hz | 第一手数据 |
| **DeviceManager** | 协议解析后 | ~30Hz | 内部处理 |
| **JettyServer API** | HTTP 查询 | 按需 | WebUI/外部查询 |
| **EventPusher WS** | WebSocket 推送 | 100ms | 实时推送 |
| **SyncOutputManager** | 派生状态 | 实时 | 驱动输入 |
| **TimecodeCore** | 线性时间码 | 25fps | LTC/MTC 输出 |

---

## 五、关键类关系

```
DeviceManager
    │
    ├─ DbConnection (TCP 连接)
    ├─ DbProtocol (协议解析)
    └─ MetadataParser (元数据提取)
         │
         ▼
JettyServer (HTTP/WebSocket)
    │
    ├─ /api/players/state ──▶ SyncOutputManager.onPlayersState()
    └─ /ws (WebSocket) ──▶ 实时推送
         │
         ▼
SyncOutputManager
    │
    ├─ 选择播放源
    ├─ 派生状态
    └─ broadcastState()
         │
         ▼
TimecodeCore
    │
    ├─ PlayerEventDetector
    ├─ 事件检测
    ├─ 重锚判断
    └─ onFrame(frame)
         │
         ▼
    ├─ LtcDriver (音频输出)
    └─ MtcDriver (MIDI输出)
```

---

## 六、当前配置

**CDJ IP 地址**: `192.168.100.132`  
**主端口**: `12523` (握手/控制)  
**数据端口**: `48304` (播放器数据)  
**本地服务**: `192.168.100.200:8080`

---

**文档生成时间**: 2026-03-16 23:52 GMT+8

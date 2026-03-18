# DJ Link Stage - Phase 1 完成文档

**Git Commit**: `71cb87a1a4ade0d1118bfef6154af32fd9ffde2d`  
**Date**: 2026-03-19  
**Status**: Phase 1 已验证完成

---

## 一、数据流链路

### 1.1 本地播放器 → 时间码 完整链路

```
WebUI (本地播放器页面)
    ↓ 导入/加载/播放/暂停/停止
JettyServer (/api/local/*)
    ↓ load/play/pause/stop
LocalSourceInput
    ↓ getPlaybackEngine()
BasicLocalPlaybackEngine
    ↓ load()/play() → audioLine
    ↓ playbackLoop (实时播放)
    ↓ getStatus() → PlaybackStatus
    ↓ getSourceTimeSec()
SyncOutputManager.onLocalPlayerState() [定时轮询]
    ↓ sourceInputManager.getSource("local")
    ↓ localSource.getState() / getSourceTimeSec()
TimecodeCore.update()
    ↓ getCurrentFrame() [基于时间计算]
    ↓ onFrame(frame) 回调
LtcDriver.onFrame(frame)
    ↓ audioLoop() → encode → audioLine.write()
MtcDriver.onFrame(frame)
    ↓ sendQuarterFrame() → midiReceiver.send()
```

### 1.2 Source 切换架构

```
SourceInputManager
├── CDJ Source (djlink)
│   └── SyncOutputManager.onPlayersState()
└── Local Source (local)
    └── SyncOutputManager.onLocalPlayerState()

SyncOutputManager
├── activeSourceType ("djlink" | "local")
├── sourceInputManager.switchToSource(type)
└── 定时轮询当前 active source
```

### 1.3 关键类职责

| 类 | 职责 |
|----|------|
| `SourceInputManager` | 管理多个 SourceInput，切换当前 active source |
| `LocalSourceInput` | 本地播放器适配器，包装 PlaybackEngine |
| `BasicLocalPlaybackEngine` | 实际音频播放，Java Sound API |
| `SyncOutputManager` | 统一状态管理，轮询输入源，驱动时间码 |
| `TimecodeCore` | 时间码核心，基于时间计算 frame，25fps 均匀输出 |
| `LtcDriver` | LTC 编码输出，audioLine 写入 |
| `MtcDriver` | MTC 编码输出，MIDI 发送 |

---

## 二、已验证通过的功能清单

### 2.1 本地播放器音频
- ✅ 音频文件导入 (WAV 格式)
- ✅ 曲目列表管理
- ✅ 播放/暂停/停止控制
- ✅ USB 音频设备独立选择
- ✅ 首次播放无杂音/颤抖 (8KB 预填充)
- ✅ 实时播放速度控制 (Thread.sleep 延迟)

### 2.2 时间码输出
- ✅ LTC (SMPTE) 时间码输出
- ✅ MTC (MIDI Timecode) 输出
- ✅ 播放时时间码前进
- ✅ 暂停时时间码停止 (帧号保持)
- ✅ 停止时时间码归零

### 2.3 状态同步
- ✅ PlaybackStatus.state (STOPPED/PLAYING/PAUSED)
- ✅ positionMs 实时推进
- ✅ SourceState 同步到 TimecodeCore
- ✅ timecode.state (STOPPED/PLAYING/PAUSED)

### 2.4 Source 切换
- ✅ DJ Link (CDJ) ↔ Local Player 切换
- ✅ /api/source/switch API
- ✅ WebUI source type 选择器

### 2.5 诊断与监控
- ✅ configuredDevice (配置的设备名)
- ✅ actualOpenedDevice (实际打开的设备名)
- ✅ lastError (加载错误信息)
- ✅ audioFormat (音频格式信息)
- ✅ 状态实时显示 (WebUI)

---

## 三、当前已知限制

### 3.1 音频格式
- 仅支持 WAV 格式 (PCM 16-bit)
- MP3 需要额外解码器 (未实现)

### 3.2 设备选择
- USB 设备被占用时会加载失败 (需明确错误提示)
- 设备格式不支持时会静默失败 (需改进错误处理)

### 3.3 播放控制
- Seek 功能未实现 (第一版简化)
- Resume from pause 从头开始 (非精确定位)

### 3.4 BPM
- 本地播放器 BPM 固定为 0 (未分析音频)
- 依赖外部 BPM 源

### 3.5 时间码
- 固定 25fps (非自适应)
- 不支持 drop-frame

---

## 四、后续禁止误改清单

### 4.1 架构层面
- ❌ **不要恢复旧的 timecode 独立 source 字段**  
  timecode.sourcePlayer 已统一到 sync.masterPlayer

- ❌ **不要绕过 SyncOutputManager / SourceInputManager 主链路**  
  必须通过 SourceInputManager.getSource("local") 访问本地播放器

- ❌ **不要再创建新的 BasicLocalPlaybackEngine 实例**  
  必须使用 SyncOutputManager 共享的实例

### 4.2 状态语义
- ❌ **不要随意改动 pause/stop/timecode 状态语义**  
  - STOPPED: 停止，position = 0
  - PLAYING: 播放中，position 递增
  - PAUSED: 暂停，position 保持

- ❌ **不要改动 LTC/MTC PAUSED 行为**  
  PAUSED 时必须持续输出同一帧 (不是停止输出)

### 4.3 UI 显示
- ❌ **不要再把本地播放器设备状态显示写成静态文案**  
  必须从 /api/local/status 动态获取

- ❌ **不要显示误导性状态**  
  如"使用系统默认设备"当实际使用 USB 时

### 4.4 错误处理
- ❌ **不要静默 fallback 默认音频设备**  
  设备打开失败必须明确报错，currentTrack = null

- ❌ **不要吞掉音频加载异常**  
  必须记录 lastError，返回给前端显示

### 4.5 时序敏感代码
- ❌ **不要改动 playbackLoop 的实时延迟逻辑**  
  基于 bytesPerSecond 的 Thread.sleep 是防止快进的关键

- ❌ **不要改动预填充逻辑**  
  8KB 预填充 + mark/reset 是消除首播杂音的关键

- ❌ **不要改动 audioLine.start() 时机**  
  必须在预填充完成后 start

---

## 五、修改的文件清单

### 5.1 核心功能
```
dbclient/src/main/java/dbclient/input/LocalSourceInput.java
dbclient/src/main/java/dbclient/input/SourceInputManager.java
dbclient/src/main/java/dbclient/media/player/BasicLocalPlaybackEngine.java
dbclient/src/main/java/dbclient/media/player/PlaybackEngine.java
dbclient/src/main/java/dbclient/sync/SyncOutputManager.java
dbclient/src/main/java/dbclient/sync/timecode/TimecodeCore.java
dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java
dbclient/src/main/java/dbclient/sync/drivers/MtcDriver.java
dbclient/src/main/java/dbclient/websocket/JettyServer.java
```

### 5.2 WebUI
```
dbclient/src/main/resources/web/index.html
```

### 5.3 库服务
```
dbclient/src/main/java/dbclient/media/library/LocalLibraryService.java
dbclient/src/main/java/dbclient/media/library/InMemoryTrackRepository.java
```

---

## 六、API 端点

### 6.1 Source 切换
- `POST /api/source/switch` - 切换输入源 (djlink/local)

### 6.2 本地播放器
- `POST /api/local/import` - 导入音频文件
- `POST /api/local/upload-and-import` - 上传并导入
- `GET /api/local/tracks` - 获取曲目列表
- `POST /api/local/load` - 加载曲目
- `POST /api/local/play` - 播放
- `POST /api/local/pause` - 暂停
- `POST /api/local/stop` - 停止
- `GET /api/local/status` - 获取状态 (含诊断信息)

### 6.3 系统状态
- `GET /api/sync/state` - 同步状态 (含时间码、驱动状态)
- `GET /api/config` - 获取配置
- `POST /api/config` - 保存配置

---

## 七、后续开发建议

### 7.1 Phase 2 候选功能
- [ ] MP3/AAC 格式支持 (需解码器)
- [ ] BPM 分析 (音频特征提取)
- [ ] Seek 精确定位
- [ ] 播放列表/队列
- [ ] 音量控制

### 7.2 改进项
- [ ] 音频设备热插拔检测
- [ ] 更详细的错误提示 (前端弹窗)
- [ ] 音频可视化波形
- [ ] 播放进度条拖拽

---

**文档版本**: 1.0  
**最后更新**: 2026-03-19  
**维护者**: OpenClaw Agent

# DJ LINK STAGE - 当前项目主文档

> 更新时间：2026-03-24  
> 当前运行版本：5b802bf（UI布局规则） / 3dc536e（mini波形调优） / ed14d81（mini落差恢复） / c68643a（在线播放器显示规则） / 20fa99d（真实波形渲染接入）  
> 项目根目录：~/agents/dev/workspace/dj-link-stage/

## 2026-03-24 基线备份说明（GitHub恢复锚点）

- 代码已按用户要求恢复到历史稳定点后继续迭代，并新增可回退锚点：
  - `ui-safe-before-mini-waveform-tune` → `c68643a`
- 当前可用于完整恢复的“桌面UI现行基线”建议以 **HEAD + 基线tag** 双保险保存到 GitHub。
- 本阶段核心状态：
  1. `/api/players/state` 与 `/api/players/track` 的关键 track 字段已对齐（durationMs/sourcePlayer/rekordboxId）。
  2. `metadataFound` 在两接口口径已对齐。
  3. desktop-ui LIVE 已改为“仅显示在线播放器、最多4台、编号升序；0台时显示2个占位”。
  4. mini 卡片波形已从占位改为真实数据绘制；高度提升；当前落差映射为默认。
  5. LTC/MTC 发送端状态可进入 PLAYING 且帧推进；LTC接收端问题暂挂起（发送端证据已留存）。

---

## 2026-03-23 重要更新

### 新增 desktop-ui 原生模块（Compose Desktop）
- 新增 `desktop-ui/` 独立模块，作为商业原生桌面 UI 起点。
- 完成 CDJ Dashboard V1 可运行版（实时监控优先，不做美化）：
  - 固定展示 Player 1~4 卡片
  - 实时显示：在线/离线、PLAY/PAUSE/STOP/CUED、On-Air、Master
  - 每卡片字段：title、artist、current/remain、raw BPM、pitch、effective BPM
  - 顶部全局栏：master player、master BPM、scan 状态、最近更新时间、数据中断提示
  - 刷新间隔可切换：200/300/500ms（默认 300ms）
- 复用真实接口：
  - `/api/players/state`
  - `/api/scan`

## 2026-03-22 重要更新

### Master 判定对齐 BLT
- **方式**: TimeFinder.getLatestUpdateFor(playerNum) + isTempoMaster()
- **优先级**: TimeFinder > VirtualCdj.getTempoMaster() > MasterListener
- **效果**: WebUI MASTER 标签实时跟随 CDJ 切换

### 统一时间基准修复
- **方式**: TimeFinder.getTimeFor(playerNum) 获取真实传输时间
- **效果**: LTC/MTC/Dashboard 时间与 CDJ 真实时间一致

### WebUI 改进
- 添加按钮视觉反馈（saveIoBtn, aiGenerateBtn, ma2SendTestBtn）

---

## 一、项目当前定位与目标

**项目定位**：面向演出场景的播放状态中枢与同步输出平台，基于 Pioneer DJ Link 协议，支持多种输出协议（Ableton Link、Titan API、MA2、Console API、LTC、MTC）。

**当前目标**：
1. 继续收敛 LTC 停止态零点抖动问题（仅 LTC 层）
2. 验证 AbletonLink / Titan / MA2 输出功能
3. 字段统一化改造已完成（timecode.sourcePlayer 和 sync.masterPlayer 已统一为 sync.masterPlayer）

---

## 二、技术栈

| 类别 | 技术 |
|------|------|
| **语言/构建** | Java 17 + Maven |
| **核心库** | beat-link 0.10.4 (Deep Symmetry) |
| **Web框架** | Jetty 9.4.51 (HTTP + WebSocket) |
| **前端** | HTML/CSS/JS (单页应用) |
| **实时通信** | WebSocket (状态推送) |

---

## 三、项目整体系统架构

```
dj-link-stage/
├── dbclient/                    # 核心业务逻辑
│   ├── src/main/java/dbclient/
│   │   ├── connection/          # DJ Link 连接层
│   │   │   ├── DbConnection.java
│   │   │   └── DbProtocol.java
│   │   ├── core/               # 核心状态管理
│   │   │   ├── DeviceManager.java      # DJ Link 设备管理
│   │   │   └── SystemState.java
│   │   ├── input/              # 输入源层
│   │   │   ├── DjLinkSourceInput.java     # DJ Link 播放源
│   │   │   ├── LocalSourceInput.java       # 本地媒体播放源
│   │   │   └── SourceInputManager.java     # 输入源管理器
│   │   ├── media/              # 本地媒体模块
│   │   │   ├── library/        # 曲库服务
│   │   │   │   ├── LocalLibraryService.java
│   │   │   │   ├── InMemoryTrackRepository.java
│   │   │   │   └── TrackRepository.java
│   │   │   ├── model/          # 媒体数据模型
│   │   │   │   ├── TrackInfo.java
│   │   │   │   ├── PlaybackStatus.java
│   │   │   │   └── AnalysisResult.java
│   │   │   ├── player/         # 播放引擎
│   │   │   │   ├── PlaybackEngine.java
│   │   │   │   └── BasicLocalPlaybackEngine.java
│   │   │   └── analysis/       # 音频分析
│   │   │       ├── AudioAnalyzer.java
│   │   │       └── BasicAudioAnalyzer.java
│   │   ├── sync/               # 同步输出核心
│   │   │   ├── SyncOutputManager.java      # 同步输出管理器
│   │   │   ├── OutputDriver.java           # 驱动接口
│   │   │   ├── timecode/
│   │   │   │   └── TimecodeCore.java       # 时间码核心（LTC/MTC共用）
│   │   │   └── drivers/                    # 输出驱动
│   │   │       ├── LtcDriver.java           # LTC 音频时间码
│   │   │       ├── MtcDriver.java           # MTC MIDI时间码
│   │   │       ├── AbletonLinkDriver.java   # Ableton Link
│   │   │       ├── TitanApiDriver.java      # Titan灯光控台
│   │   │       ├── Ma2BpmDriver.java        # MA2 BPM
│   │   │       ├── Ma2TelnetClient.java     # MA2 Telnet
│   │   │       ├── CarabinerLinkEngine.java # Carabiner链接
│   │   │       ├── ConsoleApiDriver.java   # Console API
│   │   │       ├── LtcFrameEncoder.java     # LTC帧编码器
│   │   │       ├── LtcBmcEncoder.java       # LTC BMC调制器
│   │   │       ├── LtcFrameEncoder.java     # LTC帧编码
│   │   │       ├── LtcBmcEncoder.java       # BMC调制
│   │   │       ├── AudioDeviceEnumerator.java
│   │   │       └── MidiDeviceEnumerator.java
│   │   ├── websocket/         # WebSocket服务
│   │   │   ├── JettyServer.java
│   │   │   └── EventPusher.java
│   │   ├── web/                # HTTP服务
│   │   │   └── WebServer.java
│   │   ├── parser/             # 数据解析
│   │   │   └── MetadataParser.java
│   │   ├── ai/                 # AI服务
│   │   │   └── AiAgentService.java
│   │   ├── config/             # 配置管理
│   │   │   └── UserSettingsStore.java
│   │   ├── Main.java           # 入口
│   │   └── DbClient.java
│   └── src/main/resources/web/
│       └── index.html           # WebUI
├── djlink-service/             # DJ Link 协议层
│   └── src/main/java/djlink/
│       ├── DeviceManager.java
│       └── MetadataWarmupService.java
├── config/                     # 运行配置
├── scripts/                    # 启动脚本
├── docs/                       # API文档
└── memory/                     # 项目记忆
```

---

## 四、功能模块说明

### 4.1 DJ Link 设备管理 (DeviceManager)
- **职责**：发现和管理 Pioneer DJ Link 网络中的设备（CDJ/NXS2/XDJ）
- **位置**：`djlink-service/src/main/java/djlink/DeviceManager.java`

### 4.2 同步输出框架 (SyncOutputManager)
- **职责**：统一管理所有同步输出驱动，处理播放源选择和状态派生
- **位置**：`dbclient/src/main/java/dbclient/sync/SyncOutputManager.java`
- **支持模式**：
  - 跟随 Master（自动检测 Master Deck）
  - 手动选择 Player（1-4）

### 4.3 时间码核心 (TimecodeCore)
- **职责**：LTC/MTC 共用的时间源核心，管理播放源选择和事件检测
- **位置**：`dbclient/src/main/java/dbclient/sync/timecode/TimecodeCore.java`
- **特性**：
  - 本地单调时钟线性推进
  - 事件检测（播放/暂停/停止/跳点）
  - 手动测试模式（不依赖 DJ Link）

### 4.4 输出驱动

| 驱动 | 功能 | 状态 |
|------|------|------|
| **LtcDriver** | LTC 音频时间码输出（25fps） | ✅ 运行态可用，停止态需修复 |
| **MtcDriver** | MTC MIDI 时间码输出 | ✅ 正常 |
| **AbletonLinkDriver** | Ableton Link BPM 同步 | ✅ 正常 |
| **TitanApiDriver** | Titan 灯光控台同步 | ✅ 正常 |
| **Ma2BpmDriver** | MA2 BPM 同步 | ✅ 正常 |
| **ConsoleApiDriver** | Console API 输出 | ✅ 正常 |

### 4.5 本地媒体模块
- **LocalLibraryService**：本地曲库服务
- **BasicLocalPlaybackEngine**：Java Sound 播放引擎
- **BasicAudioAnalyzer**：音频分析（BPM 返回 null）
- **InMemoryTrackRepository**：内存曲库存储

### 4.6 WebUI 页面
- **I/O 页面**：Sync Output Manager + Realtime Sync Status
- **Deck 页面**：4 个 Deck 状态显示
- **Config 页面**：AI Rules 配置

---

## 五、系统数据流

```
DJ Link 网络
     ↓
DeviceManager (设备发现)
     ↓
SyncOutputManager (状态聚合)
     ↓
┌─────────────────────────────────────┐
│  播放源选择逻辑                      │
│  - 跟随 Master Deck                │
│  - 手动选择 Player 1-4             │
└─────────────────────────────────────┘
     ↓
TimecodeCore (时间码核心)
     ↓
┌─────────────────────────────────────┐
│  消费者 (OutputDriver)              │
│  - LtcDriver → 音频输出             │
│  - MtcDriver → MIDI输出             │
│  - AbletonLinkDriver → Link网络     │
│  - TitanApiDriver → HTTP API        │
│  - Ma2BpmDriver → Telnet            │
└─────────────────────────────────────┘
     ↓
WebSocket 推送 + API 状态查询
```

---

## 六、当前实现情况

### 6.1 已实现功能
- ✅ DJ Link 设备发现和状态监听
- ✅ 播放源选择（Master / Manual）
- ✅ LTC 音频时间码输出（运行态基本正常）
- ✅ MTC MIDI 时间码输出
- ✅ Ableton Link BPM 同步
- ✅ Titan API 同步
- ✅ MA2 BPM 同步
- ✅ Console API 输出
- ✅ WebUI 实时状态显示
- ✅ 手动测试时间码模式（不依赖 DJ Link）
- ✅ LTC 声道模式（mono / stereo-left / stereo-right / stereo-both）
- ✅ 字段统一化（timecode.sourcePlayer + sync.masterPlayer → sync.masterPlayer）

### 6.2 已部分实现/未完成功能
- ⚠️ **LTC 停止态边界处理**：停止时在 -0/0 抖动，需继续修复
- ⚠️ **本地播放器 BPM 分析**：BPM 返回 null，需完善
- ⏳ **多帧率 LTC**：当前仅支持 25fps non-drop

### 6.3 当前已知问题/风险

| 问题 | 状态 | 说明 |
|------|------|------|
| LTC 停止态抖动 | ⚠️ 需修复 | 停止测试时在 -0/0 之间抖动 |
| LTC 编码潜在问题 | ⚠️ 需观察 | 运行态基本正常，但编码层偶发问题 |
| 本地播放器 BPM | ⏳ 待完善 | 分析器返回 null |

---

## 七、当前代码结构关键类说明

### 7.1 核心类
| 类 | 职责 |
|---|---|
| `DeviceManager` | DJ Link 设备扫描、状态监听 |
| `SyncOutputManager` | 同步输出总控、驱动生命周期管理 |
| `TimecodeCore` | 时间码核心（播放源+线性时钟+事件检测） |
| `LtcDriver` | LTC 编码+音频输出 |
| `MtcDriver` | MTC 编码+MIDI输出 |
| `SourceInputManager` | 输入源统一管理 |

### 7.2 关键配置
- **配置存储**：`UserSettingsStore.java`
- **API 端点**：`JettyServer.java`
- **WebUI**：`index.html`

---

## 八、当前测试状态

### 8.1 测试能力
- ✅ 手动测试模式（不依赖 DJ Link）
- ✅ LTC 声道模式切换
- ✅ roundtrip 自检工具
- ✅ 标准样本对照（/home/shenlei/下载/LTC_00000000_5mins_25fps_48000x16.wav）
- ✅ 实时 status 指标（write interval / buffer occupancy / underrun）

### 8.2 测试结论
- 运行态 LTC 基本正常，可均匀线性前进
- 停止态需继续修复零点抖动

---

## 九、项目演进关键转折点

| 时间 | 转折点 | 结果 |
|------|--------|------|
| 2026-03-07 | 早期探索 | beat-link + DeviceFinder + MetadataFinder |
| 2026-03-14 | 大规模实现 | 30M 会话，实现完整 LTC/MTC/AbletonLink/Titan/MA2 |
| 2026-03-15 AM | LTC 清理尝试 | 删除 LTC/MTC → 回退 → 基线净化 |
| 2026-03-15 PM | LTC 重构 | 重新实现 LTC，修复编码位序 |
| 2026-03-16 | LTC 收敛 | 编码层自检通过，实时链路优化，停止态修复尝试 |
| 2026-03-16 晚 | 字段统一化 | timecode.sourcePlayer + sync.masterPlayer → 统一为 sync.masterPlayer |
| 2026-03-17 | 文档整理 | 产出手册，清理历史 |

---

## 十、下一步开发计划

### 优先级 1（必须）
1. **继续修复 LTC 停止态抖动**
   - 当前运行版本 b11e0d7
   - 停止态采用策略：持续输出 00:00:00:00
   - 需继续收敛 -0/0 边界问题

### 优先级 2（重要）
2. **测试 AbletonLink / Titan / MA2 输出**
   - 验证各驱动在真实设备上正常工作

3. **完善本地播放器**
   - 修复 BPM 分析返回 null 问题

### 优先级 3（可选）
4. **扩展 LTC 帧率支持**
   - 当前仅 25fps，可扩展 24/29.97/30 fps

5. **UI 优化**
   - 增加更多状态可视化

---

## 十一、Git 仓库信息

- **仓库地址**：`https://github.com/lyl3chen/DJ-LINK-STAGE-Clean-Baseline.git`
- **当前 HEAD**：`b11e0d7`（LTC 停止态修复版本）
- **关键提交历史**：
  - `abdd7fb`：字段统一化改造
  - `b5a4b85`：本地播放器测试功能
  - `ddabfbe`：LTC 编码重构（最终有效版本）
  - `b11e0d7`：LTC 停止态边界修复

---

## 十二、API 端点

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/sync/state` | GET | 同步状态 |
| `/api/config` | GET/POST | 配置读写 |
| `/api/players` | GET | 播放器列表 |
| `/api/timecode/manual-test` | POST | 手动测试模式 |
| `/api/ma2/test-bpm` | POST | MA2 测试 |

---

*本文档最后更新：2026-03-17*

# DJ-Link Stage 项目开发手册

> 本文档为项目核心架构与开发指南，确保新会话可在无上下文情况下继续开发。
> 
> 最后更新：2026-03-19

---

## 一、项目定位

**DJ-Link Stage** 是一个支持双播放源的实时同步与触发系统：

1. **CDJ / beat-link 实时源**  
   通过 beat-link 实时获取曲目信息、播放状态、位置、BPM、拍点，触发外部协议

2. **本地播放器源**  
   播放本地音频文件，读取分析结果、Marker 数据，触发外部协议

**最终目标**：两条链路的数据统一流入 **Trigger Engine**，驱动外部协议（Titan / MA2 / LTC / MTC / OSC 等）

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        播放源层                                   │
│  ┌─────────────────────┐    ┌─────────────────────────────┐   │
│  │   CDJ / beat-link   │    │     Local Player            │   │
│  │   (实时数据流)       │    │     (本地文件 + 分析结果)    │   │
│  └──────────┬──────────┘    └─────────────┬───────────────┘   │
│             │                               │                    │
│             ▼                               ▼                    │
├─────────────────────────────────────────────────────────────────┤
│                    统一运行时状态层                               │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │           SourceInputManager / SyncOutputManager            ││
│  │           TimecodeCore (MTC/LTC 生成)                       ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                    │
│             ┌────────────────┼────────────────┐                 │
│             ▼                ▼                ▼                   │
├─────────────────────────────────────────────────────────────────┤
│  A. 连续同步输出层          │  B. 触发事件输出层                  │
│  (每帧/每拍持续输出)        │  (事件驱动一次性触发)                │
│  ┌─────────────────────┐    │  ┌─────────────────────────────┐   │
│  │ TitanBpmSyncDriver │    │  │    TriggerEngine           │   │
│  │ Ma2BpmSyncDriver   │    │  │    TriggerContextAdapter   │   │
│  │ AbletonLinkDriver  │    │  │    CdjTriggerContextAdapter│   │
│  │ LtcDriver          │    │  │    LocalTriggerAdapter     │   │
│  │ MtcDriver          │    │  │    TriggerActionDispatcher │   │
│  └─────────────────────┘    │  │    TitanTriggerActionDrv   │   │
│                             │  │    Ma2TriggerActionDrv     │   │
│                             │  └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  协议客户端层    │
                    │  TitanClient    │
                    │  Ma2TelnetClient│
                    │  (未来: OscClient)│
                    └─────────────────┘
```

---

## 三、现有稳定模块（禁止破坏）

### 3.1 核心链路（已验证通过）

| 模块 | 路径 | 说明 |
|------|------|------|
| `SourceInputManager` | `dbclient/src/main/java/dbclient/input/` | 主输入切换，已稳定 |
| `SyncOutputManager` | `dbclient/src/main/java/dbclient/sync/` | 统一运行时状态管理 |
| `TimecodeCore` | `dbclient/src/main/java/dbclient/sync/timecode/` | MTC/LTC 生成逻辑 |
| `BasicLocalPlaybackEngine` | `dbclient/src/main/java/dbclient/media/player/` | 本地播放/seek/stop/pause |

### 3.2 输出驱动（已验证通过）

| 模块 | 说明 |
|------|------|
| `TitanAdapter` / `TitanApiDriver` | Titan BPM 推送 |
| `Ma2BpmDriver` / `Ma2TelnetClient` | MA2 BPM 推送 |
| `AbletonLinkDriver` | Ableton Link 同步 |
| `LtcDriver` | LTC 时间码输出 |
| `MtcDriver` | MTC 时间码输出 |

### 3.3 本地播放器（Phase B 完成）

- `BasicLocalPlaybackEngine`：播放/seek/stop/pause 语义已修复
- `JettyServer` 本地 API：`/api/local/load`, `/api/local/play`, `/api/local/pause`, `/api/local/stop`, `/api/local/status`
- WebUI 本地播放器页面：调试界面

---

## 四、开发进度

### Phase A：稳定化收口 ✅
- 统一状态模型
- Bug 修复（MTC/LTC PAUSED 行为、设备选择器）

### Phase B：本地播放器 Bug 修复 ✅
- Stop/Play 语义修复
- Seek 实现修正
- Delete 按钮修复
- 首播杂音修复

### Phase C：曲目分析模块

| 阶段 | 状态 | 说明 |
|------|------|------|
| C1 | ✅ 完成 | BPM 基线 + 分析状态机 + UI 入口 |
| C2 | 待做 | Repository 层 + Marker CRUD |
| C3 | 待做 | 分析 MVP 扩展（Beat Grid + Waveform） |
| C4 | 待做 | Marker 打点系统 |
| C5-C7 | 待做 | Trigger 架构 |

---

## 五、数据模型（Commit 1 完成）

### 5.1 现有模型

| 类 | 用途 |
|---|---|
| `TrackInfo` | 播放时使用的轻量曲目对象 |
| `PlaybackStatus` | 播放状态枚举 |
| `AnalysisStatus` | 分析状态枚举 |

### 5.2 新增模型（2026-03-19）

| 类 | 用途 |
|---|---|
| `MarkerType` | Marker 类型枚举 |
| `MarkerPoint` | Marker 数据模型 |
| `BeatGrid` | Beat Grid + 时间换算 |
| `TrackLibraryEntry` | 统一曲目资产（文件+分析+Markers） |

### 5.3 AnalysisResult 扩展

```java
// 新增字段（向后兼容）
private BeatGrid beatGrid;  // null 表示未生成
```

---

## 六、API 概览

### 6.1 播放控制

| API | 说明 |
|-----|------|
| `POST /api/local/load` | 加载曲目 |
| `POST /api/local/play` | 播放 |
| `POST /api/local/pause` | 暂停 |
| `POST /api/local/stop` | 停止 |
| `POST /api/local/seek` | 定位 |
| `GET /api/local/status` | 状态查询 |

### 6.2 曲库管理

| API | 说明 |
|-----|------|
| `POST /api/local/import` | 导入文件 |
| `GET /api/local/tracks` | 曲目列表 |
| `POST /api/local/delete` | 删除曲目 |
| `POST /api/local/analyze` | 触发分析 |
| `GET /api/local/analysis?trackId=` | 查询分析结果 |

### 6.3 同步状态

| API | 说明 |
|-----|------|
| `GET /api/sync/state` | 同步状态 |
| `GET /api/config` | 全局配置 |
| `GET /api/players/state` | CDJ 状态 |

---

## 七、后续开发计划（按 Commit 顺序）

### Commit 2: Repository 层 ✅
- [x] `MarkerRepository` 接口
- [x] `InMemoryMarkerRepository` 实现
- [x] `TrackLibraryRepository` 接口
- [x] `InMemoryTrackLibraryRepository` 实现

### Commit 3: Service 层 ✅
- [x] `TrackLibraryService` 统一入口
- [x] `AnalysisService` 分析调度 + BeatGrid 生成 + 校验
- [x] 改造 `LocalLibraryService` 接入（可选注入）

### Commit 4: Marker CRUD API
- [ ] `POST /api/local/markers` - 创建
- [ ] `GET /api/local/markers?trackId=` - 查询
- [ ] `PUT /api/local/markers/{id}` - 修改
- [ ] `DELETE /api/local/markers/{id}` - 删除

### Commit 5: 分析 MVP 扩展
- [ ] 扩展 `BasicAudioAnalyzer` 输出 BeatGrid
- [ ] 生成 Waveform Preview

### Commit 6: Trigger 基础设施
- [ ] `TriggerContext` 统一上下文
- [ ] `TriggerContextAdapter` 接口
- [ ] `LocalTriggerContextAdapter` 实现

### Commit 7: Trigger Engine 核心
- [ ] `TriggerEngine`
- [ ] `TriggerRule` / `TriggerCondition` / `TriggerAction`
- [ ] `TriggerActionDispatcher`

### Commit 8: Trigger Action Drivers
- [ ] `TriggerActionDriver` 接口
- [ ] `TitanTriggerActionDriver`
- [ ] `Ma2TriggerActionDriver`

### Commit 9: 协议客户端层抽象
- [ ] 移动/改造 `TitanClient`
- [ ] 移动 `Ma2TelnetClient`

### Commit 10: CDJ Adapter
- [ ] `CdjTriggerContextAdapter`

### Commit 11: UI 分化
- [ ] 本地播放器波形 + Marker UI
- [ ] CDJ 模式页面

---

## 八、本地开发与测试

### 8.1 服务启动

```bash
cd /home/shenlei/.openclaw/agents/dev/workspace/dj-link-stage/dbclient
mvn exec:java -Dexec.mainClass=dbclient.Main
```

服务地址：`http://192.168.100.200:8080`

### 8.2 测试音频

```
/home/shenlei/音乐/SHENLEI - 朴树-New Boy（SHENLEI remix）.wav
```

### 8.3 USB 声卡

```
Device [plughw:3,0]
```

### 8.4 编译与测试

```bash
mvn -q compile
mvn -q test
```

### 8.5 WebUI

```
http://192.168.100.200:8080/
```

本地播放器页面：`http://192.168.100.200:8080/#local`

---

## 九、已知限制

1. **分析格式**：仅支持 WAV/AIFF/AU（Java Sound API 原生）
2. **分析时长**：最多 120 秒（避免分析过慢）
3. **WebUI 定位**：仅作为测试/调试界面，不按产品 UI 标准维护

---

## 十、重要文档索引

| 文档 | 说明 |
|------|------|
| `docs/ARCH_TRIGGER_DESIGN.md` | 触发架构完整设计 |
| `docs/UNIFIED_STATE_MODEL.md` | 统一状态模型 |
| `docs/PHASE_A_STABILIZATION.md` | Phase A 稳定化总结 |
| `docs/PHASE_C1_BPM_BASELINE.md` | C1 BPM 基线实现 |
| `docs/LOCAL_PLAYER_SUBSYSTEM.md` | 本地播放器子系统文档 |

---

## 十一、Git Commit 历史（关键节点）

| Commit | 说明 |
|--------|------|
| `6c219f6` | Commit 3: Service 层 |
| `6f9fb80` | Commit 2: Repository 层 |
| `a824f83` | Commit 1: 数据模型层 |
| `135e1b2` | UI 分析入口 |
| `e544930` | C1 后端 BPM 实现 |
| `90ecf4f` | Seek 功能修复 |
| `dce871e` | Delete 按钮修复 |
| `5823c83` | 设备打开根因修复 |

---

## 十二、注意事项

1. **禁止破坏现有链路**：任何改动不能影响 SourceInputManager / SyncOutputManager / TimecodeCore / 现有输出驱动
2. **Trigger 层只能读取**：Trigger Engine 只能读取现有模块的状态，不能改造现有模块
3. **数据模型向后兼容**：新增字段必须 nullable，避免影响现有读取逻辑
4. **小步提交**：每个 Commit 独立可测试，不做大幅度合并
5. **先文档后代码**：重大架构改动先输出设计文档，确认后再实现

---

*本文档随项目开发持续更新*

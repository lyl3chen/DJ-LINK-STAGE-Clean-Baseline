# DJ-Link Stage 项目开发手册

> 本文档为项目核心架构与开发指南，确保新会话可在无上下文情况下继续开发。
> 
> **最后更新：2026-03-19**
> 
> **当前阶段：统一触发框架已验证通过，后续优先 Local Trigger UI**

---

## 一、项目定位

**DJ-Link Stage** 是一个**以统一输入源为核心的实时同步与演出控制中枢**。

### 核心能力

1. **双播放源支持**
   - CDJ / beat-link：实时数据流，高信息密度
   - Local Player：本地文件 + 分析资产 + Marker

2. **两条并行下游能力**（职责不同，不能混为一谈）
   - **Sync Outputs**：连续同步类输出（BPM 推送、时间码推送）
   - **Trigger Outputs**：事件触发类输出（Marker 触发、段落触发、动作触发）

3. **统一 Trigger 框架**
   - 同一套 TriggerEngine 消费 CDJ 和 Local 两种 TriggerContext
   - 字段丰富度可以不同，但触发语义统一

---

## 二、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        播放源层                                   │
│  ┌─────────────────────┐    ┌─────────────────────────────┐   │
│  │   CDJ / beat-link  │    │     Local Player            │   │
│  │   (实时高密度)      │    │     (资产+Marker)           │   │
│  └──────────┬──────────┘    └─────────────┬───────────────┘   │
│             │                               │                    │
│             ▼                               ▼                    │
├─────────────────────────────────────────────────────────────────┤
│                    统一运行时状态层                               │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │           SourceInputManager / SyncOutputManager            ││
│  │           TimecodeCore (MTC/LTC 生成)                        ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                    │
│             ┌────────────────┼────────────────┐                 │
│             ▼                ▼                ▼                   │
├─────────────────────────────────────────────────────────────────┤
│  A. 连续同步输出层 (Sync)   │  B. 触发事件输出层 (Trigger)       │
│  ┌─────────────────────┐    │  ┌─────────────────────────────┐   │
│  │ TitanBpmSyncDriver │    │  │    TriggerEngine            │   │
│  │ Ma2BpmSyncDriver   │    │  │    TriggerContext          │   │
│  │ AbletonLinkDriver  │    │  │    CdjTriggerContextAdapter│   │
│  │ LtcDriver          │    │  │    LocalTriggerAdapter     │   │
│  │ MtcDriver          │    │  │    TriggerActionDispatcher │   │
│  └─────────────────────┘    │  │    TitanTriggerActionDrv  │   │
│                             │  │    Ma2TriggerActionDrv    │   │
│                             │  └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │  协议客户端层      │
                    │  ProtocolClient │
                    │  TitanClient    │
                    │  Ma2Client      │
                    └─────────────────┘
```

---

## 三、现有稳定模块（禁止破坏）

### 3.1 核心链路

| 模块 | 说明 |
|------|------|
| `SourceInputManager` | 主输入切换 |
| `SyncOutputManager` | 统一运行时状态管理 |
| `TimecodeCore` | MTC/LTC 生成逻辑 |
| `BasicLocalPlaybackEngine` | 本地播放/seek/stop/pause |

### 3.2 Sync Outputs（连续同步）

| 模块 | 说明 |
|------|------|
| `TitanBpmSyncDriver` | Titan BPM 推送 |
| `Ma2BpmSyncDriver` | MA2 BPM 推送 |
| `AbletonLinkDriver` | Ableton Link |
| `LtcDriver` | LTC 时间码 |
| `MtcDriver` | MTC 时间码 |

### 3.3 Trigger Outputs（事件触发）

| 模块 | 状态 | 说明 |
|------|------|------|
| `TriggerEngine` | ✅ 已验证 | 核心引擎 |
| `TriggerContext` | ✅ 完成 | 统一上下文 |
| `CdjTriggerContextAdapter` | ✅ 完成 | CDJ 数据适配 |
| `LocalTriggerContextAdapter` | ✅ 完成 | Local 数据适配 |
| `TriggerActionDispatcher` | ✅ 完成 | 动作分发 |
| `TitanTriggerActionDriver` | 骨架 | 待接入真实 Client |
| `Ma2TriggerActionDriver` | 骨架 | 待接入真实 Client |
| `ProtocolClient` | 接口抽象 | 待实现 |

---

## 四、开发进度（完整）

### Phase A：稳定化收口 ✅
- 统一状态模型
- Bug 修复

### Phase B：本地播放器 Bug 修复 ✅
- Stop/Play 语义修复
- Seek 实现修正
- Delete 按钮修复
- 首播杂音修复

### Phase C：曲目分析模块 ✅
- C1: BPM 基线 + 分析状态机 + UI 入口 ✅
- C2: Repository 层 ✅
- C3: Service 层 ✅
- C4: Marker CRUD API ✅
- C5: 分析 MVP 扩展（BeatGrid + Waveform）✅

### Phase D：统一 Trigger 框架 ✅
- D1: Trigger 基础设施 ✅
- D2: Trigger Engine 核心 ✅
- D3: Trigger Action Drivers 骨架 ✅
- D4: 协议客户端抽象 ✅
- D5: CDJ Trigger Adapter 落地 ✅
- D6: 双源 Trigger 验证 ✅

### Phase E：（下一步）Local Trigger UI
- 波形展示
- Marker 打点/编辑

---

## 五、数据模型

### 5.1 统一 Trigger 上下文

```java
class TriggerContext {
    // 来源
    TriggerSource source;  // CDJ / LOCAL
    
    // 曲目信息
    String trackId;
    String title;
    String artist;
    long durationMs;
    
    // 播放状态
    PlaybackStatus.State playbackState;
    long positionMs;
    double phase;
    
    // 节拍信息
    Integer bpm;
    Integer beatNumber;
    Integer measureNumber;
    long nextBeatMs;
    long nextMeasureMs;
    
    // 分析数据
    AnalysisResult analysis;
    BeatGrid beatGrid;           // Local 有，CDJ 无
    WaveformPreview waveformPreview; // Local 有，CDJ 无
    
    // Markers
    List<MarkerPoint> markers;   // Local 有，CDJ 无
}
```

### 5.2 本地资产模型

| 类 | 用途 |
|---|---|
| `MarkerType` | MARKER/CUE/TRIGGER/SECTION 等 |
| `MarkerPoint` | Marker 数据 |
| `BeatGrid` | 节拍网格 + 时间换算 |
| `WaveformPreview` | 波形峰值数组 |
| `TrackLibraryEntry` | 统一曲目资产 |

---

## 六、API 概览

### 6.1 播放控制
- `/api/local/load`, `/play`, `/pause`, `/stop`, `/seek`, `/status`

### 6.2 曲库管理
- `/api/local/import`, `/tracks`, `/delete`

### 6.3 分析
- `/api/local/analyze`, `/api/local/analysis?trackId=`

### 6.4 Marker CRUD
- `GET /api/local/markers?trackId=`
- `POST /api/local/markers`
- `POST /api/local/markers/update`
- `POST /api/local/markers/delete`

---

## 七、Trigger 语义基线（已确认）

| 操作 | 语义 |
|------|------|
| Seek 到更早位置 | 按位置重置 |
| Stop 后 Play 同一曲 | **视为新周期，清空触发状态** |
| 切歌 | 清空全部触发状态 |
| 字段缺失 | TriggerEngine 降级运行，不崩溃 |

---

## 八、CDJ 与 Local 定位差异

| 维度 | CDJ | Local |
|------|-----|-------|
| 信息来源 | beat-link 实时 | 本地分析资产 |
| 字段丰富度 | 高（beatNumber/phase 实时） | 中（需分析后才完整） |
| 适用场景 | 现场实时触发 | 预编程/预触发/marker 编辑 |
| MARKER 支持 | ❌ | ✅ |
| BeatGrid | ❌（实时计算） | ✅（预生成） |
| Waveform | ❌ | ✅ |

---

## 九、开发流程规范

1. **先架构/语义收口** → **再最小实现** → **再验证** → **最后 UI**
2. **每 commit 独立可测试**
3. **每次只推进一个小阶段**
4. **未审阅前不连续跳阶段**
5. **禁止破坏现有稳定模块**

---

## 十、Git Commit 历史

| Commit | 说明 |
|--------|------|
| `35793af` | 双源 Trigger 验证 |
| `fc635a4` | Stop->Play 语义 |
| `427f3e3` | CDJ Trigger Adapter |
| `3e847c3` | 协议客户端抽象 |
| `39be38c` | Trigger Action Drivers |
| `9bdf2ba` | Trigger Engine 核心 |
| `6f8149d` | Trigger 基础设施 |
| `d6ce679` | 分析 MVP 扩展 |
| `ba0954b` | Marker CRUD API |
| `6c219f6` | Service 层 |
| `6f9fb80` | Repository 层 |
| `a824f83` | 数据模型层 |

---

## 十一、后续建议

**当前优先级：Local Trigger UI**

| 阶段 | 内容 |
|------|------|
| B1 | Marker 列表展示 + 编辑 UI |
| B2 | 波形展示（Canvas 渲染 peaks） |
| B3 | 点击打点 + 删除/改名 |

**为什么不先做 Trigger 输出落地？**
- 风险更低：UI 不改动后端稳定逻辑
- 依赖更少：API 已完成，仅需前端调用
- 价值更直接：用户可直接使用

# 统一触发架构设计方案

基于用户明确的架构方向，以下是按现有项目结构可落地的设计。

---

## 一、现有架构回顾

```
dbclient/
├── media/
│   ├── model/        # 数据模型（TrackInfo, AnalysisResult, AnalysisStatus）
│   ├── analysis/     # 分析器接口 + 实现（BasicAudioAnalyzer）
│   ├── library/     # 曲库服务 + 仓储（LocalLibraryService, InMemoryTrackRepository）
│   └── player/       # 播放器引擎（BasicLocalPlaybackEngine）
├── sync/
│   ├── SyncOutputManager.java    # 统一运行时状态管理
│   ├── OutputDriver.java         # 输出驱动接口
│   └── drivers/
│       ├── TitanAdapter.java     # Titan 协议
│       ├── TitanApiDriver.java  # Titan API
│       ├── Ma2BpmDriver.java    # MA2 BPM 同步
│       ├── Ma2TelnetClient.java # MA2 Telnet 客户端
│       ├── AbletonLinkDriver.java
│       ├── LtcDriver.java       # LTC 输出
│       └── MtcDriver.java       # MTC 输出
```

**现状**：
- `SyncOutputManager` 管理统一运行时状态
- 各类 Driver 实现 `OutputDriver` 接口，消费统一状态做连续同步输出

---

## 二、Phase C1 设计：本地曲目资产层

### 2.1 现有模块利用

| 现有模块 | 用途 | 需改造 |
|---------|------|--------|
| `TrackInfo` | 曲目基础信息 | 扩展支持 trackId + filePath 持久化 |
| `AnalysisResult` | 分析结果 | 已有 bpm/bpmConfidence/durationMs |
| `InMemoryTrackRepository` | 内存仓储 | C1 阶段先用内存，C2 改为持久化 |

### 2.2 新增模块

```
dbclient/src/main/java/dbclient/media/
├── model/
│   ├── MarkerPoint.java          # 新增：Marker 数据模型
│   └── TrackLibraryEntry.java   # 新增：曲目库条目（关联 track + analysis + markers）
├── library/
│   ├── MarkerRepository.java     # 新增：Marker 仓储接口
│   ├── InMemoryMarkerRepository.java  # 新增：内存实现
│   ├── TrackLibraryRepository.java    # 新增：曲目资产仓储接口
│   ├── InMemoryTrackLibraryRepository.java  # 新增：内存实现
│   ├── TrackLibraryService.java  # 新增：曲目库统一入口
│   └── LocalLibraryService.java # 改造：接入 TrackLibraryService
└── analysis/
    └── AnalysisService.java      # 新增：分析任务调度（从 LocalLibraryService 拆分）
```

### 2.2.1 MVP 约束说明

**filePath 唯一性策略（MVP）**
- 当前以 filePath 作为本地文件唯一识别依据
- 限制：文件改名/移动路径后会被识别为新条目
- 后续升级：引入 fileHash 主判定或组合判定

**BeatGrid 无效值语义**
- `beatGrid == null` 或 `!beatGrid.isValid()`：表示无有效 Beat Grid
- `timeToBeat()` 越界：返回保守值（0），需结合 isValid() 判断
- `beatToTime()` 越界：使用线性计算
- Service 层需统一兜底，明确"有效"vs"无效"的语义

**fileHash 查询预留**
- Repository 接口暂不增加 findByFileHash（MVP 阶段不需要）
- fileHash 作为辅助字段存储，供后续升级使用

### 2.3 MarkerPoint 数据模型

```java
public class MarkerPoint {
    private String id;              // UUID
    private String trackId;         // 关联曲目
    private String name;            // 名称
    private long timeMs;            // 毫秒位置
    private MarkerType type;        // 类型枚举
    private String note;            // 备注
    private boolean enabled;         // 是否启用
    private long createdAt;
    private long updatedAt;
}

public enum MarkerType {
    MARKER,      // 通用标记
    CUE,         // Cue 点
    TRIGGER,     // 触发点
    SECTION_START, SECTION_END,  // 段落边界
    DROP, BUILD, BREAK,         // 能量标记
    CUSTOM     // 自定义
}
```

### 2.4 TrackLibraryEntry 数据模型

```java
public class TrackLibraryEntry {
    private String trackId;
    private String filePath;
    private String title;
    private String artist;
    private long durationMs;
    private int sampleRate;
    private int channels;
    
    // 关联数据
    private AnalysisResult analysis;
    private List<MarkerPoint> markers;
}
```

### 2.5 分析 MVP 第一阶段产出

| 数据 | 字段 | 用途 |
|------|------|------|
| BPM | `analysis.bpm` | 触发计算基准 |
| BPM 置信度 | `analysis.bpmConfidence` | 判定是否可靠 |
| 时长 | `analysis.durationMs` | 波形渲染 |
| Beat Grid | `analysis.beatGrid[]` | 拍点位置数组（毫秒） |
| Waveform Preview | `analysis.waveformCachePath` | 波形文件路径 |

**Beat Grid 结构**：
```java
public class BeatGrid {
    private int bpm;
    private long[] beatPositionsMs;  // 每拍时间点
    private long firstBeatMs;        // 第一个 Beat 时间
    private int beatsPerMeasure;    // 每小节拍数（默认 4）
}
```

---

## 三、Phase C2 设计：Trigger 架构

### 3.1 统一触发上下文（Unified Trigger Context）

```java
public class TriggerContext {
    // 来源标识
    private TriggerSource source;  // CDJ / LOCAL
    
    // 曲目信息
    private String trackId;
    private String title;
    private String artist;
    private long durationMs;
    
    // 播放状态
    private PlaybackState playbackState;  // STOPPED / PLAYING / PAUSED
    private long positionMs;              // 当前毫秒位置
    private double phase;                  // 0.0-1.0，当前拍内相位
    
    // 节拍信息
    private Integer bpm;
    private Integer beatNumber;           // 当前第几拍
    private Integer measureNumber;        // 当前第几小节
    private long nextBeatMs;              // 下一拍时间
    private long nextMeasureMs;           // 下一小节时间
    
    // 分析数据（仅 LOCAL 有）
    private AnalysisResult analysis;
    private List<MarkerPoint> markers;
    private BeatGrid beatGrid;
    
    // 实时计算
    private long timestamp;               // 上下文生成时间
}
```

### 3.2 TriggerContextAdapter 接口

```java
public interface TriggerContextAdapter {
    TriggerContext buildContext();
    TriggerSource getSource();
}
```

### 3.3 CDJ Adapter 实现

```java
public class CdjTriggerContextAdapter implements TriggerContextAdapter {
    // 从 beat-link / DeviceManager 获取 CDJ 实时数据
    // 映射到 TriggerContext
}
```

### 3.4 Local Adapter 实现

```java
public class LocalTriggerContextAdapter implements TriggerContextAdapter {
    // 从 BasicLocalPlaybackEngine 获取播放状态
    // 从 TrackLibraryService 获取分析结果 + markers
    // 映射到 TriggerContext
}
```

### 3.5 Trigger Engine

```java
public class TriggerEngine {
    private List<TriggerRule> rules;
    private TriggerActionDispatcher dispatcher;
    
    public void onContextUpdate(TriggerContext ctx) {
        for (TriggerRule rule : rules) {
            if (rule.matches(ctx)) {
                dispatcher.dispatch(rule.getAction(), ctx);
            }
        }
    }
}
```

### 3.6 Trigger Rule 定义

```java
public class TriggerRule {
    private String id;
    private String name;
    private TriggerCondition condition;  // 条件
    private TriggerAction action;        // 动作
    private boolean enabled;
}

public class TriggerCondition {
    private TriggerSource source;        // CDJ / LOCAL / ANY
    private TriggerType type;            // BEAT / MARKER / POSITION / STATE_CHANGE
    private String targetId;             // markerId / beatOffset / positionMs
    private Integer beatInterval;         // 每 N 拍触发
    private PlaybackState stateChange;    // 播放/暂停/停止
}

public class TriggerAction {
    private ActionType type;              // SEND_COMMAND / INVOKE_API / INVOKE_OSC
    private String protocol;              // TITAN / MA2 / OSC / MIDI / HTTP
    private String payload;               // 命令模板
}
```

---

## 四、Trigger Outputs 分层设计

### 4.1 现有 Sync Outputs（保持不变）

```
SyncOutputManager
    ├── TitanBpmSyncDriver      # 连续 BPM 推送
    ├── Ma2BpmSyncDriver        # 连续 BPM 推送
    ├── AbletonLinkDriver       # Ableton Link
    ├── LtcDriver               # 连续时间码
    └── MtcDriver               # 连续时间码
```

### 4.2 新增 Trigger Outputs

```
TriggerEngine
    └── TriggerActionDispatcher
          ├── TitanTriggerActionDriver   # 事件触发 Titan 命令
          ├── Ma2TriggerActionDriver    # 事件触发 MA2 命令
          └── (未来) OscTriggerActionDriver
```

### 4.3 分层原则

| 特性 | Sync Outputs | Trigger Outputs |
|------|--------------|------------------|
| 触发方式 | 连续（每帧/每拍） | 事件（一次性） |
| 数据源 | 统一运行时状态 | TriggerContext |
| 典型场景 | BPM 同步、时间码推送 | Marker 触发、段落触发 |
| 实现接口 | OutputDriver | TriggerActionDriver（新增） |

---

## 五、Titan / MA2 协议层拆分

### 5.1 现有代码

| 文件 | 当前职责 | 拆分后角色 |
|------|---------|-----------|
| `TitanAdapter.java` | 协议收发 | 拆为 TitanClient |
| `TitanApiDriver.java` | BPM 推送 | 变为 TitanBpmSyncDriver |
| `Ma2TelnetClient.java` | Telnet 连接 | 保持为 Ma2TelnetClient |
| `Ma2BpmDriver.java` | BPM 推送 | 变为 Ma2BpmSyncDriver |

### 5.2 拆分后结构

```
dbclient/src/main/java/dbclient/protocol/  # 新增：协议客户端层
├── TitanClient.java           # 连接 + 命令发送
├── Ma2TelnetClient.java       # 已有，移动位置
└── (未来) OscClient.java / MidiClient.java

dbclient/src/main/java/dbclient/sync/drivers/
├── TitanBpmSyncDriver.java    # 复用 TitanClient
├── Ma2BpmSyncDriver.java      # 复用 Ma2TelnetClient
├── AbletonLinkDriver.java     # 不变
├── LtcDriver.java             # 不变
└── MtcDriver.java             # 不变

dbclient/src/main/java/dbclient/trigger/   # 新增：触发层
├── TriggerEngine.java
├── TriggerActionDispatcher.java
├── TriggerContext.java
├── TriggerContextAdapter.java
├── CdjTriggerContextAdapter.java
├── LocalTriggerContextAdapter.java
├── action/
│   ├── TriggerActionDriver.java     # 接口
│   ├── TitanTriggerActionDriver.java # 复用 TitanClient
│   └── Ma2TriggerActionDriver.java  # 复用 Ma2TelnetClient
└── rule/
    ├── TriggerRule.java
    ├── TriggerCondition.java
    └── TriggerAction.java
```

---

## 六、最小提交顺序（按依赖拆分）

### Commit 1: 数据模型层
- 新增 `MarkerPoint.java`
- 新增 `MarkerType.java`
- 新增 `BeatGrid.java`
- 新增 `TrackLibraryEntry.java`
- 改造 `AnalysisResult.java`（添加 beatGrid）

### Commit 2: Repository 层
- 新增 `MarkerRepository.java` 接口
- 新增 `InMemoryMarkerRepository.java`
- 改造 `InMemoryTrackRepository` 支持 `TrackLibraryEntry`

### Commit 3: Service 层
- 新增 `TrackLibraryService.java`
- 改造 `LocalLibraryService.java` 接入 `TrackLibraryService`
- 新增 `AnalysisService.java`（从 LocalLibraryService 拆分分析逻辑）

### Commit 4: Marker CRUD 接口
- 在 `JettyServer.java` 新增 Marker API：
  - `POST /api/local/markers` - 创建
  - `GET /api/local/markers?trackId=...` - 查询
  - `PUT /api/local/markers/{id}` - 修改
  - `DELETE /api/local/markers/{id}` - 删除

### Commit 5: 本地分析 MVP 扩展
- 扩展 `BasicAudioAnalyzer` 输出 beatGrid
- 生成 waveform preview（简化版：预计算峰值数组）

### Commit 6: Trigger 基础设施
- 新增 `TriggerContext.java`
- 新增 `TriggerContextAdapter.java` 接口
- 实现 `LocalTriggerContextAdapter.java`

### Commit 7: Trigger Engine 核心
- 新增 `TriggerEngine.java`
- 新增 `TriggerRule.java` / `TriggerCondition.java` / `TriggerAction.java`
- 新增 `TriggerActionDispatcher.java`

### Commit 8: Trigger Action Drivers
- 新增 `TriggerActionDriver.java` 接口
- 新增 `TitanTriggerActionDriver.java`
- 新增 `Ma2TriggerActionDriver.java`

### Commit 9: 协议客户端层抽象
- 移动/改造 `TitanAdapter.java` → `TitanClient.java`
- 移动 `Ma2TelnetClient.java` → `dbclient/protocol/`

### Commit 10: CDJ Adapter
- 实现 `CdjTriggerContextAdapter.java`

### Commit 11: UI 扩展（C7）
- 本地播放器页面增加波形显示 + marker 打点 UI
- CDJ 模式页面保持现状（或微调）

---

## 七、禁止破坏的现有稳定部分

| 模块 | 禁止改造原因 |
|------|-------------|
| `SourceInputManager` | 已稳定的主输入切换 |
| `SyncOutputManager` | 已验证的连续同步输出核心 |
| `TimecodeCore` | 已验证的 MTC/LTC 生成逻辑 |
| `BasicLocalPlaybackEngine` | 已修复的播放/seek/stop 语义 |
| `LtcDriver` / `MtcDriver` | 已验证的时间码输出 |
| `TitanApiDriver` / `Ma2BpmDriver` | 已验证的 BPM 同步 |

**原则**：Trigger 层只能**读取**现有模块的状态，不能改造现有模块的内部逻辑。

---

## 八、下一步

确认以上设计方向后，我将按 Commit 顺序逐步实现：

1. **先做 Commit 1-4**：数据模型 + Repository + Service + Marker CRUD
2. **再做 Commit 5**：分析 MVP 扩展（beat grid + waveform）
3. **后续再进 Trigger 层**：Commit 6-11

请确认方向，我来开始第一步。

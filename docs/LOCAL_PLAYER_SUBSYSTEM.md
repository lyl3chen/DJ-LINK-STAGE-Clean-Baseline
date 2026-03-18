# 本地播放器子系统结构说明（Step 3）

Date: 2026-03-19  
Scope: 结构梳理与边界说明（不改主流程）

---

## 1. 子系统分层

## A) Local Media Domain（领域模型层）

### `TrackInfo`
- 负责：本地曲目元数据（id、路径、标题、时长、采样率、声道等）
- 不负责：存储、分析、播放控制
- 依赖：无（纯数据对象）
- 被依赖：TrackRepository、LocalLibraryService、PlaybackEngine、Web/API

### `AnalysisResult`
- 负责：音频分析结果载体（BPM、分析状态、错误信息、波形等）
- 不负责：分析算法执行、播放
- 依赖：无（纯数据对象）
- 被依赖：AudioAnalyzer、LocalLibraryService、Web/API

### `PlaybackStatus`
- 负责：运行时播放状态（state、positionMs、durationMs、pitch、currentTrackId）
- 不负责：实际播放、设备管理
- 依赖：无（纯数据对象）
- 被依赖：PlaybackEngine、LocalSourceInput、SyncOutputManager、Web/API

### `AnalysisStatus`
- 负责：分析流程状态语义（PENDING/ANALYZING/COMPLETED/FAILED）
- 不负责：状态机推进逻辑
- 依赖：无
- 被依赖：AnalysisResult、LocalLibraryService

---

## B) Local Media Services（服务层）

### `LocalLibraryService`
- 负责：本地曲目库用例编排（导入、查询、删除、分析触发）
- 不负责：底层存储细节、底层播放细节
- 依赖：`TrackRepository`, `AudioAnalyzer`
- 被依赖：JettyServer（/api/local/*）、LocalSourceInput（按需获取曲库）

### `TrackRepository`（接口）
- 负责：曲目持久化/查询抽象
- 不负责：分析算法、播放
- 依赖：无
- 被依赖：LocalLibraryService

### `InMemoryTrackRepository`（实现）
- 负责：内存存储曲目（当前实现）
- 不负责：持久化到磁盘/数据库
- 依赖：Java 集合
- 被依赖：LocalLibraryService

### `AudioAnalyzer`（接口）
- 负责：音频分析能力抽象
- 不负责：曲目存储、播放控制
- 依赖：无
- 被依赖：LocalLibraryService

### `BasicAudioAnalyzer`（实现）
- 负责：基础音频信息/简化分析
- 不负责：复杂 BPM/段落/能量分析（当前阶段明确不做）
- 依赖：Java 音频 API
- 被依赖：LocalLibraryService

---

## C) Local Playback Runtime（播放运行时层）

### `PlaybackEngine`（接口）
- 负责：统一播放能力抽象（load/play/pause/stop/seek/status/device）
- 不负责：曲库管理、时间码输出
- 依赖：领域模型（TrackInfo/PlaybackStatus）
- 被依赖：LocalSourceInput、JettyServer

### `BasicLocalPlaybackEngine`（实现）
- 负责：
  - 实际音频播放（Java Sound）
  - 设备配置与打开
  - 播放状态维护（PlaybackStatus）
  - 运行时诊断（configuredDevice/actualOpenedDevice/lastError/audioFormat）
- 不负责：
  - source 切换
  - 时间码核心推进
  - 曲库编排
- 依赖：AudioDeviceEnumerator、TrackInfo/PlaybackStatus
- 被依赖：LocalSourceInput、JettyServer(/api/local/status)

#### 设备相关边界（当前约定）
- `configuredDevice`：配置目标设备
- `actualOpenedDevice`：实际成功打开设备
- `lastError`：最近一次 load/play 相关错误
- 页面运行时真值以 `/api/local/status` 返回为准

---

## D) Input Integration（输入集成层）

### `LocalSourceInput`
- 负责：把 `PlaybackEngine` 适配为统一 `SourceInput` 语义
  - `getState()` / `getSourceTimeSec()` / `getSourceBpm()` / `isPlaying()`
- 不负责：
  - 实际音频播放实现（这是 PlaybackEngine 的职责）
  - 时间码帧计算（TimecodeCore 职责）
- 依赖：PlaybackEngine、LocalLibraryService
- 被依赖：SourceInputManager、SyncOutputManager

### `SourceInputManager`（local 相关部分）
- 负责：
  - 注册/管理 `local` 输入源
  - `djlink/local` 源切换
  - 返回当前 active source
- 不负责：
  - 时间码推进
  - 输出驱动控制
- 依赖：SourceInput（含 LocalSourceInput）
- 被依赖：SyncOutputManager、JettyServer

---

## 2. 与核心层边界（local 子系统 vs 核心同步）

### `SyncOutputManager` 与 local 子系统边界
- local 子系统负责“提供统一 SourceInput 状态”
- SyncOutputManager 负责“消费状态并派生统一输出状态（sourceState/sourcePlayer/sourceType）”
- TimecodeCore/LTC/MTC 仅消费统一状态，不直接关心本地播放器内部细节

### 边界原则
- local 内部细节（设备、播放循环、错误）不应泄漏到 TimecodeCore
- 输出层（LTC/MTC）不应直接依赖 PlaybackEngine
- 所有输出统一通过 `SyncOutputManager -> TimecodeCore` 链路

---

## 3. 职责混杂点 / 边界不清点（当前发现）

1. **JettyServer 同时承担 API 路由 + 运行时诊断拼装**
- 现状：`/api/local/status` 里拼装了诊断字段
- 风险：路由层逐步变厚
- 结论：当前可接受（最小改动阶段），后续可提取 StatusAssembler

2. **LocalSourceInput 同时持有 PlaybackEngine 与 LocalLibraryService**
- 现状：既做 Source 适配，也暴露部分曲库能力
- 风险：适配层边界略宽
- 结论：暂不拆（避免破坏），文档明确“以 Source 语义为主”

3. **配置视图与运行时视图易混淆**
- 现状：I/O Setup 显示 configuredDevice，本地播放器页显示 actualOpenedDevice
- 风险：若文案不清晰会误读
- 结论：已在 Step2 收口，继续保持该边界

4. **PlaybackEngine 的 seek/resume 语义仍为简化实现**
- 现状：pause 后 resume 不是精准定位方案
- 风险：未来误以为“已完整实现”
- 结论：注释中已标注 Phase1 限制

---

## 4. 最小收口修改（本轮）

- 新增本文件，明确子系统分层、职责、边界、混杂点。
- 不改主流程逻辑，不改 API 结构，不改包结构。

---

## 5. 后续演进建议（不在本轮执行）

- Step 4 可考虑：
  - 将 `/api/local/status` 诊断拼装下沉到专用 service/assembler
  - 把 LocalSourceInput 的库服务依赖改为更窄接口
  - 统一“配置视图 vs 运行时视图”命名规范

（以上仅建议，不作为当前实现变更）

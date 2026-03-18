# Phase C1 - 本地分析最小闭环（BPM + 状态机）

Date: 2026-03-19

## 实现内容

1. 真实 BPM 分析（GPL-safe）
- 仅使用 Java Sound API + 自实现 onset/autocorr（无 GPL 代码依赖）
- 分析流程：
  - PCM 解码（16-bit signed）
  - 能量包络
  - onset（正向差分）
  - 自相关 lag 搜索（60~180 BPM）
- 输出：`bpm`, `bpmConfidence`

2. 分析状态机
- 使用 `AnalysisStatus`: `PENDING / ANALYZING / COMPLETED / FAILED`
- 在 `LocalLibraryService.analyzeTrack()` 中先写入 `ANALYZING`，完成后更新最终状态

3. 最小分析 API
- `POST /api/local/analyze`：触发单曲分析
- `GET /api/local/analysis?trackId=...`：查询分析结果

4. 持久化
- 分析结果通过 `TrackRepository.saveAnalysis(trackId, result)` 保存

## 字段（C1）
- `analysisStatus`
- `bpm`
- `bpmConfidence`
- `durationMs`
- `errorMessage`
- `analyzedAt`

## 已知限制（C1）
- 仅 BPM + 置信度，不含 beat grid/waveform
- 置信度为简化指标（峰值差比例），后续可优化
- 支持格式依赖 Java Sound（WAV/AIFF/AU）

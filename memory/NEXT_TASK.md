# NEXT_TASK（2026-03-25）

## 下一步唯一任务
在不改变现有主路径前提下，做小步稳定性验证与性能微调：
1. 现场确认 detail 流畅度（播放/暂停/缩放）
2. 现场确认 hot cue overlay 可见性与位置
3. 若有卡顿，仅做节流/无效重绘优化，不新增功能

## 当前不要做
- 不新增功能
- 不改 waveform 主路径
- 不扩 WebUI
- 不回到自研 waveform 路线
- 不重新追 CueList raw/message 恢复主链路

## 当前稳定主线（锁定）
- 服务端独占 beat-link
- 桌面端最小桥接
- 原生 WaveformPreview/Detail 组件主显示
- detail zoom 走原生 setScale
- beat markers 走原生 BeatGrid
- hot cue 走原生 OverlayPainter 扩展

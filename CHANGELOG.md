# CHANGELOG

## 2026-03-22

### 新功能
- **Master 判定对齐 BLT**: 使用 TimeFinder.getLatestUpdateFor() + isTempoMaster() 获取真实 master
- **统一时间基准**: currentTimeMs 优先使用 TimeFinder.getTimeFor() 获取真实传输时间
- **WebUI 按钮反馈**: 添加按钮视觉反馈（saveIoBtn, aiGenerateBtn, ma2SendTestBtn）

### 问题修复
- LTC/MTC/Dashboard 时间偏移问题（约 1 秒）- 现已与 CDJ 真实时间一致
- Master 切换实时跟随问题 - 现在与 BLT 行为一致

### 技术细节
- TimeFinder 已初始化并用于 master 检测和时间获取
- Master 判定优先级: TimeFinder > VirtualCdj.getTempoMaster() > MasterListener
- 时间获取优先级: TimeFinder.getTimeFor() > beat grid 推导

---

## 历史版本

### 早期版本
- 基础 Pro DJ Link 设备发现
- 基础 WebUI 展示
- LTC/MTC/Ableton Link 输出

# DECISIONS.md - 关键技术决策记录

## 2026-03-15 决策（已执行）

### LTC/MTC 彻底下线清理

**状态**: ✅ 已完成清理并重建

**执行操作**:
1. ✅ 删除旧 LtcDriver.java / MtcDriver.java
2. ✅ 清理 SyncOutputManager 旧入口
3. ✅ 清理 WebUI 旧配置

---

## 2026-03-16 决策（当前状态）

### LTC/MTC 重建完成

**状态**: ✅ 已实现并验证

**实现规格**:

1. **独立架构设计**
   - TimecodeCore 完全独立于 SyncOutputManager
   - 两者有各自的 sourcePlayer 配置
   - API 区分: `sourcePlayer` (外层) vs `tcSourcePlayer` (timecode 内)

2. **时间推进机制**
   - 使用本地单调时钟 (System.nanoTime)
   - 25fps 独立线程均匀输出
   - 事件驱动重锚（非实时贴合 CDJ）

3. **重锚策略**
   - 明确事件触发: PLAY_STARTED, RESUMED, PAUSED, STOPPED, TIME_JUMPED, TRACK_CHANGED, DRIFT_TOO_LARGE
   - 跳变阈值: 10帧 (0.4s)
   - 漂移阈值: 125帧 (5s)

4. **停止态策略**
   - LTC: 发送静音帧（全0样本），不发送 00:00:00:00
   - MTC: Quarter Frame 序列暂停

5. **共享核心**
   - LTC 和 MTC 共用 TimecodeCore
   - 确保两者时间完全一致

6. **编码实现**
   - LTC: BCD LSB-first, sync word 0x3FFD, BMC 调制
   - MTC: Quarter Frame F1 xx 格式
   - 严格对照 x42/libltc 线序

7. **手动测试模式**
   - 脱离 CDJ 播放源
   - 固定从 00:00:00:00 线性推进
   - 用于独立测试时间码输出

---

## 关键文件

| 文件 | 职责 |
|------|------|
| `TimecodeCore.java` | 时间码核心（状态管理、重锚逻辑、时钟推进） |
| `PlayerEventDetector.java` | 播放器事件检测 |
| `LtcDriver.java` | LTC 音频输出驱动 |
| `LtcFrameEncoder.java` | 80-bit LTC 帧编码 |
| `LtcBmcEncoder.java` | BMC 调制编码 |
| `MtcDriver.java` | MTC MIDI 输出驱动 |
| `AudioDeviceEnumerator.java` | 音频设备枚举 |
| `MidiDeviceEnumerator.java` | MIDI 端口枚举 |

---

## API 变更记录

### 2026-03-16
- `timecode.sourcePlayer` → `timecode.tcSourcePlayer`（避免与外层 sourcePlayer 混淆）
- 添加 `timecode.manualTestMode` 字段
- 添加 LTC 诊断字段: `writeInterval`, `bufferOccupancy`, `underrunCount`, `frameDelta`

---

**文档版本**: 2.0
**最后更新**: 2026-03-16 GMT+8

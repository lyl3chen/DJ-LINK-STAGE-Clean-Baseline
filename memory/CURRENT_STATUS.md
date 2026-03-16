# CURRENT_STATUS.md - 项目当前状态

> 2026-03-16 23:26 更新：当前版本 `12b4640`，LTC/MTC 功能可用

## 功能状态

| 模块 | 状态 | 说明 |
|------|------|------|
| **LTC 输出** | ✅ 可用 | 音频设备输出 SMPTE 时间码，手动测试正常 |
| **MTC 输出** | ✅ 可用 | MIDI 端口输出 MIDI Timecode，运行中 |
| **手动测试模式** | ✅ 可用 | 脱离 CDJ 独立测试，时间码推进正常 |
| **设备枚举** | ✅ 可用 | 自动检测音频/MIDI设备 |
| **Ableton Link** | ✅ 可用 | 同步 BPM |
| **Titan API** | ✅ 可用 | 同步 Master |
| **MA2 Telnet** | ✅ 可用 | 同步 BPM |

## 今日确认问题

### 1. LTC 信号强度波浪形起伏 ⏸️ 暂停排查
- **现象**：Resolume 观测到 LTC 信号强度呈波浪形起伏
- **关键发现**：标准 LTC 文件在项目本机播放也出现同样起伏
- **结论**：问题归因于**项目本机音频输出链路**（硬件/驱动/ALSA/PulseAudio）
- **状态**：暂停修改 LTC 代码，后续单独排查本机音频系统

### 2. MTC 视觉跳帧 ⏸️ 待后续测试
- **现象**：肉眼观测可能有跳帧
- **状态**：待后续详细测试验证

## 当前版本

- **Commit**: `12b4640`
- **分支**: master
- **状态**: 服务运行中

## 今日关键提交

| Commit | 说明 |
|--------|------|
| `12b4640` | fix(timecode): add state notification when toggling manual test mode |
| `6edc439` | refactor(ltc): rewrite LtcDriver with state machine and continuous frame stream |

## 架构状态

### TimecodeCore（时间码核心）
- **定位**: 独立于 SyncOutputManager
- **职责**: LTC/MTC 共用时间源
- **推进机制**: 本地单调时钟 25fps
- **重锚机制**: 事件驱动（播放/暂停/停止/跳变/切歌/漂移）
- **状态通知**: TimecodeStateListener 接口，驱动可接收状态变化

### LtcDriver 重构要点
- **状态机**: TransportState (STOPPED/PAUSED/PLAYING)
- **发送模型**: 按音频缓冲区余量驱动 (available / BYTES_PER_FRAME)
- **停止态**: 输出稳定 00:00:00:00 帧（非静音）
- **暂停态**: 冻结 heldFrame，不更新
- **播放态**: nextFrameToWrite 严格递增

---

**最后更新**: 2026-03-16 23:26 GMT+8

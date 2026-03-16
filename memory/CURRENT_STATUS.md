# CURRENT_STATUS.md - 项目当前状态

> 2026-03-16 更新：LTC/MTC 模块已重建完成，当前版本 `2f3041e`

## 功能状态

| 模块 | 状态 | 说明 |
|------|------|------|
| **LTC 输出** | ✅ 可用 | 音频设备输出 SMPTE 时间码 |
| **MTC 输出** | ✅ 可用 | MIDI 端口输出 MIDI Timecode |
| **手动测试模式** | ✅ 可用 | 脱离 CDJ 独立测试 |
| **设备枚举** | ✅ 可用 | 自动检测音频/MIDI设备 |
| **实时诊断** | ✅ 可用 | writeInterval/bufferOccupancy 等 |
| **停止态静音** | ✅ 可用 | 停止后发送静音而非 00 帧 |
| **Ableton Link** | ✅ 可用 | 同步 BPM |
| **Titan API** | ✅ 可用 | 同步 Master |
| **MA2 Telnet** | ✅ 可用 | 同步 BPM |

## 架构状态

### TimecodeCore（时间码核心）
- **定位**: 独立于 SyncOutputManager
- **职责**: LTC/MTC 共用时间源
- **推进机制**: 本地单调时钟 25fps
- **重锚机制**: 事件驱动（播放/暂停/停止/跳变/切歌/漂移）

### 配置独立
```
sync.masterPlayer = 1        // SyncOutputManager（显示/其他驱动）
sync.timecode.sourcePlayer = 2  // TimecodeCore（LTC/MTC 专用）
```

## API 状态

```json
{
  "sourceState": "PLAYING",
  "sourcePlayer": 1,
  "timecode": {
    "tcSourcePlayer": 2,
    "state": "PLAYING",
    "currentFrame": 2500,
    "manualTestMode": false
  },
  "drivers": {
    "ltc": { "enabled": true, "running": true, ... },
    "mtc": { "enabled": true, "running": true, ... }
  }
}
```

## 已知限制

- 音频设备选择仅显示找到的设备（无 "Default" 虚拟选项）
- MIDI 端口同理
- 停止态 LTC 完全静音（接收端会看到信号消失）

## 最近提交

- `2f3041e` - refactor(api): rename timecode.sourcePlayer to timecode.tcSourcePlayer
- `f53050b` - refactor: make TimecodeCore sourcePlayer independent from SyncOutputManager
- `f8ed84d` - fix(ltc): send silence instead of 00-frame when stopped (zeroLatch)

---

**最后更新**: 2026-03-16 GMT+8

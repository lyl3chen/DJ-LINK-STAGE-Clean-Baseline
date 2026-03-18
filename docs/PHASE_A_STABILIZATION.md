# DJ Link Stage - Phase A: 稳定化收口

**Date**: 2026-03-19  
**Status**: Phase A 执行中  
**Base Commit**: `71cb87a1a4ade0d1118bfef6154af32fd9ffde2d`

---

## 一、当前稳定能力清单

### 1.1 DJ Link 模式 (CDJ)
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| CDJ 状态扫描 | ✅ 稳定 | 开启扫描后检测到在线 CDJ |
| 播放状态同步 | ✅ 稳定 | WebUI deck 显示 PLAY/STOP |
| 暂停状态同步 | ✅ 稳定 | WebUI 显示 PAUSED |
| 停止状态同步 | ✅ 稳定 | WebUI 显示 STOPPED |
| Master 切换 | ✅ 稳定 | WebUI 显示 MASTER 标识 |
| BPM 同步 | ✅ 稳定 | 全局 BPM 显示正确 |
| Waveform 显示 | ✅ 稳定 | 波形和播放头正常移动 |

### 1.2 Local Player 模式
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| WAV 文件导入 | ✅ 稳定 | /api/local/import 成功 |
| 曲目列表显示 | ✅ 稳定 | 页面显示曲目列表 |
| 加载曲目 | ✅ 稳定 | load 后显示曲目信息 |
| 播放 | ✅ 稳定 | USB 耳机有声音 |
| 暂停 | ✅ 稳定 | 点击 pause 声音停止 |
| 停止 | ✅ 稳定 | 点击 stop 回到开头 |
| USB 设备选择 | ✅ 稳定 | 诊断区显示 actualOpenedDevice |
| 首次播放无杂音 | ✅ 稳定 | 预填充后首播正常 |

### 1.3 时间码输出 (DJ Link 模式)
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| LTC 输出 | ✅ 稳定 | LTC 接收端锁定 |
| MTC 输出 | ✅ 稳定 | MTC 接收端锁定 |
| 播放时推进 | ✅ 稳定 | 时间码随播放前进 |
| 暂停时停止 | ✅ 稳定 | 时间码暂停 |
| 停止时归零 | ✅ 稳定 | 时间码回到 00:00:00:00 |

### 1.4 时间码输出 (Local Player 模式)
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| LTC 输出 | ✅ 稳定 | LTC 接收端锁定 |
| MTC 输出 | ✅ 稳定 | MTC 接收端锁定 |
| 播放时推进 | ✅ 稳定 | 时间码随播放前进 |
| 暂停时停止 | ✅ 稳定 | 时间码暂停 |
| 停止时归零 | ✅ 稳定 | 时间码回到 00:00:00:00 |

### 1.5 Source 切换
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| DJ Link → Local | ✅ 稳定 | 切换后 local 播放器可用 |
| Local → DJ Link | ✅ 稳定 | 切换后 CDJ 状态恢复 |
| 切换后 LTC 输出 | ✅ 稳定 | 时间码来源正确切换 |

### 1.6 配置与状态
| 功能 | 状态 | 验证方式 |
|------|------|----------|
| LTC 设备选择 | ✅ 稳定 | 配置保存后生效 |
| MTC 端口选择 | ✅ 稳定 | 配置保存后生效 |
| Local 设备选择 | ✅ 稳定 | 诊断区显示正确 |
| 配置持久化 | ✅ 稳定 | 重启后配置保留 |

---

## 二、最小回归测试清单

### 2.1 DJ Link 模式测试
```bash
# 1. 开启扫描
curl -X POST http://192.168.100.200:8080/api/scan/toggle?enabled=1

# 2. 检查 CDJ 在线
curl http://192.168.100.200:8080/api/players/state | jq '.players | length'
# 期望: > 0

# 3. 播放 CDJ，检查时间码
curl http://192.168.100.200:8080/api/sync/state | jq '.timecode.state'
# 期望: "PLAYING"

# 4. 暂停 CDJ，检查时间码
curl http://192.168.100.200:8080/api/sync/state | jq '.timecode.state'
# 期望: "PAUSED"
```

### 2.2 Local Player 模式测试
```bash
# 1. 切换到 local 模式
curl -X POST http://192.168.100.200:8080/api/source/switch \
  -d '{"sourceType":"local"}'

# 2. 导入测试音频
curl -X POST http://192.168.100.200:8080/api/local/import \
  -d '{"filePath":"/path/to/test.wav"}'

# 3. 加载曲目
curl -X POST http://192.168.100.200:8080/api/local/load \
  -d '{"trackId":"xxx"}'

# 4. 检查诊断信息
curl http://192.168.100.200:8080/api/local/status | jq '{
  configuredDevice,
  actualOpenedDevice,
  lastError
}'
# 期望: actualOpenedDevice 不为 null, lastError 为 null

# 5. 播放
curl -X POST http://192.168.100.200:8080/api/local/play

# 6. 检查时间码
curl http://192.168.100.200:8080/api/sync/state | jq '.timecode.state'
# 期望: "PLAYING"

# 7. 暂停
curl -X POST http://192.168.100.200:8080/api/local/pause

# 8. 检查时间码停止
curl http://192.168.100.200:8080/api/sync/state | jq '.timecode.state'
# 期望: "PAUSED"
```

### 2.3 Source 切换测试
```bash
# 1. DJ Link → Local
curl -X POST http://192.168.100.200:8080/api/source/switch \
  -d '{"sourceType":"local"}'
# 验证: local 播放器可用

# 2. Local → DJ Link
curl -X POST http://192.168.100.200:8080/api/source/switch \
  -d '{"sourceType":"djlink"}'
# 验证: CDJ 状态恢复显示
```

### 2.4 音频设备切换测试
```bash
# 1. 保存 USB 设备配置
curl -X POST http://192.168.100.200:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{"sync":{"localPlayer":{"audioDevice":"Device [plughw:3,0]"}}}'

# 2. 重新加载曲目，验证设备
curl http://192.168.100.200:8080/api/local/status | jq '.actualOpenedDevice'
# 期望: "Device [plughw:3,0]"
```

---

## 三、关键状态显示清单

### 3.1 API 状态端点

| 端点 | 关键字段 | 用途 |
|------|----------|------|
| `/api/sync/state` | `sourceType` | 当前输入源类型 |
| | `sourceState` | 当前源状态 |
| | `timecode.state` | 时间码状态 |
| | `timecode.currentFrame` | 当前时间码帧 |
| | `drivers.ltc.*` | LTC 驱动状态 |
| | `drivers.mtc.*` | MTC 驱动状态 |
| `/api/local/status` | `configuredDevice` | 配置的设备名 |
| | `actualOpenedDevice` | 实际打开的设备 |
| | `lastError` | 最后错误信息 |
| | `status.state` | 播放状态 |
| | `status.positionMs` | 当前位置 |

### 3.2 WebUI 状态显示

**I/O Setup 页面**:
- 输入源类型选择器 (djlink/local)
- LTC 设备选择
- MTC 端口选择  
- Local Player 设备选择

**Dashboard 页面**:
- CDJ 状态显示 (如果 source=djlink)
- 全局 BPM
- 在线设备数

**本地播放器页面**:
- 曲目列表
- 播放控制按钮
- 诊断信息区:
  - configuredDevice
  - actualOpenedDevice
  - lastError
  - audioFormat
- 播放状态 (state, position, duration)

---

## 四、已清理的误导性逻辑/注释

### 4.1 已删除的静态文案
- ~~`使用系统默认设备`~~ (index.html 第676行静态 span)

### 4.2 需要检查的旧注释 (如有请删除或更新)
- [ ] 检查 BasicLocalPlaybackEngine 中过时的 TODO
- [ ] 检查 SourceInputManager 中废弃的方法
- [ ] 检查 SyncOutputManager 中旧版逻辑注释

### 4.3 已添加的关键注释
- BasicLocalPlaybackEngine.load(): 诊断日志 (LOAD START/SUCCESS/FAILED)
- BasicLocalPlaybackEngine.play(): 预填充逻辑注释
- LtcDriver.onStateChange(): PAUSED 状态处理
- MtcDriver.onFrame(): PLAYING/PAUSED 区分

---

## 五、后续禁止误改清单 (Phase A 强化版)

### 5.1 绝对禁止 (破坏现有功能)
1. ❌ **不要恢复 `timecode.sourcePlayer` 独立字段**
   - 当前已统一到 `sync.masterPlayer`
   - 修改位置: `SyncOutputManager`, `TimecodeCore`

2. ❌ **不要创建新的 `BasicLocalPlaybackEngine` 实例**
   - 必须使用 `SyncOutputManager` 共享实例
   - 禁止: `new BasicLocalPlaybackEngine()`

3. ❌ **不要绕过 `SourceInputManager` 直接访问播放器**
   - 正确: `sourceInputManager.getSource("local")`
   - 错误: 直接引用 `localEngine`

4. ❌ **不要改动 `playbackLoop` 的实时延迟逻辑**
   - 关键: `Thread.sleep(delayMs)` 基于 `bytesPerSecond`
   - 改动会导致快进或卡顿

5. ❌ **不要改动首播预填充逻辑**
   - 关键: 8KB 预填充 + `mark/reset`
   - 改动会导致首播杂音

### 5.2 谨慎修改 (需充分测试)
1. ⚠️ `TimecodeCore.update()` 逻辑
2. ⚠️ `LtcDriver.onFrame()` / `MtcDriver.onFrame()` 调用条件
3. ⚠️ `SyncOutputManager.onLocalPlayerState()` 轮询逻辑
4. ⚠️ `SourceInputManager.switchSource()` 切换逻辑

### 5.3 状态语义 (禁止改变)
| 状态 | 含义 | positionMs | 时间码 |
|------|------|------------|--------|
| STOPPED | 停止 | = 0 | 00:00:00:00 |
| PLAYING | 播放中 | 递增 | 推进 |
| PAUSED | 暂停 | 保持不变 | 停止 (保持当前帧) |

---

## 六、文件修改记录

### 6.1 Phase A 修改
```
docs/
  └── PHASE_A_STABILIZATION.md (本文档)
```

### 6.2 如需清理代码，可能修改
```
dbclient/src/main/java/dbclient/media/player/BasicLocalPlaybackEngine.java
- 检查并删除过时 TODO 注释

dbclient/src/main/java/dbclient/sync/SyncOutputManager.java
- 检查并标注旧版逻辑

dbclient/src/main/java/dbclient/sync/timecode/TimecodeCore.java
- 检查并清理废弃字段引用
```

---

**Phase A 完成标准**:
- [ ] 本文档已创建
- [ ] 回归测试清单可执行
- [ ] 代码中误导性注释已清理
- [ ] git commit 已提交

**下一步**: Phase B (本地播放器可用性完善)

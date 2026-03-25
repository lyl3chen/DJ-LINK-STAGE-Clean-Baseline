# 下一步开发建议与接力说明

> 为新会话或新 agent 准备的接力说明  
> 更新时间：2026-03-25

## 2026-03-25 收口后接力（最新优先）

### 当前稳定基线
- branch: `master`
- 基线提交：`483dd26`（布局收口） + `78e54e5`（detail顺滑与高度）
- 最新清理提交：见本次 cleanup baseline commit

### 当前主线（不要偏离）
1. 服务端独占 beat-link
2. 桌面端不启动 finder/manager
3. 原生组件主路径：WaveformPreview/Detail 组件显示
4. detail zoom 保持原生 setScale
5. beat markers 保持 BeatGrid 原生路径
6. hot cue 保持原生 OverlayPainter 扩展（不再 Compose 外层自绘）

### 明确停止路线
- 自研 waveform/sample/envelope/path 主渲染
- raw/base64 手工桥接作为主路径
- CueList raw/message 恢复作为当前主链路依赖（数据源不稳定）
- 顶部按钮式 zoom

### 当前待办（最小）
- 仅做稳定性微调与性能优化；禁止新增功能扩展。

## 当前交接状态（2026-03-24）

### 当前分支/提交
- branch: `master`
- 当前 HEAD: `ed14d81`（mini波形落差恢复默认）
- 相关关键提交：
  - `20fa99d` LIVE接入真实波形 + 元数据展示补齐
  - `c68643a` 主卡/mini按在线播放器动态显示（最多4台）
  - `3dc536e` mini波形高度x2+单边
  - `ed14d81` mini波形落差恢复默认
- 可回退锚点：`ui-safe-before-mini-waveform-tune` -> `c68643a`

### 用户已确认/要求
1. 一次只改一个问题，不并行修改。
2. 每轮交付固定6点回执（文件/HEAD/链路核验/最小实测/成功标准/失败回传）。
3. 当前 LTC 接收端问题暂挂起（发送端 PLAYING+帧推进已证据化）。
4. 主线继续 desktop-ui 收口。

### 当前可直接恢复命令
```bash
cd ~/.openclaw/agents/dev/workspace/dj-link-stage
git reset --hard ed14d81
# 或回退到波形调优前锚点
git reset --hard ui-safe-before-mini-waveform-tune
```

### 下一步建议（若继续 UI）
- 仅在 desktop-ui 层做微调，不改后端链路。
- 任何涉及 player/track/metadata/waveform 改动后，都必须现场抓 `/api/players/state` 实证。

---

## 一、新会话启动 Checklist

### 1.1 必须读取的文档
- [ ] `memory/PROJECT_MASTER_DOC.md` - 项目主文档
- [ ] `memory/HISTORY_CHANGES.md` - 历史变更与废弃路线
- [ ] `memory/CURRENT_STATUS.md` - 当前状态
- [ ] `memory/NEXT_TASK.md` - 下一步任务
- [ ] `memory/2026-03-16.md` - 昨日进展详情

### 1.2 确认当前版本
```bash
cd ~/agents/dev/workspace/dj-link-stage
git log --oneline -1  # 应显示 b11e0d7
```

### 1.3 确认服务状态
```bash
# 检查服务是否运行
ss -ltnp | grep ':8080'

# 检查 API
curl -s http://127.0.0.1:8080/api/sync/state | jq '.drivers'
```

---

## 二、当前紧急任务

### 2.0 原生桌面 UI 主线（当前优先）

**当前阶段目标（已切换）**：
先做 `CDJ Dashboard V1 可运行版`，优先实时 CDJ 状态，不做本地曲库/播放器相关桌面功能。

**已完成**：
- 新增 `desktop-ui/` Compose Desktop 模块
- 启动可运行原生窗口
- 接入实时 CDJ Dashboard V1：
  - Player 1~4 卡片固定显示
  - 在线/离线、PLAY/PAUSE/STOP/CUED、On-Air、Master
  - title/artist/current/remain/raw BPM/pitch/effective BPM
  - 顶部状态栏：master player、master BPM、scan 状态、最近更新时间、断连提示
  - 刷新频率 200/300/500ms 可选（默认 300ms）

**下一步（紧接）**：
1. 用真实 CDJ 在线数据做现场验证（状态变化、BPM、时间推进）
2. 校准 `CUED/PAUSE` 状态判定语义（按现场设备行为微调）
3. 增加数据更新时间 watchdog 参数化（当前阈值 2s）
4. 明确未覆盖字段清单（如某些设备不回 artist/title 时的显示策略）
5. V1.2 已加入最小调试信息：raw 状态摘要、timeSource、连续失败计数
6. CUED 真值验证已接入：后端透传 isCued/isPaused/isTrackLoaded/isAtEnd/playState1~3/isPlaying 到 /api/players/state.debugState，desktop-ui 优先用真值字段判定 CUED
7. desktop-ui 监看界面优化版已完成：
   - 顶部全局状态栏信息层级优化（Master/Master BPM/Scan/Last Update/失败计数）
   - Player 卡片结构化展示（状态标签、曲目信息、时间/BPM指标块）
   - 调试信息收纳为可展开区域，默认不干扰主监看
   - UI 文案开始抽离到 UiText 常量对象，便于后续国际化
8. V1.4 视觉定稿（showkontrol 风格方向）已完成：
   - 深色工业风主界面（去浅色 SaaS 卡片感）
   - Player 1~4 改为横向通道条（strip）
   - 时间/状态最突出，BPM 次级，曲目信息再次级
   - 底部预留 mini deck / wave overview 占位区（仅布局，不做功能）


### 2.1 LTC 停止态抖动修复（优先级 1）

**问题描述**：
- 运行态 LTC 基本正常，可均匀线性前进
- 停止测试时在 -0/0 之间抖动
- 当前运行版本 b11e0d7 采用"持续输出 00:00:00:00"策略

**已尝试的方案**：
| 方案 | 提交 | 结果 |
|------|------|------|
| 持续输出 00:00:00:00 | b11e0d7 | ✅ 当前运行 |
| 停止即静音 | 12eb47f | ❌ 回退（无输出）|

**下一步建议**：
1. 在 b11e0d7 基础上继续调试停止态
2. 检查 zero-latch 逻辑是否正确触发
3. 检查是否有负帧（frame < 0）被送入编码器
4. 可能需要更硬的边界锁定机制

**验收标准**：
- 停止测试后，接收端不再在 -0/0 之间抖动
- 时间码稳定停在 00:00:00:00 或保持静音（不再来回跳）

### 2.2 其他输出驱动验证（优先级 2）

**待验证项**：
- [ ] AbletonLinkDriver 实际连接测试
- [ ] TitanApiDriver 实际连接测试
- [ ] Ma2BpmDriver 实际连接测试

---

## 三、代码修改规范

### 3.1 只改 LTC 层时
- **允许修改**：`dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java`
- **允许修改**：`dbclient/src/main/java/dbclient/sync/drivers/LtcFrameEncoder.java`
- **允许修改**：`dbclient/src/main/java/dbclient/sync/drivers/LtcBmcEncoder.java`
- **禁止修改**：SyncOutputManager.java（上层逻辑）
- **禁止修改**：MtcDriver.java
- **禁止修改**：其他驱动

### 3.2 提交格式要求
每次修改必须报告：
1. 改了哪些文件
2. 改了哪些方法
3. 当前最怀疑的根因是什么
4. 已排除哪些可能
5. git commit hash

### 3.3 测试规范
- 禁止贴大段日志
- 使用简明结构化格式反馈
- 每次必须包含 git commit hash

---

## 四、诊断工具使用

### 4.1 LTC 手动测试模式
```bash
# 开启
curl -X POST http://127.0.0.1:8080/api/timecode/manual-test \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'

# 关闭
curl -X POST http://127.0.0.1:8080/api/timecode/manual-test \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

### 4.2 查看 LTC 状态
```bash
curl -s http://127.0.0.1:8080/api/sync/state | jq '.drivers.ltc'
```

### 4.3 查看实时指标
```bash
# 包含 write interval / buffer occupancy / underrun 等
curl -s http://127.0.0.1:8080/api/sync/state | jq '.drivers.ltc | keys'
```

### 4.4 对照标准样本
- 标准样本位置：`/home/shenlei/下载/LTC_00000000_5mins_25fps_48000x16.wav`
- 已有对照工具在测试代码中

---

## 五、关键文件路径速查

| 用途 | 路径 |
|------|------|
| 项目根目录 | `~/agents/dev/workspace/dj-link-stage/` |
| LTC 驱动 | `dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java` |
| LTC 编码器 | `dbclient/src/main/java/dbclient/sync/drivers/LtcFrameEncoder.java` |
| BMC 调制器 | `dbclient/src/main/java/dbclient/sync/drivers/LtcBmcEncoder.java` |
| 时间码核心 | `dbclient/src/main/java/dbclient/sync/timecode/TimecodeCore.java` |
| 同步管理器 | `dbclient/src/main/java/dbclient/sync/SyncOutputManager.java` |
| WebUI | `dbclient/src/main/resources/web/index.html` |
| 启动脚本 | `scripts/start-dbclient.sh` |

---

## 六、重要教训

### 6.1 LTC 开发教训
1. **必须用标准 LTC** - 简化载波无法被设备识别
2. **位序至关重要** - LSB-first vs MSB-first 直接影响锁码
3. **实时链路可能抖动** - 独立线程比回调更稳定
4. **停止态边界复杂** - 需要明确策略并硬锁定

### 6.2 项目管理教训
1. **大模型难以在复杂代码上收敛** - 需要干净基线
2. **历史会话信息量巨大** - 需要结构化文档传承
3. **外部记忆防止上下文爆炸** - progress.md 机制有效
4. **分阶段交付比长任务更可靠** - 避免上下文耗尽

---

## 七、联系与求助

- **项目仓库**：`https://github.com/lyl3chen/DJ-LINK-STAGE-Clean-Baseline.git`
- **当前维护者**：王德发（AI助手）
- **人类负责人**：谌磊（大王）

---

*本文档最后更新：2026-03-17*

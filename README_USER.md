# README_USER.md（中文人话项目地图）

你可以把这个项目想成一套“DJ现场大脑 + 指挥台 + 显示屏”。

---

## 一、项目地图（文件夹是干什么的）

### 1) `djlink-service/` = **耳朵 + 传感器**
- 作用：在局域网里“听”CDJ/XDJ/Mixer在说什么。
- 你可以理解为：它负责把设备状态采集上来（谁在播放、BPM多少、拍点到哪了）。

### 2) `dbclient/` = **大脑 + 中控台**
- 作用：把采集到的数据整理成可用信息，再通过 API / WebSocket 发给前端。
- 你可以理解为：这里是项目核心控制层。

### 3) `dbclient/src/main/resources/web/` = **脸面（UI界面）**
- 作用：你看到的网页监控界面、三Tab界面都在这里。
- 你可以理解为：这是“给人看的外观层”。

### 4) `config/` = **设置抽屉**
- 作用：保存用户设置（LTC/MTC/Link开关、AI Key、规则等）。
- 你可以理解为：软件重启后还能记住设置，全靠它。

### 5) `scripts/` = **启动按钮工具箱**
- 作用：一键启动、构建、健康检查脚本。
- 你可以理解为：不会写代码时最常用的“快捷按钮”。

### 6) `docs/` = **说明书区**
- 作用：API说明、架构说明等文档。

---

## 二、重要文件说明（它控制什么功能）

### 后端核心
- `dbclient/src/main/java/dbclient/Main.java`
  - 程序入口。负责启动Jetty服务和状态广播。
- `dbclient/src/main/java/dbclient/websocket/JettyServer.java`
  - 最关键的总入口：HTTP接口 + WebSocket推送都在这里。
  - 你看到的 `/api/config`、`/api/sync/state`、`/ws` 都在这里处理。
- `djlink-service/src/main/java/djlink/DeviceManager.java`
  - 设备状态聚合中心（播放器、混音器、波形等）。
  - 新增“metadata 预热缓存层”接入：播放状态更新时先异步预取曲目元数据，减少首次命中延迟。
- `djlink-service/src/main/java/djlink/MetadataWarmupService.java`
  - 轻量预热服务：按 DataReference 预拉取并缓存 metadata，供 miss 时兜底。

### 第二阶段（新模块）
- `dbclient/src/main/java/dbclient/sync/SyncOutputManager.java`
  - 信号输出总调度器（LTC/MTC/Link/Console）。
- `dbclient/src/main/java/dbclient/sync/OutputDriver.java`
  - 驱动统一接口（start/stop/update/status）。
- `dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java`
  - LTC音频输出驱动（从主时钟生成时间码音频，输出到声卡）。
- `dbclient/src/main/java/dbclient/sync/drivers/MtcDriver.java`
  - MTC驱动（已实现独立发送循环和抖动观测字段）。
- `dbclient/src/main/java/dbclient/sync/drivers/AbletonLinkDriver.java`
  - Ableton Link 驱动上层入口（已改为 lib-carabiner 单路实现）。
- `dbclient/src/main/java/dbclient/sync/drivers/CarabinerLinkEngine.java`
  - lib-carabiner 的核心封装：负责 Runner 启停、消息收发、自动重连、状态汇总。
- `dbclient/src/main/java/dbclient/sync/drivers/ConsoleApiDriver.java`
  - 控制台状态输出驱动（调试用）。
- `dbclient/src/main/java/dbclient/ai/AiAgentService.java`
  - AI命令解析器（把“人话需求”转成触发规则）。
- `dbclient/src/main/java/dbclient/config/UserSettingsStore.java`
  - 设置读写器（把配置写入 JSON）。

### 前端关键
- `dbclient/src/main/resources/web/index.html`
  - 整个WebUI主文件（三Tab、波形、按钮、配置页面）。

### 配置关键
- `config/user_settings.json`
  - 用户设置总文件：LTC默认帧率、AI key、规则都在这里。

---

## 三、傻瓜式运行指南（新电脑也能跑）

> 以下在 Ubuntu / Linux 下执行。

### 步骤 1：安装基础环境
在终端（黑框）输入：

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven
```

### 步骤 2：进入项目目录
```bash
cd /home/shenlei/.openclaw/agents/dev/workspaces/dj-link-stage
```

### 步骤 3：编译
```bash
cd dbclient
mvn -q compile -DskipTests
cd ..
```

### 步骤 4：构建运行时类路径
```bash
./scripts/build-dbclient-runtime.sh
```

### 步骤 5：启动
```bash
./scripts/start-dbclient.sh
```

启动后浏览器打开：
- `http://127.0.0.1:8080/`

---

## 四、常见报错怎么修

### 坑1：`mvn: command not found`
- 原因：没装 Maven
- 修复：`sudo apt install -y maven`

### 坑2：`java: command not found`
- 原因：没装 Java
- 修复：`sudo apt install -y openjdk-17-jdk`

### 坑3：`Address already in use`（端口被占用）
- 原因：8080 已被别的程序占用
- 修复：
```bash
lsof -i :8080
# 找到PID后
kill -9 <PID>
```

### 坑4：页面打开但没有数据
- 先看右上角扫描按钮是不是“开启扫描”
- 确认你的CDJ和电脑在同一局域网

---

## 五、修改小抄（最常改的地方）

### 1) 想改 WebUI 颜色
去这个文件：
- `dbclient/src/main/resources/web/index.html`

重点改顶部 `:root { ... }` 里的颜色变量。

### 2) 想改 LTC 输出参数（帧率/声卡/Gain/采样率/播放源模式）
去这个文件：
- `config/user_settings.json`

找到 `sync`：
```json
"sourceMode": "master",
"masterPlayer": 1,
"ltc": {
  "enabled": false,
  "fps": 25,
  "deviceName": "default",
  "gainDb": -8,
  "sampleRate": 48000
},
"mtc": {
  "enabled": false,
  "midiPort": ""
},
"abletonLink": {
  "enabled": true,
  "port": 17000,
  "updateIntervalMs": 20
}
```
可改项：
- `sourceMode`：`master`（跟随主机）或 `manual`（手动指定）
- `masterPlayer`：手动模式时使用哪个播放器（1~4）
- `ltc.fps`：帧率（24/25/30）
- `ltc.deviceName`：声卡名关键字（default=系统默认）
- `ltc.gainDb`：输出音量（分贝，建议 -12 到 -3）
- `ltc.sampleRate`：采样率（常用 48000）
- `abletonLink.port`：Carabiner 控制端口（默认 17000）
- `abletonLink.updateIntervalMs`：Carabiner 更新周期（默认 20ms）

LTC 可视化验收（外行也能看懂）：
1. 打开 I/O Setup，勾选 LTC Enabled 并保存
2. 看“LTC 电平”绿条：播放时会跳动，调大 gainDb 时跳得更高
3. 看“Live Timecode”大字码：会实时滚动 `00:00:00:00`
4. 看“诊断卡”文字：
   - 正常时：`Status: Outputting | Target: [声卡名] | FPS: [25]`
   - 设备异常时：红色 `ERROR: Device Busy or Not Found`
5. 修改 deviceName/fps/gainDb 后点保存，驱动会热重载（无需重启软件）

### 3) 想改 API Key（AI）
还是这个文件：
- `config/user_settings.json`

找到：
```json
"ai": { "api_key": "" }
```
填入你的 key。

### 4) 想改 API 接口逻辑
去这个文件：
- `dbclient/src/main/java/dbclient/websocket/JettyServer.java`

### 5) 想改“AI指令转规则”逻辑
去这个文件：
- `dbclient/src/main/java/dbclient/ai/AiAgentService.java`

---

## 六、MTC（MIDI 时间码）验收指引（外行版）

1. 打开 I/O Setup
2. 勾选 `MTC Enabled`
3. 在 `MTC 端口` 下拉选择一个输出端口，点“保存配置”
4. 观察 `MTC 信号` 小圆点：
   - 变绿闪烁 = 正在发送 MTC 帧
   - 灰色不闪 = 当前空闲/无源
5. 观察 `MTC 诊断卡`：
   - `状态：输出中` = 正常
   - `状态：已断流（源未播放/离线）` = 上游播放器没在跑
   - 红字错误 = MIDI 端口占用或不可用

常见问题：
- 看不到闪烁：先确认扫描开启、且选中的播放源正在播放
- 红色错误：换一个 MIDI 端口再保存

---

## 七、今天已落地的关键更新（给未来自己看）

1. **Ableton Link 路线已统一为 lib-carabiner（Java 单路）**
   - 已移除旧 Node/C++/UDP 自研 bridge，避免双实现冲突。
   - 现由 `CarabinerLinkEngine` 负责：`canRunCarabiner()`、`start()/stop()`、消息解析、断线重连。
2. **I/O Setup 语义已统一**
   - `Ableton Link Enabled` 直接代表启停 Carabiner。
   - 不再使用旧 Bridge Start/Stop 按钮语义。
3. **时码显示稳定性修复**
   - 当 LTC/MTC 未启用，或源状态为 `OFFLINE/STOPPED` 时，Live Timecode 强制归零，避免残留时间。
4. **同步全开不卡界面**
   - `applySettings()` 改异步，Carabiner 连接改非阻塞，避免保存配置时页面卡死。

## 八、现在的项目结构（一句话）

**设备采集（djlink-service） → 事件与状态聚合（dbclient） → 输出引擎（SyncOutputManager + LTC/MTC/CarabinerLink） → API/WS推送（JettyServer） → 三Tab UI展示（index.html）**。

如果你只记一件事：
- **界面改动看 `index.html`**
- **配置改动看 `config/user_settings.json`**
- **后端逻辑改动看 `JettyServer.java` 和 `SyncOutputManager.java`**

---

## 2026-03-13 当日更新归档（全项目）

### A) Ableton Link（Java + lib-carabiner 单路）
- 明确并暴露了 Carabiner 运行/参与状态字段（running/enabled/statusSeen/versionSeen/start-stop-sync）。
- 修复 peer 重入与会话恢复：对端反复关开 Link 时自动重入，减少手工重启。
- 由 tempo-only 扩展到 tempo+phase 同步，并修正命令语义（`force-beat-at-time`）。
- 修复 UI 误报与连接抖动：
  - 启动中不再当 error 红框显示；
  - `connection refused` 增加短暂抖动窗口，连续失败才报错。
- `beatPosition` 显示语义优化：
  - 展示 source 派生 beat；
  - 同时保留 `carabinerBeatPosition` 原始诊断值。

### B) Titan API（v10/v11）
- 新增 Titan BPM 同步驱动并接入输出层（`TitanApiDriver` + `TitanAdapter`）。
- 支持 v10/v11 分支：
  - v11 扫描 handles 获取 `titanId`；
  - v10 走 `SetMasterLevel`。
- BPM 发送前统一整数化（`Math.round`）并范围保护。
- 配置/UI 简化为仅填写 IP（端口固定 4430）。
- BPM Master 从单选升级为多选，可同时向多个 master 顺序下发。

### C) MA2 BPM（Telnet，当前阶段仅 BPM）
- 新增 `Ma2BpmDriver` 与 `Ma2TelnetClient`，接入 `SyncOutputManager`。
- 新增调试接口：
  - `POST /api/ma2/test-bpm`
  - `POST /api/ma2/test-command`
- Telnet 登录链路重构：
  - 显式发送 `login "user" "pass"`；
  - 同时支持空密码与非空密码；
  - 连接生命周期按 connId 可追踪（connected/login/send/closed）。
- 自动同步逻辑：
  - 使用实际播放 BPM（`masterBpm + sourcePitchPct`）;
  - `onlyWhenPlaying`、整数化、范围过滤、节流/去重。
- 手动测试保护：发送测试命令后暂停自动同步 5 秒，避免立即被自动值覆盖。
- 密码保存语义修复：
  - 未改密码 -> 保留旧值；
  - 明确清空 -> 保存 `""`。
- MA2 UI 收口：
  - SpeedMaster 改为 1~15 下拉（非法值回落 1）；
  - 命令模板从 UI 隐藏（后端固定默认模板）。

### D) Dashboard / I/O Setup UI
- I/O Setup 重排为模块化卡片：播放源、SMPTE（含 LTC/MTC 子卡）、Ableton Link、Titan、MA2、Realtime 状态。
- Dashboard 增加 HTTP 轮询兜底，WS 抖动时避免界面空白。

### E) 配置与接口变更（摘要）
- 配置新增/扩展：
  - `sync.titanApi`（IP-only、多 master）
  - `sync.ma2Telnet`（BPM-only Telnet 配置）
- 接口新增：
  - `POST /api/ma2/test-bpm`
  - `POST /api/ma2/test-command`

### F) 当前已知限制
- MA2/Titan 属于控制台侧协议，返回文本并非每次都稳定；不返回文本不等于命令未执行。
- MA2 当前阶段仅 BPM 同步，不含 Beat/Trigger/Macro/Executor 逻辑。


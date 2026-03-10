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

### 第二阶段（新模块）
- `dbclient/src/main/java/dbclient/sync/SyncOutputManager.java`
  - 信号输出总调度器（LTC/MTC/Link/Console）。
- `dbclient/src/main/java/dbclient/sync/OutputDriver.java`
  - 驱动统一接口（start/stop/update/status）。
- `dbclient/src/main/java/dbclient/sync/drivers/LtcDriver.java`
  - LTC音频输出驱动（从主时钟生成时间码音频，输出到声卡）。
- `dbclient/src/main/java/dbclient/sync/drivers/MtcDriver.java`
  - MTC驱动占位。
- `dbclient/src/main/java/dbclient/sync/drivers/AbletonLinkDriver.java`
  - Ableton Link驱动占位。
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

### 2) 想改 LTC 输出参数（帧率/声卡/Gain/采样率）
去这个文件：
- `config/user_settings.json`

找到 `sync.ltc`：
```json
"ltc": {
  "enabled": false,
  "fps": 25,
  "deviceName": "default",
  "gainDb": -8,
  "sampleRate": 48000
}
```
可改项：
- `fps`：帧率（24/25/30）
- `deviceName`：声卡名关键字（default=系统默认）
- `gainDb`：输出音量（分贝，建议 -12 到 -3）
- `sampleRate`：采样率（常用 48000）

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

## 六、现在的项目结构（一句话）

**设备采集（djlink-service） → 事件与状态聚合（dbclient） → 输出引擎（SyncOutputManager） → API/WS推送（JettyServer） → 三Tab UI展示（index.html）**。

如果你只记一件事：
- **界面改动看 `index.html`**
- **配置改动看 `config/user_settings.json`**
- **后端逻辑改动看 `JettyServer.java` 和 `SyncOutputManager.java`**

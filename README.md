# DJ LINK STAGE

DJ LINK STAGE 是一个面向灯光师 / VJ / 舞台执行团队的 DJ Link 实时监控与触发中枢项目。

## 项目目标
- 从 CDJ/XDJ（通过 beat-link）获取实时状态与音乐结构数据
- 在 WebUI（默认端口 `8080`）进行可视化监看
- 输出可用于后续灯光触发系统（Trigger Engine）的结构化事件
- 支持 Mixer on-air 信号独立读取（`/api/mixer/state`）

---

## 架构概览

```text
CDJ/XDJ/Mixer (Pro DJ Link)
        |
     beat-link
        |
  +-------------------+
  | djlink-service     |  核心数据聚合/分析/事件
  | DeviceManager      |
  | TriggerEngine      |
  +-------------------+
        |
  +-------------------+
  | dbclient (Jetty)   |  HTTP API + WebSocket + WebUI
  | /api/*, /ws, /     |
  +-------------------+
        |
      Browser/UI
```

---

## 关键目录

- `dbclient/`：统一服务入口（Jetty + API + WS + WebUI）
- `djlink-service/`：DJ Link 数据聚合与触发核心
- `djlink-agent/`：协议实验与代理相关模块
- `docs/`：接口文档与说明
- `memory/`：Agent 日志记忆与项目过程记录
- `scripts/`：启动、构建、健康检查脚本

---

## 主要接口（当前）

- `GET /api/players/state`
- `GET /api/players/events`
- `GET /api/players/track|sections|beatgrid|cues|waveform|artwork`
- `GET /api/mixer/state`（mixer 独立路径，当前含 on-air）
- `GET /api/scan`
- `GET /api/scan/toggle?enabled=1|0`
- `WS /ws`

兼容旧路径保留（后续可下线）：`/api/djlink/*`, `/api/triggers/events`

---

## 环境要求

- OS: Linux/macOS/Windows（推荐 Linux 服务器环境）
- Java: 17+
- Maven: 3.8+
- Git
- 可选：`gh`（GitHub CLI）

---

## 本地安装与运行

### 1) 安装依赖
```bash
# Ubuntu 示例
sudo apt update
sudo apt install -y openjdk-17-jdk maven git
```

### 2) 构建模块
```bash
cd djlink-service
mvn -q install -DskipTests

cd ../dbclient
mvn -q compile -DskipTests
```

### 3) 生成运行 classpath 并启动
```bash
cd ..
./scripts/build-dbclient-runtime.sh
./scripts/start-dbclient.sh
```

### 4) 访问
- WebUI: `http://127.0.0.1:8080/`
- API: `http://127.0.0.1:8080/api/players/state`

---

## 在新电脑上快速恢复

1. 克隆仓库
```bash
git clone <YOUR_REPO_URL>
cd DJ-LINK-STAGE-Full-Backup
```

2. 安装 Java/Maven（见上）

3. 构建并启动
```bash
./scripts/build-dbclient-runtime.sh
./scripts/start-dbclient.sh
```

4. 如需长期运行（Linux）
- 使用 systemd user service（可参考项目内脚本与现有配置）

---

## 说明

- 本仓库包含完整项目代码、文档、记忆数据与运行脚本。
- `memory/` 中包含开发阶段的过程记录（按日期归档）。
- 后续触发系统 UI/规则引擎将在当前基础上继续扩展。

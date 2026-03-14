# PROJECT_CONTEXT.md - 项目上下文

## 项目概述

**项目名称**: DJ LINK STAGE  
**项目定位**: 面向演出场景的播放状态中枢、同步输出平台与事件触发平台  
**GitHub 仓库**: https://github.com/lyl3chen/DJ-LINK-STAGE-Clean  
**当前 Commit**: 43867ca  
**项目根目录**: /home/shenlei/.openclaw/agents/dev/workspace/dj-link-stage/

## 项目架构

### 6 层架构

1. **输入源层** - 数据来源（DJ Link / Local Playback）
2. **统一播放状态核心** - 唯一播放状态真源
3. **连续同步核心** - 连续型同步输出
4. **事件触发核心** - 离散事件检测与触发
5. **输出适配层** - 外部系统输出
6. **表现与控制层** - WebUI / API

### 详细架构说明

参见文档：`PROJECT_ARCHITECTURE.md`

## 核心模块

### 设备输入与协议层

| 模块 | 职责 |
|------|------|
| DeviceManager | DJ Link 设备管理 |
| DbConnection | 数据库连接 |
| DbProtocol | 协议解析 |
| MetadataParser | 元数据解析 |
| SystemState | 系统状态管理 |

### 同步输出框架

| 模块 | 职责 |
|------|------|
| SyncOutputManager | 同步输出管理器 |
| OutputDriver | 输出驱动接口 |

### 当前输出模块

- AbletonLinkDriver - Ableton Link 同步
- TitanApiDriver - Titan API 同步
- Ma2TelnetClient - MA2 Telnet 同步
- ConsoleApiDriver - Console API 同步

### 表现与控制层

- WebServer / JettyServer
- EventPusher
- WebUI

## 时间码模块

### 当前状态

LTC / MTC 已从仓库中完全清除，准备从 0 重建。

### 重建原则

- LTC 与 MTC 必须共用同一时间源核心
- 输出层只负责编码和发送
- 不得各自维护独立时间推进逻辑
- 必须使用本地单调时钟或等价稳定时基

## 未来规划

### 本地播放模式

- 媒体库模块
- 音频分析模块
- 本地播放器模块
- 分析缓存模块

### 事件触发系统

- 播放开始/暂停/停止事件
- Hot Cue / 切歌事件
- 段落切换事件

---

**最后更新**: 2026-03-15 GMT+8

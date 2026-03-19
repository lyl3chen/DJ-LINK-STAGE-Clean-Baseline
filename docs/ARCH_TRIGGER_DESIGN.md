# 统一触发架构设计方案

> **本文档已反映 2026-03-19 验证通过的实际状态**

---

## 一、核心架构（已验证）

### 1.1 统一 Trigger 框架

```
TriggerContext（统一数据模型）
    ↓
TriggerEngine（规则评估）
    ↓
TriggerEvent（事件生成）
    ↓
TriggerActionDispatcher（动作分发）
    ↓
TriggerActionDriver（Titan/MA2 协议执行）
```

### 1.2 双源适配

| Adapter | 来源 | 可用字段 |
|----------|------|----------|
| `CdjTriggerContextAdapter` | CDJ/beat-link | playbackState, positionMs, bpm, beatNumber, phase |
| `LocalTriggerContextAdapter` | Local Player | + beatGrid, waveformPreview, markers |

---

## 二、TriggerContext 字段定义

### 字段来源划分

- **source metadata**：trackId, title, artist, durationMs
- **runtime**：playbackState, positionMs
- **analysis**：bpm, beatGrid, waveformPreview
- **marker**：markers

### 字段缺失处理

| 场景 | 处理 |
|------|------|
| beatGrid == null | BEAT 规则不触发（除非 beatNumber 有效） |
| markers == null | MARKER 规则不触发 |
| waveformPreview == null | 不影响触发，只影响 UI |

---

## 三、TriggerEngine 语义

### 3.1 重置语义（已确认）

| 操作 | 语义 |
|------|------|
| Seek 到更早位置 | 按位置重置 |
| Stop → Play 同一曲 | **视为新周期，清空触发状态** |
| 切歌 | 清空全部触发状态 |

### 3.2 防重复触发

| 条件类型 | 防重复逻辑 |
|----------|------------|
| BEAT | beatNumber 变化且命中 interval |
| MARKER | 同一 markerId 仅触发一次，回到 marker 前重置 |
| POSITION | 首次跨越阈值触发，回到阈值前重置 |
| STATE_CHANGE | 状态真正变化时触发 |

---

## 四、Rule / Condition / Action

### 4.1 支持的条件类型

| 类型 | 说明 | CDJ | Local |
|------|------|-----|-------|
| BEAT | 每 N 拍触发 | ✅ | ✅ |
| MARKER | Marker 触发 | ❌ | ✅ |
| POSITION | 位置触发 | ✅ | ✅ |
| STATE_CHANGE | 状态变化触发 | ✅ | ✅ |

### 4.2 支持的动作类型

| 类型 | 说明 | 状态 |
|------|------|------|
| NONE | 空动作 | ✅ |
| LOG | 日志 | ✅ |
| CALLBACK | 回调 | 骨架 |
| SEND_COMMAND | 发送协议命令 | 骨架 |

---

## 五、协议客户端层

### 5.1 接口抽象

```java
interface ProtocolClient<C> {
    boolean connect();
    void disconnect();
    String sendCommand(String command);
    boolean isConnected();
}
```

### 5.2 专用接口

- **TitanClient**：Titan 协议
- **Ma2Client**：MA2 协议

### 5.3 复用关系

| 驱动 | 复用 Client |
|------|--------------|
| TitanBpmSyncDriver | 后续用 TitanClient |
| TitanTriggerActionDriver | 后续用 TitanClient |
| Ma2BpmSyncDriver | 后续用 Ma2Client |
| Ma2TriggerActionDriver | 后续用 Ma2Client |

---

## 六、已验证结论

✅ TriggerEngine 可同时消费 CDJ 和 Local 上下文
✅ 同一套规则可跨两种来源工作
✅ 字段缺失时自动降级，不崩溃
✅ Stop → Play 视为新周期
✅ 双源 Trigger 验证通过

---

## 七、下一步（Local Trigger UI）

1. Marker 列表 + 编辑 UI
2. 波形展示（Canvas 渲染）
3. 点击打点

---

*本文档最后更新：2026-03-19*

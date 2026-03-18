# 本地播放器错误可视化规则（Step B3）

Date: 2026-03-19  
Scope: 错误提示统一收口

---

## 1. 错误来源清单

| 操作 | 错误来源 | 后端字段 | 前端显示 |
|---|---|---|---|
| 导入 | 文件格式不支持、文件不存在、导入异常 | `data.error` | 统一错误区 |
| 上传 | 非 multipart、格式不支持 | `data.error` | 统一错误区 |
| load | 曲目不存在、设备打开失败 | `data.error` / `data.lastError` | 统一错误区 |
| play | 未初始化、播放异常 | `data.error` | 统一错误区 |
| pause | 未初始化 | `data.error` | 统一错误区 |
| stop | 未初始化 | `data.error` | 统一错误区 |
| seek | 未初始化 | `data.error` | 统一错误区 |
| 删除 | 曲目不存在、TRACK_IN_USE | `data.error` / `data.code` | 统一错误区 |
| 状态刷新 | 网络异常、服务端异常 | `e.message` | 统一错误区 |

---

## 2. 统一错误展示规则

### 2.1 单一真实错误来源
- **主错误**：后端 `data.error` 或 `data.lastError`
- **次错误**：网络/前端异常 `e.message`
- **禁止**：不要把 HTTP HTML 错页、JSON 解析失败作为独立错误展示

### 2.2 统一显示区域
- **位置**：`localStatusMsg`（播放控制区下方）
- **样式**：
  - 成功：`diag-card`（绿色系）
  - 错误：`diag-card error`（红色系）

### 2.3 错误可见周期保护
- 错误产生后**最少可见 3 秒**
- 期间状态刷新（每 500ms）不会覆盖错误
- 3 秒后自动恢复显示当前状态
- 用户新操作成功时立即清除错误

### 2.4 错误文案规范
- 统一前缀：
  - 错误：`❌ `
  - 警告：`⚠️ `
  - 成功：`✅ `
- 优先显示后端真实错误，不掩盖
- 网络异常追加简要说明

---

## 3. 主错误 vs 附加诊断信息

### 主错误（必须显示在统一错误区）
- `data.error`（API 返回的业务错误）
- `data.lastError`（后端持续错误状态）
- 操作异常（网络、JSON 解析失败）

### 附加诊断信息（显示在诊断区，不替代主错误）
- `configuredDevice`（配置目标设备）
- `actualOpenedDevice`（实际打开设备）
- `audioFormat`（音频格式）

---

## 4. 实现要点

### 前端状态变量
```javascript
let localActiveError = null;      // 当前活跃错误文本
let localActiveErrorTime = 0;     // 错误产生时间戳
const LOCAL_ERROR_MIN_VISIBLE_MS = 3000;  // 最少可见 3 秒
```

### 错误显示函数
```javascript
function showLocalControlMsg(msg, isError) {
  // 显示消息
  // 如果是错误，记录到 localActiveError 并打时间戳
}
```

### 状态刷新逻辑
```javascript
// 在 refreshLocalPlayerStatus 中
const errorAge = now - localActiveErrorTime;
const shouldShowError = localActiveError && errorAge < 3000;

if (shouldShowError) {
  // 保持显示活跃错误
} else {
  // 显示当前状态（或清除过期错误后显示状态）
}
```

### 成功操作清除错误
```javascript
// play/pause/stop/load/delete/seek 成功时
clearLocalActiveError();
```

---

## 5. 禁止事项

- ❌ 不要把 HTML 错误页内容显示给用户
- ❌ 不要把 JSON 解析失败作为独立主错误
- ❌ 不要让状态刷新立即覆盖操作错误
- ❌ 不要在多个区域同时显示冲突的错误提示

---

## 6. 本轮变更文件

- `dbclient/src/main/resources/web/index.html`
  - 增加错误状态管理变量
  - 统一 `showLocalControlMsg` 错误记录
  - 修改 `refreshLocalPlayerStatus` 错误保护逻辑
  - 所有操作函数统一使用 `showLocalControlMsg`
  - 成功操作调用 `clearLocalActiveError`

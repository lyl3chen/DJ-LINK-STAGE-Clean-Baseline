# BLT → OpenClaw 连接与设置指南（v1）

适用场景：
- 你希望 Beat Link Trigger（BLT）把 **节拍/位置/段落信息**实时推送到 OpenClaw，用于 AI 灯光系统。

本指南基于已验证可用的一套配置：
- BLT：`192.168.100.134`
- OpenClaw：`192.168.100.200`

---

## 1. 总体架构（已跑通）

DJ 系统（CDJ / DJ电脑）
→ 音乐识别（BLT / 音频分析）
→ Show Control Server（OpenClaw，接收事件总线）
→ 灯光控台（MA2 / Tiger Touch）
→ 灯具

本次实现的是中间一段：

**BLT →（OSC/UDP + JSON）→ OpenClaw 事件总线（HTTP/WS + JSONL 日志）**

---

## 2. 网络与端口（必须放行）

### OpenClaw 侧监听
- **UDP 9900**：接收 BLT 发来的 OSC `/blt/event`（参数为 JSON 字符串）
- **TCP 9910**：Web 监控界面 + Health/Stats + WebSocket 事件流

### 如果 OpenClaw 主机启用了 UFW（默认 incoming deny）

```bash
sudo ufw allow from 192.168.100.0/24 to any port 9900 proto udp
sudo ufw allow from 192.168.100.0/24 to any port 9910 proto tcp
```

验证：打开 `http://192.168.100.200:9910/health`，看到 `received` 增长。

---

## 3. OpenClaw 侧：接收服务（blt-osc-ingest）

功能：
- 接收 OSC/UDP（9900）
- 写 JSONL 日志
- 通过 WebSocket 广播事件
- 提供 Web 监控界面
- 内置 Normalizer：`trackTimeReached → trackPositionMs`

运行：

```bash
cd blt-osc-ingest
npm install
node index.js
```

常用地址：
- Web UI：`http://192.168.100.200:9910/`
- Health：`http://192.168.100.200:9910/health`
- Stats（给 UI 列出可选 events/players）：`http://192.168.100.200:9910/stats`
- Schema：`http://192.168.100.200:9910/schema`
- WebSocket：`ws://192.168.100.200:9910/ws`

---

## 4. BLT 侧：Expressions 配置（核心）

约定：
- 目标：OpenClaw `192.168.100.200:9900/udp`
- OSC Address：`/blt/event`
- OSC 参数：**一个字符串**，内容为 JSON

### 4.1 全局 Setup / Shutdown（创建 OSC client）

**Global Setup Expression**：

```clojure
(swap! globals assoc :oc-osc (osc/osc-client "192.168.100.200" 9900))
```

**Global Shutdown Expression**：

```clojure
(osc/osc-close (:oc-osc @globals))
(swap! globals dissoc :oc-osc)
```

### 4.2 位置 Position（普通 Trigger → Tracked Update Expression，20Hz）

> 这条是“精度最高、最有用”的输入：`playing + trackTimeReached(毫秒) + bpm`。

```clojure
(let [now (System/currentTimeMillis)
      last (or (:last-pos-sent @locals) 0)]
  (when (>= (- now last) 50)   ;; 50ms = 20Hz
    (swap! locals assoc :last-pos-sent now)
    (let [payload {"ts" now
                   "source" "blt"
                   "event" "position"
                   "player" device-number
                   "bpm" effective-tempo
                   "playing" playing?
                   "trackTimeReached" track-time-reached
                   "trackTitle" track-title
                   "trackArtist" track-artist}]
      (osc/osc-send (:oc-osc @globals) "/blt/event"
                    (cheshire.core/encode payload)))))
```

OpenClaw 侧会额外映射：
- `trackTimeReached` → `trackPositionMs`

### 4.3 段落 Phrase（Show → Phrases → Phrase Beat Expression）

> 只有在 **Show/Phrase** 的表达式上下文里才有 `phrase-type`、`section`。

```clojure
(let [payload {"ts" (System/currentTimeMillis)
               "source" "blt"
               "event" "phraseBeat"
               "player" device-number
               "bpm" effective-tempo
               "beat" beat-within-bar
               "trackBank" track-bank
               "phraseType" phrase-type
               "section" (name section)
               "trackTitle" track-title
               "trackArtist" track-artist}]
  (osc/osc-send (:oc-osc @globals) "/blt/event"
                (cheshire.core/encode payload)))
```

---

## 5. 常见问题（已踩坑总结）

### Q1：`received 0 / lastEventTs null`
A：大概率是防火墙（UFW 默认 deny incoming）没放行 **UDP 9900**。

### Q2：表达式报错 `Unable to resolve symbol: XXX`
A：变量只在特定上下文存在（Trigger / Phrase / Show）。
- `phrase-type` 只能在 Phrase Trigger 上下文。
- `pitch` 在你的 Tracked Update 上下文中不存在（已验证）。

### Q3：我能不能用 BLT 的 OBS Overlay Web Server `/params.json` 拉数据？
A：可以做**监控面板**，但它是 pull 轮询，不适合做高精度节拍/位置控制。

---

## 6. 验证清单（最小闭环）

1) OpenClaw：打开 `http://192.168.100.200:9910/` 看到卡片刷新
2) OpenClaw：`/health` 的 `received` 持续增长
3) 日志：`blt-events-YYYY-MM-DD.jsonl` 能看到 `position` 带 `trackTimeReached`
4) Phrase：能看到 `phraseBeat` 带 `phraseType/section`

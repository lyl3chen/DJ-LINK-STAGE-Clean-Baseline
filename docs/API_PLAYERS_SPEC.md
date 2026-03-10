# DJLINK Players API 规范（v1）

> 主命名空间统一为 `/api/players/*`。旧路径保留兼容，但建议迁移。

## 1. 总览

Base URL:
- `http://<host>:8080`

实时通道:
- `ws://<host>:8080/ws`

统一返回:
- JSON 编码（封面接口除外，返回二进制图片）
- CORS: `Access-Control-Allow-Origin: *`

---

## 2. 核心接口

### 2.1 获取播放器统一状态
`GET /api/players/state`

用途:
- WebUI 主数据源
- AI/规则引擎读取实时状态

示例响应（节选）:
```json
{
  "players": [
    {
      "number": 1,
      "active": true,
      "master": true,
      "playing": true,
      "beat": 324,
      "bpm": 126.0,
      "pitch": -0.15,
      "currentTimeMs": 153937,
      "durationMs": 235000,
      "remainingTimeMs": 81063,
      "beatTimeMs": 153937,
      "triggerKey": "player-1",
      "canTrigger": true,
      "decision": {
        "mode": "READY",
        "energyLevel": "MEDIUM",
        "ready": true,
        "hasHotCues": true,
        "sectionType": "UNKNOWN"
      },
      "track": {
        "title": "Only Girl (In The World)",
        "artist": "Rihanna",
        "album": "Only Girl (In The World)",
        "duration": 235,
        "trackType": "REKORDBOX",
        "sourceSlot": "SD_SLOT",
        "artworkAvailable": true,
        "artworkId": 563,
        "artworkUrl": "/api/players/artwork?player=1&id=563"
      },
      "analysis": {
        "beatGridFound": true,
        "beatCount": 496,
        "cueFound": true,
        "cueCount": 4,
        "hasHotCues": true,
        "hotCueCount": 4,
        "hotCueTimesMs": [600, 92026],
        "previewWaveformFound": true,
        "previewLength": 1200,
        "previewSample": [0, 80, 82]
      },
      "currentSection": {
        "type": "UNKNOWN",
        "index": 11,
        "startTimeMs": 152508,
        "endTimeMs": 167270,
        "confidence": 0.5
      }
    }
  ],
  "online": 2,
  "master": "1",
  "updatedAt": 1773009404,
  "ruleVersion": "sections-mvp-v2"
}
```

---

### 2.2 获取触发事件队列
`GET /api/players/events`

用途:
- 读取 TriggerEngine 事件

常见事件:
- `PLAYER_ONLINE`
- `PLAYER_OFFLINE`
- `TRACK_CHANGED`
- `PLAY_STARTED`
- `PLAY_STOPPED`
- `MASTER_CHANGED`
- `SECTION_CHANGED`
- `BAR_CHANGED`
- `HOTCUE_REACHED`

---

### 2.3 专辑封面（二进制）
`GET /api/players/artwork?player=<number>&id=<artworkId>`

参数:
- `player` 必填，播放器编号
- `id` 建议传入（用于缓存键稳定）

响应:
- `200 image/jpeg` 或 `200 image/png`
- 无封面时 `404 no artwork`
- `Cache-Control: public, max-age=86400, immutable`

---

## 3. 扩展接口（players 命名）

- `GET /api/players/track`
- `GET /api/players/sections`
- `GET /api/players/beatgrid`
- `GET /api/players/cues`
- `GET /api/players/waveform`

---

## 4. WebSocket 规范

`WS /ws`

消息类型:

### EVENT
```json
{
  "type": "EVENT",
  "event": "PLAY_STARTED",
  "player": 1,
  "time": 1773009404,
  "data": {}
}
```

### STATE
```json
{
  "type": "STATE",
  "players": [],
  "time": 1773009404
}
```

---

## 5. 兼容旧路径（Deprecated，但仍可用）

- `/api/triggers/events` -> `/api/players/events`
- `/api/djlink/track` -> `/api/players/track`
- `/api/djlink/sections` -> `/api/players/sections`
- `/api/djlink/beatgrid` -> `/api/players/beatgrid`
- `/api/djlink/cues` -> `/api/players/cues`
- `/api/djlink/waveform` -> `/api/players/waveform`
- `/api/djlink/artwork` -> `/api/players/artwork`

建议新代码全部使用 `/api/players/*`。

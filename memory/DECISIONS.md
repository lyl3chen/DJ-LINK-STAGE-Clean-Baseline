# DECISIONS.md - 关键技术决策记录

## 2026-03-15 关键决策

### LTC/MTC 彻底下线清理

**决策**: 彻底下线并清理 LTC/MTC 相关代码，不再修补

**原因**:
1. 长期修改未能收敛
2. 代码污染严重
3. 新旧模块并存过久
4. 临时补丁、调试逻辑、过渡实现、历史残留过多
5. 当前大模型能力不足以在现有基础上可靠收敛

**执行操作**:
1. 删除 LtcDriver.java
2. 删除 MtcDriver.java
3. 清理 SyncOutputManager 中的 LTC/MTC 注册入口
4. 清理 WebUI 中的 LTC/MTC 配置卡片和状态显示
5. 创建 TIMECODE_REBUILD_REQUIREMENTS.md 记录后续实现规格

**保留模块**:
- AbletonLinkDriver
- ConsoleApiDriver
- Titan API
- MA2 Telnet

---

## 后续重新实现原则

**必须遵循** (记录在 TIMECODE_REBUILD_REQUIREMENTS.md):

1. 使用本地单调时钟 (monotonic clock) 作为时间推进基础
2. 不允许 wall-clock 式反复重贴
3. 只允许在明确事件发生时重锚
4. LTC 与 MTC 共用同一套时间源核心
5. 输出层只负责编码与发送，不负责决定时间推进
6. 必须明确区分暂停/停止/播放状态

---

**文档版本**: 1.1
**最后更新**: 2026-03-15 04:30 GMT+8

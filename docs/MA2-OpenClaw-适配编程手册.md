# grandMA2（onPC）酒吧现场通用适配编程手册（适配 BLT→OpenClaw→MA2）

> 目标：适配酒吧现场多变环境（灯具不固定/每天风格不同），并与本项目的 **Intent/Rules → MA2 Executors** 映射稳定对接。

---

## 0. 核心原则（保证“多变 + 可控”）

1. **分层（Layering）**：主 Look 不要包揽一切；打点、Build、Strobe、移动、颜色都拆成独立层。
2. **类型优先（Fixture-type first）**：按灯具类型组织（Beam/Wash/Spot/Strobe），而不是按具体品牌型号。
3. **用“变化源”驱动变化**：
   - 变化源 1：音乐段落（scene：INTRO/BUILD/DROP/BREAK/CHORUS/FILL/IDLE）
   - 变化源 2：打点（hit）
   - 变化源 3：旋钮（energy/brightness/speed/movement/strobe/colorMood）
4. **每层至少准备 3~6 个 Variation（变体）**，系统轮换/随机/按规则切，避免“每天同一套”。
5. **所有自动化都必须可一键停**（Blackout / Kill / Override）。

---

## 1) 现场快速建模：先把灯“归类”

不管酒吧是什么灯，尽量做这 4 个 Group（没有就留空）：

- `GRP_BEAM`
- `GRP_WASH`
- `GRP_SPOT`
- `GRP_STROBE`

如果还有（强烈推荐）：

- `GRP_BLINDER`（观众面白灯/COB）
- `GRP_LED_BAR`（像素条/矩阵）

> 现场最怕“灯混在一起”，后面所有多变都依赖 Group。

---

## 2) 预置（Preset）怎么做才通用

你不需要做得很细，但建议做“够用且可组合”的 preset。

### 2.1 颜色（Color Presets）

至少做一套通用色盘（给 wash/spot/beam 都能用）：

- `C_NEUTRAL`（中性白/浅色）
- `C_WARM`（琥珀/暖白）
- `C_COOL`（冷白/浅蓝）
- `C_RED`
- `C_BLUE`
- `C_RAINBOW`（多色/滚动色，如果灯支持）

### 2.2 位置（Position Presets）

- `P_CENTER`（中间聚焦）
- `P_WIDE`（打开/扫观众）
- `P_SWEEP_LR`（左右扫）
- `P_SWEEP_FAN`（扇形）

### 2.3 图案/棱镜（Gobo / Prism）

- `G_SIMPLE`
- `G_COMPLEX`
- `PRISM_ON`（有就做）

### 2.4 Dimmer / Shutter（基础强度）

- `DIM_LOW` / `DIM_MID` / `DIM_HIGH`
- `SHUTTER_OPEN`

---

## 3) Executor 结构（适配 OpenClaw 的通用控台布局）

下面是推荐的 Exec 1~20（你先做 1~9 就足够强大）。

### A. 主场景层（Base Look）

#### Exec 1：MAIN LOOK（Scene Bank）

做一个 Sequence，多 cue，对应 scene：

- Cue 1：INTRO（冷/低/慢）
- Cue 2：BUILD（中/紧/升）
- Cue 3：DROP（亮/彩/大）
- Cue 4：BREAK（低/留白）
- Cue 5：CHORUS（亮/彩/更满）
- Cue 6：FILL（短促/更激动）

关键：**Exec 1 只负责“底色/底运动”**，不要把 strobe/hit 做进去。

> OpenClaw 映射方式：后续用 `Goto Executor 1 Cue X`。

### B. 打点层（Accent / Hit）

#### Exec 2：HIT（Flash/Temp）

用途：`hit=true` 时“闪一下”。

建议至少做 3 个变体 cue：

- Cue 1：White hit
- Cue 2：Warm hit
- Cue 3：Color hit

关键：**极短（0.10~0.20s）**，不破坏主 look。

> OpenClaw 触发方式：FlashPulse 或 TempPulse 均可。

### C. Build 层（张力/加速）

#### Exec 3：BUILD FX（Temp Layer）

用途：BUILD 时叠加一层“越来越紧张”的运动/扫描/光束。

建议做 3 个变体 cue（不同 movement pattern）。

> OpenClaw 映射方式：scene=BUILD 时 TempPulse/Toggle；离开 BUILD 时 Off。

### D. 频闪层（持续 strobe，与 hit 区分）

#### Exec 4：STROBE LAYER

内容：可持续 strobe（不同速率/不同 shutter pattern）。

关键：不要用它当 hit；hit 是 Exec2。

### E. 运动层（更大范围的运动）

#### Exec 5：MOVE BIG（Pan/Tilt FX）

让 DROP/CHORUS 更“活”。不改颜色，只动位置/速度。

### F. 颜色层（只管氛围）

#### Exec 6：COLOR MOOD

Cue：NEUTRAL / WARM / COOL / RAINBOW / RED / BLUE。

只改颜色，不改 dimmer/位置（避免层之间互相打架）。

### G. 图案层（Gobo / Prism）

#### Exec 7：GOBO/PRISM

Cue：simple/complex + prism on/off。

### H. 安全层（现场必备）

#### Exec 8：BLACKOUT / KILL

一键全黑（优先级最高）。必须手动也能用，自动化也能触发（紧急停）。

### I. 观众能感知的强刺激

#### Exec 9：BLINDER

仅在高潮/少量 hit 使用。建议做 2~3 个变体（短闪、双闪、追闪）。

---

## 4) “多变”的关键：变体（Variation）怎么做

推荐最少变体数量：

- Exec1（MAIN LOOK）：每个 scene 至少 2 个 cue（INTRO_A / INTRO_B…）
- Exec2（HIT）：至少 3 个 cue（white/warm/color）
- Exec3（BUILD）：至少 3 个 cue（不同 movement）
- Exec4（STROBE）：至少 3 个 cue（慢/中/快 或不同 pattern）
- Exec6（COLOR）：至少 6 个 cue（通用色盘）

这样同一首歌也能“每天不同”。

后续可以由 OpenClaw 在映射规则里做：

- 同一个 scene 命中时 **轮换 cue**
- 或按 `colorMood` 自动选 cue

---

## 5) 让 OpenClaw 好映射：你需要保持稳定的“接口”

为了适配最稳，请尽量保证：

- Exec 编号稳定（至少 1~9 不乱改）
- Exec1 的 cue 编号/命名稳定（scene cue 编号固定）
- HIT 层永远在 Exec2（灯换了只改 group/preset，不改接口）

---

## 6) 现场最小落地流程（30~60 分钟）

只做这三项就能跑：

1. Exec1：做 4~6 个 cue（INTRO/BUILD/DROP/BREAK/CHORUS/FILL）
2. Exec2：做 3 个 hit cue（white/warm/color）
3. Exec3：做 3 个 build cue（不同 movement）

做完后，把 Exec1/2/3 的 cue 编号告诉我，我就能生成一份 `intent-map.yml`，让 9940 一键开启 intent 后直接驱动。

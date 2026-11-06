# W1 六流归属真值核对（2026-11-06，L2 离线）

> **★ 2026-11-06 更正（见 `docs/W1_RNG_CALLSITE_MAP.md`）**：本文件末尾把
> 「地狱之刃随机攻击牌 / 随机取攻防牌」也归为 `cardRng` 类、天生近似，
> 是**过度概括**。游戏 `AbstractDungeon.returnTrulyRandomCardInCombat()` 实际用
> **`cardRandomRng`**（在六流内），这几处映射是正确的；只有
> `CardGroup.getRandomCard()`（从已有牌堆**取**一张）用 `cardRng`（未捕获）。
> 原文保留作历史记录，判定以 `W1_RNG_CALLSITE_MAP.md` 为准（AGENTS.md 3.C/7）。

> 本文件核对「引擎里每个 nextIntFrom 调用点映射到哪个游戏 RNG 流」，
> 结论用于纠正 W1-h 的一处错误对齐，并登记无法用六流精确重放的机制。

## 捕获的六流（RngStreams.java，来自真机 Reader.java）

`aiRng, cardRandomRng, miscRng, monsterHpRng, potionRng, shuffleRng`

## 游戏 / sts_lightspeed 里各流的真实用途（字节码 / C++ 源码）

| 流 | 真值来源 | 用途 | 本引擎映射 |
|---|---|---|---|
| aiRng | `vendor/.../Actions.cpp:451` `bc.aiRng.random(validCount-1)`；`:532` `randomBoolean()` | 随机目标、怪物招式/分支、Gremlin 生成 | `MonsterAi`/`MonsterManual` 的 `nextIntFrom(AI, ...)` ✓ 正确 |
| cardRandomRng | `CardManager.cpp:220` `cardRandomRng.random(drawPile.size()-1)`；`:406` `random(3)` | 插入抽牌堆位置、Snecko Oil 费用随机化 | W1-g 插入位置 ✓ 正确 |
| shuffleRng | `CardManager.cpp:28` `java::Random(bc.shuffleRng.randomLong())` | 洗牌 | W1-g 洗牌 ✓ 正确 |
| monsterHpRng | `Monster.cpp:113` `initHp(bc.monsterHpRng, ...)`；`:119-129` 随机 HP | 怪物初始 HP / HP 随机 | **未建模**（本引擎用固定值）→ 真机 L4 会差异 |
| miscRng | `MonsterGroup.cpp:114/128/147/159` `bc.miscRng.*` | 怪物组构成、分裂/召唤选择 | **未建模** → 真机 L4 会差异 |
| potionRng | 六流之一 | 药水相关随机 | **无调用点** |

## ★ 关键：cardRng 不在六流内

游戏 `AbstractDungeon` 另有 `cardRng` 字段（javap 已列）。
`CardGroup.getRandomCard(boolean)` 字节码：

```
getstatic AbstractDungeon.cardRng
invokevirtual Random.random(I)I
```

即**随机取一张手牌/牌堆牌走 cardRng**，而 `cardRng` **不在捕获的六流里**。
依赖它的引擎机制包括：
- 木乃伊之手（随机一张手牌本回合 0 费，`CombatEngine:247`）
- 地狱之刃 / 随机攻击牌（从牌池随机生成，`CombatEngine:676`）
- 随机取攻击/技能牌（`CombatEngine:928`）

这些点**永远无法用六流精确重放**，只能保持「近似」。把它们映射到
`CARD_RANDOM`(=cardRandomRng) 是**流归属错误**，即便数值/counter 等价也不行。

## 对 W1-h 的更正

W1-h 曾把 `CombatEngine:247`（木乃伊之手）从 `nextIntFrom` 改为
`randomFrom(CARD_RANDOM, ...)`，理由是「对齐 wrapper counter」。本轮核对证明：
- counter 对齐本身没错（游戏 `Random.random(int)` 确实 counter+1）；
- 但**流选错了**：该调用点是 `cardRng`，不是 `cardRandomRng`。
- 因此本轮**回滚**该改动，保留 `nextIntFrom` 近似路径，并加注释说明真值缺口。

`RngCounterSemanticsCheck`（5/5）固化的「数值等价、只差 counter」这一
**语义事实仍然成立且有用**，但**不足以**作为把任意调用点改成 randomFrom 的依据——
必须先核对流归属。该自检保留。

## 结论（如实）

- W1 的 aiRng / cardRandomRng / shuffleRng 三处映射经真值核对**正确**。
- monsterHpRng / miscRng 是**未建模缺口**（不是映射错），真机 L4 会出现差异。
- cardRng 不在六流内 → 随机取牌类机制**天生只能近似**，这是 W1 L4 逐位一致的
  结构性障碍之一，需在任务书 W1 验收时明确列出，不能声称「全部随机点已闭环」。
- L2 离线（字节码 / vendored C++ 源码核对），非真机 L4。

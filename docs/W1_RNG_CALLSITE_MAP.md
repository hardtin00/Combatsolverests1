# W1 全部 RNG 调用点的流归属核对表（2026-11-06，L2 离线）

> 承接 `W1_RNG_STREAM_ATTRIBUTION.md`。本轮把引擎里**全部 16 处** `nextIntFrom`
> / `randomFrom` / `shuffleSeedFrom` 调用点按「游戏真实 RNG 流」逐类核对。
> 真值来源：游戏 `desktop-1.0.jar` 字节码 + vendored `sts_lightspeed` C++ 源码。
> **只核对、不做猜测式批量替换**（W1-h→W1-i 已证明流错配风险）。

## 六流（RngStreams 捕获）与游戏字段

`aiRng, cardRandomRng, miscRng, monsterHpRng, potionRng, shuffleRng`
游戏 `AbstractDungeon` 另有 `cardRng`（未捕获）、`MathUtils.random`（libGDX 全局，未捕获）。

## 逐类核对结果

| # | 引擎调用点 | 机制 | 游戏真值（字节码/C++） | 本引擎流 | 判定 |
|---|---|---|---|---|---|
| 1 | CombatEngine:251 木乃伊之手 | 随机一张手牌 0 费 | `CardGroup.getRandomCard(boolean)` → `AbstractDungeon.cardRng` | `CARD_RANDOM`(=cardRandomRng) | **流错配**；cardRng 未捕获 → 只能近似（W1-i 已登记） |
| 2 | CombatEngine:503 奇异勺子 | 50% 不消耗 | `CardGroup.moveToExhaustPile` → `AbstractRelic.onExhaust`；判定不在 relic 类内，jar 内仅见 `MathUtils.random`（libGDX 全局，**非六流**） | `CARD_RANDOM` | **非六流 / 近似**（2026-11-06 复核） |
| 3 | CombatEngine:680 地狱之刃随机攻击牌 | 生成随机攻击牌 | `InfernalBlade.use` → `AbstractDungeon.returnTrulyRandomCardInCombat(type)` → **`cardRandomRng`** | `CARD_RANDOM` | ✓ **正确**（本轮新证据，纠正 W1-i 过度概括） |
| 4 | CombatEngine:932 死亡树枝随机牌 | 消耗牌后生成随机牌 | `DeadBranch.onExhaust` → `MakeTempCardInHandAction` + 同上 `returnTrulyRandomCardInCombat` → `cardRandomRng` | `CARD_RANDOM` | ✓ **正确**（同上） |
| 5 | CombatEngine:1481 怪物伤害随机范围 | `mv.damageMax>damage` 时取随机伤害 | 游戏 `DamageInfo` 只有 `base/output`（固定伤害，javap 已核），**原版无伤害随机范围**；`damageMax` 是本引擎 `MonsterAi` 自造的近似字段（Bestiary 区间数据） | `AI` | **引擎内部近似**（无对应游戏 RNG 流，2026-11-06 复核） |
| 6 | CombatEngine:1532 洗牌（未注入回退） | 洗牌 Fisher-Yates | 注入时用 `shuffleRng.randomLong()` + java LCG（W1-f/g 已对齐） | `SHUFFLE` | ✓ 注入路径正确；未注入为近似 |
| 7 | CombatEngine:1549 随机取攻/防牌（近似） | 随机生成攻/防牌 | 同 #3/#4 `returnTrulyRandomCardInCombat` → `cardRandomRng` | `CARD_RANDOM` | ✓ **正确** |
| 8–12 | MonsterAi:345/403/629/631/664 招式权重与分支 | 怪物选招 | `AbstractMonster.getMove` → `aiRng.random(99)`；`Monster.cpp:633` `bc.aiRng.random(99)` | `AI` | ✓ **正确** |
| 13–16 | MonsterManual:83/85/222/237 特定怪分支 | 怪物选招 | 同 #8 招式选择用 `aiRng` | `AI` | ✓ **正确** |
| 17 | CombatEngine:413/490/1397 插入抽牌堆位置（W1-g 已改 randomFrom） | 洗入位置 | `CardManager.cpp:220` `cardRandomRng.random(size-1)` | `CARD_RANDOM` | ✓ 正确 |
| 18 | shuffleSeedFrom (W1-f/g) | 洗牌种子 | `CardManager.cpp:28` `shuffleRng.randomLong()` | `SHUFFLE` | ✓ 正确 |

## 对 W1-i 的更正（重要）

W1-i 写「随机取牌类机制（木乃伊之手 / 地狱之刃 / 随机取攻防牌）都走 cardRng、
天生只能近似」——**过度概括，现更正**：

- 游戏有**两个不同的随机牌 RNG**：
  - `AbstractDungeon.returnTrulyRandomCardInCombat()`（从全牌池**生成**一张）→ **`cardRandomRng`**，在六流内 →
    地狱之刃、死亡树枝、随机取攻/防牌**可以用六流精确重放**，我们的 `CARD_RANDOM` 映射**正确**。
  - `CardGroup.getRandomCard()`（从**已有**手牌/牌堆**取**一张）→ **`cardRng`**，不在六流内 →
    木乃伊之手这类**只能近似**。
- 因此 W1 的结构性障碍**比 W1-i 写的要小**：只剩
  ① `cardRng` 未捕获（木乃伊之手等"从已有牌取"类）；
  ② `monsterHpRng`/`miscRng` 未建模（怪物初始 HP / 组构成）；
  ③ `potionRng` 无调用点；
  ④ 奇异勺子用 `MathUtils.random`（libGDX 全局，非六流）；怪物伤害随机范围是引擎自造近似（原版无此机制）。二者均已复核，非"待核对"，但都**不在六流内、只能近似**。
- 仍**不得声称「全部随机点已闭环」**，但也不应把可精确重放的生成牌机制误列为近似。

## 结论与边界

- 大多数调用点（怪物选招、生成随机牌、插入位置、洗牌）映射**正确**；
  少数（木乃伊之手 `cardRng` 未捕获、伤害范围引擎自造、奇异勺子 `MathUtils`）属已确认的**非六流近似**，不再标"待核对"。
- 本轮**不改任何生产代码**：上一轮已证数值等价但流错配会造成 L4 对不上，
  批量替换必须先有逐点真值 + 真机验证，故只登记。
- L2 离线（字节码 / vendored C++ 源码），非真机 L4。
- W0 冻结指标不受影响（本轮零代码改动）。

## 2026-11-06 补记（闭合两处「待核对」）

- **奇异勺子**：`javap -c CardGroup.moveToExhaustPile` 显示它遍历 `player.relics` 调
  `onExhaust`，`StrangeSpoon` 类自身无 RNG 调用；jar 内相关随机只见 `MathUtils.random`
  （libGDX 全局）。结论：**不走六流，只能近似**，不再"待核对"。
- **怪物伤害随机范围**：游戏 `DamageInfo` 字段只有 `base`/`output`（固定值），
  `AbstractMonster` 字节码里那条 `MathUtils.random(0,3)` 是音乐分支、与伤害无关。
  本引擎 `MonsterAi.damageMax` 是对 Bestiary 区间数据的自造近似字段，
  `CombatEngine:1481` 的随机伤害**没有对应的游戏 RNG 流**。结论：**引擎内部近似**，
  不是流归属错误，也不该被算进「W1 六流闭环」。

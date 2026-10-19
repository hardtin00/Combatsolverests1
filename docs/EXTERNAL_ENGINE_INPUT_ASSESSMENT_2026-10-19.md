# 外部引擎实时战斗输入架构评估（2026-10-19）

## 结论

**最终可行性结论：C 为有条件 GO；A 目前没有已证实的低成本生产修复。** STS1 活动 RNG 的状态可通过公开 getter/字段无推进地读取，且算法与 sts_lightspeed 状态布局对应；所以 C 的关键技术前提成立。C 仅对明确映射的 vanilla Ironclad 卡牌/遗物/怪物机制可 GO，未映射或 Mod 自定义机制必须拒绝。报告不授权实施 C，也不允许其路线在真实快照差分前执行。

选择 C 的原因：sts_lightspeed 当前 `mcts_save` 固定从 `SaveFile → GameContext::initFromSave → BattleContext::init` 重建战斗；这个路径会重新洗牌、创建怪物并初始化 RNG，无法表示已经发生变化的实战牌堆、怪物状态和回合状态。路线 A 只解决读取事件，不会补上缺失的“当前战斗快照”；路线 B 生成的 STS 存档格式也没有当前战斗对象图，不能直接保存精确中途战斗。C 是唯一能明确表达完整活动战斗状态的边界。

## 现有链路和证据

- Java `LiveCapture.captureRoot()`（`src/main/java/com/combatsolver1/runtime/LiveCapture.java:25`）采集玩家 HP、格挡、当前能量、回合、手牌/抽牌堆/弃牌堆/消耗堆、遗物 ID、药水 ID、部分 powers 和怪物快照。牌堆来自当前 `CardGroup.group` 顺序；这是可复用的实时数据。
- `LiveCapture` 的 `rngState` 是由常量、回合号和手牌数拼出的占位值（第 42 行），不是游戏 RNG 状态。`simCard()`（第 157 行）只转换部分卡牌属性，没有传输 STS1 实例 UUID、misc/special data、全部当前费用状态和所有临时标志。怪物转换（第 174 行起）只复制有限属性；未解析意图时用 fallback，之后还使用 `EnemyModel` 历史均值或 Java 周期表预测后续招式（第 200–213 行）。这些是本地模拟输入/估计，不能拿来声称 C++ 根状态精确。
- C++ `mcts_save` 位于 `vendor/sts_lightspeed/apps/test.cpp:963`；它调用 `SaveFile::loadFromPath`、`GameContext::initFromSave` 和 `BattleContext::init`，而不是接收 Java 实时对象。`SaveFile`（`include/game/SaveFile.h:85`）保存 run/deck/relic/potion/seed-counter 等数据，没有当前战斗的有序牌堆、战斗中卡牌实例、当前格挡/能量、当前怪物 powers/招式历史、已消耗的动作队列等字段。
- `BattleContext::init`（`src/combat/BattleContext.cpp:22`）按 seed/floor 初始化 AI、怪物 HP、shuffle、card、misc、potion 等 RNG，并重建怪物、牌堆及战斗开局效果。`CardManager::init`（`src/combat/CardManager.cpp:16`）按 RNG 重洗整副牌，uniqueId 由 deck index 生成。把一个已运行战斗的实时状态塞回该入口会被重新生成状态覆盖。
- Java 当前已有严格来源新鲜度、STATE0 / journal / 身份及安全执行校验。此评估不建议放宽这些门控。旧 bundle 中的 `23/13561` mismatch 只能证明当前 hook 输入不可信；它没有证明可用磁盘存档代表当前战斗。

## C++ 根状态字段要求与 STS1 可得性

| C++ 必需字段 | STS1 内存可得性 | 当前 LiveCapture | 精确性风险 |
|---|---|---|---|
| 玩家职业、HP/maxHP、当前格挡、能量、回合/升格 | 多数是公开运行对象字段，可直接读取 | HP、格挡、能量、turn 已读 | 需要只在动作队列稳定、可行动时快照；起始能量与回合边界语义需校验 |
| 手、抽牌、弃牌、消耗堆的**顺序**和每张实例 | `AbstractPlayer` 的 CardGroup 可遍历；卡对象有当前属性/实例标识候选 | 当前会遍历各 group，但只投影成简化 `SimState.Card` | C++ `CardInstance` 需要 `id/uniqueId/specialData/cost/costForTurn/upgraded/freeToPlayOnce/retain`；当前没有完整映射。生成牌、复制牌和临时费用也要纳入 |
| relic ID、counter、战斗触发阶段；potion 槽/消耗状态 | relic/potion 实例和 counter 在内存；需核实所有 relic counter 及触发旗标的 API | 只拷贝 relic ID、potion ID | C++ 的 relic 位、计数器和“本回合已触发”状态不等于 ID 集合；缺字段会改变能量、抽牌、伤害和触发序列 |
| 玩家 powers、层数、just-applied、战斗内计数器/stance/orb | powers 可遍历；其他字段分布在 Player、Energy/Orb 等对象 | 仅固定名称子集 | 未枚举能力、stance/orb、刚施加状态、牌数计数等不能假定为零 |
| 每只怪物的 stable identity/index、HP/maxHP、block、状态/power、特殊阶段、逃跑/半死 | 当前 AbstractMonster 与 power list 可读；move history/特殊字段需针对游戏字节码确认 | 有 HP/block 及少量 power；当前意图伤害可尝试读 | `Monster` 还需 `moveHistory[2]`、独有计数/阶段字段；“当前 intent”不足以恢复后续 AI 状态 |
| 怪物 AI 和未来招式 | 当前 intent 可观测；怪物类通常持有历史/计数/阶段 | 后续用 `CombatEngine.initMonsters`、周期表或观测均值 | C++ 后续 `rollMove/takeTurn` 依赖精确 `aiRng` 和怪物历史/内部状态；平均伤害、猜周期不是精确 AI |
| RNG：AI、cardRandom、misc、monsterHP、potion、shuffle 的种子/内部状态及计数 | `AbstractDungeon` 中战斗流对象公开；`Random.counter` 和 `Random.random` 公开；`RandomXS128.getState(0/1)` 读取私有 seed pair。getter 字节码仅取字段并返回，不调用随机函数；`Random.copy()` 也用这两个 getter 克隆状态 | 当前 LiveCapture 的 `rngState` 仍是合成值，未导出真实流 | 可只读精确捕获。STS1 `RandomXS128` 与 C++ `sts::Random` 使用同一 xorshift128+ 状态更新和 nextLong/nextDouble/nextInt(bound) 有界抽取逻辑；直接映射 state0/state1/counter，无需重放推进原生 RNG。实值运行时对照尚未做，须作为实施前离线/实机闸门 |
| 动作/卡牌队列、输入阶段、待结算效果、当回合历史 | `GameActionManager`、队列和 card queue 可读，但完整序列化需逐项核查 | controller 只做稳定状态门控；LiveCapture 不导入 C++ 队列 | 快照需在无待结算动作且玩家输入稳定时采集；否则即使 UI 看起来相同，顺序也会不同 |
| 战斗遇敌、地图节点、种子/升格、初始遗物效果来源 | 当前房间/怪物对象及 dungeon run 数据可读 | 部分在 capture 中隐式使用 | C++ 当前从 `GameContext` 重建地图节点/encounter；直接状态协议应传入 encounter，不再重新抽遭遇 |

因此，STS1 的核心战斗字段有明确读取路径；核心 RNG 已由目标 JAR 字节码确认可无推进读取。`AbstractMonster` 的 `moveHistory`、`nextMove`、`intent` 公开；父类 `AbstractCreature` 的 HP/block/powers 公开。`AbstractCard` 的 UUID、cardID、misc、costForTurn、damage/block、exhaust 等字段公开；`CardGroup.group`、`AbstractRelic.counter`、玩家 powers/orbs/stance 也公开。限制在于不同怪物子类另有私有 AI 状态（本 JAR 有 73 个顶层怪物类；例如 Sentry `firstMove`、Lagavulin `asleep/idleCount`、Book of Stabbing `stabCount`、Champ `thresholdReached/numTurns`），Java 8 反射原则上能只读取，但本轮未做实际反射调用，也未为所有子类证明 C++ 对应语义。可读不等于已建模。

## 三条路线比较

| 路线 | 成本 | 可靠性/随机一致性 | 复杂度与维护风险 | 结论 |
|---|---|---|---|---|
| A 修复原生存档捕获 | 表面上低，根因未明时实际成本未知 | 字节码确认 `loadSaveString(String)` 的唯一参数直接用于 `Gdx.files.local(path).readString()`，随后按 `isObfuscated` 原样或 decode 返回；事务 ID、FileHandle 实际 canonical path、Postfix `String __result` 已绑定到同次调用。故 `nativeChars=23` 确实是打开该 autosave 路径的那次方法返回长度，不是已证实的 C++ 或字段错配。捕获代码和原生代码使用同一流及同一解码器，按已审代码无法解释 23/13561 的矛盾 | 未发现可直接修的参数漏传、错 overload、字符集差异或 decode 算法差异。若改捕获逻辑而无新证据会是猜测；修复 mismatch 也不能代表当前战斗快照一定正确 | **当前 NO-GO 为 V1.0 主入口**：没有已证实的低成本修复。仅凭已有 bundle 不能定位注入/运行时不一致的具体根因 |
| B 从内存生成兼容存档 | 中 | `SaveFile` 是 run 级格式；可在战斗开局重建，但不能忠实表达战斗中途状态。seed/counter 不等同已证明的活动 RNG 状态 | 新增 Java 侧生成/序列化逻辑，容易伪造遗漏字段；C++ 仍会重新 init 和 shuffle | 不推荐。仅当目标严格限定“开战零动作时重建”，且快照字段逐项匹配实机时可做短期桥接 |
| C C++ 直接接收完整实时状态 | 中高 | 核心运行态可从 Java 只读获取，RNG state pair/counter 可直接映射；精确性取决于逐项 field adapter 和状态差分 | 需版本化 DTO/解析器、直接恢复 `BattleContext` 的 importer，以及 Ironclad 已建模卡牌/遗物/怪物适配器；未知字段拒绝 | **推荐路线**。技术前提 GO（限定已映射 vanilla 内容），全内容 GO 否 |

## 最小可执行范围（供批准后实施）

1. 批准实施后，先做实际只读 Java 8 smoke：调用 `getState(0/1)` 和 counter 前后比对，不调用 random 方法；将捕获 state pair 直接灌入测试用 C++ `Random`，比对多种 bound/float/double/boolean 输出及后续状态。再对一场真实开局的六条流、首手和每只怪物 move 对照；不可记录存档明文。
2. 闸门通过后，定义 `live_battle_state v1`：职业/升格/遇敌、所有玩家状态和 relic counters、药水槽、各 pile 的有序卡实例及引擎要求的字段、受支持怪物的 move history/私有计数器、turn/action phase、六条战斗 RNG 状态。采用严格 schema/version 和必填校验；缺字段、未知必需机制或 RNG 不匹配即拒绝。
3. Java 只在主线程、动作稳定点读取并写入快照；C++ 新增独立的 `mcts_state` 入口，直接恢复 `BattleContext`，不得调用会重洗牌/重掷怪物的 `BattleContext::init`。保留现有 `mcts_save` 作为另一种明确来源，不静默切换。
4. 维持 STATE0、逐卡实例/目标绑定、journal 重放、输入版本及路线失效拦截。先离线对同一真实快照比较 Java/C++ 所有根字段，再比较 1 回合 trace；之后才允许实机一回合候选执行，随机多回合仍需 RNG 对照证据。

**停止条件：** 若实际 getter 对照测试推进 RNG、Java/C++ 输出序列不匹配，或受支持怪物的关键私有状态无法读取/映射，则随机多回合路线 **NO-GO**，不得用 LiveCapture 近似值补洞。未支持场景保持安全拒绝。即便闸门和离线测试通过，也不能标为 L3/L4；还需真实战斗根字段、执行及状态回放证据。

## 本轮验证范围

只读检查了 `AGENTS.md`、`STATUS.md`、`LiveCapture.java`、`EngineBridge.java`、sts_lightspeed 的 `SaveFile`、`GameContext::initFromSave`、`BattleContext::init`、`CardManager::init`、`CardInstance`、`Monster` 与 CLI `mcts_save` 路径。未改代码、未构建、未运行游戏或部署。工作区原有 `sendToDevs/` 未跟踪内容保留。

## 最终限定验证（2026-10-19）

- 路线 A：JDK 6 自带 `javap -private -c` 对游戏 JAR 中 `SaveAndContinue` 的反汇编显示 `private loadSaveString(String)` 在 bytecode offset 3 把唯一参数 `aload_0` 直接传给 `Gdx.files.local(String)`，再调用 `FileHandle.readString()`；offset 15 起对该读取结果判断 obfuscation 并原样返回或调用 `SaveFileObfuscator.decode`。补丁 Prefix 当前无参数，但 FileHandle hook 记录同事务的实际 canonical path；Postfix 接收目标方法唯一返回值。`battle-20261019-120212-544-969f6e6b-4a74-41a8-b929-07ea0fbf54db` 的 call id=1 因而把 `nativeChars=23` 关联到实际 autosave 流路径，参数/返回值没有发现错 overload 或跨调用串线。捕获重解码和原生调用使用同一个 STS1 decoder。原因仍未定位：无法在不看被拒绝存档/文本的条件下解释同次读取返回 23 与捕获解码 13,561 字符的矛盾；A 没有可证明的最小生产修复。此前错误的“JDK 无 javap”表述更正：`C:\Program Files\Java\jdk1.6.0_43\bin\javap.exe` 可反汇编目标类；它无法解析带 invokedynamic 的 `AbstractMonster`，该类字段由 Python classfile 常量池解析器只读提取。
- RNG：JAR `AbstractDungeon` 有公开 `aiRng/cardRandomRng/miscRng/monsterHpRng/potionRng/shuffleRng`（另有 run-level map/event/merchant/card/treasure/relic/monster RNG）。`Random.random` 与 `counter` 是 public；`random` 的 `RandomXS128` 有 public `getState(int)`，JAR 字节码显示它只是读取 private `seed0/seed1` 并返回；`Random.copy()` 也是通过 getters 复制双 seed 和 counter，不触碰原对象。C++ `sts::Random` 使用相同 xorshift128+ state transition，`nextLong(n)` 与 GDX `RandomXS128.nextInt(bound)` 的 unsigned-shift/modulo/rejection 规则一致，浮点转换也对应。因此**可无推进读取并映射**。本轮验证等级为静态字节码/源码级，未在正在运行的游戏中调用 getter，也未做真实 state sequence 对照。
- 其他字段：`CardGroup.group` 顺序公开；`AbstractCard.uuid/cardID/misc/cost/costForTurn/damage/block/exhaust` 公开；`AbstractRelic.counter` 公开；`AbstractCreature` 的 HP/block/powers 公开；玩家手牌/牌堆、药水、orbs、stance、energy 和 `GameActionManager` 大部分稳定阶段字段公开；`AbstractMonster.moveHistory/nextMove/intent` 公开。目标 JAR 的 classfile 检查发现 vanilla monster 子类仍有各自私有 AI 字段（例如 Sentry、Lagavulin、BookOfStabbing、Champ）；可考虑 Java 8 反射读取，但尚未实测反射权限或逐类校验 C++ 语义。当前 LiveCapture 没有捕获许多这些精确字段，仍是 Java 近似模型，不能复用为 C++ exact DTO。
- 评估结论：**A 当前 NO-GO（无已证实低成本修复）；C 有条件 GO（RNG 技术前提已成立，完整字段适配尚待验证）。最低风险完成 V1.0 的候选是小范围 C 输入适配，限定 vanilla Ironclad 和明确建模内容，versioned DTO、直接恢复、不走 SaveFile 初始化；未知/自定义机制拒绝。** 其规模中高，至少涉及 Java 快照采集、C++ CLI/状态恢复和离线 differential tests；不属于本轮批准范围。最终是否实施 C 仍待用户决定。
- 命令/证据：`javap -private -c -classpath "<game>\\desktop-1.0.jar" com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue`（JDK 6，目标类反汇编成功）；`javap -private -classpath ... com.megacrit.cardcrawl.random.Random`、`com.badlogic.gdx.math.RandomXS128`、`AbstractDungeon`、`AbstractCard`、`AbstractRelic`、`AbstractPlayer`、`GameActionManager`（字段输出成功）；Python 3 classfile parser 读取游戏 JAR 中 `AbstractCreature`、`AbstractMonster` 及 vanilla monster 子类字段。`javap` 对 `AbstractMonster` 因旧工具不支持常量池 tag 18 失败，随后 parser 成功读取 class version 52 及其字段。未构建、未改生产源码、未运行游戏或部署；`sendToDevs/` 保留。

# CombatSolver1 核心能力差异审计与开发路线

## 2026-10-19 V1.0 差距复核（优先于下方历史审计）

基线 `78ca857`；开工仅有未跟踪 `sendToDevs/`。下方 2026-10-18 报告是历史快照，其中“原生选牌未实现”已过期：当前 Exhume v3 协议、候选身份校验、原生点击及结果门控已编码，17 项离线检查通过，但最新实机失败后的修复没有成功复测证据。不得由此记作 L3/L4。

最短交付清单仅包含：UI 可读性/交互候选版；地图新战斗外部路线与 G/H/J 实测；两次 Exhume 多候选及后续动作；累计 10 场覆盖普通/精英的有效验收；最后冻结、正式 JAR/哈希、文档与普通推送。Continue、扩职业、机制全集与算法替换退出本轮范围，已有安全拒绝保留。

历史 STATE0、journal、FailureLatch、卡实例和目标验证保持不动。未发现足够新证据授权新增规则修复；已知发布阻断是实机验收缺口和 UI 待验收。详细门槛见 [V1.0 验收记录](V1_ACCEPTANCE.md)。

历史审计日期：2026-10-18

CombatSolver1：ae4711e7df38023b25ed5b33e3f347b71dbe6b2f（本地 main，审计开始时与 origin/main 一致）
参考项目：[Torch1230/CombatSolver 固定源码提交 5c773caaec742bc55588bc9ee21c361326a732f0](https://github.com/Torch1230/CombatSolver/tree/5c773caaec742bc55588bc9ee21c361326a732f0)

## 范围与证据口径

本报告依据本地提交中的 Java、C++、测试和状态记录，以及参考仓库固定提交的真实源码。参考项目的 GitHub 文件树和关键实现文件可经连接的只读 GitHub 接口访问；本机直接 git clone 因网络连接被阻止。审计覆盖参考项目与求解器相关的运行时、预测、搜索、UI、测试目录及其关键调用路径；没有逐行复核其每一个第三方或外围文件。参考源码路径均链接到固定提交，便于复查。不能访问到的东西不推断为已实现。

证据等级沿用 AGENTS.md：L0 未实现、L1 已编码、L2 离线验证、L3 完整 Mod 在游戏中运行并验证对应交互、L4 同一真实战斗的状态/动作重放一致、L5 多种代表性实战与回归稳定通过。源码结构、目录数、ID 登记都不等于机制准确或实战通过。对“准确”分类还需要特定机制的测试或真实对照；不能仅凭代码里存在卡牌 ID 宣称精确。

### 当前工作区

审计开始时位于 main，HEAD 为上述 ae4711e，main...origin/main 无提交差异。唯一未跟踪项目为 sendToDevs/；它属于用户已有内容，审计和文档提交均不触碰它。

## 1. 两个项目的架构与模块对应

参考项目是面向《杀戮尖塔 2》的 C#/.NET 战斗规划器，借助该代游戏的原生模型、克隆状态和预测钩子工作；CombatSolver1 面向第一代游戏，当前同时包含 Java fallback、一个本地修改的 C++ sts_lightspeed 快照以及 Mod 内的状态捕获、部署、UI 和记录器。二者解决相似问题，但不共享同一游戏规则或运行时对象。

| 模块 | CombatSolver1：主要路径 / 入口 | 参考项目：主要路径 / 入口 | 数据流、依赖与当前边界 |
|---|---|---|---|
| 1. 游戏状态捕获与序列化 | src/main/java/com/combatsolver1/runtime/LiveCapture.java capture()；engine/SimState.java；runtime/EngineBridge.java | [CombatRootSnapshot.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Runtime/CombatRootSnapshot.cs) Capture；[ModelPredictionStateWriter.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Prediction/ModelPredictionStateWriter.cs) | 一代从原生对象抓取 HP、block、energy、牌堆、遗物/药水、部分力量与怪物状态；再构造 Java 状态或转换为存档供外部引擎恢复。玩家和怪物实体身份、原生槽位、随机流及部分生命周期字段不是完整可见状态。二代用游戏原生 combat root 快照建立预测根，写入预测状态；依赖其自身模型图克隆。两者序列化边界不同。 |
| 2. 战斗规则模拟器 | engine/CombatEngine.java，playCard、endTurn、抽弃/消耗处理；C++：vendor/sts_lightspeed/src/combat/BattleContext.cpp、Actions.cpp、Player.cpp | [CombatPredictionSimulator.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Engine/InCombat/Simulation/CombatPredictionSimulator.cs)、CombatPredictionSimulator.Card.cs、CombatPredictionSimulator.EndTurn.cs | Java 是独立规则近似模型；C++ 通过 BattleContext 和动作队列模拟；参考项目是对 StS2 原生模型的分支/预测执行层。没有任何一套代码可直接证明另一代规则等价。 |
| 3. 卡牌效果 | engine/CardLibrary.java、CombatEngine.playCard；C++ include/constants/Cards.h、CardInstance.cpp、BattleContext.cpp | [CardEffectSpecRegistry.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Prediction/CardEffectSpecRegistry.cs)、CardOnPlaySupport.cs | Java 卡牌规格经 ID 转为模拟卡，再由通用字段和专用分支解释。C++ 枚举数量远大于“已精确实现数量”；需看动作分支、状态效果和测试。二代有明确 supported / unsupported effect hook，但 StS2 卡牌内容不可移植为 StS1 规则。 |
| 4. 遗物、药水、能力、状态效果 | CombatEngine、SimState、engine/PotionLibrary.java；C++ BattleContext、Relics.h、Player.cpp | [RelicPredictionStateSupport.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Prediction/RelicPredictionStateSupport.cs)、PotionOnUseSupport.cs、CorePowerSupport.cs、PowerLifecycleSupport.cs | 一代 Java 使用有限的命名检查与数值字段；充能球、Watcher 姿态等不是完整通用状态域。C++ 有更多原生内容实现，但真实支持要沿相应触发函数审查。参考项目显式枚举预测覆盖与生命周期支持，降低“有 ID 就当支持”的风险。 |
| 5. 敌人 AI、意图和回合结算 | engine/MonsterAi.java、GeneratedMonsterData.java、MonsterManual.java、CombatEngine.endTurn；C++ MonsterSpecific.cpp、MonsterMoveDamage.cpp、MonsterGroup.cpp | [IntentForecaster.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Prediction/IntentForecaster.cs)、MonsterRandomBranchResolver.cs、MonsterSpawnSupport.cs | Java 有原版怪物模型并含固定/推测招式循环；C++ 有单独怪物状态机和 RNG；二代由原生模型预测意图。意图文本、随机选择和回合钩子要与真实执行对照。 |
| 6. RNG 与随机分支 | Java SimState.rngState 与 CombatSearch.measureRobustness；C++ BattleContext / 游戏 RNG 子流与 MCTS rollout RNG；根由 test.cpp 存档读取 | [PredictionForking.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Engine/Common/PredictionForking.cs)、PredictionStateStore.cs、CombatPredictionRngSet.cs、PredictionTrace.cs、MonsterRandomBranchResolver.cs | Java 根 RNG 是合成值，四个分支样本不是实际概率估计。C++ 可从对应存档恢复随机状态，但是否与当场所有 RNG 消耗同步仍需同场轨迹验证。二代克隆预测 RNG 并记录分支/风险；这不等于 StS1 的 RNG 管理。 |
| 7. 搜索算法、预算、剪枝、评价 | Java search/CombatSearch.java：回合级 beam；C++ BattleScumSearcher2.cpp：MCTS/UCT 风格树搜索与随机 rollout | [CombatBeamSolver.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Search/CombatBeamSolver.cs)、CombatBeamSolver.NoveltySearch.cs、CombatBeamSolver.StateEvaluation.cs、CombatSearchCoordinator.PlanSearch.cs | 参考不是单一简单算法：beam 搜索、novelty/portfolio/continuation 等分支由 coordinator 调度。C++ MCTS 与 Java beam 的探索方式、状态键和评价函数均不同；节点或 rollout 更多不自动代表路线质量更高。 |
| 8. 跨回合路线与预测 | Java CombatSearch.step() 对每个候选执行 CombatEngine.endTurn，直至胜负或 maxTurns；C++ 动作树包含回合结算；路线通过 Plan/engine protocol 返回 | [CombatCrossTurnPlanning.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Search/CombatCrossTurnPlanning.cs)、[PlanSearch.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Search/CombatSearchCoordinator.PlanSearch.cs) | Java/C++ 都规划多个回合，但未知抽牌和怪物随机行动依赖各自 RNG/模型。C++ 已有实机多回合执行与至少一次跨回合状态核对的 L3 局部证据；不能扩展成普遍无漂移。 |
| 9. 路线展示和 UI | ui/PanelModel.java、ui/OverlayRenderer.java | [SolverOverlay.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/UI/SolverOverlay.cs)、SolverRouteRow.cs | 一代 overlay 显示路线与执行状态；二代原生 UI 展示搜索路线、动作和控制。参考 UI 交互可以作为体验参照，布局/游戏 API 不能直接移植。 |
| 10. 自动执行、目标映射、安全门控 | 一代新增：runtime/CombatController.java、PlanDeployer.java、ExecutionSafety.java、CardTargetRef.java；G 当前回合，H 全自动，J 暂停 | Runtime/SolverController.cs、SolverDispatcher.cs、UI/SolverActionRun.cs；有 Play turn / Full Auto 类工作流 | G/H/J 是 StS1 移植版已有能力，不能因参考项目没有同名按键就判缺失。FailureLatch、原生目标身份映射与动作队列门控是本代独有安全工作。参考源码不是其执行安全证明。 |
| 11. 回放、测试与一致性 | 一代：CombatRecorder.java、ActionJournal.java、BattleArtifacts.java、TurnBoundaryDiagnostics.java、tools/recordercheck/、tools/solvertest/、C++ CLI replay_trace/replay_search | [UnattendedTestRunner.cs](https://github.com/Torch1230/CombatSolver/blob/5c773caaec742bc55588bc9ee21c361326a732f0/src/Testing/Host/UnattendedTestRunner.cs)、UnattendedTestRunner.NativeReplay.cs、UnattendedTestRunner.Offline.cs、CombatReplayRecording.cs | 一代保留可配对 bundle、journal、字段比较与回放拒绝原因；二代有 unattended/headless 原生模拟和 replay/checkpoint 测试路径。两套 fixture 格式和规则集不同，测试数量不能横向比较准确度。 |

### 边界结论

参考项目值得借鉴的是“显式预测支持/风险分类、分支来源追踪、预算内多路搜索、状态评估分层和 unattended replay”的工程方法。它是 StS2 C# 项目，规则与实体生命周期属于 StS2。CombatSolver1 的 C++ 不是该参考项目的移植代码，而是 vendor/sts_lightspeed/PROVENANCE.md 说明的本地修改快照；导入时无可确认的上游 commit，且曾有历史修改未审计。不能把该快照 README 的 RNG/覆盖声明当作本项目 L4 证明。Java fallback 与 C++ 引擎是两套模拟器，也不能混合测试结果。

## 2. StS1 机制覆盖矩阵与统计依据

### 统计口径

本仓库没有可证明完整、版本固定的四职业卡牌—遗物—药水—敌人总目录，也没有按每个真实机制效果标记 L2/L3/L4 的统一数据库。故不报告全游戏“支持百分比”。以下只报告可复核的局部集合和明示的实现边界：

* 卡牌：tools/solvertest/Checks.java::cardTable() 有 75 个 Ironclad ID 的明确数组，并断言这 75 个均可在 CardLibrary 查到。它证明的是“75/75 已登记”，不是 75 张均经真实状态验证。该测试还有一组关键行为断言；approx 标记检查最多容许 8 个，但这是维护约束而非全卡精确率。静态检查可用 rg -n 'String\[\] mustKnow|approx|cardTable' tools/solvertest/Checks.java 复查。CardLibrary.java 注册总 ID 的文本数不作为游戏全集分母，因为同名覆盖、状态牌、生成牌混在一起。
* 怪物：同文件 monsterAi() 以 Bestiary 的 65 个核心 ID 为明确分母，测试它们都能识别，并对每个 MonsterAi.modeledIds() 条目尝试生成 10 步招式序列。它不证明伤害、buff、AI 随机分支和回合触发精确。C++ 怪物枚举或存档映射数不等于已完整模拟的怪物数。
* 药水：PotionLibrary 的哈希表有 20 个不同 ID（含 Liquid Bronze 覆盖写入）；这个分母是“当前 Java 表已登记”，不是 StS1 全部战斗药水。能力槽规格/近似也需要逐效果核查。
* 遗物：实现以 hasRelic("…") 分支散落在 CombatEngine、LiveCapture 和状态构造中；没有完整遗物全集及每一触发点的统一表格。明确报告“分母未建立”，不以字符串数造覆盖率。
* 角色、能力/姿态/充能球：当前 Java SimState 与 CardLibrary 主要围绕 Ironclad 战斗状态。虽然 C++ 卡牌枚举含多职业 ID，CLI test.cpp 的恢复入口固定加载 Ironclad；登记不能证明其他职业状态转移可用于搜索。Defect 的 orb channel/evoke、Watcher stance/retain 等状态域没有在 Java 模拟中形成完整通用角色模型。

| 机制类别 | 精确支持（证据范围） | 近似支持 | 未支持 / 仅入口 | 无足够证据或统计边界 |
|---|---|---|---|---|
| Ironclad 牌及升级版 | 75 个指定常用/核心 Ironclad ID 均通过登记测试；若干关键牌（Blood for Blood、Dual Wield、Headbutt、Exhume、Burning Pact、Havoc、Armaments、Flex、Clash、Warcry、Perfected Strike、Reaper、Feed、Void 等）有离线字段或效果断言。证据为 L2 离线，不代表每级升级及组合完整。 | 代码标 approx 的卡牌和使用简化决策的选牌/生成效果；近似项可从 CardLibrary 的 approx 字段和 Checks.cardTable() 当前输出核对。 | 限定选择/复制/随机生成链有入口但 native choice 执行未接入。非关键牌、遗物组合、异常顺序和高 Ascension 仍需逐项验证。 | 75 是测试数组的分母，不是 StS1 Ironclad 官方总牌池分母。不得由此称“全 Ironclad 覆盖”。 |
| Silent、Defect、Watcher 卡及升级 | 无 Java 职业级支持证据。 | 少量通用状态字段可能容纳共用效果，但未构成各职业完整模拟。 | 角色专属牌、Poison/Shiv/Discard 联动、Orb/Focus/Evoke、Stance/Mantra/Retain 等没有完整的 Java 状态转移与搜索链。C++ ID 存在不等于这一能力。 | 未审计官方全卡分母，覆盖率未知。 |
| 状态牌、诅咒、特殊生成牌 | 对若干实际牌实例与状态牌有 Java/C++ 行为分支和既有回归；近期自动 Curse journal 误记已有离线修复。 | 生成牌、自动回合触发、稀有生成结果依赖有限模型/随机源。 | 原生选牌结果的自动执行未实现；未支持的生成内容可令 C++ replayInvalid。 | 全部状态/诅咒/生成来源没有统一全集或逐效果矩阵。 |
| 遗物（普通/Boss） | 多个常见效果在 CombatEngine 有条件分支，少数配对根状态与真实 bundle 曾逐字段检查。 | 一部分触发时机被简化为出牌/回合结算中的固定分支；组合触发和楼层/战斗外状态未系统验证。 | 不能从 hasRelic 分支推断完整遗物支持；未覆盖遗物也可能未在 SimState 携带必要状态。 | 完整遗物目录分母与按遗物效果的 L2/L3/L4 统计均未建立。 |
| 药水与目标 | Java PotionLibrary 20 个 ID 登记，若干常见效果可计算；真实执行目标映射有独立身份安全机制与离线回归。 | Speed Potion 等时间/持续效应简化；Snecko Oil 费用随机化标为未建模；药水增强与遗物影响不全。 | 其余未登记药水由求解器拒绝/不建模；目标用途和持续效果组合需逐瓶验证。 | 20 是当前表登记数，不是官方总数；不存在完整的精确支持分子。 |
| 玩家能力、减益、姿态、充能球 | 铁甲战士常见力量、格挡、虚弱、易伤、金属化、力量类效果已有相应字段和代码；关键断言覆盖部分。 | 持续时间、回合末衰减、重复触发及死亡/复活顺序有近似区域；Java 根不含真实 RNG。 | Defect orb 与 Watcher stance 没有完整 Java 状态系统；非铁甲专属能力不是因为有卡牌 ID 就能运行。 | 无统一完整 Power/状态 ID 清单与逐触发对账结果。 |
| 普通怪、精英、Boss AI/意图 | Java 测试对 65 个核心 Bestiary ID 检查可建模和招式生成，部分固定序列有具体断言；C++ 有独立 AI 实现。 | 随机意图、Ascension 差异、条件分支和召唤/变身依赖模型细节及 RNG。 | “可返回招式”不覆盖真实攻击伤害、debuff、buff、意图更新、死亡阈值和多怪同步的所有情况。 | 65 是该测试列出的核心 Bestiary 集合；不是含事件怪/所有变体/所有 Ascension 的完整游戏状态数。 |
| 抽牌、弃牌、消耗、保留、随机生成、回合结束 | 有 CombatEngine.endTurn/drawCards、卡牌实例和牌堆操作测试；记录器对 endTurn 生命周期有定向回归。部分配对 bundle 的动作前缀通过真实状态比较。 | 抽空洗牌、随机牌序、选牌自动决策和某些牌堆触发顺序依赖模拟 RNG/简化的选择策略。 | 选牌窗口 native execution 未接入；未能追踪的原生事件会拒绝严格 journal 重放。 | 真实日志覆盖的是有限战斗与动作前缀，不是全部牌堆机制。 |

结论：当前可靠边界最明确的是 Ironclad 一部分可观测状态下的单场规划与受约束自动执行；尚未形成覆盖四职业和全内容的机制基线。要讨论覆盖增长，应按“已登记、离线行为测试、同根真实状态比较、实机执行”四层分别统计。

## 3. C++ 搜索器：能力、差异与风险

### 可从源码确认的算法差异

1. Java fallback 是回合级 Beam Search。默认 beamWidth=6、withinTurnWidth=8、maxTurns=8、maxNodes=4000；按状态签名去重，内层生成回合动作序列，外层模拟 endTurn。评分偏向生存、胜利、较少 HP 损失/怪物剩余生命。到墙钟/节点/回合预算时，返回当前最好候选。
2. C++ BattleScumSearcher2 是 MCTS/UCT 风格：按模拟次数逐次选择、扩展、rollout 和回传；子节点选择式包含平均评价与探索项，终局评价在 evaluateEndState。它与 Java 的固定宽度 beam 在探索分布、重复状态处理和弱候选保留上不同。
3. 参考项目 CombatBeamSolver 采用 StS2 原生预测状态，另有 CombatNoveltySearch、portfolio/continuation/cross-turn coordinator、状态评估与 risk/coverage 信息。参考不能据名称推断优于一代两种求解器；必须先用 StS1 固定题库评估路线质量。
4. C++ apps/test.cpp 提供 mcts_save、replay_search、replay_trace、JSON/存档转换。replay_trace 是验证/诊断工具，不等于 MCTS 搜索；协议成功/进程退出 0 不等于动作后状态一致。

### 状态表示和随机信息

Java LiveCapture.capture() 将游戏对象映射为 SimState，只保留捕获字段；其随机根是合成值，不是从游戏抽取的完整 RNG 状态。因此 Java 的跨回合未知抽牌及怪物随机行为只能视作近似。有限四分支复推只描述所抽样的四条模拟路径，不是胜率置信区间。

C++ 从同场 engine-in.save 恢复战斗与游戏 RNG 子流，有条件做连续回放；仍受 save 阶段、存档转换、协议动作覆盖、每种状态字段输出及真实事件完整性的约束。项目 PROVENANCE.md 明言这是无法绑定具体上游 SHA 的本地修改快照；其 inherited README/RNG 声明不能用作当前版本 L4 证明。

状态摘要漏字段会让“看起来匹配”掩盖漂移。已出现过真实 1 HP 边界：某旧样本从同一存档重放 9 动作后，C++ HP 52，真实新回合稳定状态 HP 51；旧 journal 缺完整 endTurn post 且标脏，缺少逐动作伤害链，根因未确认。该历史问题不是当前已复现缺陷。最新三哨卫 bundle 的 replayInvalid 可追到第三条动作是自动触发的 Doubt Curse 被误当玩家出牌；修复已离线验证，但该修复尚无新的 L3 实机样本。历史 stale journal 2 -> 3 是过期异步请求被较新状态淘汰，不应直接列为引擎错误。

### 终止、评价和次优路线风险

Java 搜索把生存和确认胜利放在评价前列，但这是有限预算内的启发式。跨回合里只保留 beam 宽度内候选，去重/状态键或近似机制若把有价值的状态合并，会剪掉后续更优路线。MCTS 的 rollout 质量、随机探索、终局分数和有限模拟次数会影响输出；单次“找到胜利”既不等于最低 HP 损失，也不等于最优。

源码可确认的风险（尚非已证明策略 bug）：部分近似牌/药水、选择策略、敌方随机行动、有限 beam/节点预算、模拟目标函数和本地 synthetic RNG 都可能造成路线质量下降或漂移。要判定搜索“明显次优”，需要固定状态题库、小状态穷举 oracle、策略基线和预算一致的路线指标；目前没有依据把算法替换列为首要工作。

## 4. 端到端链路与现有证据

| 阶段 | 当前实现与证据 | 主要限制 / 影响 | 前置关系 |
|---|---|---|---|
| 原生游戏状态 → Java 捕获 | LiveCapture 抓取稳定可行动状态；实机 bundle root 曾与首张动作 pre 完全一致；最近有真实 root 与外部引擎比较 61/62 个可比字段一致（唯一费用表示差异单独证实为不可打出的 AscendersBane 编码）。L2/L3/L4 都是局部样本，不是字段全集。 | 真实 RNG 与未暴露状态不完整；捕获时机、同名实例/多怪身份必须继续保证。状态根错会污染所有下游。 | 所有搜索/执行/回放的前置。 |
| Java 状态 → Java/C++ 输入 | SimState 构造本地状态；EngineBridge 转存档、配置并启动 sts_lightspeed；输入身份和引擎版本记录在 bundle。某个外部引擎曾在实机多回合计划中运行并通过第二回合 drift 核对（L3 局部）。 | engine-in.save 开局阶段和真实 root 阶段可能不完全相同；协议/特定存档字段转换失败必须 fail closed；local 与 C++ 结果不可混测。 | 根状态准确性先于算法与评分。 |
| 搜索 → 路线解析 | Java CombatSearch；C++ MCTS 计划输出解析在 EngineBridge；历史终局工具用 replay_search 识别 victory 终局类别。 | 返回计划或 REPLAY_OK 不足以证明动作 post；状态摘要未覆盖字段仍未知。终局只有 outcome match 时不可称全字段 match。 | 模拟规则与动作协议。 |
| 路线 → UI | PanelModel/OverlayRenderer 展示本地或外部路线来源、计划动作和搜索信息。 | 路线来源标识很关键；搜索质量与 UI 观察不是同一证据。旧界面证据不能替代当前版本逐项交互。 | 计划解析成功。 |
| UI授权 → G/H/J 与原生动作 | CombatController/PlanDeployer 有 G 当前回合、H 跨回合、J 暂停；目标对象身份映射、过期状态检查与 FailureLatch 防同状态重试。已有故障注入 L2；用户报告 Java 本地 H 在真实战斗多回合运行过，C++ 路线也有至少一次实机跨回合核对（L3 局部）。 | 依赖精确卡实例、目标、药水和队列边界；自动选牌当前不能执行。离线安全测试不保证所有原生动作时序可靠。 | 原生状态采集和可执行动作编码。 |
| 原生反馈 → drift/回放 | CombatRecorder、ActionJournal、BattleArtifacts、TurnBoundaryDiagnostics；replay_trace 和终局离线验收。最新日志有原生 TURN_DIAG 采样；已配对胜利 bundle 前 8 个稳定动作摘要通过，最后致胜动作只验证了终局类别。 | 旧 1 HP 漂移缺回合中间对应数据；部分旧样本 dirty/open/缺 post；审计必须按每份 bundle 限定可信前缀。 | L4 的关键闭环。 |

总体证据边界：L2 离线工具和故障注入较多；L3 证明了特定版本/特定战斗的功能存在（包括本地 H 多回合及 C++ 路线一次跨回合核对），不等于广泛稳定；L4 只覆盖具体完整 bundle 的根和连续前缀/终局类别，不能外推全场、全部字段、全部职业或当前每个修复。L5 未建立。

## 5. 缺口严重度与三个最高优先级模块

| 优先级 | 模块 | 影响与理由 | 复杂度 / 离线验证 / 实机依赖 |
|---|---|---|---|
| P0（当前待确认，不等同于已知复现 bug） | 同根根状态、动作 post 与端到端差分的可信证据闭环 | 过去的 1 HP 漂移仍未有足够逐事件字段解释；新 Curse 记录修复、endTurn 边界和 terminal outcome 都要新样本确认。若根/动作阶段错，搜索与搜索质量评估都不可信。当前源码没有证据表明 1 HP 问题仍在，因此列为“证据缺口门槛”，不是已确认 P0 故障。 | 中；现有 bundle/工具离线可复核；最终需要一份 clean、同根、journal clean 的新 L3/L4 战斗。 |
| P1 | 预测状态/机制支持目录（先 Ironclad 牌、能力、遗物、药水、回合触发和敌人动作） | 卡牌/敌人登记表不能说明触发链正确；不透明近似会造成假精确。需建立有分母、支持等级和行为断言的逐机制表，将代码入口、离线 fixture、真实对照分开。 | 中高；大部分可离线完成；关键边界用少数实机 bundle 复核。 |
| P1 | 原生选牌闭环 | 搜索模拟可自动选“最佳牌”，但 PlanDeployer 在实际选择 UI 出现时 fail closed，协议虽有部分选择编码，原生选择执行未完成。该功能会显著扩大 Corruption/Exhaust/Discovery 类路线可执行范围，且是路线规划与 G/H 一致性的阻断项。 | 中；协议、身份安全、测试可离线；之后需要游戏选择界面 L3 测试。 |
| P2 | 跨求解器路线质量和性能评估 | Java Beam 与 C++ MCTS、参考项目 Beam/novelty 结构不同；目前不能用节点数宣称优劣。无小状态 oracle 与固定质量指标，贸然调参或移植算法风险大。 | 中高；固定状态可离线评估，最终仍需要真实分布抽样。 |
| P2 | UI/覆盖扩展和非铁甲职业 | 扩大内容有价值，但依赖模拟状态准确与可靠基准；否则会增加未验证面。 | 高；角色模型与卡牌联动改动面大，需要阶段验收。 |

### 推荐接下来集中开发的三个模块（按实施顺序）

1. **同根回放验收证据闭环（P0 门槛任务）**：不重建系统，先将现有 bundle checker 作为唯一入口检查 manifest/哈希/dirty/root/动作目标与连续链；对已有样本明确输出可比较字段覆盖、首个不一致、未捕获字段和终局类别。随后只用新的 clean bundle 验证近期 Curse/endTurn 修复，并为 1 HP 事件保留 TURN_DIAG 阶段。根与动作证据不可靠时不得扩机制或比较搜索质量。
2. **Ironclad 机制覆盖与差分基准（P1）**：以 75 卡测试数组为现有下限，逐 ID 把精确、近似、未实现、仅入口和实机证据分层；先处理会改变能量、目标、牌堆/消耗、伤害/格挡和回合结束顺序的已登记机制。把关键单卡、升级、遗物/药水触发和跨回合组合映射到 solvertest/真实对照。扩展规则前先补固定失败 fixture，避免单纯增加 ID。
3. **原生选牌协议与执行（P1）**：前两项建立安全根和动作校验后，将“模拟选择结果 → 稳定卡实例标识 → 游戏原生选择窗口 → 选后状态核对”做成一条小闭环。失败即停止路线，不能在 UI 上盲选。先用可控离线协议 fixture，再做一个低风险游戏内选择 L3 测试。

这个顺序先让状态、动作和评价可信，再扩大可执行机制；搜索策略/性能优化暂列 P2，等待质量基准，而不是先把 beam、MCTS 或参考项目算法换掉。

## 6. 连续开发与验收顺序

| 阶段 | 交付 | 离线验收 | 实机验收 / 停止条件 |
|---|---|---|---|
| 0. 固定验收基准 | 指定每个 bundle 可用前缀、字段集、动作可信度、终局比较类型；保存历史原样。 | BattleBundleCheck、TerminalReplayCheck、replay_trace 对现有配对样本运行；明确断链拒绝和未比较字段。 | 只把已匹配的样本记为局部 L4；不合格样本不得补写预期。 |
| 1. 回合/终局诊断确认 | 一份新的 clean 战斗 bundle，TURN_DIAG 如需诊断则明确启用；保留阶段和动作来源。 | 用同一 engine-in.save 连续重放 journal；逐动作对比稳定阶段；检查 Curse/endTurn 修复。 | 先短普通战斗验证采集与封存 L3，再按证据决定是否具备 L4；若中途差异立即定位，不继续报通过。 |
| 2. Ironclad 覆盖账本 | 卡牌/遗物/药水/状态/敌人按支持等级分条；加入有明确状态断言的最小失败回归。 | tools/solvertest/run.ps1 -Command all；新增 fixture 分项运行；生成每项证据与依赖。 | 只对高风险触发做少量代表实战，避免要求用户人工穷测。 |
| 3. 选牌端到端 | 模拟选择协议带稳定 card identity、动作类型、来源与确认状态；部署器能执行并在原生状态验证，失败 fail closed。 | 重复选择、牌实例移动/死亡/取消/回合变更与协议损坏故障注入；ExecutionSafety 回归。 | 一场可控选择场景验证 L3；bundle/journal 可对应时再判局部 L4。 |
| 4. 搜索质量基线 | 固定若干 StS1 根状态，建立小状态近穷举 oracle 与路线指标（胜负、HP损失、回合、资源风险、执行可行性）。 | 同预算比较 Java Beam、C++ MCTS；使用重复 seed/固定 RNG 条件并记录硬件、预算、样本集。 | 以新战斗样本检验分布外偏差；搜索节点和单次胜利不作为质量结论。 |
| 5. 扩职业/扩大策略 | 只在角色状态模型与对应规则支持表可验收后逐角色扩展。 | 角色专属机制与组合回归；固定状态差分。 | 每个职业单独 L3/L4 代表场景，禁止把 Ironclad 证据推广。 |

每阶段一批只完成一个主要闭环，按 AGENTS.md 留下命令、退出码、样本数、精确匹配字段、缺口与证据等级。历史异常计数只代表旧日志样本，不作为当前缺陷发生率。

## 7. 关键复核路径与可重复检查

本地源码与测试主要入口：

* Java 状态/规则/搜索：src/main/java/com/combatsolver1/runtime/LiveCapture.java、engine/SimState.java、engine/CombatEngine.java、engine/CardLibrary.java、engine/MonsterAi.java、engine/PotionLibrary.java、search/CombatSearch.java、search/Plan.java、search/ChoiceProtocol.java。
* C++ 规则/搜索/CLI：vendor/sts_lightspeed/PROVENANCE.md、src/combat/BattleContext.cpp、CardInstance.cpp、MonsterSpecific.cpp、MonsterMoveDamage.cpp、src/sim/search/BattleScumSearcher2.cpp、apps/test.cpp。其中 CLI 回放入口可查 apps/test.cpp 的命令派发部分。
* Mod 链路：runtime/EngineBridge.java、CombatController.java、PlanDeployer.java、ExecutionSafety.java、CardTargetRef.java；UI 为 ui/PanelModel.java 与 OverlayRenderer.java。
* 记录/一致性：runtime/CombatRecorder.java、ActionJournal.java、BattleArtifacts.java、TurnBoundaryDiagnostics.java；工具为 tools/recordercheck/ 和 tools/solvertest/。
* 机制检查清单：tools/solvertest/Checks.java::cardTable()（75 个 Ironclad 测试 ID）与 monsterAi()（65 个 Bestiary 核心 ID）；这些断言的范围比“登记检查”更窄。

参考项目可复核源码（固定 SHA）：

* 预测 root 和模拟器：src/Runtime/CombatRootSnapshot.cs、src/Engine/InCombat/Simulation/CombatPredictionState.cs、CombatPredictionSimulator.cs、CombatPredictionSimulator.Card.cs、CombatPredictionSimulator.EndTurn.cs。
* 规则支持：src/Prediction/CardEffectSpecRegistry.cs、CardOnPlaySupport.cs、PotionOnUseSupport.cs、RelicPredictionStateSupport.cs、IntentForecaster.cs、MonsterRandomBranchResolver.cs、PowerLifecycleSupport.cs。
* 搜索与评分：src/Search/CombatBeamSolver.cs、CombatBeamSolver.NoveltySearch.cs、CombatBeamSolver.StateEvaluation.cs、CombatBeamSolver.CrossTurnPlanning.cs、CombatSearchCoordinator.PlanSearch.cs、src/Search/CombatSearchCoordinator.NoveltyPortfolio.cs。
* 运行时/UI/回放：src/Runtime/SolverController.cs、SolverDispatcher.cs、CombatReplayRecording.cs、src/UI/SolverOverlay.cs、SolverActionRun.cs、src/Testing/Host/UnattendedTestRunner.cs 及 NativeReplay/Offline 部分。

本轮没有修改战斗代码、执行逻辑、搜索算法或历史 bundle。参考 GitHub 来源访问受限但固定提交的源码文件已由连接器读取；如日后参考仓库 main 改变，应明确固定新 SHA 并重新审计相关文件。

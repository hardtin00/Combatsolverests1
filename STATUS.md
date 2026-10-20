## 2026-10-21：修复 Actions 离线自检编译依赖遗漏

- 现象/证据：Actions run `39446815484` 在提交 `2a96145dcefbdc2b5287908e12413ff35c1cfc83` 的离线编译步骤失败；`SearchQuality.java:458` 引用 `SolverSettings`，而 `.github/workflows/ci.yml` 的 `javac` 输入未包含设置包，导致后续自检被跳过。
- 修改：CI 离线编译命令加入 `src/main/java/com/combatsolver1/settings/SolverSettings.java`，与已有 `tools/solvertest/run.ps1` 的编译输入保持一致；不改变游戏运行逻辑。
- 验证等级：L2 离线。Windows JBR 21.0.9 使用 `javac --release 8` 编译模拟层、设置类和自检工具，`Solvertest all` 退出 0；`ExtractMonsterFacts.java` 编译退出 0。GitHub Actions run `39457706140`（提交 `8f93ae6d9cfb7e53d86ee25defb9d521adc4225c`）使用 JDK 8，离线编译、自检和 ID 提取工具编译步骤全部成功。
- 限制：旧 run `39446815484` 保持历史失败。新 run 的完整 Mod 构建因仓库缺少游戏依赖，明确为 `FULL_MOD_BUILD=not_run`；没有 L3/L4 实机验证。

## 2026-10-20：P1 宽度剪枝（搜索参数策略，用户单独授权）

- 现象/目标：Q9 等局面在默认档下报告必败、PROVABLE 层级漏优 33.3%。本轮只调 `SolverSettings` 的档位宽度/节点预算，不改搜索算法结构、评分权重、执行链路或 UI。
- 实测依据（宽度网格 `--sweep`，2 回合预算、放开节点与墙钟，`build/p3-sweep.txt`）：每个场景的帕累托前沿里 within 最大 32（Q9、Q12），beam 最小可到 3；即 `within=32` 是关键维度，beam 需求低。中档取 `within=32/beam=24` 已由基准实证为 12/12 同分，单场景 ≤91ms。
- 逐档取值（`src/main/java/com/combatsolver1/settings/SolverSettings.java:56-95`，顺序一律 within/beam）：低 12/8/maxNodes 4000；中 32/24/maxNodes 60000（原 12/8/8000）；高 48/32/150000；极高 64/48/400000。自定义档默认值未动（6/8/8/4000），避免静默改写用户自选参数。墙钟预算 `timeBudgets` 未改。
- 修正测量缺陷：`SearchQuality` 原来自带一份档位数字且只在传 `--preset` 时生效，导致上一轮「已加宽」实际测的还是旧 8/12、数字毫无变化。现改为无条件调用 `SolverSettings.applyTo()`（与 `CombatController:383` 同一权威来源），报告头标明档位来源；`run.ps1` 增设编译 settings 包。
- 结果（同模型同目标）：档位 1 PROVABLE 漏优 **33.3% → 0%（0/12）**，BUDGET 层级漏优 1 → 0；档位 2、3 均 0 漏优；档位 0 保持 33.3%（省预算档，按设计不追最优）。Q9 在 2 回合深度已与参照同分（-11373，存活）；10 回合深度下参照本身穷举判定「不存在必胜路线」，此时报必败是模型内经验证结论，不再是搜索假象。参照存在必胜路线而 beam 未报胜：中档仍 2 个（Q7 药水、Q8 长线，两者的参照值都是 CAPPED 下界，只说明参照「找到过」一条可达必胜路线，不等于该预算内必然必胜）；高档与极高档降为 1 个（`build/p3-bench-preset2.txt`、`build/p3-bench-preset3.txt`）。
- 时间代价（`--wide`，BIG 真实规模：10 张手牌/15 张牌堆/3 敌，`build/p3-wide.txt`）：按帧调用 `step()` 时最慢一帧 旧档 8-9ms → 新「中」档 67-71ms（两次运行有 JIT 抖动），整场搜索总耗时 30ms → 154ms；真实预算下深度仍用满 10/10、未被墙钟或节点截断。极端局面（更多牌/更多敌）单帧成本会继续上升，最高档可达约 200ms/帧。
- 验证等级：L2 离线 —— `Solvertest all` 退出 0，通过 125 项 / 失败 0（含上一轮两条收束口径回归与「可斩杀仍优先交出获胜路线」对照）；`SearchQuality --selftest` 退出 0（参照 60/0）；`build/final-all.txt`、`build/p3-benchmark.txt`、`build/p3-bench-preset0/2/3.txt`、`build/p3-wide.txt`。
- 新达到的等级：**完整 Mod 编译 L3 前置已具备**——`tools/build/build-release.ps1` 用本机 `desktop-1.0.jar` + 创意工坊 ModTheSpire/BaseMod 编译通过，字节码 major=52（Java 8）校验通过、133 个 jar 条目全用正斜杠；脚本随后的 `git rev-parse` 因本机 PATH 无 git 而失败（BUILD_EXIT=1，与本轮改动无关）。**未部署、未启动游戏、无真实战斗记录**，不得据此宣称 L3/L4。
- 纠正上一轮结论：我上一轮说「宽度是 Q9 默认档报必败的直接原因」。实测推翻：在 10 回合真实深度下，加宽到 64/48 也不改变 Q7/Q9 的结果；Q9 之所以在 2 回合深度漏优才由宽度决定。宽度本轮消除的是 PROVABLE 层 4 个漏优，以及 Q8/Q11 的长线取舍（Q8 预测扣血 56 → 42，即结束生命 24 → 38；Q11 结束生命 42 → 50）。
- 待办（未动，等授权）：Q7 药水机会成本仍是固定 15 分（参照 10 回合内可胜而 beam 判负）；Q11 在深度 10 的漏优无法归因（放宽后参照穷举不完）；全局 `visited` 永久关闭、12 步回合硬上限、玩家真实 RNG 未克隆、选牌启发式代选等既有限制不变。
- 用户验收：需要你在游戏内确认「中」档是否卡顿可接受——按 L 键切档观察面板搜索进度；若掉帧明显，告诉我，我把中档回退到 within=24/beam=16 或把墙钟降到 4000ms（数据已在 `build/p3-wide.txt`；宽度取值依据 `build/p3-sweep.txt`）。本轮未提交、未推送、未部署到游戏 mods/；已删除本轮自建的临时探针目录（`build/stress`、`build/probe`、`build/oldfinish`）。

## 2026-10-20：修复收束口径 finish() 交出死亡路线（P1，用户单独授权）

- 整合主工作区实时状态协议/采集诊断、C++ 恢复差分、搜索质量基准与终局选择修正，以及卡牌 Worktree 的 Cleave/Bludgeon 映射、导入准入、效果回归和批次文档。保留主工作区状态记录；未纳入 `sendToDevs/`、构建产物、JAR、存档或临时运行材料。
- 当前 MinGW g++ + Ninja Release 构建退出 0；引擎 `build/engine-mingw-unified-release/combatsolver1-engine.exe`，SHA-256 `C74E62F13A9612960C374AB89E23B8EA02CCAF82190EB96D55E6EFCDEB207A61`。配置与完整构建日志位于 `build/engine-mingw-unified-release/verification/unified-20261020/`。
- 卡牌 Java→C++ 互操作退出 0：Java 8 class major 52、16/16 卡牌实例匹配、149 路径中 148 MATCH/0 MISMATCH/0 UNAVAILABLE/1 NOT_APPLICABLE；Cleave/Bludgeon 效果回归 4/4。共享协议专项及 Shrug 非法 `special_data` 拒绝测试退出 0。
- Windows PowerShell 5.1 下 `tools/solvertest/run.ps1 -Command all` 退出 0。运行器增加 UTF-8 BOM 与 javac 原生 stderr 的正确退出码处理。历史 recordings 仍显示既有诊断差异，不能据此宣称实战字段差分通过。最终 `git diff --check` 退出 0（`build/engine-mingw-unified-release/verification/unified-20261020/12-git-diff-check-final.log`）；JBR 21 `SearchQuality --selftest` 60 项通过、0 失败。
- 证据等级为 L2 离线；本轮没有启动游戏、部署 JAR 或修改存档。Cleave/Bludgeon 和实时快照恢复仍未完成新的 L3/L4 实机验收。批次细节见 `docs/IRONCLAD_CARD_BATCH_1_REPORT.md` 与 manifest。

## 2026-10-20：修复收束口径 finish() 交出死亡路线（P1，用户单独授权）

- 现象/目标：上一轮基准在 Q9「生存下限」报告 beam 交出死亡路线、而同深度存在存活路线，即向玩家虚报必败。本轮只修 `CombatSearch.finish()` 的收束比较，不动宽度截断、评分权重、执行链路或 UI。
- 实际修改：`src/main/java/com/combatsolver1/search/CombatSearch.java:160-174`——旧实现只要 `best`（搜索期间捕获的任一终局分支）非空就无条件采用；改为把终局分支与预算耗尽时的存活叶子放进同一集合，按同一个 `evaluate` 分数取优。确认胜利（+1000000 级）仍优先于任何叶子，死亡（-1000000 级）低于任何存活叶子。
- 回归断言：`tools/solvertest/Checks.java:732-780` 新增两条（1 回合预算 8/12 宽度、2 回合预算 48/48 宽度），场景复用 `SearchQuality.corneredCase()` 这一唯一定义（基准与自检不再各写一份），另加「可斩杀时仍优先交出获胜路线」的反向对照防回归。
- 修复前失败证明：把同一套测试与还原为旧 `finish()` 的源码副本一起编译运行，`Solvertest search` 退出 1、恰好这 2 条失败（旧结果 endHp=-4 / score=-1000018 与 endHp=-2 / score=-1000016），反向对照通过；修复后同命令退出 0。证据 `build/p1-unpatched.txt`、`build/p1-final-all.txt`。
- 修复后验证（L2 离线，全部实际执行）：`Solvertest search` 退出 0；`Solvertest all` 退出 0，通过 125 项、失败 0，输出含「全部自检通过」；`SearchQuality --selftest` 退出 0（参照求解器 60 项通过、0 失败）。终端为 GBK 时中文乱码，故统计一律按 GBK 解码 `cmd /c ... > 文件` 的原始输出，不靠肉眼判断。完整 Mod 构建、游戏内运行、真实战斗验证均未执行（L3/L4 无）。
- 纠正上一轮结论（同一轮证据推翻旧归因）：上一轮记为「TERMINAL_PICK + RESIDUAL」，并把 finish() 描述成「不与同深度存活叶子比较」。实测表明：默认宽度 8/12 下修复后 beam 仍交出死亡路线，因为 -11373 的存活叶子被 `withinTurnWidth`/`beamWidth` 剪掉、根本没进最终集合；48/48 下修复后才交出存活路线。因此 finish() 缺陷真实存在且已修（由上面两条断言钉死），但 Q9 在玩家默认宽度下的表现由**宽度剪枝**决定，不是收束比较。基准标签已从误导性的 TERMINAL_PICK 改为 DEAD_OUT 症状描述，并注明「同深度已无存活候选可取时属宽度剪枝」。
- 修复后基准（`build/p1b-search-quality.txt`，preset「中」不变）：PROVABLE 层级仍 12/12 EXACT、漏优 4、漏优率 33.3%（Q3、Q8、Q9、Q12，归因全为 WITHIN / BEAM / BEAM+WITHIN）；BUDGET 层级参照 EXACT 1 / CAPPED 11、漏优 0；参照已找到可达必胜路线而 beam 未报胜仍为 2 个（Q7 药水时机、Q8 长线）。本轮改动只影响终局/叶子的取优，不改变回合预算内的剪枝宽度，故 PROVABLE 层级漏优率不变符合预期。
- 待办（本轮未动，等授权）：P1 宽度截断——放宽 withinTurnWidth/beamWidth 即可消除全部 4 个 PROVABLE 漏优，代价是每节点耗时上升，需要与墙钟预算一起调；P1 跨回合存活叶子的宽度剪枝导致 Q9 默认设置仍报必败；P2 Q11 在深度 10 的漏优无法归因（放宽任一层后参照穷举不完，需更大参照预算或迭代加深）。
- 剩余风险：`measureRobustness` 仍按 cardId 在重放时找牌，本轮未改；收束比较改动会影响「搜索见过死亡分支后仍报告存活叶子」的展示口径，玩家可能看到「预测扣血但不确定能否打赢」的路线（诚实且分数更优，属预期变化）。
- 用户验收：待确认。未提交、未推送；本轮删除了自己生成的临时副本目录 `build/oldfinish`、`build/probe`，未触碰工作区既有未提交改动。

## 2026-10-20：P0 搜索质量基准（可穷举参照 + 固定题库 + 漏优率）

- 现象/目标：此前无法客观判断内置 Beam Search 是否错过更优合法路线。本轮只建立测量底座，不改搜索算法、不改运行时行为。
- 新增文件（离线工具，不进 Mod）：`tools/solvertest/Oracle.java`（同模型可穷举参照求解器）、`tools/solvertest/SearchQuality.java`（12 场景固定题库 + 漏优率 + 截断归因）。`tools/solvertest/run.ps1` 自动纳入 `tools/solvertest/*.java`，未改构建脚本与任何 `src/main/java` 文件。
- 口径：双方共用 `CombatEngine` 结算与 `CombatSearch.evaluate` 打分；合法动作集合、12 步回合上限、药水 spec 门槛、叶子选取规则（终局 ∪ 恰好用满回合预算）全部同构。参照候选集 C(h) 的最大值即 beam 用满 h 时不可能超越的上界。判定按 beam 实际展开深度 d 取 C(d)，避免把“预算没用完”当成搜索缺陷。
- 参照求解器自检：A1 跨回合记忆化不改变结果、A2 回合内去重与暴力按序枚举得到同一状态集合、B beam 在宽预算下不超过参照、C 可复现、D beam 路线可原样重放且终局一致。命令 `java -cp build\solvertest SearchQuality --selftest`，结果 60 项通过、0 失败（证据 `build/p0-selftest.txt`）。
- 基准结果（`build/p0-search-quality.txt`，preset「中」beamWidth=8 withinTurnWidth=12 maxTurns=10 maxNodes=8000 timeBudgetMs=8000，Java 21 JBR）：PROVABLE 层级（回合预算 2）12/12 参照 EXACT，漏优 4 个，**漏优率 33.3%**；BUDGET 层级（回合预算 10）参照 EXACT 仅 2 / CAPPED 10，EXACT 子集漏优 1（50.0%）；参照已找到可达必胜路线而 beam 未给出获胜预测 2 个（Q7 药水、Q8 长线）。
- 归因：Q3-防御时机、Q12-易伤链 = WITHIN（回合内 `turnScore` 排序截断到 withinTurnWidth）；Q8-长线消耗 = BEAM（跨回合 beamWidth 截断）；Q9-生存下限 = TERMINAL_PICK + RESIDUAL；Q11 在深度 10 下各放宽均使参照穷举不完，如实记为「无法归因」。
- 记录但未修改的缺陷：`CombatSearch.finish()`（src/main/java/com/combatsolver1/search/CombatSearch.java:160-172）只要搜索期间出现过任一终局分支（含玩家死亡，分 -1000000-hpLoss）就无条件采用它，不与同深度存活叶子比较；Q9 中它交出死亡路线（-1000016）而可穷举参照给出存活路线（-11373）。该缺陷会导致“本可存活却报告必败”，属 P1「搜索明显漏掉可验证的优质路线」，等用户单独授权后再修，本轮未改。
- 证据等级：L2（离线）。实际命令与退出状态：`powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command search` 编译并跑通既有自检（外层 exit 1 仅因 PowerShell 把 javac 的 unchecked 提示写入 stderr，内部自检输出「全部自检通过」）；`java -cp build\solvertest SearchQuality` 退出码 2（含义：存在漏优，参照自检 0 失败）；`--selftest` 退出码 0。终端为 GBK 显示乱码，报告文件本身为 UTF-8。
- 未执行：完整 Mod 构建、游戏内运行、任何真实战斗验证（L3/L4 均无）。合成场景不是实机记录；模拟器 RNG 非 STS1 真实 RNG，需要玩家选择的牌（军备、燃烧契约、双持、头槌、战吼、掘墓、浩劫、地狱之刃、发现）由引擎启发式代选，这些牌已从题库排除，但仍是全局模型的既有限制。
- 剩余限制：本基准测的是「该模拟器 + 该目标函数 + 该回合预算」内的相对搜索质量，不是现实游戏的全局最优；BUDGET 层级参照多为 CAPPED，其「未漏优」不构成最优证明；题库 12 个场景规模偏小，参照在 10 回合预算下算力不足（触顶 300000 状态 / 12000ms）。基准运行总耗时约 5–6 分钟，尚未纳入默认 `all` 命令，需按需单独调用。
- 用户验收：待确认（本轮未提交、未推送；工作区既有未提交改动未触碰）。

## 2026-10-18：消耗触发的终局黑暗之拥门控

- 本轮审计 `BattleContext::triggerAndMoveToExhaustPile`、`Actions::FiendFireAction`、`Actions::SecondWindAction`、True Grit/Burning Pact/Exhume 分支及动作队列。原版依据为本机 `desktop-1.0.jar` 字节码：`DarkEmbracePower.onExhaust(AbstractCard)` 只在 `MonsterGroup.areMonstersBasicallyDead()` 为 false 时排抽牌；`FeelNoPainPower.onExhaust` 每次都排格挡；原版 `BlockPerNonAttackAction` 对 Second Wind 按非攻击牌逐张排格挡与消耗。STS1 当前代码因此确认存在一项实际差异：C++ 消耗共享入口不检查战斗是否已无存活怪，终局动作链仍可将 Dark Embrace 抽牌排入队列。
- 先加入 `exhaust_trigger_check` 并在旧实现运行，活怪场景通过、全怪死亡场景失败（旧结果会从抽牌堆错误抽走一张牌）。随后在 `BattleContext::triggerAndMoveToExhaustPile` 为 Dark Embrace 增加 `!monsters.areMonstersBasicallyDead()` 门控。没有改 Feel No Pain、批量消耗次序、RNG、卡牌选择或牌移动语义。
- 修复后离线回归覆盖：两张连续消耗且活怪时，Feel No Pain 4 格挡逐张触发为 8、Dark Embrace 抽两张、消耗堆新增两张；全怪死亡时仍得到 Feel No Pain 4 格挡，但 Dark Embrace 不抽牌，抽牌堆保持两张、消耗堆新增一张。该测试直接走共享 `triggerAndMoveToExhaustPile` 与生产动作队列；搜索和 `replay_trace` 均由 `search::Action::execute()` 进入同一 `BattleContext` 卡牌结算，因此共享效果代码相同，但本回归没有单独驱动协议回放场景。
- 机制状态（限代码审查/本轮测试）：Feel No Pain 每张消耗触发一次格挡已由本回归离线验证；Dark Embrace 普通存活战斗抽牌及终局抑制在本回归验证。Fiend Fire 通过随机逐张消耗、逐牌安排伤害，仍受 RNG 及顺序组合限制，记为部分实现；Second Wind 自动选择所有非攻击牌并按牌数安排格挡/消耗，基础路径有代码但本轮未逐状态回归，记为部分验证；True Grit 普通版随机消耗、升级版进入 EXHAUST_ONE 选择，随机/选择分支未作本轮全面验证；Burning Pact 复用 EXHAUST_ONE 后抽牌，受选牌执行边界限制；Exhume 的 `Actions::ExhumeAction` 仍有源码 TODO，排除在本轮修复范围。卡牌登记不等于效果精确，亦无本轮新 L3/L4 证据。
- 构建与验证：旧实现 `exhaust_trigger_check` 退出 1，失败为 `dark_embrace_draw_after_terminal`；修复后 `exhaust_trigger_check` 与 `block_modifier_check` 均退出 0。引擎由 `tools/build/build-engine.ps1` 构建，protocol v2 正常；`mcts_save` 在胜利样本输入上退出 0、返回 `PLANEND outcome=1`。新 EXE 为 `build/engine/combatsolver1-engine.exe`，SHA-256 `3941FF7A53F34391E67CB72AF4CD9D89A47AC958FF681D5AD1E61FE62688A280`。`tools/solvertest/run.ps1 -Command all` 退出 0；同根差分 `-SelfTest` 30/30；三份历史 bundle 均未改写，作为回归检查仍各自保留诊断限制：虱虫样本 dirty/错位、最长合法前缀 11 且 52/51 仅受限边界观察；三哨卫动作 3 `REPLAY_INVALID`、前缀 2；胜利样本配对动作摘要 72/72、终局没有稳定 post 且 dirty。均不是本轮消耗机制的实机证明。
- 本地/远程：上一轮提交 `308378b318a266c757502ac3ca95b70865afdb7e` 位于本地 `main`，开工时工作区只有既有未跟踪 `sendToDevs/`；`origin/main` 为 `c5beb305a16a46fe0c667f8d6cb196b1058c5389`。开工普通 `git push origin main` 因 GitHub:443 连接失败，后续源码提交的推送结果需在本轮收尾记录。用户目录 `settings.properties` 的 `engine.path` 已读核实为本轮 `build/engine/combatsolver1-engine.exe`；此处仅检查配置，未修改。

## 2026-10-18：Ironclad 格挡修正的 Iron Wave 重复应用

- 本轮选择“卡牌格挡修正”机制族。`BattleContext::calculateCardBlock()` 是 Ironclad 多张格挡牌共享入口，按 No Block、Dexterity、Frail 结算；源码审查发现 `BattleContext::useAttackCard()` 的 Iron Wave 把已修正值再次传回同一函数，导致 Dex/Frail 及边界值被重复套用。其余检查过的 Defend、Shrug It Off、Impervious 等分支只应用一次。玩家可见影响：Iron Wave 在 Dex/Frail/No Block 组合下会得到偏高或偏低的格挡；基础 5 格挡、升级 7 格挡及攻击伤害不变。
- 最小修复是 Iron Wave 只对其基础格挡调用一次 `calculateCardBlock()`；未改共享公式、搜索器、协议、Java fallback 或历史 bundle。`block_modifier_check` 走生产 `search::Action::execute → BattleContext::executeActions → playCardQueueItem/useAttackCard` 路径，覆盖基础/升级、Dex+Frail 组合、Frail 向下取整、No Block、负 Dexterity 下限和能量不足拒绝；核对格挡、怪物 HP、能量、手牌及弃牌堆。修复前旧实现确切失败于 `frail_rounding`（期望 3 格挡，实际因重复修正不足）；修复后 9 组通过。
- 搜索与 `replay_trace` 都通过 `search::Action::execute()` 投递原生卡牌队列，再由同一 BattleContext 卡牌结算函数处理；本 fixture 覆盖该共享动作结算入口。测试不是 STS1 原生代码差分，也不代表实机验证。
- 当前机制状态：Iron Wave 在本次覆盖的基础、升级及格挡修正组合上从已知错误改为“离线精确验证”；未据此推定其所有遗物/力量/伤害交互均精确。`calculateCardBlock()` 其他调用方未改。本轮没有对应新 L3/L4 证据。
- 验证：旧实现测试复现 `frail_rounding` 失败（5 基础格挡被两次取 75%，实际 2、期望 3）；修复后 `build/engine/combatsolver1-engine.exe block_modifier_check` 退出 0，9/9 场景通过。`tools/solvertest/run.ps1 -Command all` 退出 0；`run-root-replay-diff.ps1 -SelfTest` 为 30/30；新 EXE 上三份历史 bundle 均完成 `replay_trace`：虱虫样本 `REPLAY_INVALID` 最长协议前缀 11、可见字段 24/24，52/51 HP 仍是受限回合边界观察；三哨卫动作 3 `REPLAY_INVALID`、前缀 2、24/24；胜利样本 9 个协议动作可重放、8 个配对动作加 root 为 72/72，终局仍无全字段 post。三者均是 diagnostic-only L2，历史记录原样保留。新引擎 `mcts_save` smoke 返回 `PLANEND outcome=1`；settings.properties 的 `engine.path` 指向本轮构建路径，协议 v2 正常。完整引擎 SHA-256、实际命令和提交/推送结果见本轮交付。没有对应新 L3/L4 样本。

## 2026-10-18：终局胜负离线验收命令

## 2026-10-18：G/H 执行安全审计

## 2026-10-18：阻断 H 全自动同状态重试

- 复现控制流：部署器无目标/不可执行时返回 invalidated；`onDeployFinished` 清空路线并把 `searchAttempts` 归零，但原先不抑制 H。下一稳定帧重新搜索/复用，错误路线再次部署。现在失败会关闭 H、锁住失败时的 `turn|SimState.signature` 并抑制搜索/部署；只有观察到不同稳定根状态后再收到新的 G/H 授权才释放。J 仍可随时停止。回归向同一失败状态注入 200 次“重规划并重新启 H”，必须零次部署；自然状态变化本身仍阻断，变化后新授权才允许重试。
- 索引审计确认：本地 `LiveCapture` 仅把存活怪加入 `SimState.mobs`，本地目标索引是压缩列表位置；sts_lightspeed 使用固定 `MonsterGroup.arr[5]` 槽位。当前 `PlanDeployer` 对两种来源一律按原版完整房间列表解析，`localizePlanNames` 也使用完整列表。触发例：原生列表 `[活 A, 死 B, 活 C]`、本地模拟列表 `[A,C]`、本地目标索引 1；部署器会取到死槽位 B，报告无目标；同 ID/同名时还可能映射错怪。本轮按“定点审计、不修改其他系统”要求只报告，不改映射。
- journal 错误调用链为 `CombatController.maybeRequestReplayEnginePlan → EngineBridge.writeJournal → BattleArtifacts.replaceJournal`，发生在临时 journal 写入、原子替换或 manifest 更新阶段。此前只输出通用错误；`BattleArtifacts` 已保存 `journalError`，本轮让运行日志输出该底层原因。离线回归用缺失父目录触发替换失败并确认异常原因保留；未改真实 bundle。
- expected HP 43/live HP 42 暂列待诊断，没有可复现规则证据，本轮不改战斗规则。选牌界面导致中止仍是预期 fail-closed 行为。会话中提供了核心日志摘录，但工作区仅找到 2026-09-03 旧日志，未发现本场完整 ModTheSpire 日志文件，因此未作未提供行段的逐行关联。
- 验证：`ExecutionSafetyCheck` 9 组通过（包括 200 次同状态重试故障注入）；`BattleArtifactsCheck` 通过；`tools/solvertest/run.ps1 -Command all` 退出 0。完整 Java 8 构建与部署为 L2，不等于实机修复验证。

- 代码审计发现并修复三项 fail-closed 缺口：旧动作日志上的异步引擎 fetch 可能被当作起点计划采用；计划签名和轻量 live 签名格式不一致，导致过期路线防护无效；连续验证失败达到上限时可能降级强行使用旧路线。现在异步结果先按 journal 长度拒绝过期输入，执行前比较完整 `SimState.signature()`，多次验证失败则抑制执行。
- 部署器不再为已死亡/错位的计划目标回退攻击其他存活怪；药水要求目标时也拒绝缺失/过期目标。原生 useCard 边界校验牌实例及目标签名，发现手动插入/目标改变即取消剩余路线。J 现在清除 G 请求、关闭 H、停止搜索并取消尚未执行的本部署队列（当前正在结算的卡牌无法回滚）。
- 离线故障注入：`ExecutionSafetyCheck` 8 组通过（过期 fetch、回合新旧、未验证/过期/被抑制签名、J 暂停、G/H 授权、重复按键、死亡/错位目标、队列仅撤销本执行器所有权的动作）。命令为 Java 8 `javac --release 8 ... ExecutionSafety.java ExecutionSafetyCheck.java` 和 `java -cp build/executesafety com.combatsolver1.runtime.ExecutionSafetyCheck`，退出 0。
- 本轮不触碰 CombatRecorder、BattleArtifacts、真实 bundle、战斗规则、搜索算法或 UI。当前结论为 L2（针对纯策略门控与队列撤销策略的离线回归 + 完整 Mod 构建）；没有启动游戏或进行 L3/L4。剩余风险：出牌动作以批次入队，已开始结算的卡效果不能撤销；药水执行中的玩家干预还没有与 card-use 一样的原生边界确认；引擎跨回合状态核对仅使用既有摘要字段；重复同 ID 卡牌仍按计划下标/ID恢复，不核对升级后实例签名。

- 实机诊断准备补充：增加 G 来源、H 跨回合候选与状态摘要核对、原生 useCard 确认、意外牌/目标详情、J 撤销数量日志；关闭 H 时明确记录当前已启动部署不会被取消。仅增加日志，不改变执行行为；需重新构建部署。

- 新增 `tools/recordercheck/run-terminal-replay-check.ps1` 与 `TerminalReplayCheck.java`，复用 JSONL/journal、`replay_trace` 与 `replay_search`。报告归档哈希、非终局连续性、动作编码/目标可信度、终局胜负及终局全字段比较五项；最后致胜动作可只验收胜负类别，保留 `journalDirty` 与缺失 post，不判完整严格 L4。
- 回归使用胜利 bundle `battle-20261018-150839-671-747a4a3c-cf7b-436e-b792-2b68254a6e20`，原始文件未修改。离线协议测试 12 项通过，覆盖封存状态/哈希错误、可信终局动作、拒绝不可信目标、手牌/目标/endTurn 编码错误、非终局断链、胜负不符，以及唯一已确认的目标伤害重算边界。
- 实际命令：`powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-terminal-replay-check.ps1 -BundlePath <bundle>`。该样本封存和五份 SHA-256 一致；8 个已配对动作全部按连续 `replay_trace` 对比，90 个摘要字段（root 10 + 8 个 post × 10）匹配。第 7 行仅有 Strike_R 伤害 4→6，按已有证据分类为同目标状态下原生伤害重算。journal 9/9 动作编码及目标可信度通过。`replay_search` 返回 `PLANEND outcome=1`，匹配 manifest victory；终局全字段未比较，`journalDirty=true`，严格验收仍未通过。死亡终局未覆盖。
- Java 8 目标工具编译、12 项回归和真实样本验收退出 0，证据 L2（离线诊断）。未修改运行时代码或真实 bundle，无需部署游戏 JAR；不宣称完整整场严格 L4。源码提交和远程同步状态见本轮交付。

## 2026-10-18：记录真实出牌目标

- 原版游戏字节码确认：`AbstractPlayer.playCard()` 把玩家选中的 `AbstractMonster` 放进 `CardQueueItem.monster`；`GameActionManager.getNextAction()` 将该引用传给 `AbstractPlayer.useCard(card, monster, ...)`，该方法先计算伤害，再将同一引用交给 `card.use`。旧记录器读取鼠标 hover 索引，不能证明它等于队列目标。
- 新补丁在原生 `AbstractPlayer.useCard` 的 `card.use` 调用前取得该目标对象。统一映射器用对象身份在原版完整房间列表及 LiveCapture 的存活怪物列表中分别定位：`targetEngineIndex` 是保留死亡/逃跑槽位的原生顺序，也对应 sts_lightspeed `MonsterGroup.arr[targetIdx]`；JSONL 同时保存压缩后的 `targetLiveIndex`，用于将目标身份映射回当前存活列表。journal 编码与 JSONL `target` 均使用 `targetEngineIndex`。手动和自动出牌共用同一映射；自动路径原先选中的 `AbstractMonster` 也用于 journal。
- 无单体目标牌明确写 `none`、`self` 或 `all_enemies`，索引均为 -1；引擎 journal 格式需要的数值占位 0 只保留在动作位编码，前置注释保留目标模式。目标缺失、死怪或对象不在房间 roster 时，写明 `targetTrusted=false` 和原因，并将 journal 标脏；严格连续验收器拒绝没有可信目标元数据的动作。旧记录保持原样，严格检查样本时 5 张卡牌均因缺少历史目标元数据被拒绝，不将旧 `target=1` 改写为 `target=0`。
- 回归覆盖不同怪物对象、死亡/空槽位后的双索引映射、手动/自动共用映射、无目标模式、未知/死亡目标、journal 和归档元数据，以及原生补丁注册和调用签名。单体目标测试确认完整房间槽位 2、当前存活列表位置 1 时，journal 仍编码引擎固定槽位 2（动作 `20002`）；JSONL 另存 `targetLiveIndex=1` 供身份交叉检查。`LiveCapture` 同时跳过空槽位。
- 验证：目标映射及结束回合边界回归退出 0；`tools/solvertest/run.ps1 -Command all` 退出 0；严格验收合成可信目标退出 0、未可信目标退出 1；真实历史样本严格检查退出 1（预期，5 条为 `legacy-target-metadata-missing`）。最终固定槽位源码提交为 `610b596fd059927b710e924baf23186568d618f9`；`powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1 -Deploy` 退出 0，Java 8 major=52，JAR 88 个条目。构建 JAR 与部署 JAR SHA-256 均为 `36C67EBBC15B13F746A3095713C183EE480C847A593A353949D839A168FF92DE`；包内含目标补丁、映射器类和 ModTheSpire 描述文件。对 `git push origin main` 的三次尝试均遇到 GitHub HTTPS 连接失败；随后 GitHub API 查询确认远程 `main` 仍为 `ff15154a21587d77676c4671565284e3c09ecef6`，本地提交尚未推送。当前为 L2；未启动游戏，未取得新实机记录，不宣称 L3/L4。原始样本未修改。

## 2026-10-18：死亡战斗 bundle 封存

- 死亡样本 `battle-20261018-064132-559-5a8d8a85-52cc-4408-871a-65043f363253` 的 manifest 仍为 `open`；`BattleBundleCheck <sample-dir>` 退出 1，原因 `battle bundle is not closed`。现场 JSONL 与 journal 文件哈希也与该 open manifest 中的值不同；未改动样本文件。
- 原版 `AbstractPlayer.damage()` 在致死时置 `isDead` 并创建 `DeathScreen`，不会调用 `AbstractRoom.endBattle()`；后者会调用 `AbstractPlayer.onVictory()`。BaseMod `AbstractRoom.EndBattleHook` 发布 `PostBattleSubscriber`，而 `DeathScreen.PostDeathHook` 构造后发布 `PostDeathSubscriber`。Mod 之前只订阅 PostBattle，死亡遗漏封存回调。
- 新增死亡回调并把胜利、死亡、离开 dungeon/被新战斗替换分别记为 `victory`、`death`、`interrupted`。关闭入口幂等；未稳定结算的末张牌只在 manifest 标为 `unpaired-no-stable-post`、journal 标脏并保留原文件，不生成虚构 post；封存时按最后写入内容刷新 JSONL/journal 哈希。
- 离线回归覆盖死亡/胜利/中断、重复关闭、延迟存档关闭、保留此前配对动作、未结算末动作不造 post、哈希刷新。命令：Java 21 JBR `javac --release 8 ... BattleArtifacts.java BattleArtifactsCheck.java` 与 `java -cp build/death-archive-check com.combatsolver1.runtime.BattleArtifactsCheck` 退出 0；完整 Mod 源码加 `CombatRecorderTransitionCheck` 编译（`--release 8 -proc:none`）并运行退出 0，保留既有 endTurn 边界回归。
- 完整部署命令 `powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1 -Deploy` 退出 0；Java 8 字节码 major=52、85 个 JAR 条目通过。构建与部署路径分别为 `build/libs/CombatSolver1.jar` 与 `C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire/mods/CombatSolver1.jar`，SHA-256 均为 `D08BC7590A04A4727E60D99E7A9215E89C3CA7C185336FFE9E804C7B2821BE63`。部署包确认包含 `CombatSolver1Mod`、`CombatRecorder` 和 `BattleArtifacts`，含 death/victory/interrupted 与未配对末动作标记。
- 源码提交 `ff15154a21587d77676c4671565284e3c09ecef6` 已推送，远程 `origin/main` 同 SHA。当前证据 L2；没有启动游戏或新增实机样本，不宣称 L3/L4；强制结束进程时游戏不会保证执行任何 Java 回调，manifest 可能继续保持 open。

## 2026-10-18：AscendersBane 费用哨兵差异审计

- 既有同场 root 对照中 62 个可比字段有 61 个相同；唯一差异为 AscendersBane 费用：实机 JSONL 为 -2，sts_lightspeed `CardInstance` 初始化为 -3。本轮没有改卡牌规则或真实预期值。
- 费用路径核查：`CardInstance::canUse` 先按卡牌类型拒绝无蓝蜡烛的诅咒；带蓝蜡烛时能量检查对正常非负能量下的 -2/-3 都成立。`BattleContext::useCard` 仅对 `costForTurn > 0` 扣能量。费用折扣、随机费用和选牌筛选只针对正费或非负费用，二者同样不会命中；X 费按卡牌 ID 判断。
- 合法选择路径按手牌/牌堆下标处理，不读取费用。新增现有引擎测试程序命令 `ascenders_bane_cost_check`，检查普通动作门控、蓝蜡烛下两种负费用编码的可用性与能量扣除、单/多张消耗选择、Gamble 弃牌选择及普通 Defend 动作。
- 验证命令：`cmake --build build/engine --target combatsolver1-engine --parallel 4` 退出 0；`build/engine/combatsolver1-engine.exe ascenders_bane_cost_check` 退出 0。真实配对存档的单动作 `replay_trace` 拒绝 AscendersBane（`REPLAY_INVALID n=1`），接受 Bash 并输出状态；未回放整份 journal。
- 当前证据为 L2 离线测试，未启动游戏。该表示差异没有发现规则影响；已知样本第 7/13/18 行仍有结束回合状态断链，因此本样本尚不满足严格连续动作重放条件。不能据此宣称整场 L4 通过。

## 2026-10-18：首份真实 bundle 复盘与归档/root 修复

- 实样本 `battle-20261018-051005-474-f7972e19-b4b3-4f16-9cc0-24b7a1620b09` 未入库。manifest 为 `open`，raw autosave 与引擎输入均 `pending`，root 的 engine 字段为 `local`。控制器原先只在 `sts_lightspeed` 已配置时启动初始工作线程，所以此样本没有执行 autosave 查找；pending 不能据此说明游戏存档本身不存在，也不能归因于转换失败。
- manifest 最后更新时间为 05:10:09，JSONL 最后写入为 05:10:33，且状态仍 open。SHA 不匹配是未封存期间文件继续追加的结果；该证据支持在 onBattleEnd 前打包，但没有 RAR 时间戳/游戏日志，无法排除战斗结束回调未触发。严格 bundle 校验拒绝 open 状态。
- 既有 RecordIntegrityCheck 对原文件报告 14 行、1 root、11 card、2 endTurn，断链 4 处：第 2 行 root 与首牌 pre、第 5 行攻击药水、第 7 行启动期误判 endTurn、第 10 行结束回合手牌自动移入弃牌堆。`handSize` 来自 `player.gameHandSize`，表示每回合抽牌目标，不代表当前手牌数量。
- 第 5 行的 AttackPotion 从药水槽消失，同时其结果 `Wild Strike` 加入手牌；控制器的被动检测将 ActionJournal 标为 dirty，原因保留为未记录药水。该 journal 不能用于外部引擎连续动作验收。
- 第 7 行的错误 endTurn.pre 回退到开战牌堆/药水/怪物意图状态，根因是启动期 `turnHasEnded` 在玩家尚不可操作时被记录器当成回合结束。现在只在观察到玩家可操作帧后接受结束回合；root 改在首个稳定且玩家可操作状态捕获。第 10 行手牌移入弃牌堆属于结束回合自动结算阶段。
- 初始 autosave 归档现独立于 sts_lightspeed 配置；未配置时写明 `not_configured`，只归档原始存档，不生成或冒充引擎输入。归档线程在战斗结束后仍可完成，manifest 直到采集结束才封存。校验器拒绝 dirty journal、哈希错误、缺原始存档及 root 与首张牌 pre 不同的 bundle。
- 回归命令：`java -cp build\artifactcheck com.combatsolver1.runtime.RecorderTurnGateCheck` 退出 0；`java -cp build\artifactcheck com.combatsolver1.runtime.BattleArtifactsCheck` 退出 0；`java -cp "build\artifactcheck;build\classes" com.combatsolver1.runtime.FreshAutosaveCheck` 退出 0。实际样本的 `RecordIntegrityCheck <sample-dir> --strict` 退出 1，为预期诊断结果并打印上述 4 行。
- 完整构建/部署命令 `powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1 -Deploy` 退出 0，Java 8 major 52、85 个 JAR 条目通过；部署 JAR SHA-256：`CEB1B7B1ADD3647B5E49B764CAF2AE79C582DA8CE99C195FD831F19D7ACED482`。源码提交 `62eb833276e137c7edec438393bef657367bc4e3`；状态记录提交随后补入。仅 L2 离线/构建验证，未做 L4，也未修改搜索算法或 sts_lightspeed 规则；用户尚未验收。
# 项目现状与后续工作

## 2026-10-18：真实 bundle 根状态对照

- 样本 `battle-20261018-054109-273-e6990591-c40b-4854-a3e6-4d0822290532` 的 manifest 为 closed；raw autosave、engine-in.save、JSONL 和 journal 均存在且 SHA-256 与 manifest 一致。root 阶段为首个稳定可操作状态；自动存档采集更早，未假定两者等价。
- 对原记录引擎执行 `replay_trace <engine-in.save> <empty-actions> 0`，得到 `TRACE0`、`REPLAY_OK n=0`，退出 0。turn 映射为游戏 turn = 引擎 turn + 1（BattleContext 初值 0，`getMonsterTurnNumber()` 返回 turn+1）。未回放真实 journal。
- 最小诊断输出提交 `e204c984`（`vendor/sts_lightspeed/apps/test.cpp`），只追加 TRACE0_DETAIL / MONSTER / RNG 行，不改模拟规则。隔离构建退出 0；使用该扩展引擎对同一 engine-in.save 执行零动作 trace 退出 0。外部原始设置路径所指二进制未替换。
- 逐字段对照 62 个可比字段路径，61 匹配。唯一差异为 `hand[1].cost`：JSONL root 的 AscendersBane 为 -2，引擎 CardInstance 初始化为 -3；源码将其作为不可打出牌的内部标记。本轮不修改规则。Akabeko、三件遗物、药水、手牌顺序/攻击伤害、三类牌堆及可见怪物状态均匹配。
- 未捕获项：JSONL root 无真实 RNG 状态（`LiveCapture.rngState` 不在 root JSON 中）；玩家 Berserk 缺引擎字段；怪物 localized name 与 move cursor 无同名可比字段。不能把这些计为一致。
- 第 7、13、18 行断链的差异字段仅为 hand 与 discardPile：旧回合尾牌由手牌整体移入弃牌堆，turn 不变，属于合法 end-turn 自动清理阶段；这是记录事件边界缺口，非外部引擎规则对照差异，本轮不改记录器。
- 证据等级：诊断输出与隔离构建为 L2；存在一份真实样本的同场根状态对照，但由于 1 个字段差异和未捕获字段，不构成通过的 L4 准确性验收。未执行整场连续回放、未启动游戏、未改个人样本。

## 2026-10-18：真实战斗样本配对归档

- 每场战斗按 UUID battleId 建立独立目录，保存 UTF-8 `combat.jsonl`、带 battleId 注释的 `actions.journal`、未经修改的 `raw-autosave.save`、引擎派生存档及 `manifest.json`。manifest 记录来源路径/时间、SHA-256、引擎协议与二进制 SHA-256、JSONL 根状态阶段和动作日志状态；原始存档与首次玩家动作前 root 的阶段明确分开。
- 外部引擎只从已归档原始存档生成本场派生输入。自动存档超过新鲜度门槛、归档失败或存档转换失败时拒绝继续，不回退使用更旧的存档；manifest 明确记录该存档仍需与 root 做内容对照。
- 修复前旧存档探针返回 2 分钟前的 autosave；修复后同一探针返回 null，新鲜 autosave 被选中。归档回归通过：不同战斗 ID 拒绝、旧存档拒绝、复制失败写明状态、替换原始存档/日志后哈希拒绝。
- 完整 Java 8 Mod 构建退出 0，major 52，JAR 84 个条目通过校验；已部署到游戏 `mods/CombatSolver1.jar`，部署前自动备份旧 JAR，部署文件 SHA-256 与构建包一致：`A0C353308F07536DA08D7759786835CD0B6D6A7C8416E21D20564939CC399F4F`。
- 当前仅为 L2 离线/构建验证。下次实机生成归档后仍须比较外部引擎从归档存档初始化的状态与 JSONL root，才能确认同场内容配对；不宣称 L4。
- 收尾审查补充：后台归档/引擎线程增加未预期异常兜底，记录具体异常到归档 manifest 与 CombatLog，并只更新当前战斗提示；新增引擎输入转换失败状态与原因回归。采集失败仍不会改变手动战斗流程。

## 2026-10-18：卡牌动作边界与记录顺序

- 实机 `combat-20261018-042655.jsonl` 证明旧记录器会让最后一张 card 的 post 跨入下一回合，并在之后倒序写 endTurn。
- `CombatController` 现在在 `turnHasEnded` 首次可见时调用记录器：先在旧回合结清待记录卡，再冻结 endTurn.pre；新回合稳定或首张牌开始前按时间顺序写 endTurn。
- card 若仍跨回合会写 `paired=false` 与 `reason=turn-boundary-before-card-settled`，不再伪装成正常卡牌效果；严格检查器新增 card 同回合与 endTurn 单调断言。
- root 改在第一项真实玩家动作边界写入，避免首个 idle 帧早于开战遗物/抽牌结算。
- 状态机回归覆盖同回合稳定、立即结束回合、新回合首张牌、多个连续回合、重复稳定帧和战斗结束重置；旧 042655 在第 5/9/12/15/20/26/32/37 行按预期报告跨回合 card，第 10/38 行报告链倒序。
- 本轮只修改记录器、其调用点和测试；未修改搜索、规则、怪物或 UI。验证等级仍为 L2，待新实机记录才能升级。

## 2026-10-18：修复回合过渡状态机

- 修复 `CombatRecorder` 中 `lastTurn` 在第一新回合帧立即覆盖、导致双稳定帧计数无法达到的问题。
- `TurnTransitionTracker` 独立保存过渡前快照、目标回合和稳定帧数；第二个同回合稳定帧写一条 endTurn，后续帧不重复；过渡中出牌和战斗重置会取消未完成过渡。
- 新增 `tools/recordercheck/CombatRecorderTransitionCheck.java`，覆盖旧回合稳定、新回合第一/第二/后续稳定帧、过渡中出牌和战斗结束重置。
- 实际验证：回归命令退出 0；完整 Java 8 Mod 构建、JAR 条目校验和部署退出 0；`tools/solvertest/run.ps1 -Command all` 退出 0。未启动游戏，未宣称 L4。

## 2026-10-18：结束回合记录与 JSONL 编码

- 实机证据中的 endTurn 断点来自记录器在首个 action queue 变空帧捕获状态；回合结算仍可能继续清手牌、入弃牌堆或更新怪物。
- `CombatRecorder` 现在等待同一新回合连续两个稳定帧才写 endTurn，并标注 `phase=turn-transition`；不丢弃手牌/弃牌堆变化。
- 新记录改用 UTF-8 `OutputStreamWriter`；`RecordIntegrityCheck` 自动识别 UTF-8/旧 GBK，严格报告 root、动作和相邻状态链问题。
- 离线验证：`tools/solvertest/run.ps1 -Command all` 退出 0；现有 recording fixtures 完整性检查退出 0；旧真实 GBK corpus 仅保留诊断，禁止作为严格连续样本。
- 完整 Mod JAR 已重新构建并部署，Java 8 major=52，JAR SHA-256 记录在本轮交付中；未启动游戏，不宣称 L4。

## 2026-10-18：CombatRecorder 生命周期与记录完整性

- 修复记录器按怪物阵容切分战斗的问题：召唤、死亡或阵容变化不再隐式开启新记录；战斗身份改由 `onBattleStart` / `onBattleEnd` 生命周期控制，并写入 `battleKey`。
- `receiveOnBattleStart` 现在先建立记录会话，首个稳定状态写出的 root 位于第一条动作之前；战斗结束仍关闭文件并清理待配对动作。
- 新增 `tools/solvertest/RecordIntegrityCheck.java`：检查 root 数量、动作连续性及相邻 `post → pre` 一致性，不判断模拟规则正确性。
- 运行结果：旧 corpus 分类为 `011651` 多 root（3 个，来源混合/仅单步），`011810` 与 `011821` 结构连续候选，`011854` 第 12 行出现 `pre!=previousPost`，属损坏或跨段记录。旧 corpus 未删除、未改预期值。
- 验证：记录完整性检查编译并运行退出 0；`tools/solvertest/run.ps1 -Command all` 退出 0（70 动作，668/793，48 个差异）；完整 Java 8 Mod 编译退出 0。均为 L2，未进行 L3/L4。
- 当前结论：新记录器具备采集单场连续记录的代码条件，但尚无新生成实机 JSONL 通过完整性检查，不能称真实战斗验证完成。

## 2026-10-18：采用开发规约并同步当前工作快照

- 用户明确授权：原样复制 Downloads/AGENTS.md 到仓库根目录，并提交、推送当前本地项目。该文件成为后续开发流程与验收规则；复制前后 SHA-256 均为 `3F480F8A6FF7DA3EB0B3E3F63F98B66160FCD580FB7EB2E75F408B349E5ED656`。
- 当前快照包含上一批引擎复用，以及尚在开发的选择协议 v2、选择日志编码、`replay_trace` 连续回放输出。**原生选牌执行尚未接入（L0），不能称选牌功能已完成**；v2 协议及连续回放未完成专项回归，只有本次编译证据。
- 固定守护者样本发现：实机根能量 5，引擎原先恢复为 3。游戏字节码 `AncientTeaSet.atTurnStart` 显示 counter=-2 时首回合增加 2 能量；已据此调整外部引擎恢复条件。此修复本次只编译，未完成样本重放验收（L1）。起点核对仍需取消跳过能量的旧行为。
- 同步前实际执行：`powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1`，退出 0；证据 `build/sync-solvertest.log`。现有三个记录 fixture 与记录问题回归通过（L2）；corpus 的 70 动作仍有 48 个差异动作，属于诊断输出，不作为通过门禁。
- 外部引擎执行 `tools/build/build-engine.ps1`，使用本机 CMake、MinGW 与 Ninja（具体参数见下方），退出 0；证据 `build/sync-engine.log`、`build/engine/engine-info.txt`。继承源码的位移越界警告仍存在。
- 完整 Java 源码执行 `javac --release 8 -encoding UTF-8 -proc:none -cp <desktop-1.0.jar;ModTheSpire.jar;BaseMod.jar> -d build/mod-sync-check <src/main/java 全部 .java>`，退出 0；证据 `build/sync-mod.log`。**仅编译，未启动游戏，不满足 L3**。
- 引擎构建的实际调用：`powershell -ExecutionPolicy Bypass -File tools/build/build-engine.ps1 -CMake "C:/Program Files/Microsoft Visual Studio/18/Community/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe" -Compiler "C:/Users/3476/Documents/Codex/2026-10-15/jie/work/mingw64/mingw64/bin/g++.exe" -Ninja "C:/Program Files/Microsoft Visual Studio/18/Community/Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe"`。
- 本次未部署、未启动游戏、未做 L4/L5；未声称连续整场一致或选择自动执行通过。用户尚未验收这些开发中功能。日志/构建产物位于忽略的 build/，不随源码上传。
- 下一项：完成固定样本的根状态与连续动作回归。继续遵守“不扩展 Java 规则表、不引入第二求解器”的约束；当前任务以规约入库与同步为止。

## 2026-10-17：复用已有外部引擎的第一批落地

- 已将本机既有、带 CombatSolver1 输出协议的 sts_lightspeed 最小源码快照纳入 vendor/，保留 MIT 许可及导入前文件 SHA-256。提取目录没有上游提交信息，因此明确标为本地修改快照。
- 添加独立 CMake/PowerShell 构建入口，只构建 CLI，默认 2 路编译，支持增量编译；无 Python、训练或下载依赖。MinGW 生成静态运行库链接的 exe，并写协议与文件指纹。
- 外部引擎异常退出、输出缺少 PLANEND、回放失败或缺少 REPLAY_OK 时，Java 桥拒绝结果，避免部署残缺路线。
- 补上本地 corpus 测试所需的 dataIssues 分类：无法解析及动作与 pre 不一致的记录不计入模拟字段误差；严格 diff 对数据问题/跳过返回失败。新增负面输入回归。
- 本次验证：完整 Java Mod 按 Java 8 目标编译通过；离线自检及 3 个 fixture 通过；实际新引擎完成 100 次迭代的起点搜索和 3 动作回放；Java 桥对有效输出与无效回放的集成检查通过。
- 当前本地 corpus 为 70 动作，字段匹配 668/793（约 84%），48 个动作存在差异。仅描述该语料和现有单步比较器，不是整场准确率或外部引擎胜率。
- 未修改用户引擎设置、未部署到游戏、未进行可见游戏内验收。既有引擎入口按铁甲读取存档；继承源码存在位移越界编译警告，后续需审计。选牌/其他职业未因本批改动自动获得支持。

下一批优先：在固定实机样本下验证根状态与连续动作一致性，完成选牌协议及原生执行；在此之前不扩大内置 Java 规则表或引入第二套求解器。

最后更新：2026-10-17（Void 抽牌触发效果补全）

## 当前验证状态（v0.3.0 后）

- 离线差分记录现已保存并比较实机可捕获的玩家减益 / 已建模能力，以及怪物神器、可塑、板甲、无形、无敌、死亡节拍与时间扭曲层数；新合成 fixture 覆盖非零玩家状态字段。
- `javac --release 8` 下离线模拟 / 搜索 / solvertest 编译通过；全套离线断言及所有 JSONL fixture 通过。此项**不等于完整 Mod 构建或实机战斗通过**。
- 本地 Java fallback 未捕获或复刻实机 RNG，4 分支是有限抽样，不是胜率保证；可选 `sts_lightspeed` 从战斗存档恢复 RNG 状态，但需单独安装，且本项目尚未完成实机精度验收。
- 已补全 Void 状态牌：抽到时扣 1 能量但不低于 0，直接进入手牌不触发扣能，回合结束时按虚灵消耗；对应模拟断言通过，尚未做游戏内实机差分。
- 搜索分支去重已经改用完整模拟 `searchKey`（随机游标、隐藏触发计数、牌实例、增长计数与怪物 AI 状态）；实机路线复用继续用可捕获字段的签名，不将伪 RNG 当真实 RNG。
- 新增 `live-weakened-strike.jsonl` 端到端回归样例：覆盖实机记录的当前伤害 4、易伤 1 层、怪物生命 14→8、能量 1→0；默认 `Solvertest all` 会自动重放此 fixture。
- 外部 RNG 引擎与本地 fallback 的能力边界现已写清：上游声明的精确度不等于本项目完成实机验证，面板也区分外部引擎与近似本地路线。
- README 的当前限制已改为与支持矩阵一致；历史开发记录保留原样，仅供回顾。
- `v0.3.0` 是源码标签，不代表已有可下载 GitHub Release 包；发布包应在含游戏依赖的机器上按 `tools/build/build-release.ps1` 构建，并先完成实机验收。

## 〇·二十二、本轮：Void 抽牌触发机制补全（2026-10-17）

- 将 Void 从“抽到时扣能未建模”的近似牌改为精确条目；按原版时机只在从牌堆抽到时扣 1 能量，并遵守不能降到负能量的规则。
- Void 仍保留虚灵属性：回合结束时仍在手牌便消耗。若由其他效果直接加入手牌，不触发抽牌扣能。
- 新增离线回归覆盖上述边界及搜索键/复制字段传播；Java 8 目标编译、全部离线断言和现有 JSONL fixtures 通过。未声称完成游戏内实测。

## 〇·十九、本轮：扩大差分状态覆盖并校正文档（2026-10-16）

- 实机 JSONL 为已建模玩家效果与怪物防御能力写出字段；离线重放器将其装载回模拟状态并逐动作比较。
- 新增非零玩家效果合成 fixture，自动纳入 `all` / CI；旧格式 JSONL 仍可读取，但缺失字段按零值处理，不能据此验证新字段。
- 当前牌堆身份比较使用多重集合，仍不比较真实 RNG 或牌堆顺序；随机分支复推不能替代实机对照。
- 修正 README 里旧的 v0 骨架、怪物覆盖、药水数量与“全自动尚未实现”等过时表述；以支持矩阵和限制章节为准。

## 〇·二十、本轮：搜索内部状态键补全（2026-10-16）

- Beam Search 的回合内去重、跨回合去重与循环检测统一改用 `SimState.searchKey()`；其纳入模拟 RNG 游标、出牌 / 洗牌 / 遗物相关计数、成长数据、完整牌堆牌实例，以及怪物 AI 状态机和当前招式效果。
- 另保留 `signature()` 作为实机可对照字段子集；由于游戏 RNG 与若干运行时内部计数尚未从实机捕获，不能声称路线复用键是完整真实战斗状态。
- 新增 7 条状态键回归断言；Java 8 目标编译、全部离线自检与两个差分 fixture 均通过。

## 〇·二十一、本轮：真实牌值差分闭环 fixture（2026-10-17）

- 把实机对局中“虚弱将打击压到 4，击中 1 层易伤的拉格威林，从 14 降到 8”的案例加入 JSONL fixture。
- 该样例经完整 `DiffRunner` 执行，覆盖 JSON 解析、严格牌实例匹配、目标回放和 11 个状态字段比对；Java 8 目标编译和默认 `all` 回归通过。
- 这是脱敏 / 固定的真实案例回归，不表示已在本次提交重新进行游戏内战斗验证；后续实机状态变化仍需继续录制与比对。

## 〇·十六、第九轮：卡牌侧核对（2026-10-16）

卡牌这边原先只做完铁甲红牌，这轮用现成数据源做了核对，并给出准确缺口。

### 数据源与结论

- **Wiki `Module:Cards/data`**（Lua，人工校验过）已抓到并用上：每张牌给出
  Name / Color / Type / Cost / Text（含 `[基础|升级]` 数值）/ Traits。
  注意它的 `Traits` **不含** `SelfExhaust`——「打出即消耗」写在 `Text` 里（`#Exhaust.`），
  所以判定要看文本末尾，不能看 Traits。
- 新增 `tools/inspect/CardAudit.java`：按颜色核对 CardLibrary（缺哪些牌、哪些标记对不上）。

核对结果：

```text
红牌（铁甲）：共 75 张，已登记 75 张，缺 0 张，标记不一致 0 处
无色牌：      共 53 张，已登记 1 张，缺 52 张
```

也就是说**铁甲红牌已经做全**，而且「打出即消耗」这套标记先后被两个独立来源验证过：
先是游戏自带 `localization/zhs/cards.json`（当年据此修掉 8 张错误），现在是 Wiki 的 Lua 数据，
两者一致（唯一一处"不一致"是 Wiki 把 `Exhaust.` 写进了升级分隔符里造成的解析假象）。

（`slaythespire-codex` 的 Cards 数据集是 Parquet 格式，纯 Java 无依赖解析不划算；
它给的字段——费用、伤害、格挡——本来就由实机卡面读取，所以对模拟精度帮助有限。
它的价值主要在**无色牌**的补齐上。）

### 剩余

无色牌已补了一批（见下一节），其余机制无法建模的保持"未建模"。

## 〇·十七、第十轮：无色牌（2026-10-16）

按 Wiki 的 `Module:Cards/data` 逐张核对后补录。原则不变：**能建模的才登记，
建模不了的保持"未建模"**——"认识这张牌但算错效果"比"不认识它、保守处理"更糟。

已登记（数值仍由实机卡面读取，这里只描述"做什么"）：

| 牌 | 效果 |
| --- | --- |
| 迅捷打击 / 良好直觉 / 灵巧 / 钢铁闪光 | 伤害 / 格挡 / 格挡+抽 1 / 伤害+抽 1 |
| 致盲 / 绊倒 / 黑暗镣铐 | 全体虚弱 / 全体易伤 / 敌人 -9 力量并消耗 |
| 戏剧入场 / 绷带 / 吸血 / J.A.X. | 全体伤害并消耗 / 回 4 血 / 7 伤害回 2 血 / 失去 3 生命换 2 力量 |
| 深呼吸 / 战略大师 / 洞察 / 庇护 / 重击 / 暴力 / 匕首 | 抽牌 / 抽 3 / 抽 2 / 12 格挡 / 12 伤害 / 20 伤害 / 4 伤害（后六张均消耗） |
| 先见之明 / 贪婪之手 / 仪式匕首 / 净化 / 神化 | 抽 2 并放回一张 / 20 伤害 / 斩杀成长（近似）/ 消耗（近似）/ 升级手牌（近似） |

新增 `healFlat`（固定回血）这一效果，用于吸血与绷带。未登记的是机制超出模拟范围的：
幻影（无形）、紧急按钮（两回合不能获得格挡）、发现 / 万花筒 / 蜕变 / 传变 / 磁力 /
混沌 / 残酷天性 / 炸弹 / 阿尔法-贝塔-欧米伽、思想超前 / 疯狂 / 洞察（选牌类）等。

## 〇·十八、第十一轮：为无色牌补引擎能力（2026-10-16）

上一轮留下的"机制超出范围"的那批，这轮把引擎能力补上了，无色牌登记数 **24 → 42**（缺 11）。

新增的引擎能力：

| 能力 | 语义 | 用到的牌 |
| --- | --- | --- |
| 玩家无形 | 受到的伤害降为 1，按回合递减 | 幻影 |
| 禁格挡 | 若干回合内不能用卡牌获得格挡 | 紧急按钮 |
| 随机生成牌 | 打出时生成随机牌（无色 / 攻击 / 技能），可加入手牌或洗入抽牌堆 | 发现、万事通、蜕变、蝶化、传变 |
| 每回合生成 / 免费出牌 | 磁力每回合给一张无色牌；混沌每回合免费打出牌堆顶 | 磁力、混沌 |
| 按次数触发能力 | 华丽每回合第 5 张牌群体 10 伤 | 华丽 |
| 减益联动 | 残忍天性：上减益追加 5 伤 | 残忍天性 |
| 延迟结算 | 炸弹：三回合后 40 点群体伤害；欧米伽：回合结束 50 点群体伤害 | 炸弹、贝塔 / 欧米伽 |

对账后仍缺的 11 张：思想超前 / 疯狂 / 启蒙（改费用与牌序）、心灵爆震 / 处刑者 / 奇迹
（伤害或效果依赖牌堆 / 职业机制），以及 4 张状态牌（灼伤 / 眩晕 / 黏液 / 伤口 ——
这三类由引擎在运行时直接生成，本来就不需要登记，属于工具的误报）。

**排查过程中踩到一个坑值得记下**：我用 `Select-String 'rror'` 过滤编译输出，中文报错
（"错误"）不会被匹配，于是**编译失败被静默吞掉、跑的是旧类**，一度误报"全部通过"。
后面改成看 `$LASTEXITCODE` 并用 `Select-Object -First N` 直接看前几行，才抓到
`SimState.Card` 少字段的真实错误。这类"过滤器吃掉报错"的隐患在 CI 里同样存在。

## 〇·十五、第八轮：手写怪物也分进阶（2026-10-16）

最后一项数值活：手写的 22 只怪物原先只用基础（进阶 0）数值，现在也按进阶分档了。

做法上先核对了差异形态——这批怪每只只在高进阶**有一档伤害跃升**（普通怪 A2、
精英 A3、首领 A4；A7/A17 那两档对它们只改血量，而血量本来就由实机读取）。于是用一张
覆盖表精确编码，不必把 22 × 4 份脚本抄一遍：

```text
Champ A4+：重斩 16→18、掌掴 12→14        BronzeAutomaton A4+：连枷 7×2→8×2、超能光束 45→50
蛇怪 A2+：快速擒抱 16→18、重砸 22→25      尖塔长矛 A3+：串刺 10×3→10×4、燃烧打击 5×2→6×2
腐化之心 A4+：血弹 2×12→2×15、回声 40→45   爬行者 A3+：巨咬 30→34、蛇击 13×2→16×2
（另有地精傲娇 / 掠夺者 / 奴隶主 / 尖刺怪 / 巨口 / 暗影 / 蠕动之躯 / 自爆者 / 收集者 / 尖塔盾）
```

覆盖表只在手写怪物上生效（生成表的怪物已按进阶分出不同脚本），所以不会重复加成。

新增 3 条断言（勇士 A0 无覆盖 / A4 提升到 18、长矛串刺变 4 段、覆盖表可被招式命中），
**当时共 118 项全部通过**（历史快照，不含后续新增的搜索键断言）；当时 Mod 全量编译通过，最终 jar（160KB）已部署，旧版七个备份保留。

至此：65 只原版怪物全部建模、进阶分档齐全、Wiki 与 Bestiary 的机制（时间扭曲 / 缠绕 /
可塑 / 召唤 / 复活 / 逃跑 / 两阶段）全部实现。

## 〇·十四、第七轮：收尾（2026-10-16）

补上上一轮列出的两个功能性缺口：

- **地精首领**：按 Wiki/Bestiary 的规则——场上没有小怪时 75-25 偏向「集结！」（召唤 2 只地精），
  有 1 只时按上一招在「集结/鼓舞」「集结/刺击」间分配，超过 1 只时 66-34 偏向鼓舞；任何招式都不连续使用。
- **爬行者**：起手召唤匕首，之后三招均等（蛇击不连续两次、召唤不连续三次、巨咬不连续两次）；
  场上有 4 只匕首时不再召唤。召唤上限现在按种类区分（匕首 4 只，其余 2 只）。
- **觉醒者两阶段**：第一阶段打空血量后不再"死亡"，而是满血「重生」进入第二阶段并换招式表
  （起手黑暗回响 40，之后淤泥 18 / 擒抱 10×3 各半，均不连续三次）。

自检新增 8 条断言（第二阶段满血重生与起手、集结召唤 2 只地精、匕首上限 4 等），
**共 115 项全部通过**；Mod 全量编译通过，新 jar（159KB）已部署，旧版六个备份保留。

### 仍然保留的近似（已无功能性缺口）

手写的 20 只怪物用的是基础（进阶 0）数值；45 只生成的才有 A2/A7/A17 分档。
Wiki 的怪物页给了各进阶的伤害数值，可以用同一套生成器补成分档。

## 〇·十三、第六轮：按 Wiki 补全机制（2026-10-16）

不跑游戏，改用**杀戮尖塔一代 Wiki**作为现成知识核对与补全。

### Wiki 核对结果

- 进阶结构完全对上：A2/A7/A17 影响普通怪（伤害 / 血量 / AI），A3/A8/A18 精英，
  A4/A9/A19 首领——与 Bestiary 的 `min_asc` 分档一致，说明分档实现是对的。
- 从 Wiki 拿到几条 Bestiary 没写清、而我原先按近似处理的规则，已按 Wiki 修正：

| 机制 | Wiki 原文要点 | 实现 |
| --- | --- | --- |
| 时间扭曲 | 每打出 12 张牌就结束你的回合并获得 2 力量（跨回合累计） | 牌数计数；触发后本回合不能再出牌，持有者 +2 力量 |
| 缠绕 | 你的回合结束时受到 X 伤害 | 回合结束先结算缠绕伤害 |
| 可塑 | 受到攻击伤害时获得 X 格挡，**每次触发 +1**，玩家回合开始重置 | 格挡量递增并在回合开始归零 |
| 召唤 | 收藏者召唤 2 只火炬头、最多 2 只；铜制机械人召唤 2 只铜球 | 引擎支持战斗中新增怪物（当回合不出手） |
| 复活 | 暗影血量归零后，若同伴存活，两回合后以半血复活 | 复活倒计时 + 半血站起 |
| 逃跑 | 掠夺者抢两次 → 烟雾弹 → 逃跑 | 逃跑后脱离战斗 |

### 引擎变化

`SimState` 新增缠绕 / 回合内出牌数 / 回合锁 / 可塑格挡量 / 复活倒计时 / 时间扭曲；
`MonsterAi.Move` 新增召唤、逃跑、复活、缠绕四种效果；`CombatEngine` 支持战斗中新增怪物、
回合强制结束、复活与缠绕结算。数值与语义均按 Wiki 条目实现，并在 `MonsterManual`
与自检里留痕。

自检新增 14 条断言（时间扭曲第 11 张不触发、第 12 张触发并 +2 力量；缠绕回合结束掉血；
召唤 2 只且不超上限；逃跑后不再参战；暗影两回合后半血复活），全部通过（共 107 项）。

## 〇·十二、第五轮：全部 65 只原版怪物补齐（2026-10-16）

按「用现成的」的思路做到底：**65 只原版怪物全部有了招式表**。

### 做法

- 新增生成器 `tools/inspect/GenMonsters.java`：把 Bestiary 的 AI 描述解析成引擎脚本，
  直接产出 `engine/GeneratedMonsterData.java`（116 条脚本，含 A2 / A7 / A17 分档）。
  能自动处理：起手固定、百分比与 N-M-K 权重分配、交替循环、每招连续使用上限、
  禁止起手、以及「两招之间随机起手后交替」。
- 解析不可靠的（多阶段、召唤、逃跑、条件分支）**不猜**，打印 MANUAL 清单，
  由手写的 `engine/MonsterManual.java` 覆盖（20 只，含全部首领）。
- `MonsterAi` 支持**按进阶等级分档**：模拟根带上 `ascension`（实机捕捉时读
  `AbstractDungeon.ascensionLevel`），取 `minAsc <= 进阶` 的最高一档，
  所以 A2 / A7 / A17 的伤害与 AI 变化都会生效。
- 新增群体与状态效果：全队加力量、给伤得最重的队友治疗 / 加格挡、自身叠尖刺。

### 覆盖情况

| 范围 | 状态 |
| --- | --- |
| 第一幕 | 全部手写（含 3 精英 3 首领），数值与字节码核对 |
| 第二幕 | 常见怪手写；其余生成表覆盖（掠夺者 / 硬壳寄生体 / 刺书 / 地精首领 / 三首领） |
| 第三幕 | 生成表覆盖；巨口 / 暗影 / 蠕动之躯 / 匕首 / 自爆者手写 |
| 终局 | 时间吞噬者 / 觉醒者 / 唐与迪卡 / 尖塔盾与矛 / 腐化之心（部分阶段近似） |

手写部分里**明确近似**的点：召唤与复活（收藏家 / 铜制机械人 / 爬行者）、逃跑（掠夺者）、
半血复活与阶段切换（觉醒者 / 暗影）。这些都在代码里标了 `approx` 并在 `MonsterManual`
头部说明。

### 怪物防御性能力（本轮一并补上）

| 能力 | 语义 | 涉及怪物 |
| --- | --- | --- |
| 神器 | 每层挡掉一次减益 | 球状守护者 3、铜制机械人 3、尖塔盾/矛 1 |
| 可塑 | 受到伤害后获得等量格挡 | 蛇草 3 |
| 板甲 | 怪物回合开始获得格挡，受攻击伤害掉一层 | 硬壳寄生体 14 |
| 无形 | 受到的伤害降为 1 | 涅墨西斯（隔回合） |
| 无敌 | 每回合最多承受固定伤害 | 腐化之心 300 |
| 死亡节拍 | 玩家每打出一张牌受到伤害 | 腐化之心 1 |

这些能力既从实机 power 读取（LiveCapture），也用 Bestiary 的固有值兜底；
自检新增 12 条断言（含「痛击的易伤被神器挡掉且神器少一层」「可塑挨打获得 3 格挡」
「板甲被打掉一层」「无形把 6 点伤害降为 1」「死亡节拍每张牌扣 1 血」）。

自检：新增「Bestiary 收录的 65 只全部建模」断言与铜制机械人循环、匕首、勇士嘲讽、
腐化之心起手等行为断言，连同原有卡牌表 / 怪物 / 搜索 / 差分重放全部通过。

## 〇·十一、第四轮：照现成的开源数据补齐第二幕（2026-10-16）

按你的建议，不再依赖真实记录，改用现成的开源成果补齐怪物：

### 数据来源

**Bestiary**（<https://github.com/casey-c/Bestiary>，MIT，作者 Casey C）的
`Bestiary/monsters.json`：覆盖全部 65 只原版怪物、区分进阶等级，每只给出招式集合、
每招效果，以及一段精确的 AI 描述（概率权重、每招连续使用上限、起手限制）。
它的描述与我之前从字节码读到的分支完全吻合（例如鸟是「飞行时 50-20-30，
啄击不能连续三次，俯冲与鸣叫不能连续两次」），可以放心作为选择规则的依据。

新增工具 `tools/inspect/BestiaryFacts.java`：把这份 JSON 转成紧凑的「怪物事实单」，
一次列出若干只怪的 HP / AI 描述 / 招式与效果，便于照单实现。
署名与许可原文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 引擎能力升级

`MonsterAi` 新增：按权重随机 + **每招连续使用上限** + 禁止起手，以及群体效果
（全队加力量、治疗 / 加格挡给伤得最重的队友）、飞行层数与击落状态机。

### 本轮补齐的怪物

| 怪物 | 实现要点 |
| --- | --- |
| 大颚虫 | 修正为「起手啃咬，之后 45-30-25；猛击不连续三次、咆哮与啃咬不连续两次」 |
| 鸟 | 飞行时 50-20-30；被打掉 3 层飞行后落地 → 失衡 → 头撞 → 重新起飞 |
| 被选者 | 戳刺 → 咒术 → 「衰弱/汲取 50-50」与「电击/戳刺 40-60」交替 |
| 蛇草 | 65% 三连咬（最多两次连续）/ 35% 孢子（不能连续两次） |
| 球状守护者 | 启动（+25 格挡）→ 攻击+残废 → 猛击 / 硬化 交替 |
| 百夫长 | 秘术师活着时 65% 掩护队友 / 35% 斩击；秘术师死后掩护变狂怒 |
| 秘术师 | 队伍缺血 ≥ 16 就治疗（最多连续两次），否则 60% 攻击+残废 / 40% 鼓舞全队 |

自检新增 12 条针对性断言（起手顺序、连用上限、双人组合的存活判定、飞行击落流程），
连同原有的卡牌表 / 怪物 / 搜索 / 差分重放全部通过。

新 jar 已部署（`mods/CombatSolver1.jar`），旧版备份为 `CombatSolver1.jar.bak-时间戳`。

下一步：第二幕剩余（硬壳寄生体、掠夺者、刺书、地精首领、三首领）与第三幕
（暗影 / 尖塔哨兵 / 巨口 / 涅墨西斯 / 时间吞噬者 / 觉醒者 / 唐与迪卡 / 腐化之心），
同样照 Bestiary + 字节码核对补齐。

## 〇·十、第三轮：按真实对局日志修问题（2026-10-16）

你跑了一局，我读了实机记录 `~/.combatsolver1/combat-records.tsv` 与 Mod 日志
（`sendToDevs/mts_process_launch.log`），共定位到四类真问题，已全部修复。

### 1. 卡牌「打出即消耗」标记错了 8 张（最关键）

日志里反复出现 `deploy aborted: 能量不足：痛击 需要 2，只剩 1`——规划出的路线能量不够。
根因是**哨卫被误标成“打出即消耗”**：它写的是「如果这张牌被消耗，获得 [R][R]」，
并不会自己消耗，模拟却按“打出就消耗”给了 2 点能量，于是规划出了实机做不到的路线。

顺手用游戏自带的 `localization/zhs/cards.json` 把整张表对了一遍，一共 8 张错：

| 修正 | 卡牌 |
| --- | --- |
| 去掉误标的“打出即消耗” | 燃烧契约、破灭、哨卫 |
| 补上漏标的“打出即消耗” | 缴械、恶魔之焰、地狱之刃、突破极限、战吼 |

自检工具新增 `Solvertest gamedata <cards.json>` 子命令，用游戏原文对账「打出即消耗」；
并加了哨卫的回归断言（打出只扣自己 1 费、进弃牌堆、不进消耗堆）。

### 2. 执行器只认牌名，引擎给的却是截断名

日志里 `deploy index mismatch: plan Shrug vs hand[1]=Shrug It Off`、
`计划里的牌取不到：[MISSING_TITLE]（下标 1）`——引擎（sts_lightspeed）回给计划的是
截断英文名或占位符，执行器却拿它当唯一依据，于是一整回合直接判失效。
现在：牌名不可信（查不到这个 id）时按**位置**取牌，位置才是引擎的权威信息。

### 3. “本回合内刚抽到的牌”被判成取不到

计划常常包含「先打过牌、再打刚抽到的那张」，但执行器在实机还没抽到时就直接判失效。
现在会先等这一段动作结算完、再对着实机手牌重新解析（最多等 8 次），
只有确实等不到才判失效。

### 4. 校验反复失败却仍然硬用计划（空转 + 打一半失效）

日志里同一状态连搜 3 次、然后 `validation kept failing; using current plan as-is`，
接着就是一串 `deploy aborted`。现在：
- 校验失败会打印具体原因（状态签名 / 手牌对不上），下次日志能直接定位；
- 计划与实机手牌确实对不上时，本回合**停止自动执行**（而不是打到一半才失效），
  状态一变（出牌 / 抽牌 / 换回合 / 手动点执行 / 重算）就自动恢复；
- 反复搜索也随之中止。

### 5. 顺带确认（不是 bug）

- `CardGroup.getTopCard()` 从字节码里确认是 `group.get(size-1)`——也就是我上一轮改的
  “从列表末尾抽牌”是对的，`getBottomCard()` 才是 index 0。
- 抽牌堆顺序、怪物力量 / 易伤 / 虚弱从实机 power 读取，都已生效。

### 6. 新增开发工具（为“照游戏本体补齐怪物”准备）

- `tools/inspect/DumpBytecode`：打印某方法的字节码。
- `tools/inspect/TraceMethod`：按顺序打印某方法的常量与调用轨迹（含 `tableswitch` /
  `lookupswitch` 解码），用它读 `getMove` / `takeTurn` 就能还原怪物的出招分支。
  已用它读出被选者、鸟等第二幕怪物的完整分支结构。

本轮自检：卡牌表 / 怪物 / 搜索 / 差分重放全部通过，游戏原文对账 0 不一致。

## 〇·九、本轮：差分验证脚手架 + 模拟保真度收敛（2026-10-16）

按上一轮定下的优先级，把「最该先补的两件事」落地：差分验证脚手架与模拟保真度收敛。

### 1. 差分验证脚手架（已提交进仓库，CI 全绿）

- **实机记录器**（`runtime/CombatRecorder`）：每次出牌与回合切换都写一条 JSONL
  （战斗根快照 + 这张牌 + 出牌前状态 + 出牌后实际状态），落在
  `~/.combatsolver1/recordings/combat-<时间戳>.jsonl`，不再是一次性的 TSV。
- **离线自检工具**（`tools/solvertest`，纯 Java，不依赖游戏本体）：`check`（卡牌数据表）、
  `pattern`（怪物行动）、`search`（整场搜索）、`diff <记录>`（差分重放）、`summary`、
  `all`；一条命令编译 + 运行：`tools/solvertest/run.ps1`。
- **差分重放**：把记录里的「前状态」喂给模拟器重放同一张牌 / 同一个结束回合，逐字段比对
  生命 / 格挡 / 能量 / 手牌（多集）/ 抽牌堆 / 弃牌堆 / 消耗堆数量 / 每个怪物的血量格挡易伤；
  记录里没写目标下标时，自动对每个敌人试一遍取最吻合的目标，避免把“打错目标”误报成模拟错误。
- **CI**（`.github/workflows/ci.yml`）：每次提交跑数据表 / 怪物 / 搜索三组断言 + fixtures 差分重放。
- **游戏数据核对工具**（`tools/inspect/ExtractMonsterFacts`）：直接读 `desktop-1.0.jar`
  的类文件，导出每个怪物的真实 `ID` 与静态数值（血量、招式伤害），本轮所有怪物数值都以此为准。

### 2. 模拟保真度收敛

- **怪物逐招式建模**（新 `engine/MonsterAi`）：第一幕全部遭遇怪物 + 红蓝奴隶主，共 24 个。
  含招式伤害 / 段数 / 自身格挡 / 自身力量 / 玩家减益 / 塞状态牌，以及机制：仪式（邪教徒每回合 +3 力量）、
  睡眠与被打醒（拉格威林）、狂怒（大块头，玩家打技能就 +2 力量）、蓄力（地精巫师三回合后 25 点爆裂）、
  模式切换（守护者累计被击 30 点进入防御模式：+20 格挡、尖刺 3）、死亡触发（真菌兽孢子云给 2 易伤）。
- **卡牌精确化**：双持 / 头槌 / 掘墓 / 燃烧契约 / 军备改为按价值选最优目标；活动肌肉改成真正的临时力量；
  斗殴 / 回旋踢的可打性判定接入；狂野打击 / 鲁莽冲锋洗入抽牌堆；愤怒复制自身；完美打击按“打击”牌名统计；
  地狱之刃生成随机攻击牌；血怒按本场掉血次数计价。仍标近似的只剩 2 张（灼热打击、献祭的细节）。
- **牌堆语义对齐游戏**：抽牌改为从牌堆顶（列表末尾）取，不再随机取下标；洗牌改成整堆 Fisher–Yates。
- **玩家侧减益生效**：虚弱（伤害 −25%）、残废（格挡 −25%）、缠身（本回合不能打攻击牌）、吸魂减力量敏捷。
- **遗物触发补齐**：手里剑 / 苦无 / 双节棍 / 开信刀 / 卡戎之灰。
- **搜索**：回合内可以继续打出抽到的牌（不再受初始手牌数限制）；同签名状态合并去重，宽度不再被等价状态占满。

### 3. 顺带修掉的三个真 bug

1. **怪物表用显示名做键**：`m.id` 实际是 `JawWorm` / `AcidSlime_M` / `FuzzyLouseNormal`，
   而表里写的是 `Jaw Worm` / `Acid Slime (M)` —— 绝大多数条目从未命中过，一直在退回经验平均。
   现在 MonsterAi 与 MonsterPatterns 都改用实机 ID，并有自检断言防止回退。
2. **抽牌用随机下标**：实机捕捉的抽牌堆顺序就是真实顺序，旧实现每抽一张随机挑一张，
   等价于每抽一次都重新洗牌。现在从堆顶取。
3. **浩劫取错牌堆端**：`remove(0)` 取的是牌堆底，现在取末尾（堆顶）。

### 4. 本轮自检结果

```text
卡牌数据表：75 张全登记，关键效果 15 项断言通过，近似牌 2 张
怪物行动：24 个建模怪物连续 10 招不断档；序列 / 仪式递增 / 狂怒惩罚断言通过
整场搜索：能给出路线、预测可击败、承伤在合理区间、两次搜索结果完全一致
差分重放：样例记录 3 条动作，字段匹配 24/24，0 条差异
```

实机验证建议：进一场战斗打几张牌，再把 `~/.combatsolver1/recordings/` 里最新的 JSONL
交给 `run.ps1 -Command diff`，就能看到这台机器上模拟与实机的逐字段差异清单。

## 〇·七、坐标约定修正（2026-10-16，修“按钮溢出面板 / 文字整体偏高”）

实机截图暴露出一个**长期存在的坐标约定问题**：`fillRounded/box/fillRect` 的 `y` 是**盒子
上边缘、向下延伸**（`yB = yTop - h`，libgdx y-up），而 `text()` 用的
`renderFontLeftTopAligned` 也是 **y 为文字上边缘、向下延伸**。但代码里多处按
`y + (H - lineH)/2` 算文字位置，把文字放到了盒子**上方**，导致：

- 每行/每个胶囊/每个按钮的文字都比盒子高 `(H - lineH)` 像素，行内容贴着顶部、
  底部空出一截（这正是“面板里大片空白”的成因之一）；
- 底部按钮框（尤其金色“执行本回合”）因 `by = bottom + PAD` 且框向下延伸，
  直接**溢出到面板底边之外**盖在游戏画面上（截图中那个悬浮的金色条）。

修正：
- 所有“盒子 + 文字”组合的文字偏移由 `y + (H - lineH)/2` 改为 `y - (H - lineH)/2`：
  `chip`、`button`、状态徽章、回合号与指标、行动胶囊、标题状态点；
- 行动胶囊在行内的 y 由 `rowTop + (ROUTE_ROW_H - PILL_H)/2` 改为 `- ...`；
- 底部按钮框改到 `by = bottom + PAD + BTN_H`，框变成 `[bottom+PAD, bottom+PAD+BTN_H]`，
  完整落在面板内，与上方 1px 分隔线保持 4px 间距；
- **面板高度改为始终按内容自适应**（拖拽手柄只调宽度），彻底消除残留自定义高度
  造成的空白。

空状态提示框（`renderEmptyState`）原本就用 `boxBottom + (boxH + lineH)/2` 从底边计算，
是正确的，保留不改。

## 〇·六、界面精修（2026-10-16，针对布局空白与控件重复）

在 〇·五 的基础上，针对实机反馈的问题（面板中间大片空白、底部与顶部设置重复、
路线不够突出）再做一轮精修：

- **面板高度自适应收紧**：底部不再放一整行设置 chip，footer 只剩一行按钮，
  内容区与按钮区之间加 1px 分隔线；无路线时空状态框（等待 / 搜索中 / 未找到生还路线 /
  已暂停）取代原来的空白行，面板高度随内容自动收缩，不再留大片黑框；
- **设置收纳到顶部**：预算 / 透明度 / 主题只保留标题栏右侧三个 chip，底部不再重复；
- **路线回合折叠展开**：默认显示 5 回合，超过时出现“展开全部 N 回合”按钮，
  展开后最多显示 20 回合，再点击收起；
- **回合行视觉分层**：当前回合加左边金色高亮条（加粗到 4px）+ 边框加强，
  回合号用金色；后续回合用更暗的背景与次要色；回合标签改为紧凑的“T1/T2/T3”格式；
- **行动胶囊防溢出**：胶囊内文本按剩余宽度截断加省略号，预留目标名与 ×N 的宽度，
  长卡名不再越出胶囊边界；放不下时优先保留“+N 个行动”指示器与结束/击杀胶囊；
- **主按钮层级**：执行本回合占底部按钮行 32% 宽度（其余三个均分剩余），
  金色底色 + 顶部高光，视觉上明显强于其它按钮；
- **背景不透明度保底从 0.78 提到 0.88**：面板底色更实，游戏背景对文字的干扰更小；
- **标题栏状态点**：标题右侧跟随一个状态色圆点，折叠时也能看到当前状态。
- **字符兼容性**：新增文案只用游戏字体图集里确定存在的字符（CJK / 拉丁 / → × … · —），
  不使用 ⚔ ⚡ ◉ ▲ 等可能缺字形的符号。

## 〇·五、界面重做（2026-10-16，对齐 CombatSolver 原项目）

按原项目（STS2 版）的 UI 设计语言重做了覆盖层（参考其 SolverOverlay /
SolverUiTokens / SolverRouteRow / SolverActionPill）：

- 深色半透明圆角面板 + 1px 描边 + 投影，浅色主题可切换，整体透明度可调；
- 顶部标题栏可拖动，右上角三个快捷设置（预算 / 透明度 / 主题）与折叠按钮；
- 状态徽章（搜索中 / 就绪 / 执行中 / 已暂停）+ 上下文说明 + 摘要主行
  （“预计整场扣血 X HP　剩 Y/Z”，按损失等级着色）+ 搜索进度条；
- 路线区：标题随状态变化（推荐路线 / 正在执行的路线 / 已保存路线（已过期）），
  右侧“预计用 N 瓶药”“本局扣血 X HP”；逐回合行：左侧 3px 高亮条（当前回合），
  “第 N 回合”，中部彩色行动胶囊（攻击红 / 技能蓝 / 能力黄 / 状态紫 / 药水青 /
  选牌橙 / 结束回合灰，击杀绿色），右侧“受击 / hp / 能量”三项指标；
- 底部动作条：全自动（开时高亮）、执行本回合、重新计算（搜索中变“停止搜索”）、暂停；
- 右下角拖拽手柄调整尺寸，面板位置与尺寸持久化到 settings；
- 折叠模式只剩一条标题栏（全自动 / 执行本回合 / 展开）。

技术要点：圆角矩形用“四角圆片 + 四边 + 中心”拼接实现任意尺寸；字体用
FontHelper.prepFont 生成 12/14/15/16 号中文字体；同类型连续动作合并成 ×N。

## 〇、本轮实机测试修掉的问题（2026-10-16）

用户反馈："查看日志还是没有把能量花完。策略也看不懂，甚至没有搜索路线。"
读日志定位到四个真实原因，全部已修并在实机验证：

1. **第二张同名牌打不出去（能量花不完的直接原因）**
   引擎给出的手牌下标是"动作发生那一刻的下标"：每打出一张牌，后面的下标都会前移。
   旧执行器按"回合开始时的下标"取牌，于是第二张打击取到已经被用掉的槽位，
   日志里就是 `deploy skip (gone): Strike`，回合结束还剩 1 点能量。
   现在执行器维护一份"虚拟手牌"，逐步消费下标，与引擎语义完全一致。
   实测：痛击(下标4)+打击(下标0) → 能量 3/3 → 0/3，两张牌都打出。

2. **面板看不懂 / 没有路线**
   面板之前显示引擎计划时用的是英文牌名（Strike），回合号全是 0、每回合血量是 0；
   引擎计划用完后 `currentPlan()` 仍返回空计划，面板就变成"没有可执行的路线"。
   现在：ID → 中文名（游戏语言包 + 内置对照表）、每回合结束血量、
   "本回合/T2/T3" 逐回合路线、路线来源行（精确引擎 / 内置求解器）、动作日志计数。
   引擎计划用尽会自动回退到内置求解器路线。

3. **引擎计划只在"真正的战斗存档"上有效**
   像"?"节点现场变成战斗这种场合，自动存档里记的还是事件房、没有怪物，
   引擎会直接拒绝（"Only save files from the start of combat are supported right now."）。
   现在战斗开始时会把存档 JSON 里的 `current_room` 补成实机房间类型，
   引擎用同一套 RNG 重新生成这场遭遇 —— 实测大颚虫 46/46 与实机完全一致，
   面板直接给出"可击败（预计 3 回合）　预计承伤 12　剩余 68/80"。
   顺带修了上游引擎 `SaveFile::writeJsonToSaveFile` 漏掉 xor 加密、写出的存档读不回来的 bug。

4. **状态核对时机（引擎路线动不动就"失真"）**
   回合切换那一瞬间手牌还没抽、动作还在跑，旧的核对逻辑会把这种中间态当成"偏差"，
   于是引擎路线被丢弃、退回近似求解器。现在核对分成两步：
   回合开始时只登记待核对状态，等到"稳定帧"（玩家可操作、动作队列安静、没有界面）
   再逐项比对；部署进行中不核对；引擎结果晚到且日志已经继续增长时按"过期"丢弃重算。

## 历史快照：以下旧总结 / 支持表 / 待办均已过时

> 以下内容保留作开发历史，不代表当前功能覆盖或当前剩余工作。请以本文件开头的“当前验证状态”及仓库 README 当前支持矩阵为准。

### 早期架构总览（非当前支持情况）

主线程捕获稳定战斗根 → 独立分支上做整场 Beam Search → 只读路线快照 → 面板展示 →
只有玩家选择执行时才用游戏原生入口部署“当前回合”。

```text
engine/    纯模拟：SimState（可 fork）、CardLibrary（铁甲全卡表）、CombatEngine（结算规则）、
           MonsterPatterns（行动节奏表）、PotionLibrary（药水效果表）
search/    CombatSearch（可中断、增量推进的整场 Beam Search）、Plan（只读路线）
runtime/   LiveCapture（实机 → 模拟根）、PlanDeployer（部署当前回合）、CombatController、
           EnemyModel（怪物节奏的实战自我校正）
ui/        OverlayRenderer（可拖动窗口）、PanelModel
settings/  SolverSettings（搜索预算 / 透明度 / 主题，持久化到 ~/.combatsolver1）
```

### 早期功能快照（历史）

| 维度 | 状态 |
| --- | --- |
| 铁甲红牌 | 75 / 75（约十余张含已记录的内部简化，见下） |
| 能力与联动 | 金属化、无痛、黑暗拥抱、恶魔形态、肉钩、燃烧、破裂、荆棘、双发、愤怒、狂暴、暴行、壁垒、腐化；虚灵、X 费、消耗联动、牌库操作（复制/取回/生成/塞状态牌）、牌实例成长 |
| 药水 | 11 种可评估可执行（火焰/爆炸/格挡/力量/虚弱/恐惧/鲜血/再生/能量/迅捷/液态青铜） |
| 遗物 | 显式 8 件（水银沙漏、山铜护符、羊角扣、船长轮盘、青铜鳞片、冰淇淋、钢笔尖、红骷髅）；另一批（瓦杰拉、长颈瓶、锚、针与线、灯笼、奴隶主项圈、背包）由实机数值隐式包含 |
| 怪物 | 约 40 种行动节奏表 + 实战自我校正（观察 4 个回合后改用真实周期） |
| 使用方式 | 仅查看 / 执行本回合（不结束回合）/ 全自动（自动结束回合）；随时停止与重新计算 |
| 界面 | 可拖动窗口、深/浅主题、透明度、逐回合路线、敌方意图（含多段）、药水、牌组覆盖度 |
| 搜索预算 | 低 / 中 / 高 / 极高 / 自定义，持久化 |
| 路线复用 | 实机状态与预测一致时沿用既有路线 |

### 早期底层修复记录

1. **打出的牌在模拟里会消失**：牌库循环曾完全错误，现已按“消耗 or 弃牌堆”正确回流。
2. **能量读错字段**：曾一直读 `player.energy.energy`（不随出牌扣减），现改为 `EnergyPanel`。
3. **结束回合会清空出牌队列**：曾导致最后一张牌被吞，现整回合一次性入队 + 连续空闲帧判定。
4. **计划过期不重算**：玩家手动出牌后计划不刷新，现每帧比对状态签名。
5. **载入期间抢跑**：战斗载入/切换瞬间点击执行会丢牌，现要求连续空闲帧才动手。

### 早期已知简化清单（历史）

- 选牌类：浩劫 / 双持 / 头槌 / 掘墓 / 燃烧契约 取“第一张”而非最优；
- 数值类：军备升级按 +3 伤害 / +2 格挡；地狱之刃生成打击而非随机攻击牌；血怒费用公式；
- 时点类：收割回血在格挡结算之后；虚灵在回合结束时消耗（与游戏一致）。

### 早期剩余工作清单（历史，勿视为当前路线图）

### 1. 差分验证脚手架 —— 已完成

见本轮「〇·九」。记录器、离线比对器、CI 都已进仓库；后续每处精度提升都用
`run.ps1 -Command diff <实机记录>` 验证。

### 2. 怪物完整 AI —— 第一幕已完成，第二幕起待补

第一幕全部遭遇怪物已逐招式建模并通过自检。剩余：
- 第二幕之后仍用节奏近似；建议按“最常见的 10 种怪”逐只建模（鸟类 / 被选者 / 球状守护者 /
  蛇草 / 刺书 / 百夫长与秘术师 / 蛇怪 / 守护者三人组……），每只配一条差分记录。
- 召唤类（地精首领 / 收藏家 / 爬行者）与分裂类（史莱姆首领）需要新增"生成新怪物"的模拟能力。

### 3. 其他缺口

- 药水执行路径尚未实机验证（用 `AbstractPotion.use` + `removePotion`）；
- 药水目标取“第一个存活敌人”，未做最优目标；
- 未建模的药水与遗物按忽略处理（不会误算，只是不参与规划）。
- 守护者 / 六火亡魂 / 史莱姆首领的内部细节仍是近似（已在招式表里标 approx）；
- 灼热打击的升级成长、少数能力牌的升级幅度按通用数值近似。

## 六、构建与安装

源码在 `outputs/combatsolver1/`，用 JDK 8+ 编译（本机可用 IntelliJ 自带 JBR 的 javac）：

```powershell
javac --release 8 -encoding UTF-8 -cp "<游戏>/desktop-1.0.jar;<MTS>/ModTheSpire.jar;<BaseMod>/BaseMod.jar" `
  -d <classes> (Get-ChildItem src/main/java -Recurse -Filter *.java).FullName
```

打包后把 jar 与 `ModTheSpire.json` 一起放进游戏 `mods/` 目录即可。
离线自检入口见 `work/solvertest/`（IroncladCheck / PatternCheck / SearchCheck）。

## 2026-10-18：修复真实 bundle 的结束回合边界

- 配对样本 `battle-20261018-054109-273-e6990591-c40b-4854-a3e6-4d0822290532` 的第 7、13、18 行断链均只涉及 `hand` 与 `discardPile`：上一动作 post 保留 4、3、1 张手牌，而旧 endTurn.pre 已将其移入弃牌堆；endTurn.post 则包含新回合抽牌及结算状态。原始 bundle 未改写，哈希保持原值。
- 游戏字节码确认玩家按钮通过动作队列发起结束回合；`AbstractRoom.endTurn()` 先触发回合结束效果，再排入清牌/弃牌动作。记录器现在由该方法入口的前缀补丁捕获旧回合 pre；既有双稳定帧追踪继续把原生弃牌、消耗及新回合开始完整放进 endTurn.post。移除基于 `turnHasEnded` 空闲帧检测的延迟采样。
- 离线回归覆盖最后一张牌后立即结束、多张保留牌与普通牌/虚灵清理、跨回合抽弃牌、连续回合和 root → card → endTurn → card 顺序。
- 验证：`CombatRecorderTransitionCheck` 编译并运行退出 0；`tools/build/build-release.ps1` 完整 Mod Java 8 构建退出 0，字节码 major=52、85 个 JAR 条目有效，SHA-256 `15B0F6DC0DA0FEDB5A5FBB0663D587A989509BA8D950313D33F7AC867D464173`。原真实样本严格检查仍按预期在第 7、13、18 行失败，证明历史证据保留；未运行游戏，尚无新实机记录，证据等级 L2，不宣称 L4。
- 用户已授权本轮源码提交并推送；待提交。用户尚未验收。

## 2026-10-18：前移结束回合边界到原生队列入口

- 新 bundle `battle-20261018-062233-943-f6adba14-d616-4c37-ba3f-227bb14d046c` 的第 19 行相对前一张牌 post 差异为 Burn 从手牌移入弃牌堆、格挡 13→11；第 23 行为 Burn 移入弃牌堆、Metallicize 使格挡 8→11、Burn 消耗 2 格挡后为 9，并由 Juggernaut 对 Hexaghost 造成 5 伤害（141→136）。相应原生证据来自游戏 JAR：`GameActionManager.getNextAction` 在 null-card end-turn sentinel 处分派 `callEndOfTurnActions`；该方法先触发 end-turn relic/pre-card powers，再调用手牌的 `triggerOnEndOfTurnForPlayingCard`。因此 `AbstractRoom.endTurn()` 前缀采样已经太迟。
- 最小修复：删除 `AbstractRoomEndTurnRecorderPatch`，新增 `GameActionManagerEndTurnRecorderPatch`，在 `callEndOfTurnActions` 前缀捕获 pre；原生 Burn、Metallicize、Juggernaut、弃牌和下一回合变化继续保留在 endTurn.post。未修改战斗规则、搜索、UI 或 bundle 身份机制。
- 回归先在旧目标上失败（断言钩子必须位于 `GameActionManager.callEndOfTurnActions`），迁移后通过。测试反射确认游戏方法和 ModTheSpire prefix 注解，并按原生顺序执行 Burn-only（block 13→11）和 Metallicize/Juggernaut/Burn（block 8→9、怪物 HP 141→136）状态边界，另覆盖立即结束、保留手牌、弃牌/抽牌及连续回合。命令：Java 8 `javac --release 8 ... CombatRecorderTransitionCheck.java`，然后 `java -cp <test-classes;desktop-1.0.jar;ModTheSpire.jar;BaseMod.jar> com.combatsolver1.runtime.CombatRecorderTransitionCheck`，退出 0。
- 真实旧 bundle 严格完整性检查仍只报第 19、23 行，退出 1（预期保留诊断失败）；文件及 manifest 未改写。最后记录时 boss 仍存活，manifest 为 open、JSONL/journal 哈希陈旧，表示 bundle 在未封存时被检查；缺少游戏日志，无法判断稍后是否执行 `receivePostBattle`，不归因于结束回调异常。
- 源码提交 `2859cbaba0cacfa6b89660faaf8ee5a858d09e8a` 已推送 `main`。状态记录随后更新。完整 Mod Java 8 构建尚待源码状态记录完成后最终复跑。当前证据为 L2，不宣称新实机连续性或外部引擎 L4。用户尚未验收。

## 2026-10-18：本地计划目标索引与原生槽位对齐

- 问题：`LiveCapture` 只保留存活怪物，本地 `Plan.Action.targetIndex` 因而是压缩索引；旧 `PlanDeployer` 把它直接用于原生完整槽位。`[活 A、死 B、活 C]` 的本地目标 1 会指向死 B，修复前 `ExecutionSafetyCheck` 对该案例退出 1。
- 修改：部署时按明确的计划来源保存目标映射。本地路线在验证根状态后捕获存活怪物对象顺序，以对象身份解析后续出牌和药水目标；外部引擎仍按固定槽位解析。目标死亡、逃跑、移除、来源不明或根状态无法配对时拒绝执行，沿用 `FailureLatch` 阻断。药水 journal 写入实际原生固定槽位，路线显示使用同一来源语义；本地未来回合的无法确认目标名不猜测。
- 验证：`ExecutionSafetyCheck` 修复后 10 组通过、退出 0，覆盖死怪压缩、多怪、移除、逃跑、同名、药水固定槽位和引擎原语义；`tools/solvertest/run.ps1 -Command all` 退出 0；`tools/build/build-release.ps1 -Deploy` Java 8 构建与部署退出 0。证据 L2；未启动游戏验证 G/H 原生队列目标，未达到 L3/L4。提交及最终 JAR 哈希见本轮交付。用户尚未验收。

## 2026-10-18：引擎配置保留与战斗开局药水空槽误报

- 实机启动日志显示未配置引擎；实际 `~/.combatsolver1/settings.properties` 缺少 `engine.path`，而已存在的引擎 EXE 运行 `protocol` 返回 v2、退出 0。`SolverSettings.save()` 原先重建属性表，界面保存会删除引擎键；修复前 `SettingsPersistenceCheck` 失败，修复后保留引擎及其他非界面键。已备份并恢复本机配置，原有界面设置保留。
- 真实归档 `battle-20261018-171705-652-d0d15e27-95fc-48dc-91af-dedee44d8f53` 在动作数 0 时已因“药水栏”标脏，`journalError` 为空。原因是开局未到玩家可行动阶段就建立药水快照，且把原生 `PotionSlot` 空槽对象当作药水；修复前 `PotionJournalSnapshotCheck` 失败。现在首个玩家可行动帧才建立基线，空槽变化不计作药水消耗，真实药水对象消失仍保持标脏。
- 其他历史归档的 `journalError` 有两次 Windows 文件占用异常（`actions.journal.tmp` 替换失败）；`EngineBridge.writeJournal` 会记录底层原因，`BattleArtifacts` 也会把异常写入 manifest。未改写任何历史 bundle。离线测试与构建结果见本轮交付；游戏未重启验证本次配置及记录器变更，证据 L2，用户尚未验收。

## 2026-10-18：C++ 连续回放首个 1 HP 漂移边界与 journal 替换锁

- 真实样本 `battle-20261018-174030-825-a05ceb11-08ef-40fa-82ed-6d9029360f0d` 位于 `%USERPROFILE%\.combatsolver1\recordings\`。`combat.jsonl`、`actions.journal`、`engine/engine-in.save` 的 SHA256 分别为 `bca76116dc5cb708bba750a88d2e57a7bc1c2e7c57ea181981180120b9658155`、`5023067ae55904ecef73632c2f07c29baf5715fc758cfda0502851e9cd9eae8a`、`7dc5bed02a031a74cc37c4c39e7b5e63209e118fb3a0a68dc6c10ed14490faca`，与 manifest 一致；但 `journalDirty=true`（致胜动作无稳定 post），JSONL 也缺第二个 endTurn post，故只作为受限前缀证据。
- 单一 `engine-in.save` 连续执行 journal 前 9 项：`build/engine/combatsolver1-engine.exe replay_trace <engine-in.save> build/louse-prefix-9.journal 9`，退出 0、`REPLAY_OK n=9`。根状态至第二回合前三张牌摘要一致；卡牌后的记录采样包含结束回合清牌，而 TRACE8 是清理前即时阶段，不能逐字段直比。动作 9 endTurn 后引擎 turn=2（映射实机 turn=3），HP=52；实机第三回合首个可靠 pre 为 HP=51。格挡、能量、手牌、抽/弃/消耗数量及两只虱虫 HP 摘要相同。玩家/怪物详细状态、逐击敌伤、回合中间 HP 与 RNG 后态没有足够的 post-turn 输出；因此已定位首个可比边界，但无法判定 1 HP 来自敌方伤害结算、随机流/动作阶段或记录采样。Burning Blood 相同且不能解释此项。未改战斗规则或放宽 drift 检查。
- journal 根因在 `BattleArtifacts.replaceJournal`：原实现持有 `actions.journal.tmp` 的 Writer 时就调用 move。新离线故障注入由独立 Windows 进程对目标 journal 持有不允许删除的共享句柄，修复前得到 `AccessDeniedException: actions.journal.tmp -> actions.journal`；修复将写入流关闭后再替换，并对仍存在源/目标文件的短暂 `FileSystemException` 最多重试 12 次、间隔 25ms。持续锁定仍返回失败并持久记录异常，不静默清除 dirty。另修复 `CombatController` 的失败调用点：替换失败后标记 ActionJournal dirty 并停止引擎回放。未能从历史异常确定占用者身份。
- 修复前回归以 HEAD 版本源码编译，锁占用触发 `AccessDeniedException` 并令“预期成功”用例失败；修复后短暂锁用例输出 `JOURNAL_REPLACE_OK`，持续锁用例输出 `JOURNAL_REPLACE_FAILED_DIRTY`，并检查 manifest 同时保留 dirty reason 与底层 IOException。`BattleArtifactsCheck`、`ExecutionSafetyCheck`（10 组）和 `tools/solvertest/run.ps1 -Command all` 退出 0。完整 Java 8 构建/部署及 JAR 哈希见本轮报告。证据 L2；未启动游戏，未取得新的真实回放，L3/L4 均未完成。历史 bundle 未改写，用户尚未验收。

## 2026-10-18：journal 脏状态的异步回放门控与回合诊断采样

- 审计确认 `CombatController.maybeRequestReplayEnginePlan()` 在 `EngineBridge.writeJournal()` 失败时只标脏；未采纳 `pendingEngineFetch` 可留存，且异步 worker 仍可能之后回填。新增 `ExecutionSafety.canAdoptReplayFetch`，引擎回放结果只有在 journal 仍可用且动作数匹配时才能采纳；写失败时丢弃已有 pending，迟到结果由采纳门控拒绝。已经采纳的 `enginePlan`、`planValidated` 和签名不被清除，仍受现有当前状态签名及 drift 检查约束。修复前 `ExecutionSafetyCheck` 新用例失败“pending engine replay based on a dirty journal was adopted”；修复后该检查 11 组通过。
- 增加只读回合诊断，默认关闭；在 `~/.combatsolver1/settings.properties` 设置 `diagnostics.turnBoundary=true`，或设置 JVM 属性 `-Dcombatsolver1.turnDiagnostics=true` / 环境变量 `COMBATSOLVER1_TURN_DIAGNOSTICS=true` 启用。日志前缀 `TURN_DIAG`，单次 endTurn→新回合稳定可行动阶段上限 160 行。采样包括原生结束回合入口、回合结束触发器刚入队（明确标注尚未结算）、`GameActionManager.update` 后发生的状态变化、DamageAction/LoseHPAction 的来源/目标/数值及目标 HP/block 前后值、新回合稳定状态。状态摘要含玩家和怪物 HP、格挡、力量、易伤、虚弱，怪物意图/意图伤害，以及手牌/抽牌堆/弃牌堆/消耗堆顺序。历史 bundle 未补字段或改写。
- 验证命令：`BattleArtifactsCheck` 退出 0；`ExecutionSafetyCheck` 修复前退出 1、修复后 `passed=11 failed=0`；`tools/solvertest/run.ps1 -Command all` 退出 0；journal 锁 transient/persistent 故障注入均通过。`tools/build/build-release.ps1 -Deploy` 在代码提交 `18f1451` 上退出 0，字节码 major=52、99 个 JAR 条目通过，构建与部署 SHA256 为 `3E59BF7D88D6442DEE79BD056AF7DDCB0CAD0B17388779E1F62F583EC59A0AB4`；GitHub main 已推送至同一提交。证据为 L2；未启动游戏，诊断采样和 C++/实机 HP 来源尚无 L3/L4 证据。用户尚未验收。

## 2026-10-18：三哨卫真实 bundle 的回放拒绝与自动 Curse 记录

- 样本 `battle-20261018-184303-365-2aecdaee-33b3-40ab-a927-75e3538a9b4a` 与 `sendToDevs/mts_process_launch.log` 通过时间、MonsterRoomElite、三只 Sentry、根状态和 battleId 对应。bundle closed/victory，journal 有 34 个动作、`journalDirty=true` 仅因致胜 Strike_R 无稳定 post；四份 combat/journal/raw-save/engine-save 哈希均匹配 manifest。JSONL 为 1 root、22 card、11 endTurn；row 5、24 的链断分别由 Doubt 行把 Weak/自动弃牌结算误记为 card post 导致。
- 完整进程日志有 1 次 `engine fetch stale: journal 2 -> 3`，属于异步结果被较新动作数淘汰；有 25 次 `replayInvalid`，分布于实机 turn 2–12，均可由同一引擎起点复现为 journal action 3 `bits=1` 被拒绝。root 只有 Burning Blood、没有 Blue Candle；TURN_DIAG 显示回合结束队列内的 UseCardAction 对 Doubt 施加 Weak 并将其移出手牌。该自动 Curse 不是玩家出牌。没有 `journal dirty`、journal 替换失败或 engine state mismatch 日志。
- 原 journal 的 `replay_trace` 前 2 动作通过，第 3 动作返回 `REPLAY_INVALID n=3 bits=1`（进程退出码 0 只是协议正常输出拒绝标记）。仅作根因诊断的临时动作流用 Bash、Strike_R、原第 4 项 endTurn 重放，得到 game turn 2 映射 engine turn 1；HP 47、block 0、energy 3、手牌顺序、抽/弃/消耗数量及怪物 HP 摘要与 TURN_DIAG/JSONL 匹配（10 组摘要字段）。它不是原始 journal 的严格通过结果。TURN_DIAG 共 10 个 DamageAction、0 个 LoseHPAction、11 个新回合稳定样本；原生 HP 链为 turn1 57→47、turn2 两次 10 伤害被 30 格挡吸收、turn3–8 分别损失 5/5/10/5/5/5、turn9 无伤、turn10 损失 5、turn11 无伤，turn12 胜利。后续伤害无法与 C++ 同根连续重放比较，因为 journal 在 action 3 已拒绝，且结束战斗前的最后一击无稳定 post。
- 最小修复 `ccac2794c7f246c5f7e82e7f57b1027e67c668f0` 在原生出牌记录入口跳过无 Blue Candle 的 Curse `useCard` 事件；有 Blue Candle 的 Curse 与普通卡牌仍记录。回归 `CardActionRecordingPolicyCheck` 6 项通过。`BattleArtifactsCheck`、`ExecutionSafetyCheck`（11）、`CombatRecorderTransitionCheck`、`TargetReferenceCheck`、`PotionJournalSnapshotCheck`、`SettingsPersistenceCheck`、`FreshAutosaveCheck`、`TerminalReplayCheckTest` 均通过；`tools/solvertest/run.ps1 -Command all` 通过。`tools/patchcheck/run.ps1` 用 MTS 3.30.3 完成 5 个补丁发现与注入。Java 8 构建/部署 major=52，构建与游戏 mods JAR SHA256 均为 `CE1D47AA45D1C404F09351597051AE8D3F03610C9355D4BE70CAFF64F541F175`。证据 L2；本轮未重新启动游戏或采集新 bundle，L3/L4 未通过。GitHub 推送待确认，用户尚未验收。

## 2026-10-18：核心能力差异审计与开发路线

- 只读审计基线 `ae4711e7df38023b25ed5b33e3f347b71dbe6b2f`；参考 CombatSolver 源码固定在 `5c773caaec742bc55588bc9ee21c361326a732f0`。报告为 `docs/CORE_CAPABILITY_AUDIT.md`。连接器读取参考仓库的真实源码目录与关键实现；shell 直连 GitHub 被网络阻断。报告逐模块对照状态捕获、模拟、搜索、UI、执行及 replay，明确 sts_lightspeed、Java fallback 与 StS2 参考项目的来源和证据边界。
- 覆盖统计只使用可复核的局部基准：离线检查中 75 个 Ironclad ID 登记断言、65 个 Bestiary 核心怪物识别/招式生成检查、PotionLibrary 20 个当前登记 ID；这些不是精确机制覆盖率。四职业、完整遗物/药水全集无仓库内可信分母，报告不编造百分比。优先顺序为同根回放证据闭环、Ironclad 机制覆盖与差分基准、原生选牌协议执行。
- 验证：`tools/solvertest/run.ps1 -Command all` 退出码 0；该工具同时报告历史 corpus 70 个动作中 48 个有差异（668/793 个字段匹配），只作为已有诊断样本，不等同当前缺陷率或严格完整 L4。`git diff --check` 退出 0。未构建/部署 JAR（仅文档）。L2 仅指现有离线检查；报告中的 L3/L4 均限于 STATUS 所记具体历史战斗，L5 未建立。文档提交已生成；推送尝试因无法连接 `github.com:443` 失败，检查时远端 `main` 仍为 `ae4711e7df38023b25ed5b33e3f347b71dbe6b2f`。

## 2026-10-18：最小同根回放差分闭环

- 新增 `tools/recordercheck/run-root-replay-diff.ps1` 与说明。工具不改 bundle：检查 manifest 三份文件 SHA-256、保留 dirty/error/outcome/actionCount，逐步用同一个 `engine-in.save` 调用 `replay_trace`，解析 `REPLAY_OK`/`REPLAY_INVALID`，按 `game turn = engine turn + 1` 对齐稳定 post，并输出 JSON 与 `SUMMARY`。
- 真实回归：两只虱虫 `battle-20261018-174030-825-a05ceb11-08ef-40fa-82ed-6d9029360f0d` 哈希一致、dirty=true、12 个 journal 动作、最长未拒绝前缀 11；回合边界明确报告 engine HP=52、game HP=51，未猜测伤害根因。三哨卫 `battle-20261018-184303-365-2aecdaee-33b3-40ab-a927-75e3538a9b4a` 哈希一致、dirty=true，动作 3 首次 `REPLAY_INVALID`，最长前缀 2；未把旧 Curse 记录改成已修复。此前成功胜利 bundle `battle-20261018-150839-671-747a4a3c-cf7b-436e-b792-2b68254a6e20` 哈希一致、最长前缀 9，稳定 post 比较无差异；终局仍按无稳定 post 的受限证据处理。
- 离线回归：`run-root-replay-diff.ps1 -SelfTest` 输出 `{"selfTest":"PASS","checks":10}`，退出 0；覆盖合法前缀、协议拒绝但进程可返回、dirty、缺 post/终局、回合映射、阶段分类、HP 首差异、哈希/未知字段拒绝语义。`tools/solvertest/run.ps1 -Command all` 退出 0，`git diff --check` 退出 0。未修改 Java/C++、未构建部署 JAR，证据为 L2；真实 bundle 结果是受限 L4 诊断，不是全场严格 L4。

### 2026-10-18 可信性加固（更新上段工具结论）

- 原脚本每轮覆盖 `$run`，最终把最后一次前缀 TRACE 用于多条原生 post；同时只按 `REPLAY_INVALID` 子串定前缀，哈希不匹配仍继续比较，自测有恒真断言。现已抽出 `tools/recordercheck/RootReplayDiff.psm1` 作为实际生产解析/门控/比较逻辑：每个动作数独立执行和保存结果；`TRACE0` 是根，`TRACEi` 只对应动作 i 成功后的状态；只有退出码 0、准确 `REPLAY_OK n=i` 和完整 `TRACE0..TRACEi` 才推进合法前缀。明确拒绝标记优先保留其动作号；超时/崩溃、缺输出、格式错误分别分类，失败前缀不会进入后续比较。
- 文件 SHA 逐一按 manifest 字段核验；缺文件、JSON/manifest 解析错误、root 缺失、哈希不符会阻止回放比较。另行报告动作类别/手牌/目标身份对齐前缀、root/相邻 pre-post 状态断链、C++ 协议结果、可见稳定字段比较和证据等级。`journalDirty`、open bundle、链断裂和 journal-only 终局动作均限制为 `diagnostic_only`；药水动作可识别，但当前 JSONL 缺少可核对其完整 payload 的协议字段，会截断严格动作对齐。终局动作标为 `terminal_not_comparable`，不会虚构 post。工具从真实 `TRACE` 输出直接对根与各配对 post 比较 HP、最大 HP、格挡、能量、回合映射及抽/弃/消耗堆数量；手牌顺序、怪物完整状态、遗物、全 powers 和 RNG 仍未比较。
- 真实样本均原样保留且三份核心文件哈希各 3/3 匹配。两只虱虫 bundle 的 journal 12 项、JSONL 8 个动作行；动作 3 为 journal 药水而 JSONL 记录卡牌，动作对齐前缀仅 2。引擎协议确认前缀 11，动作 12 `REPLAY_INVALID`；根与前两个动作可见字段 24/24 匹配。首个新回合边界记录为 engine turn 2/action 9 HP 52 与 game turn 3/action 8 pre HP 51，但动作时间线和 dirty 状态使之仅为 `restricted_boundary_observation`，不据此判定规则差异。三哨卫 journal/JSONL 前 33 项对齐，动作 3 明确 `REPLAY_INVALID`，合法前缀 2；根与前两个动作 24/24 可见字段匹配，旧 Curse 样本未被当作新记录器修复证据。胜利样本前 8 个配对动作的可见字段 64/64 匹配，根另 8/8；动作 6 的 hand 链断裂、`journalDirty=true`，第 9 个 journal-only 致胜动作无稳定 post，故不宣称全字段或完整 L4。
- 自测替换为合成 bundle 和注入引擎响应，直接调用生产门控/解析/比较函数；30 项通过，覆盖哈希、损坏/缺失、dirty、重复/错位、动作身份和目标、终局缺 post、合法/非法协议、退出码/超时/缺 TRACE、回合映射、阶段、未知字段、首个 HP 差异及失败前缀隔离。可见字段命中数为 24/24、24/24、72/72（最后一份含 root 8/8 与 8 个已配对动作 64/64），但回合链和未知字段仍单独限制证据。`tools/solvertest/run.ps1 -Command all`、`git diff --check` 和三份真实工具命令均通过。未改运行时代码或 bundle，证据 L2；历史 bundle 只支持受限诊断，不是新的全场 L4。后续优先开发 Ironclad 机制覆盖，不再扩展此工具；本条待用户验收。

## 2026-10-18：Ironclad Exhume 消耗堆选择与取回

- 原生规则以本机 STS1 字节码核实：Exhume 基础费用 1、升级费用 0，出牌后自身消耗；只可取回消耗堆中的非 Exhume 卡。空消耗堆或满手时不取回；升级 Exhume 不升级所选卡。多候选时按消耗堆位置选择具体实例，单候选沿用自动取回路径。
- 修复 `BattleContext::chooseExhumeCard` 对直接调用路径未拒绝自身/越界索引的问题；修复 `CardInstance::setCostForTurn` 将 Corruption 的 `-9` 免费费用哨兵错误钳为 0。搜索与 replay_trace 继续通过同一 `SINGLE_CARD_SELECT` 动作和生产结算路径；协议 v2 未变。Java `PlanDeployer` 仍拒绝自动执行选牌动作，故 C++ 可模拟选择，G/H 自动执行受限并安全停止。
- `exhume_check` 覆盖多候选、同 ID 不同实例、动作 bits、搜索/replay 分支、单候选、空堆、自身/越界候选、满手、Corruption、费用/升级、自身消耗的 Feel No Pain / Dark Embrace 次序与完整出牌流程。修复前分别复现直接选回 Exhume 未被拒绝、Corruption 返回技能费用哨兵被清零；修复后检查通过。
- 验证：`exhume_check`、`exhaust_trigger_check`、`block_modifier_check` 通过；`tools/solvertest/run.ps1 -Command all` 退出 0；`tools/recordercheck/run-root-replay-diff.ps1 -SelfTest` 30 项通过；`git diff --check` 通过。C++ Release 引擎已重建，协议 v2 启动检查通过；SHA-256 `2D3831D3BED98B0F12E6317BF1608119D9EAF8CEAF493BFBD274B363054A9268`，settings.properties 的 `engine.path` 指向该 EXE。
- 本轮沙箱无法读取 `C:\Users\3476.combatsolver1\recordings`，三个指定真实 bundle 未能重新运行；历史已知结果仅作为受限旧证据，不能证明 Exhume 的实机准确性。无新的 L3/L4 证据，当前为 L2 离线验证。Java Mod、真实 bundle、搜索策略和执行逻辑未修改。

### 后续：Exhume 原生选牌执行第一阶段

- C++ 对 Exhume 选择输出专用 `PLANSELECT v=3`，含消耗堆候选的模拟器槽位、ID、升级数和当前费用；槽位只用于协议内交叉核验，Java 不将其当作原生 UI 索引。Java 只在选中候选的 ID/升级数/费用签名于完整候选多重集中唯一时映射到实际卡对象；候选顺序变化可接受，签名重复或候选集合变化则拒绝。
- `PlanDeployer` 仅对 `source=engine` 的 v3 Exhume 路线启用执行；等待原生 `ExhumeAction` 正在打开的 GRID 消耗堆选择，基于目标卡当前 hitbox 合成一组点击输入，不直接修改牌堆或选牌列表。验证选择后回到稳定战斗状态，并核对原手牌保留、目标卡移入手牌、目标从消耗堆移除、Exhume 入消耗堆和能量；不匹配、玩家干预、超时、取消或路线失效均停下并标脏 journal。
- Java `ExhumeExecutionCheck` 直接调用生产协议解析器、候选身份匹配和等待门控，15 项通过，含不同顺序、同 ID 不同升级、同 ID 同升级歧义拒绝、界面等待/错误界面、点击阶段、取消、超时、手动输入、单候选原生快路径、结算漂移和旧版非 Exhume v2 兼容。修复前协议解析拒绝 v3；C++ 单候选旧假设回归被改为显式枚举/执行。`exhume_check`、`exhaust_trigger_check`、`block_modifier_check`、G/H `ExecutionSafetyCheck`（11 项）、`solvertest all` 和同根差分 SelfTest（30 项）通过。
- Java 字节码 major=52，MTS 3.30.3 对构建 JAR 的 6 个补丁完成 discovery 与 injection，含 `GridCardSelectExhumePatch`。构建后第一次直接部署遇到系统对游戏 `mods` 目录的 Access Denied，尚待以获准写权限完成最终部署及 SHA 核对。C++ Release EXE 为 `build/engine/combatsolver1-engine.exe`，SHA-256 `6D858D8A1EF87851186289C1BCED61B336E9D2CFEDEBAEFC0C22EDDBE8B9F830`；protocol v2、Exhume/消耗/格挡检查，以及现有胜利 bundle 的只读 `mcts_save`（1 次模拟）和 `replay_trace`（9 动作）启动/回放 smoke 通过。`engine.path` 已指向该绝对路径。
- 证据为 L2：离线执行模型与真实 MTS 注入已验证；本轮未启动游戏，尚未证明 Exhume 网格屏幕中的 hitbox 输入、原生结算及后续路线可在实际游戏中完成。尚未达到 L3/L4。仅 Exhume 自动执行已启用；Java local 计划及其他选择路线继续安全拒绝。

### 首次 Exhume L3 现场日志复核（2026-10-18/19）

- H 的 Slime Boss 外部路线在出牌 3/4 后报告 `deploy potion not found: Dexterity`；同场 bundle 的 potion 快照显示 `Dexterity Potion`，名字/ID 的匹配根因尚未确认。路线随后失效并改用 local route。Turn 4 local route 在未携带 `PLANSELECT v=3` 的情况下打出 Exhume，随后出现原生选牌界面并按现有安全策略中止。没有 `Exhume pre-choice identity matched`、原生输入、选择验证或后续动作证据；这是安全中止，不是选牌成功。该场 `battle-20261018-235230-235-1f912c14-9996-468a-84ca-043ed56938d6` 当时仍 open/in_progress，combat.jsonl 被运行中的游戏占用，不能做封存完整性验收。
- 修复：`PlanDeployer` 现在在打出 Exhume 前要求紧随其后的路线动作是来源为 sts_lightspeed 的完整 v3 Exhume 选择；local、缺失/不完整选择会在耗能和打开界面前停止。已排入的前序动作先结算，Exhume 本身不执行。若 Exhume 已打出但选择中断，即使 pendingChoice 尚未建立，失败也会将 journal 标脏。
- 验证：Exhume 执行检查、solvertest all、RootReplayDiff SelfTest（30 项）、Java 8 完整构建（major 52）及 MTS 3.30.3 补丁发现/注入均通过。测试为 L2，不能代替本修复版本的实机验证。游戏进程仍在运行时未覆盖 mods JAR；需要关闭游戏后部署并重新测试。G Exhume、H 多候选点击仍未验证；此前 H 单候选成功只覆盖旧部署版本。

### 2026-10-19：保留多词药水 ID 的外部路线解析

- 对 battle `battle-20261018-235230-235-1f912c14-9996-468a-84ca-043ed56938d6` 的原始输入运行当前 C++ 引擎，`mcts_save` 输出 `PLANPOTION idx=0 id=Dexterity Potion tgt=0`；bundle potion 快照也包含 `Dexterity Potion`。Java 通用字段解析按空格截断 ID，故部署器精确查找原生药水对象时误报 `deploy potion not found: Dexterity`。首个不一致在协议解析阶段；无证据显示药水缺失、提前消耗或槽位错位。
- `EngineProtocolFields.potionId` 现在按 `id=` 到 `tgt=` 分隔符解析完整值，malformed 协议显式拒绝；`PlanDeployer` 保留完整 ID 的严格相等校验。增加真实生产解析函数回归，覆盖多词/单词 ID、精确匹配、截断不匹配拒绝及 malformed 行拒绝。未改 C++ 协议、Exhume 状态机或药水安全门控。
- 当前日志显示旧部署路线因敏捷药水解析失败而退出外部计划，之后运行 local 路线并在 Exhume 网格选择时安全中止。旧 bundle 的动作状态不能证明选择由 H 完成；没有 `PLANSELECT v3`、原生输入与验证日志。该历史证据保持原样，L3 仍未验证。
- 回归：药水协议身份回归 8 条断言、`run-exhume-execution-check.ps1`（15 项）、`tools/solvertest/run.ps1 -Command all`、RootReplayDiff SelfTest（30 项）、C++ `exhume_check` 均退出 0；`git diff --check` 通过。历史 bundle 的 `run-root-replay-diff.ps1` 在 `RootReplayDiff.psm1:66` 报 `Index was outside the bounds of the array`，未形成可用差分结论；本轮不改差分工具或历史归档。
- 完整构建/部署：Java 8 字节码 major 52，JAR 含 108 个条目。修复提交 `4843d6bae274b6397354d19e8233f554eaae9d6d` 后重建部署；MTS 3.30.3 对实际部署 JAR 再次发现 6 个补丁并输出 `Injecting patches...Done`。构建 JAR 与游戏目录 `mods/CombatSolver1.jar` SHA-256 均为 `AC8575A0679FED1DD7DC924C46252EB00CF7906E93C00074A663F6B681EFE776`；部署前备份为 `CombatSolver1.jar.bak-20261019-002457`。当前 C++ EXE 未改，SHA-256 `6D858D8A1EF87851186289C1BCED61B336E9D2CFEDEBAEFC0C22EDDBE8B9F830`；同 bundle `mcts_save` 退出 0 且保留完整 `PLANPOTION ... id=Dexterity Potion ...`，但返回路线为死亡结局且不含 Exhume 选择，不能证明 H/G Exhume。`sendToDevs/` 保持未跟踪、未触碰。

### 2026-10-19：H 多候选 Exhume 实机失败与最小修复

- 新实机日志对应 bundle `battle-20261019-005100-753-9ea14200-e20e-4853-9ac7-6b5fb62cd183`。外部 `sts_lightspeed` 路线实际包含 Strike、Exhume 与后续步骤；日志确认 Exhume 候选数 4、目标 Impervious。候选校验首次失败，因为 C++ 对 AscendersBane 编码费用 `-3`，原生 STS1 为 `-2`；其余候选身份一致。没有成功原生点击或结算证据，H 本次 L3 失败，G 未覆盖。原 bundle 为 open/in_progress、`journalDirty=true`，保留原样，不作为严格完整回放证据。
- 同一失败回调在原生 `AbstractPlayer.useCard` 过程中同步取消剩余路线。日志显示取消逻辑从 `cardQueue` 移除 1 项，之后 31 ms 游戏在 `GameActionManager.getNextAction()` 对空 `actions` 列表执行 `remove(0)` 并崩溃。修复仅对已确认回调中的当前出牌保留其原生队列项、取消其余计划卡牌；不改变原生结算、不重试或绕过身份校验。身份比较仅将 AscendersBane 的已知 `-2/-3` 不可打费用哨兵视为等价，仍拒绝 `-4`，不改变模拟费用或合法性。
- 修复前回归分别失败于真实四候选池（AscendersBane `-3` 对 `-2`）和取消时保留正在执行的卡牌队列项；修复后 `run-exhume-execution-check.ps1`（17 项）、`ExecutionSafetyCheck`（11 项）、药水协议检查（8 项）、C++ `exhume_check`、`tools/solvertest/run.ps1 -Command all`、RootReplayDiff SelfTest（30 项）及 `git diff --check` 通过。以上是 L2 离线证据；修复后完整构建、MTS 注入检查、部署和新一轮 H/G 实机结果待本轮完成/取得，不据此宣称 L3。
- 修复提交 `428053ed1173e8c28baed9baa79be9e4dcab4433` 已推送并与远程 `main` 一致。Java 8 完整构建 major=52；MTS 3.30.3 对实际部署 JAR 完成 6 类补丁注入并输出 `Injecting patches...Done`。构建与部署 JAR SHA-256 均为 `D37EF2F7607D2DDD02918B46DD0DF16814BA31886ACA6B0580D427B6E9439060`，部署前备份 `CombatSolver1.jar.bak-20261019-010232`。新版本尚无实机复测；H 需在此版本重测，G 仍未测。

### 2026-10-19：Continue 读档战斗的外部引擎根状态绑定

- 失败根因：`CombatController.requestInitialEnginePlan()` 原先只接受修改时间晚于本场战斗开始的自动存档。STS1“继续游戏”先加载角色槽位中的存档再进入战斗，文件时间早于 battle start，因而被 stale-save 门控拒绝；H 随后回退 Java 本地路线，而 local 路线没有可执行的 Exhume v3 身份协议。旧 journal 不能从已加载存档推导，不能仅凭文件名或时间戳放行。
- 原生路径依据：本机 STS1 字节码确认 `CardCrawlGame.loadPlayerSave(AbstractPlayer)` 调用 `SaveAndContinue.loadSaveFile(chosenClass)` 并写入 `CardCrawlGame.saveFile`；私有 `SaveAndContinue.loadSaveString(String)` 是原生实际读取/解码主存档或 `.backUp` 的方法。新增只读钩子在原生 loadPlayerSave 事务中开始捕获，并在此具体读取方法返回时，以 `__result` 对比当前角色槽位主文件和备份的解码内容。只有内容与原生加载返回值完全相同的文件字节才作为来源；若主/备份字节相同，清楚记录两条等价来源路径，若两份内容不同则拒绝歧义。原生存档不写入、不改时间戳。
- 可信绑定分层：一次性凭证记录来源路径、字节 SHA256 和实际 `SaveFile` 对象身份；战斗开始时再核对角色、楼层、种子、来源/当前房间遭遇池、HP/maxHP、牌组、遗物计数器和药水。预战斗房间与当前战斗房间允许不同，但遭遇池须匹配。来源归档后才生成引擎输入；外部引擎回传时，`STATE0` 的 HP、格挡、能量、手牌、怪物摘要及 engine turn→game turn 映射必须匹配实时状态。journal 从恢复后新战斗 root 起记；dirty、断链、无法准确重放的选牌/RNG、root 漂移或过期异步结果均继续拒绝执行。异步结果还需同时匹配 battleId 与 artifactId。
- 当前 sts_lightspeed 硬编码仅支持 Ironclad；其他角色明确拒绝外部引擎。Java fallback 不会被标记为 sts_lightspeed，缺少 Exhume 可信选择身份的本地路线仍在自动执行前安全停止。
- 回归：新增 `ResumeSaveBindingCheck`（可信旧时间 Continue 源、备份、相同字节的主/备份等价来源、一次性凭证、缺少来源、错误槽位及角色/楼层/种子/遭遇/HP/牌组/遗物/药水/对象身份拒绝）和 `EngineRootStateBindingCheck`（8 项必需根字段及回合映射校验）。修复前相同场景因不存在生产绑定入口而失败；修复后两项测试均退出 0。`ExecutionSafetyCheck`（11 项）、`solvertest all`、Exhume 执行（17 项）、药水协议（8 项）、RootReplayDiff SelfTest（30 项）、C++ `exhume_check` / `block_modifier_check` / `exhaust_trigger_check` 均通过。
- Java 8 完整构建 major=52。MTS 3.30.3 实际补丁 discovery/injection 首次抓到新增钩子对目标参数名的错误依赖；改用稳定 `__instance` / `__result` 注入后，检查输出 `MTS_VERSION=3.30.3`、发现 8 个补丁并 `Injecting patches...Done`。构建脚本为 MTS 需要的实例参数保留 LocalVariableTable。当前已部署至 `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\mods\CombatSolver1.jar`；构建与部署 JAR SHA256 均为 `DDE67184C033CD7A80B990AFA7CE179BEFF69C745CA4DB3188EAC9B5D41F3EC2`，部署前备份 `CombatSolver1.jar.bak-20261019-015930`（前版 SHA256 `6C25227E80420D4747D40EBD1FA1A2C1CF39F64F44D36FE221289E23EFCA0902`）。
- 本轮证据为 L2。没有新“继续游戏”战斗记录，不能宣称 L3 或 L4；实际是否成功取得原生根并通过引擎 STATE0 核验仍需未来游戏日志确认。

### 2026-10-19：从 STS1 原生输入流捕获 Continue 存档字节

- 最新失败 bundle `C:\Users\3476\.combatsolver1\recordings\battle-20261019-020426-571-9db54121-9d7d-4dd4-bb12-b3b4aa8450b5` 的 manifest 确认旧失败原因仍为 `native loaded text matches neither current character save slot nor its backup`，且没有 `raw-autosave.save` / `engine-in.save`。原始 Continue 存档未保留，因此无法从该文件确定当时究竟发生了路径、读后变化或解码差异。
- 本机 STS1 字节码确认：`CardCrawlGame.loadPlayerSave(AbstractPlayer)` 以玩家职业调用 `SaveAndContinue.loadSaveFile(PlayerClass)`，返回后才赋值 `CardCrawlGame.saveFile`；私有 `loadSaveFile(String)` 调用私有 `loadSaveString(String)`，后者执行 `Gdx.files.local(path).readString()`，使用 `FileHandle.read()` 和默认字符集，再按 `SaveFileObfuscator` 解码。主存档解析抛 `SaveFileLoadError` 时原生尝试 `.backUp`。
- 替换加载后磁盘重扫：新增 `NativeSaveFileReadPatch` 只在原生 Continue 事务内对主/备份 `FileHandle.read()` 返回流做透明 tee；精确记录该流读出的字节、规范化实际路径和 SHA256。`SaveAndContinueLoadedBytesPatch` 将字节按原生默认字符集及同一 obfuscator 解码，与原生返回文本严格核对；仅当外层原生加载成功并已设置活动 `SaveFile` 后封存一次性证据。主存档失败而回退备份时，实际成功路径覆盖候选；路径不符、空/未完整流、解码不符、缺少活动对象均拒绝，不扫描、不改写存档。
- 修复前证据：最新真实 bundle 的 manifest 保存了上述拒绝结果；自动化失败夹具还复现“文件读取后发生变化时二次扫描与原生读入不同”。修复后 `NativeSaveReadCaptureCheck` 16 项通过，覆盖主/备份、相同字节但准确来源、路径拒绝、读后改写、字符解码、完整流及对象身份。`ResumeSaveBindingCheck`、`EngineRootStateBindingCheck`、`ExecutionSafetyCheck`（11）、`ExhumeExecutionCheck`（17）、药水协议（8）、`solvertest all`、RootReplayDiff SelfTest（30）、C++ `exhume_check` / `exhaust_trigger_check` / `block_modifier_check`、`git diff --check` 均通过。
- 使用既有真实 Ironclad bundle `battle-20261018-174030-825-a05ceb11-08ef-40fa-82ed-6d9029360f0d` 的 `engine/engine-in.save`，`replay_trace` 空动作退出 0 并输出 TRACE0/REPLAY_OK n=0；`mcts_save` 退出 0 且输出 STATE0。该样本只证明旧真实格式引擎输入能初始化，不是本轮 Continue 捕获或 L3/L4 证据。用户最新失败 bundle 缺存档，无法做同场离线转换验证。
- Java 8 完整构建退出 0、major=52。MTS 3.30.3 对实际部署 JAR 发现 9 类补丁，输出 `Injecting patches...Done`。部署路径 `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire\mods\CombatSolver1.jar`；构建/部署 SHA256 均为 `08854AF00FDBBD14951689201F83BB629B41839C4A49F917DD2B848FBB40F6A8`，旧版备份 `CombatSolver1.jar.bak-20261019-022751`，SHA256 `DDE67184C033CD7A80B990AFA7CE179BEFF69C745CA4DB3188EAC9B5D41F3EC2`。引擎 `engine.path` 仍指向现有 EXE，SHA256 `6D858D8A1EF87851186289C1BCED61B336E9D2CFEDEBAEFC0C22EDDBE8B9F830`。
- 代码提交 `3dda1c1ca2262fa2e3b5b6a5c61a4ba7d544c76f`。新方案尚未在游戏内 Continue 场景执行；证据为 L2，L3/L4 未通过。`sendToDevs/` 保持未跟踪，未提交私人记录。

### 2026-10-19：Continue 原生读流与加载事务失败诊断

- 新实机 bundle `battle-20261019-023602-060-ab4adec2-ad75-4fc5-8e6f-47db434f2b41` 记录 `native load transaction mismatch: successful loader has no matching captured read`，未保留原生存档字节。检查同机 STS1 字节码：`CardCrawlGame.loadPlayerSave` 同步调用 `SaveAndContinue.loadSaveFile`；私有 `loadSaveString(String)` 同步调用 `FileHandle.readString()`，其 `readString()` 再虚调用 `read()`；实际 `LwjglFileHandle` 未重写 `read()`。因此未发现线程切换或子类绕过的字节码证据。旧错误由 `sealSuccessfulLoad()` 覆盖早期捕获原因，单靠旧 bundle 无法再区分读钩子未触发、路径不符或流不完整。
- 最小修复：保留原生 Prefix/Postfix 兼容签名，用 `NativeReadEvidence.ReadScopes` 将每次 `FileHandle.read()` 的事务和路径按调用栈配对，避免共享单值 ThreadLocal 被嵌套读取覆盖；只捕获当前加载事务中命中主/备份规范路径的字节。`sealSuccessfulLoad()` 不再覆盖首个失败原因，并分别报告未观察到流、路径不符、流不完整、解码差异或缺少 SaveFile。
- 修复前回归：新增“未观察到 read 流后成功 loader 封存”断言，在旧实现上失败（seal 覆盖了 `native loadSaveString returned without an observed FileHandle.read stream`）。修复后 `NativeSaveReadCaptureCheck` 19 项通过，覆盖嵌套无关读取、外层来源恢复及错误原因保留；`ResumeSaveBindingCheck`、`EngineRootStateBindingCheck`、`ExecutionSafetyCheck`（11）、`ExhumeExecutionCheck`（17）及 `tools/solvertest/run.ps1 -Command all` 通过。真实 Continue 场景尚未重测，L2；旧样本不能确定其当时是否发生了嵌套读取，也无 L3/L4 结论。
- 代码提交 `a731a18c8510fbbe187c558254304eeb60455fd1`；Java 8 构建 major=52，MTS 3.30.3 对部署 JAR 的 9 个补丁注入完成。构建与部署 JAR SHA256 均为 `D11EF4BCBEA2DE2BC4E8BCCCFC00F2A454350F1888421DE980837A86A8B26E18`；部署前备份为 `CombatSolver1.jar.bak-20261019-025218`。本记录只证明 L2，未取得新版 Continue 实机成功日志。

## 2026-10-19：native read/decode mismatch 定位（尚未修复实机分支）

- 目标 bundle `battle-20261019-104622-510-47f9aa7b-2a36-48e7-a15c-37baa11be856` 与 `battle-20261019-105535-395-f0de597d-563b-4f62-8926-9f73224ba7c7` 都报 `native read/decode mismatch`、路径为 Ironclad autosave、长度 18084。错误由 Java `NativeReadEvidence.Transaction.returned()` 在原生 `loadSaveString` 返回后比较“已捕获 FileHandle 流重解码文本”和原生解码返回文本时产生；当时 C++ 尚未接到存档，因此不是 C++ JSON 字段/协议解析阶段。失败 bundle 按安全策略没有归档被拒绝的原始流，无法从旧 JSONL 求出当次首个差异字段。
- 找到同一实际 autosave 的既有只读副本：bundle `battle-20261019-102938-114-1b883da4-4b72-4d5f-9749-d87f438490d8/raw-autosave.save` SHA-256 为 `FCEF11A6F39531B6C4E19F753C50C07EBE14763671B2FD637BB4FF3C5702B07C`，与当前物理 autosave 完全相同。游戏 Java 8 `FileHandle.readString` + `SaveFileObfuscator.decode` 对该精确文件与 `NativeReadEvidence.ReadScopes` 捕获流重放比对一致。外部引擎 `mcts_save` 100000 模拟可输出与实战根一致的 `STATE0 hp=44... hand=[Exhume,Defend_R,Strike_R,Bash,Strike_R] mons=39|42|43`，以及 `PLANEND hp=12 outcome=1`。因此相同静态文件的 Java/C++ 编码、读取和搜索链路可工作；原故障尚未在真实 MTS hook 执行上下文重现，具体触发点仍未确认。
- 生产诊断最小修改：仅当解码文本不等时附加字符长度、首个差异 offset 和仅限 ASCII 的 JSON key，不记录字段值；扩展 `NativeSaveReadCaptureCheck`，检验不一致被拒绝并指出 `field=seed`。同时新增 `run-native-save-engine-interop-check.ps1` 和 `NativeSaveEngineInteropCheck.java`，接收本机游戏存档，使用游戏 Java 类、生产 `NativeReadEvidence.ReadScopes`、当前引擎 `json` / `json_to_save` / `mcts_save` 路径做端到端回归，不把私人存档放入仓库。
- 本轮验证：`powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-resume-save-binding-check.ps1` 退出 0（ResumeSaveBinding、NativeSaveReadCapture 20 项、EngineRootStateBinding）；`powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-native-save-engine-interop-check.ps1 -SavePath <既有 bundle raw-autosave.save> -ExpectedStatePrefix <对应 STATE0>` 退出 0，`PLANEND hp=12 outcome=1`；`powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command all` 退出 0（语料报告 70 动作仍有 48 项差异）；`powershell -ExecutionPolicy Bypass -File tools/build/build-release.ps1` 退出 0，major=52、131 entries；`git diff --check` 退出 0。日志分别在 `build/v1-audit/native-read-regression.log`、`native-save-exact-bundle-interop.log`、`native-mismatch-solvertest.log`、`native-mismatch-build.log`。
- 限制与下一步：上述只是 L2 复现/离线端到端证据，不是 MTS 实机 Continue hook 的 L3/L4 修复验证；没有证据支持修改 C++ 解码或放宽安全门。诊断候选已构建并部署，但未重启当前游戏进程：构建和安装 JAR SHA-256 同为 `B350BB6C2E9523CDAA9AFD29972FB25790902BBA83726E0BC01DFF2E820151C9`，部署前备份 `CombatSolver1.jar.bak-20261019-112433`；MTS 3.30.3 实际部署 JAR 的 9 类补丁 discovery/injection 检查通过。当前运行进程尚未加载此候选，故尚不能获取 mismatch 的实机 firstDiff/field，根因与修复仍待确认。用户需在安全时正常退出并重新启动游戏，在新战斗复现一次；届时按新日志定向修复。此轮没有执行游戏出牌。`sendToDevs/` 保持未跟踪。

### 同日实机复测（候选诊断版已加载，问题仍存在）

- 新 bundle `battle-20261019-112735-916-e36ffc29-a6e9-41e6-97fa-4c5d18667dd6/manifest.json` 确认候选版诊断已运行：仍以 `native read/decode mismatch` 拒绝 Continue 存档；原生返回文本长度 23，捕获字节重解码文本长度 13,561，首差异 offset=0，未能关联 JSON 字段。Engine combat save 状态为 failed，因此该新战斗没有外部有效路线；玩家后续手动操作有记录，但不是求解/部署验收。bundle 未保存被拒绝流，不能还原两段文本的实际内容。
- 当前磁盘 autosave 长 18,084 字节、时间为 10:29:37，早于该战斗，不能当作新战斗实际读取流。对当前 autosave 重跑 `powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-native-save-engine-interop-check.ps1 -SavePath <IRONCLAD.autosave> -ExpectedStatePrefix <对应 STATE0> -Simulations 100000` 退出 0，`STATE0` 与 bundle root 一致，输出 `PLANEND hp=12 outcome=1`。这是离线互操作成功，不能解释实机 hook 中 23 对 13,561 字符的分叉。
- 此次真实 Continue 加载阶段 L3 复现失败，没有 L3 成功或 L4 证据。根因未定位到首个字段；下一项最小诊断应核对 `loadSaveString` 调用参数/次数与捕获流所属调用是否一致，并只保留不含存档值的调用身份摘要。继续拒绝未验证存档、禁止无有效路线时出牌。bundle 路径 `C:\Users\3476\.combatsolver1\recordings\battle-20261019-112735-916-e36ffc29-a6e9-41e6-97fa-4c5d18667dd6`；离线日志 `build/v1-audit/native-mismatch-current-autosave-interop.log`。

### 2026-10-19：新战斗 Continue 捕获与 fresh autosave 分流修复

- 根因（来源选择阻断）：STS1 字节码显示 `CardCrawlGame.loadPlayerSave(AbstractPlayer)` 调 `SaveAndContinue.loadSaveFile(PlayerClass)` 一次；该方法对传入路径调用 `loadSaveString(path)`，JSON 解析异常时会递归尝试 `.backUp`。这属于 Continue 加载流程。新战斗生成/读取的 fresh autosave 则由 `CombatController.requestInitialEnginePlan()` 独立扫描。旧控制器只要本进程曾观察到 Continue 读取，就走 Continue 分支；若该证据解码不匹配便直接 return，完全不检查本场 fresh autosave。最新 bundle 的 `rawAutosaveStatus=rejected_unverified_native_load` 与 `engineError=Native Continue save rejected...` 证实本场被这个短路阻断。
- `nativeChars=23` 的确切内容无法从旧 bundle 恢复（安全策略没有保存存档/返回文本），因此不把它伪称为已证明的原生返回分支。另一个已证实的旧风险是同一 `loadPlayerSave` 生命周期共用单个 `NativeReadEvidence.Transaction`；STS1 主存档失败可递归读取 backup，多个 `loadSaveString` 调用可能共享捕获状态。现在每次目标方法进入都创建唯一调用 ID 和独立 Transaction；`FileHandle.read()` 只关联当前栈顶调用，返回值只校验同一调用的字节。日志仅记录 ID、实际读取路径、字符长度及无值的 mismatch 摘要。需新版下一次实机 Continue 读取来确认历史 23 字符属于哪个 ID/路径。
- 最小来源修复：只有 `nativeLoadObserved && verifiedSourceAvailable` 才使用 Continue 字节；无效/不存在 Continue 证据时转入原有 fresh autosave 查找，保留 battle-start 3 秒 mtime 门槛、归档 SHA 校验、引擎根状态/`STATE0` 绑定与所有执行安全检查。若无 fresh autosave 仍明确拒绝旧文件，不会将无效 Continue 证据升级为可信来源。
- 修复前回归模拟旧共享事务会将 23 字符返回值与解码后 13,561 字符捕获文本配成 offset 0 mismatch；修复后 `NativeSaveReadCaptureCheck` 27 项通过，覆盖唯一调用 ID、独立主/backup 调用与无读流调用拒绝。`ResumeSaveBindingCheck` 与 `EngineRootStateBindingCheck`（8 项）通过；`ExhumeExecutionCheck`（17 项）通过；`tools/solvertest/run.ps1 -Command all` 退出 0（历史语料 70 动作仍有 48 项差异）。`run-native-save-engine-interop-check.ps1` 对当前实际 Ironclad autosave 退出 0，生产 per-call 捕获路径输出 `STATE0 hp=44...` 并得到 `PLANEND hp=12 outcome=1`。这些是 L2，不是新版实机 L3/L4。
- Java 8 完整 Mod 构建退出 0，字节码 major=52、133 个 JAR 条目；MTS 3.30.3 对最终候选版发现并注入 9 个补丁类，`Injecting patches...Done`。先前使用 `String[] __args` 的中间候选未通过 MTS 参数绑定，已改为无参 Prefix；最终候选的 patchcheck 通过。代码提交 `d0a68d412717220177766339285e6f1da0b1bb99` 已推送至 `origin/main`。构建与已部署 JAR SHA-256 均为 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`，旧版备份 `CombatSolver1.jar.bak-20261019-115520`。新版本尚未由用户重启加载/实机复测；新战斗外部路线、执行及 23 字符返回值的调用 ID 仍待 L3/L4 证据。V1.0 仍被新战斗路线实机验收阻断；`sendToDevs/` 保持未跟踪、未修改。

### 2026-10-19：候选版新战斗复测仍被输入来源阻断

- 用户截图对应 bundle `battle-20261019-120212-544-969f6e6b-4a74-41a8-b929-07ea0fbf54db`。本次调用级记录确认 `loadSaveString call id=1` 读取 `saves/IRONCLAD.autosave`，仍出现 `nativeChars=23`、`capturedChars=13561`、`firstDiff=0`；原文未记录。该证据将 mismatch 关联到实际路径及唯一调用，但不能解释游戏为何对该路径只返回 23 字符。
- 控制器不再因无效 Continue 捕获提前退出，已尝试 fresh-autosave 分支；由于磁盘 `IRONCLAD.autosave` 的最后写入仍是 2026-10-19 10:29:37，早于本次战斗开始时间 12:02:12，严格 freshness 检查拒绝它。bundle `engineCombatSaveStatus=failed`，错误为 `No fresh autosave found; stale autosave was not used`；本次未进入 `engine-in.save`/C++ `STATE0`，无外部 `plan ready`。游戏日志在 `04:02:12` 记录 `publish start battle`，`04:03:20` 记录 autosave 成功加载；日志未显示该存档在战斗开始后被更新。该候选修复证明了无效 Continue 不再遮蔽 fresh-source 查找，但没有解除新战斗输入阻断。
- 本次是新版实机复现失败（L3 失败）；没有外部路线或执行，不计 L4。不可将旧 autosave 放宽为可信输入。针对性来源分流修复已部署，运行证据显示剩余问题是战斗当前状态没有可用的可信快照。停止继续叠加分支；下一方案需先经用户决定：在战斗开始时从游戏当前内存态生成/序列化经 `STATE0` 绑定的输入，或维持拒绝并将该环境标为不支持。任何序列化方案都需先验证一致性与可用 API，再单独实现和验收。

### 2026-10-19：路线 C 第一阶段 RNG/怪物字段验证（未达 GO）

- 静态证据：Random Number Prediction Master 反汇编显示其从 `RandomXS128.seed0/seed1` 读取状态，并通过 `Random.copy()` 复制 `seed0/seed1/counter`；预测逻辑在副本上抽取。STS1 `RandomXS128` 的 `getState(0/1)` 与游戏 `Random.copy()` 可支持只读读取设计。STS1 与 C++ `sts::Random` 的共同 xorshift128+ 基础已定位，但尚无真实捕获状态输入，因此不能报告逐步序列一致。
- 隔离代码：新增 `tools/rng-capture-validation/`，含一次性只读 MTS 探针、Java 反射式 RNG trace、C++ `sts::Random` trace 及运行说明。探针只在 COMBAT、玩家回合且 action/card/monster 队列为空时读取六流，并在怪物字段读取前后核对 seed0/seed1/counter；读取不变才记录。代码没有接入正式输入协议/出牌路径。
- 离线构建：JDK 6 `javac -source 1.6 -target 1.6` 编译 Java trace 与 probe（probe 使用隔离 MTS/游戏 API stub）；MinGW `g++ -std=c++17 -O0 -I vendor/sts_lightspeed/include` 编译 C++ trace，均退出 0。生成了隔离 probe JAR。未运行 Java/C++ 同状态 trace：没有真实 RNG 状态，且不得用合成状态替代。拒绝采样边界已纳入 trace；`INT_MAX` 会触发 C++ `range+1` 有符号溢出，不作为有效调用输入，协议导入前必须校验。
- 实机阻塞：直接启动本机 MTS 3.30.3 报 `Could not connect to Steam`，没有启动游戏或加载探针，故无真实六流前后快照、随机事件对照或怪物私有状态运行记录。临时 `mods/CS1RngProbe.jar` 已移除；确认未运行游戏操作、未改 autosave、未改正式 CombatSolver1 JAR/引擎。
- 怪物静态映射：STS1 Sentry 的 `firstMove`、`beamDmg`、`dazedAmt` 为可反射读取的私有字段，通用 `nextMove`、`intent`、`moveHistory` 可反射访问；lightspeed 侧通过 Sentry 的 `idx`、`moveHistory`/`firstTurn()` 分支及 `miscInfo` 建模。是否与实机具体招式和 AI RNG 消耗一致仍未知，不能推广到其他怪物。
- 结论：本阶段 NO-GO（证据不足，非证明技术不可行）。未达到六流无推进、Java/C++ 逐步完全一致、真实怪物状态映射和随机事件验证条件；路线 C 不得进入生产实施。验证命令：Java `javac -source 1.6 -target 1.6 ...JavaRngTrace.java`（0）；C++ `g++ -std=c++17 -O0 ...cpp_rng_trace.cpp`（0）；隔离探针编译/打包（0）；真实游戏运行及双端同态 trace 未执行。未提交/推送；原有 `sendToDevs/` 保持未跟踪且未触碰。

### 2026-10-19：恢复 Steam 启动并修正隔离 RNG 边界测试（实机采样待用户启动）

- 已复用上一轮探针/双端 trace；未重建测试框架。Steam `gameprocess_log.txt` 记录 Steam AppID 646570 于 13:22:22 启动 `mts-launcher.jar`，13:22:23 启动 Workshop ModTheSpire 进程；桌面窗口列表出现 `Modded Slay the Spire` 与 `ModTheSpire 3.30.3`。原有 Modded 主菜单正常显示，随后在主菜单退出；`IRONCLAD.autosave` 长 18,084 字节，时间仍为 10:29:37。原有 Modded 启动已验证可运行。
- 修复隔离测试：新增 `cpp_rng_range_guard.h` 和 `cpp_rng_boundary_check.cpp`，拒绝 `INT_MIN`、负数、`INT_MAX`，接受 0 和 `INT_MAX-1`；C++ 主 trace 的 inclusive int wrapper 统一先过 guard，并加入 `INT_MAX-1` 真实序列项，与 Java trace 对齐。`INT_MAX` 不直接传入 C++（其 `range+1` 有符号 UB），而在边界验证处覆盖；STS1 Java `random(INT_MAX)` 会先增加 counter 再因有符号 wrap 后的负 bound 抛错，因此无效输入的失败前副作用不同，快照/协议须在调用前拒绝。
- 首次启动 C++ trace 的系统错误经 `objdump -p` 确认依赖 `libgcc_s_seh-1.dll`、`libstdc++-6.dll` 等 MinGW DLL；已在本次 PowerShell 进程 PATH 临时加入 MinGW bin 后可正常启动。隔离 boundary 回归输出 `RNG_RANGE_BOUNDARY_OK rejected=INT_MIN,-1,INT_MAX accepted=0,INT_MAX-1`，退出 0。Java trace javac 退出 0；C++ trace 编译退出 0。未运行随机序列，因没有真实快照。
- 临时探针曾重新放入 `mods/CS1RngProbe.jar`。退出原有游戏后再次调用 Steam URI，没有观察到新的 AppID 646570 进程记录；Computer Use 对 Steam 窗口的访问授权超时，因此不能确认 Steam 当时的具体提示，也不能确认第二次启动是否到达 Play with Mods 选择。探针 JAR 已清理。没有六流真实捕获、前后无推进记录、Sentry 实机字段或 Java/C++ 序列比较；不能判定 RNG 算法失败，技术结论待定。
- 当前仅隔离测试/README/STATUS 有修改；正式协议、求解器、部署 JAR、存档和通知配置未改。`sendToDevs/` 保持未跟踪且未触碰。命令：JDK6 `javac -source 1.6 -target 1.6 ...JavaRngTrace.java`（0）；MinGW `g++ ...cpp_rng_trace.cpp`（0）；MinGW `g++ ...cpp_rng_boundary_check.cpp` + 执行（0）。Steam 启动/退出证据：`C:\Program Files (x86)\Steam\logs\gameprocess_log.txt`。本记录为待用户完成 Play with Mods 启动后继续的状态，不是路线 C GO/NO-GO。

### 2026-10-19：隔离探针版本元数据修正（等待用户重新启动）

- 用户截图中的 MTS 警告根因为探针 JAR 缺少根目录 `ModTheSpire.json`，并非游戏版本不兼容或 RNG 探针运行失败。证据：原探针归档仅有 `META-INF/MANIFEST.MF` 与 `rngprobe/LiveRngProbe.class`；MTS 3.30.3 字节码含 `ModInfo`、`ModVersion` 解析及缺少/无效版本告警；生产 JAR 与已订阅 Random Number Prediction Master 均在 JSON 提供 `version`、`sts_version`、`mts_version`。
- 最小修正：新增实验专用 `tools/rng-capture-validation/ModTheSpire.json`，为隔离 JAR 打包元数据；README 补入可复现打包命令。JDK 6 编译退出 0；归档检查确认 metadata JSON 有效、包含 probe class、排除所有编译 stub；SHA-256 `8B906A22DD5015F1B32354EAA47923C6C7E271DD8E167790CB2321928D0F590A`。已将修正版部署为临时 `mods/CS1RngProbe.jar`，旧错误版备份至 `%TEMP%\CS1RngProbe.bad-version.jar`。正式 CombatSolver1 JAR、引擎、autosave、通知配置未修改。
- 尚未验证 MTS 实际重新加载此元数据，也没有真实战斗采样。等待用户从 Steam 手动重新进入 Play with Mods；若启动成功且无版本警告，请进入现有/正常开局的 vanilla Ironclad 战斗，在动作队列稳定、玩家可操作时停下并回复。不得为测试修改存档。六流无推进、双端随机 trace、Sentry 实读、真实随机事件验证仍未完成；当前结论 BLOCKED，路线 C 技术结论待定。

### 2026-10-19：首轮真实 RNG 捕获与双端 trace（Sentry 映射待复测）

- 用户从 Steam 启动后已进入稳定 Ironclad 战斗，隔离探针成功输出真实运行快照。状态边界为 COMBAT、玩家回合、currentAction=null，actions/cardQueue/monsterQueue 均为空。六流 aiRng/cardRandomRng/miscRng/monsterHpRng/potionRng/shuffleRng 的 seed0/seed1/counter 在读取前后全部相同，六个对象身份不同；这是本次实机无推进证据。未经脱敏的 RNG 值只保留在被忽略的 `build/rng-capture-validation/live-capture.tsv`。
- 使用同一真实快照逐流执行 Java 与 C++ trace：Java 8 游戏运行时与 `sts::Random` 对每流 18 次固定调用、共 108 行输出及每步 state/counter 完全一致，首次差异无（零差异）。边界回归重编译并运行通过：`RNG_RANGE_BOUNDARY_OK rejected=INT_MIN,-1,INT_MAX accepted=0,INT_MAX-1`。
- 真实反射读出三只 Sentry，HP 39/42/43、beamDmg=10、dazedAmt=3、firstMove=false；静态 C++ 模型用 ascension 阈值得到 10/3，并按 encounter index 奇偶确定首招，与 vanilla Sentry 字节码的初始选择规则一致。但当前 probe 把 Java ArrayList moveHistory 当成数组，导致输出的空历史不可信；同时本行 `nextMove=-1` / `intent=DEBUG` 不能作为已初始化招式状态。因此不计 Sentry 完整映射通过。已修复隔离探针：按 Iterable 输出 moveHistory、记录列表 index，并等待 Sentry 招式和意图就绪；Java 6 编译与包检查通过，临时包已更新，SHA-256 `0F0433AD8C429A28ECF5E8FB9ED2C61A25ED445279B5F7967BC5AD0835A997A4`，需用户再次从 Steam 重启后才能采样。
- 尚无真实随机游戏事件与副本预测的对照。报告 `tools/rng-capture-validation/RUNTIME_RESULT_2026-10-19.md`；本阶段为 **BLOCKED**，不是路线 C GO，也不是 RNG 算法 NO-GO。生产协议/求解器/安全门控/正式 JAR/引擎/玩家存档/通知均未改。

### 2026-10-19：修正探针后完成 Sentry 实读（随机事件对照待定）

- 用户安全重启 Play with Mods 后，修正版探针于 14:30:38 写出第二个真实样本。三只 Sentry 的 groupIndex 0/1/2、`nextMove` 与 `moveHistory` 为 3/4/3、意图为 DEBUFF/ATTACK/DEBUFF；`firstMove=false`、beamDmg=10、dazedAmt=3。STS1 Sentry 字节码及 C++ `MonsterSpecific.cpp` 的首次奇偶索引、后续招式和 ascension 阈值映射一致。第一份样本因探针把 ArrayList 当数组且状态仍 DEBUG 作废，不作结论。
- 第二份真实样本六流均无推进；针对该样本重跑 6 组 Java8/C++ trace，每组 18 行、108 行全匹配，无首次差异。边界回归通过。详情见 runtime result 报告和 ignored `build/rng-capture-validation/` 日志。
- 用户未触发可见随机结果，故“真实游戏随机事件与副本预测”尚未验证；此缺项为证据未取得，不代表算法失败。阶段仍标记 **BLOCKED**，不宣称路线 C GO。后续若不再开展本次随机事件测试，则应保留该限制并清理临时探针。正式 JAR SHA-256 仍为 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。
- 已尝试清理临时 `mods/CS1RngProbe.jar`，但 Windows 报告该文件正被当前运行游戏占用；没有强制结束游戏。待游戏正常退出后再删除临时探针。正式 JAR 未被占用或修改，哈希仍为上列值。
- 只读查看当前游戏画面，手牌为 Exhume/Defend/Strike/Bash/Strike，药水为 Swift Potion 和空槽；该场没有可见随机生成/随机目标效果，未为测试消耗卡牌或药水。随机事件对照因此仍缺证据。

### 2026-10-19：True Grit 随机事件尝试（BLOCKED：预事件快照过期）

- 复用隔离探针和 RNG trace；未修改正式 Mod、求解协议、搜索/执行/安全门控或玩家存档。真实采样 `1792392723519`（本地 14:52:03）处于稳定 Sentry 战斗玩家操作边界，六流读取均无推进。用户确认未升级 True Grit 仍在手牌，屏幕顺序为 Metallicize、Bash、Strike、True Grit、Defend。
- STS1 `TrueGrit.use` 字节码确认未升级分支使用 `ExhaustAction(1,true,false,false)`；`ExhaustAction` 调用 `player.hand.getRandomCard(AbstractDungeon.cardRandomRng)`，`CardGroup.getRandomCard(Random)` 再调用 `random(size-1)`。排除已打出的 True Grit 后，候选数为 4、inclusive bound 为 3。
- 使用 14:52 实拍 `cardRandomRng` 状态，在独立 Java 8 与 C++ `sts::Random` 副本上执行单次 `random(3)`，两端值及后续 state/counter 完全相同，索引为 1；按屏幕顺序映射为 Bash。用户报告并由真实 `combat.jsonl` 记录确认，实际消耗的是 Defend。该差异不能计为算法失败：战斗记录显示快照在第 1 回合，True Grit 实际在第 3 回合才执行，故输入快照不是事件前状态。
- 事件后探针样本 `1792394059618`（本地 15:14:19）处于稳定第 3 回合边界；真实记录中 Defend 已进入消耗堆。`cardRandomRng.counter` 从第 1 回合快照的 0 变为事件后 2；True Grit 自身消耗一次，说明旧快照与事件间至少还有一次该流调用。事件真正发生前没有新鲜快照，因此不可由本次数据判定该事件的 RNG 预测是否正确。既有 Java/C++ 108 步证据保持有效。
- 结论：本次真实随机事件实验 **BLOCKED（采样时机错误）**，不是 GO，也不是算法 NO-GO。真实结果为 Defend、旧快照副本预测 Bash；首次差异明确为陈旧的第 1 回合输入状态。事件后重新采样使用一次性 Attach agent，仅复位隔离探针的内部 `captured` 标记；未触碰 RNG 或战斗状态。游戏仍运行，临时 `CS1RngProbe.jar` 仍被占用，须在玩家正常退出后再删除。正式 JAR/引擎/存档/通知配置未改。

### 2026-10-19：True Grit 同次随机调用观测钩子（BLOCKED）

- 复核未升级 True Grit 路径：`TrueGrit.use` 入队 `ExhaustAction(1,true,false,false)`；动作调用 `player.hand.getRandomCard(AbstractDungeon.cardRandomRng)`；`CardGroup.getRandomCard` 对当前 `group` 顺序执行 `random(size-1)`。候选顺序需以该调用入口实时记录为准，不能用 UI 截图/先前回合手牌替代。
- 隔离新增 `TrueGritSelectionProbe`，仅在上述 hand、RNG、ExhaustAction 三项同时匹配时，于 `getRandomCard` 入口读取六流和候选实例顺序，返回后记录选择实例及六流状态。没有调用原生 RNG、改返回值或修改战斗状态。增加 Java/C++ 单次有界调用 trace；未接入生产代码。
- 离线构建：JDK 6 `javac -proc:none -source 1.6 -target 1.6` 编译隔离探针和 Java trace、`jar cf` 打包且排除编译 stub、MinGW `g++ -std=c++17 -O0` 编译事件 trace 均退出 0。首次遗漏 `-proc:none` 时 JDK 6 因 Java 8 注解处理器 major 52 编译失败，禁用 annotation processing 后通过。旧快照单次 trace 的 Java/C++ 匹配不构成事件级验证。
- 上一场事件已在第 3 回合结束且旧探针已随游戏正常退出后移除；当前无同一事件可采样。新事件级 JAR 未部署，没有本次事件入口/出口记录、实际实例与副本选择对照。保留六流无推进、108/108 trace、Sentry既有证据；最小真实随机事件预测结论 **BLOCKED**，非算法失败。报告见 `tools/rng-capture-validation/RUNTIME_RESULT_2026-10-19.md`。生产协议、求解器、安全门控、正式 JAR、引擎、存档与通知配置未改；`sendToDevs/` 保留。

### 2026-10-19：路线 C 第一阶段状态恢复入口原型（未验收）

- 新增 `docs/LIVE_BATTLE_STATE_V1.md`，记录来源→引擎字段、UUID 与引擎牌号分离、稳定操作边界及未知状态拒绝约束。根据现有源码把第一轮 C++ 原型严格限制为已验证的三只 Sentry vanilla Ironclad 遭遇；不支持其他遭遇时拒绝，不调用 `GameContext::initFromSave` 或 `BattleContext::init`。
- 新增 `vendor/sts_lightspeed/apps/live_battle_state.h` 与 `mcts_state <json>` 入口。解析版本、职业、阶段/队列、六 RNG 状态和不重复流身份、玩家/牌堆/UUID、遗物/药水、Sentry AI 私有字段、怪物招式/历史与计数器，并直接赋值创建 `BattleContext`。旧 `mcts_save`、`replay_search`、`replay_trace` 分支未改；协议命令说明仍保留版本 2 并追加新命令。
- 新增合成解析 fixture `tools/solvertest/fixtures/live_battle_state_v1_sentry.json` 及 `tools/solvertest/live-state-v1-check.ps1`。验证有效 fixture 输出 `STATE0 hp=70 max=80 blk=0 en=3 turn=1`，执行中阶段以退出码 3 安全拒绝；这是 L2 parser 验证，fixture 是合成数据，不是实机证据。
- 构建命令 `tools/build/build-engine.ps1`（显式使用 VS CMake/Ninja 和本机 MinGW 路径）退出 0；`tools/solvertest/live-state-v1-check.ps1` 通过。`tools/solvertest/run.ps1 -Command all` 完成，历史真实差分 70 动作仍为 668/793 匹配、48 个字段差异；未把历史差异计作本次通过。首次构建遇到全局 `Stance` 命名空间及过宽 enum 模板实例化警告，最小修正后构建成功。
- 尚未实现 Java `live_battle_state` 只读采集器、C++ 规范化 `STATE0_DETAIL` 全字段回读/逐项比较、Java 生成文件→C++ 互操作回归或实机快照；因此本阶段未完成，当前仅为 L2 恢复入口原型。尚不能确认 C++ 原型的整根状态与真实运行一致，不接入搜索、计划采用或自动执行，不部署正式 JAR。下一步先实现 Java 侧严格只读快照与同协议回归，再做字段回读差分；需另行取得稳定真实 Sentry 战斗完成 L4。正式部署、玩家存档、通知配置未改；`sendToDevs/` 保持未跟踪。

### 2026-10-19：实时恢复闭环审查（仍阻断）

- 本轮继续检查现有协议/C++ 恢复原型及 `LiveCapture`。确认 `LiveCapture` 的 `rngState` 是合成值，并会初始化/估算怪物后续行为，不能作为实时协议输入；C++ `CardInstance::uniqueId` 是 16 位 dense ID，现有 parser 仅验证 UUID 唯一性，没有保留 UUID↔dense ID 映射。
- 当前协议还包含 `loop_count`、`energy_wasted`、`cards_drawn` 和 32 位 `misc_bits`。它们对应引擎内部统计/模拟标记；尚未证明这些字段在本阶段真实根状态有精确 Java 来源，也未定义哪些是重置统计、哪些影响模拟。因此不能从 fixture 的零值推定实机值，也不能判全字段恢复成功。药水槽、所有 power/status 的逐项映射和 Sentry 私有字段的 C++ 原始/恢复逐字段比较也尚无 Java 快照证据。
- 修复 `tools/solvertest/live-state-v1-check.ps1` 在 Windows PowerShell 5.1 下的预期拒绝路径：引擎把拒绝原因写入 stderr 时，`$ErrorActionPreference='Stop'` 会在检查退出码前终止脚本；现在只对该原生命令临时使用 `Continue`，随后恢复原设置。正向恢复 fixture 与 `EXECUTING_ACTIONS` 退出码 3 拒绝均通过。此为测试脚本修复，不改变引擎行为。
- 验证：`tools/build/build-engine.ps1` 退出 0（本次 Ninja 显示 no work to do；引擎 SHA-256 `84D130566CF149A8B50BBCA7AA38795A1146E15FF7D3ACD21932E1586FDD6A24`）；`tools/solvertest/live-state-v1-check.ps1` 退出 0；`tools/solvertest/run.ps1 -Command all` 退出 0，真实历史语料仍报告 70 个差异字段、668/793 匹配，属既有诊断结果。没有 Java collector、C++ `STATE0_DETAIL`、真实 Java→文件→C++ 互操作或本轮实机快照；进程检查未发现运行中的 STS1。未运行完整 Mod 构建，没有部署或改存档。
- 结论：仅合成入口 L2 回归通过；实时状态恢复闭环未达成，不能进入实机验收。首个实质阻断是 Java 真实快照与协议字段源映射未实现/未证明，且 UUID 与模拟器统计映射不完整；本轮未制造伪造样本或扩大支持范围。生产 JAR、安全门控、通知配置和玩家存档未修改；`sendToDevs/` 保留未跟踪。

### 2026-10-19：隔离实时战斗来源采集器（实机快照阻断）

- 新增 `tools/live-state-validation/LiveBattleStateProbe.java`：仅在 COMBAT、WAITING_ON_USER、当前动作为空、无出牌结算、三条动作队列为空且无弹窗的阶段尝试读取；限定 vanilla Ironclad 三只 Sentry。只读取玩家基础字段/powers、四类牌堆中的卡牌 UUID/ID/升级/费用/misc/标志、遗物/药水、怪物槽位/HP/block/状态/意图/招式历史/Sentry 私有字段，以及六条 RNG 流的 state0/state1/counter/对象身份。对六流前后状态及两次完整源读取作一致性检查，不调用 RNG 抽样方法。使用 `Settings.seed`，不读取 autosave。
- 探针输出版本化 `live_battle_capture_report`，字段来源随报告记录。它不会输出或冒充可被 C++ `mcts_state` 接受的 `live_battle_state`：`loop_count`、`energy_wasted`、`cards_drawn`、`misc_bits` 没有已证明的真实游戏来源；牌 UUID↔C++ dense ID 运行时映射尚未实现。玩家/怪物 power、药水运行时特有字段也尚未完成协议枚举映射。因此现有字段只是第一阶段原始来源观测，不可直接送入引擎。
- 隔离 JAR 用本机 IntelliJ JBR `javac --release 8 -proc:none` 编译，class major=52，MTS 元数据版本 3.30.3；JAR 不包含 API stub。构建脚本 `tools/live-state-validation/build-probe.ps1` 退出 0。临时部署包 SHA-256 `22A8324DCD2DC231602821F61F0A1DA710AF7BE6E3701D5EB263D989033B2314`；正式 `mods/CombatSolver1.jar` SHA-256 保持 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。
- 两次用户确认的真实启动尝试均没有生成战斗快照。第一次确实运行到稳定边界，但报告错误为误读 `AbstractDungeon.seed`（首轮报告 `build/live-state-validation/first-attempt-error.json`，SHA-256 `69929AFA8E21D85F522C4F42F71389652B2B7FFDAADD2F573DC126C19301483B`）。修正为项目现有 `Settings.seed` 后，第二次加载探针并到达稳定边界，错误为把静态 `actNum` 经普通对象读取，反射目标落到 `java.lang.Class.actNum`（`build/live-state-validation/second-attempt-error.json`，SHA-256 `FF12F1B57940E752DCA025ED72EB4EBF46384517571680C1F744325ACF7D491A`）。随后静态字段访问已在源码修正并离线重新编译，但依照两轮停止规则，没有进行第三次实机重启。两份文件都是错误报告，不是快照，故没有真实快照 SHA 或 RNG 无推进结果。
- 本轮构建/验证：`tools/live-state-validation/build-probe.ps1` 对最终源码编译退出 0（Java 8 class major 52）；JAR 条目检查确认仅包含 probe class 与 MTS 元数据。旧合成 `mcts_state` 检查未作为本轮实时采集验收依据。未构建/部署正式 CombatSolver1 JAR，未接入生产协议、搜索或执行，未修改玩家存档/通知配置。结论：真实来源读取代码已编码（L1），隔离构建通过（L2），真实快照采集 **未通过/阻断**，没有 RNG 前后实测证据；停止进一步重试。第二版临时 `mods/CS1LiveStateProbe.jar`（SHA-256 `22A8324DCD2DC231602821F61F0A1DA710AF7BE6E3701D5EB263D989033B2314`）仍因当前游戏进程占用而无法删除；第三版只在 workspace 编译，未部署。需游戏正常退出后移除第二版临时 JAR。

### 2026-10-19：实时采集探针离线字节码审计（等待下一次实机采集）

- 审阅两份历史错误 JSON：首次因从 `AbstractDungeon.seed` 读取种子失败；第二次已到 `COMBAT/WAITING_ON_USER` 稳定门槛，因对 `AbstractDungeon.class` 按实例读取静态 `actNum` 而失败。当前源码已分别使用 `Settings.seed` 静态读取及 `AbstractDungeon.actNum` 静态读取。新离线审计从本机 `desktop-1.0.jar` 解析类文件字段描述符/访问标记，确认 `Settings.seed: Long static`、`actNum: int static`，所以这两处访问路径修正有字节码证据。
- 新增 `FieldBytecodeAudit.java` 与 `audit-fields.ps1`。审计当前探针的反射目标、字段描述符和静态/实例属性；全部实际读取项通过。运行 `powershell -ExecutionPolicy Bypass -File tools/live-state-validation/audit-fields.ps1` 退出 0，报告 `build/live-state-validation/field-audit.txt`。报告列出 `UNAVAILABLE`：`AbstractRoom.name`、`AbstractPower.justApplied`，探针不读取/不填值；对应协议和引擎状态仍不完整。
- 字节码还确认 `GameActionManager.turn` 是静态 int，已改为静态访问；`AbstractCard.uuid` 为 `java.util.UUID`，保留通过 `String.valueOf` 输出完整 UUID；`AbstractCard.isEthereal` 是实例 boolean 字段，已改用字段读取；怪物 HP/Block/powers/id 的声明在 `AbstractCreature` 父类，逐级继承访问可解析；`moveHistory` 是 `ArrayList`；`AbstractMonster.intent` 是其内部 `Intent` enum。探针对必需对象/字段值为空会报出具体来源错误。探针保持 `live_battle_capture_report`，不是 `live_battle_state`，不生成伪完整协议或 C++ 可导入输入。UUID↔C++ dense ID、power `justApplied`、房间 encounter 名称、engine-only `loop_count/energy_wasted/cards_drawn/misc_bits` 仍未映射。
- `tools/live-state-validation/build-probe.ps1` 退出 0，探针 class major=52（Java 8）；JAR 仅有探针类和 MTS 元数据，隔离包 SHA-256 `DD93392B71184DFD73AA2AC86DC5B5E710445C94DCB7E238BD627C6F2FFB424C`。`git diff --check` 退出 0。正式 JAR SHA-256 仍为 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。进程检查未见 Java/STS 窗口后，已移除游戏 mods 目录中的临时 `CS1LiveStateProbe.jar`；未启动游戏、未改存档和正式部署。
- 实机采集仍未完成：下一次唯一验收目标是稳定 vanilla Ironclad 三 Sentry 战斗生成一个 `CAPTURED_SOURCE_ONLY` 报告，且 `rng_before == rng_after`、两次 source 快照相等、边界前后仍稳定；若有未料到的缺失字段须输出 `ERROR`/`REJECTED`，不得把报告视作 C++ 输入。该目标不是路线 C 状态恢复或 L4 求解验收。保留 `sendToDevs/`、通知配置和既有 Git 历史。

### 2026-10-19：live_battle_state v1 必填字段与房间来源收窄（L2）

- `loop_count`、`energy_wasted`、`cards_drawn` 从 JSON 输入移除。源码用途已核对：`BattleContext.h` 默认初始化为 0；`BattleContext.cpp` 在动作循环、结束回合及抽牌中更新；`loopCount` 用于循环保护/诊断，`energyWasted` 与 `cardsDrawn` 的搜索评分见 `BattleScumSearcher2.cpp:418-419`。新 `BattleContext` 根状态保留这些 C++ 默认值，模拟实现和评分未改。专项恢复输出确认根值均为 0；现有 Solvertest 全套回归通过。
- `misc_bits` 不再作为调用者提供的任意 32 位输入。`miscBits[0]` 的设置仅经 `Monster::stealGoldFromPlayer`；该函数当前由 `MonsterSpecific.cpp:913,923,959,970` 的偷窃招式调用，结算奖励由 `BattleContext.cpp:499-508` 读取。仅在遭遇已匹配 THREE_SENTRIES、三只均验证为 SENTRY 后显式 reset。其他遭遇（专项以 TWO_THIEVES 验证）在恢复前拒绝，不能默认清零继续。其余位没有完整游戏字段映射，仍不宣称可恢复。
- `room_context` 成为必填字段，含 `kind`、具体 room class、`elite_trigger` 及 event class/encounter/enemy index（地图房间要求后三者为空）。只接受地图 `MonsterRoomElite` 的 THREE_SENTRIES。根据现有 STS1 字节码证据，`DeadAdventurer` 的 `enemy` 和 `getMonster()` 可产生“3 Sentries”，并在 `EventRoom` 中安装该遭遇、设置 `eliteTrigger`；C++ 不保存其事件来源/选择上下文，因此 EventRoom 明确拒绝。当前没有 Java 快照生产器，协议新字段尚无本轮实机来源值。
- 更新合成 fixture、拒绝回归与 `docs/LIVE_BATTLE_STATE_V1.md`。`mcts_save`、replay 分支和旧 `STATE0` 格式未改；此前实验性 mcts_state JSON 若携带 `battle_counters` 现在明确拒绝并需按修订 schema 重生成。
- 验证：`tools/build/build-engine.ps1` 默认命令因当前 PATH 找不到 cmake 退出 1；使用本机 VS 18 CMake/Ninja 与已有 MinGW g++ 的显式路径重跑退出 0，engine SHA-256 `C638FF2B8FEF3299F17DAC1CD37D42C911D9120CFC4CBE51A8874157B798B9A4`。`tools/solvertest/live-state-v1-check.ps1` 退出 0：有效根状态 1 项、内部初始化 1 项、五种拒绝通过。`tools/solvertest/run.ps1 -Command all` 退出 0；其中既有真实记录差分仍显示 70 个差异字段、668/793 匹配，不代表本协议恢复或实机通过。最终 `git diff --check` 退出 0。未启动游戏、未改存档、未部署或修改正式 JAR；没有 Java→C++ 实时快照、完整状态差分或 L3/L4 证据。
### 2026-10-19：实时输入闭环实施尝试（尚未闭环，L2 局部）

- 隔离探针增加来源门控：只接受运行时具体类 MonsterRoomElite、IRONCLAD，以及恰为 vanilla Sentry 类/ID 的三只怪物；其他来源会拒绝。来源报告附 room_context，事件房可记录 EventRoom 与 Dead Adventurer 的 event class、enemy index、getMonster() 结果，但事件场景不生成可导入状态。牌堆读取保留 UUID 全字符串，并生成 hand/draw/discard/exhaust 顺序对应的 dense ID 映射；重复或非 36 字符 UUID 拒绝。该文件仍是 live_battle_capture_report，不是 live_battle_state v1。
- 扩展 FieldBytecodeAudit，使用本机 desktop-1.0.jar 核对 room 来源、玩家计数、GameActionManager 计数、AbstractCreature.lastDamageTaken、DeadAdventurer.enemy/getMonster 等字段。命令 powershell -ExecutionPolicy Bypass -File tools/live-state-validation/audit-fields.ps1 退出 0；报告在忽略目录 build/live-state-validation/field-audit.txt。该静态检查只确认声明/描述符/静态属性，不证明所有字段语义和实机值。
- C++ mcts_state 新增每张牌的 STATE0_DETAIL_CARD 输出：完整 UUID、dense ID、原始 pile/index、协议卡牌 ID、C++ 规范化卡牌 ID和映射状态。首次测试发现 STRIKE_RED 与 C++ 输出 Strike_R 是别名表示差异；现按同一 CardId 枚举值比较，记录 source_id 和 normalized_id，不是状态不匹配。重复 UUID拒绝用例新增。
- 构建：引擎通过本机 VS 18 CMake/Ninja 与 MinGW g++ 构建，退出 0，SHA-256 DF80BBA643B9B431B881E13B10102CD5BDB6E97717BC68CF6927A663D77EC4D7。tools/solvertest/live-state-v1-check.ps1 退出 0：合成根 1 项、身份映射 1 项、内部计数初始化 1 项、6 类拒绝通过。tools/solvertest/run.ps1 -Command all 退出 0；历史真实差分保持 70 项差异、668/793 匹配。探针 Java 8 class 编译检查退出 0（只编译到 build 检查目录），git diff --check 退出 0。
- 阻断：Java 生产器还未转换为 live_battle_state v1，协议中 player counters/status 的精确映射、卡牌的引擎 ID 规范化和若干 C++ 专用战斗 bookkeeping 尚未完整审计/实现；当前 C++ 详细输出只覆盖卡牌身份，不是完整 STATE0_DETAIL，没有逐字段 Java→C++ 比较。故没有 Java 生成协议文件、端到端互操作测试或合法实时快照。本轮不构建/部署隔离探针 JAR，不请求 Steam 实机操作；没有 L3/L4 结果。正式 JAR、存档、通知配置、旧 mcts_save/replay 路径未改。
### 2026-10-19：Java→C++ 离线互操作（部分完成，L2）

- 增加严格 Java v1 JSON 写入门及 fixture-only 驱动。驱动必须读取带 `OFFLINE_FIXTURE_NOT_REAL_GAME_STATE` 标记的 fixture；缺少协议必需字段、房间来源非法、RNG 流缺失/计数非法、UUID 无效/重复或怪物数量不符时拒绝写文件。Gson 启用 null 序列化，保留协议要求的显式 null 房间来源字段。
- C++ `mcts_state` 新增 `STATE0_DETAIL_FIELD`：当前实现集覆盖玩家基础数值、六条 RNG、怪物槽位/生命/格挡/标志/招式历史，以及空状态集；卡牌 UUID、dense ID、牌堆次序和卡牌规范化检查沿用 `STATE0_DETAIL_CARD`。这不是协议所有字段的完整差分。
- 离线互操作实际执行 Java 8（class major 52）读取标记 fixture 并生成 JSON，再由 C++ 引擎读取；当前实现集 49 项 MATCH、0 MISMATCH、0 UNAVAILABLE，首次差异 NONE。证据：`build/live-state-validation/offline-interop-diff.txt`。它仅是 fixture L2，不是真实内存快照。
- 定向协议测试拒绝 10 类错误输入并通过。`tools/solvertest/run.ps1 -Command all` 本轮退出 0；历史真实记录差分仍是 70 个差异字段、668/793 匹配（不是本协议验收）。C++ 引擎用 VS 18 CMake/Ninja 增量构建退出 0，Java fixture producer 编译为 class major 52；`git diff --check` 退出 0。原生 `STATE0` 和旧入口未有意改动。
- 发布阻断仍在：实时探针尚未映射/序列化完整协议所需的玩家 powers/状态与计数、卡牌统计、遗物 counter、药水槽完整语义、怪物 powers/AI 与 group 状态、战斗队列字段等；当前未实现字段没有用默认值填充。没有实机采集、全字段差分、C++ 搜索或执行验收。
### 2026-10-19：全协议字段映射审计（离线，未闭环）

- 新增 `docs/LIVE_BATTLE_STATE_V1_FIELD_AUDIT.md` 和可复跑的 `tools/live-state-validation/write-field-manifest.ps1`。按协议 fixture 的 required schema 展开 155 个规范化路径形状（包含重复数组元素模板和空数组元素结构）：83 VERIFIED、16 UNVERIFIED、56 UNAVAILABLE。VERIFIED 表示来源声明/反射路径有证据，不代表采集器到 C++ 的端到端映射通过。
- Java probe 为 RNG 记录增加稳定 `stream_id`（对应 `AbstractDungeon` 的六个字段名）；诊断报告列出仍不能形成完整协议的字段及原因。probe 不调用协议 writer，仍只生成 diagnostic report。
- C++ 离线 detail 报告目前为 49 行已实现字段，全部 MATCH；按 155 path-shapes 计，106 行没有逐项输出/比较，首个未报告项为 `$.act`。差分结果明确是 PARTIAL，未将未比较项算成 MATCH。
- 主要阻断：无稳定 battle_id；玩家/怪物 status 的 `just_applied` 无可信源；玩家运行计数和 card_counters 尚未实现精确采集/映射；卡牌 ID/special_data 规范化、遗物计数、药水槽、Sentry unique powers/miscInfo、monster group flags/cursor 及战斗队列终态尚未全字段还原。字段细节见审计表。因必需字段不完整，Java 不生成 `live_battle_state`，当前不具备实机采集前提。
- 验证：`audit-fields.ps1` 退出 0（installed `desktop-1.0.jar` 字段/描述符检查通过）；Java 8 互操作编译和执行退出 0、class major 52；C++ engine build 退出 0（Ninja no work）；`tools/solvertest/live-state-v1-check.ps1` 退出 0（C++ 拒绝 10 项、Java writer 拒绝 5 项）；`tools/solvertest/run.ps1 -Command all` 退出 0；`git diff --check` 退出 0。完整协议差分仍不通过，不能把 49 项子集视为验收。
- 本轮禁止启动游戏和部署。协议 fixture 仍明确标记为离线，不是实机样本；没有完整 Java 实时协议快照，因此当前不具备实机采集条件。

### 2026-10-19：155 路径分类与逐路径差分修正（L2，仍阻断）

- 将 155 个规范化路径形状逐项分类：REQUIRED 109、CONDITIONAL 36、DERIVABLE 8、INTERNAL 0、UNUSED 0、BLOCKED 2。`INTERNAL/UNUSED=0` 是因为清单只收录协议路径；三个搜索计数器没有协议路径，另列在协议说明中。`battle_id` 无稳定来源；`monster_turn_idx` 的玩家操作边界恢复语义尚无证据，均保持 BLOCKED。
- 扩展字段审计表，分离字段来源/反射证据与端到端映射；旧 `VERIFIED` 仅表示来源声明或字节码证据，不代表生产采集器接通。新增 `live-state-v1-full-diff.ps1`，将 C++ 输出与全部路径形状对账，未输出项显式 `UNAVAILABLE`，空数组项标 `NOT_APPLICABLE`，不再用 `155 - 输出行数` 推算覆盖率。
- Java 8 fixture → C++ `mcts_state` 当前逐路径结果：50 MATCH、0 MISMATCH、66 UNAVAILABLE、39 NOT_APPLICABLE；首次差异为 `$.act: UNAVAILABLE`（元数据被解析但没有从恢复根状态回读），并非真实差异。109 项 REQUIRED 中 50 MATCH、27 UNAVAILABLE、32 在该合成 fixture 中因空集合 NOT_APPLICABLE；条件字段 29 UNAVAILABLE、7 NOT_APPLICABLE，派生字段 8 UNAVAILABLE。此结果只证明部分离线恢复路径，实际 Java 实时协议生产仍未实现，故可信实机映射接通数为 0。
- 仍未解决的必需输入包括 ACT/升格/楼层/种子/回合/角色/遭遇元数据回读，完整 Sentry 属性与 AI 字段，card counters 与完整 UUID 反向身份、stance/last-target、药水和遗物状态等。必需字段不可用即不得采集为完整协议，也不得进入搜索。
- 验证：`tools/solvertest/live-state-v1-check.ps1` 退出 0（Java 8 class major 52；C++ 导入 1 个离线 fixture；拒绝路径 C++ 10、Java writer 5）；C++ 构建退出 0（Ninja no work）；`tools/solvertest/run.ps1 -Command all` 退出 0（70 个历史差异字段、668/793 匹配）；`git diff --check` 退出 0。无真实快照、无实机测试、未启动游戏或部署 JAR。

### 2026-10-20：battle identity / act / monster cursor 阻断复核（L2，仍阻断）

- `battle_id` 全仓调用链只有 Java diagnostic 的 UNAVAILABLE 标记、Java wire writer 的必填项，以及 C++ `restore()` 的非空检查；不参与 BattleContext、搜索、路线有效性或执行门控。将协议字段改为 `capture_id`，明示其只是事务 UUID。Java serializer 缺省生成 UUID，并写 `.capture.json` sidecar（capture_id + 序列化文件 SHA-256）；C++ 只验证 UUID 并在 STATE0_DETAIL 标 `NOT_APPLICABLE`，不把它作为游戏状态或执行凭证。
- `act` 已由 Java 源采集器从 `AbstractDungeon.actNum` 静态 int 读取；旧 C++ parser 曾用 `(void)number<int>(j,"act")` 丢弃。新增 `BattleContext.act`，旧 `BattleContext::init(gc)` 从 `GameContext.act` 同步，新 mcts_state 赋值后由 STATE0_DETAIL 读取回比较。离线回归把 act 从 1 改为 3，恢复输出为 3，证明不是输入回显。
- `monster_turn_idx` 是 C++ `BattleContext` 的内部怪物槽遍历游标，不是 STS1 字段。源码显示初始 sentinel=6、玩家结束回合后重置 0、每个原始怪物槽处理后递增，完整怪物轮次结束时停在 monsterCount。该值依赖引擎控制流程；当前没有经过证实的实时阶段/原生字段映射能无歧义重建，所以保持必需输入阻断，不按 turn 推算、不填默认值。
- 最新离线差分 155 路径：51 MATCH、0 MISMATCH、64 UNAVAILABLE、40 NOT_APPLICABLE。分类更新为 REQUIRED 109、CONDITIONAL 36、DERIVABLE 8、UNUSED 1（capture_id）、BLOCKED 1（monster_turn_idx）。REQUIRED 内：51 有 C++ fixture 回读、26 未回读、32 仅因合成 fixture 中空集合而 N/A。真实 Java collector 仍只生成 diagnostic report，故真实来源→完整协议→C++ 链路中已接通字段为 0/109。
- 验证：Java 8 编译/class major 52、capture UUID/SHA-256 sidecar 检查、C++ act 变值回读和 11 类拒绝测试由 `tools/solvertest/live-state-v1-check.ps1` 通过；C++ Release 引擎构建退出 0（VS 18 CMake/Ninja）；`tools/solvertest/run.ps1 -Command all` 退出 0（历史差分仍 70 项、668/793）；`git diff --check` 退出 0。未启动游戏、未部署 JAR、未改存档。
- 结论：协议生产器及其他必需字段仍未闭环，且 `monster_turn_idx` 来源未解决；不能生成可信完整协议文件，不具备实机采集条件。

### 2026-10-20：受限 Sentry 字段差分与游标初始化复核（L2，实机仍阻断）

- `monster_turn_idx` 从 wire fixture、Java必需字段和 C++输入读取中移除。源码证明原生玩家边界游标不必恒为 6：首轮可为 sentinel 6，三 Sentry 怪物轮结束后可为 3；但 accepted `PLAYER_NORMAL` 且队列空时，该游标在玩家动作阶段不被消费，每条受支持的结束回合动作都先重置为 0，再进入 MonsterGroup 遍历。`mcts_state` 在新根中初始化内部 sentinel 6，不改生产搜索路径。
- 新增 `live_state_cursor_check`，从同一真实语义模型比较初值 6 与上轮终点 3，运行 `UnnamedEndOfTurnAction`、MonsterStartTurnAction 和实际 `doMonsterTurn` 槽遍历；两者都访问槽位 `0,1,2`，Sentry结果相同。属于 C++离线回归，不是游戏实机证明。
- 原 26 个 `REQUIRED/UNAVAILABLE` 路径逐一复核：6 个 CardManager计数由 stable piles 精确推导并在恢复时与协议值交叉验证；其余 20 个添加了真实 BattleContext/enum/status读回，或由严格 allowlist/升格约束验证。`docs/LIVE_BATTLE_STATE_V1_FIELD_AUDIT.md` 列出每条路径、推导/读取方式及边界。六计数分类现为 DERIVABLE，不再伪称独立游戏字段。
- 将协议 fixture 扩为非空 hand/draw/discard/exhaust，并含升级 Bash、`special_data=7` 的 Ritual Dagger、遗物和 Happy Flower 计数、Fire Potion、玩家 WEAK、Sentry WEAK 与 just-applied。状态和卡牌字段通过恢复后的 C++ 对象回读；药水导入支持 `Potion Slot` 原生名，并修正 relic bitset 详细输出不得读取 128 位范围外枚举值。
- 原 32 个 required N/A 路径中，30 个牌堆字段现由非空 fixture逐项匹配；limbo/stasis两项明确验证稳定三哨卫状态仅接受空集合，并增加非空拒绝回归。当前完整差分为 149 路径：148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE（仅 transport `capture_id`）。分类为 REQUIRED=100、DERIVABLE=14、CONDITIONAL=34、UNUSED=1；其中 REQUIRED 100/100 fixture MATCH。
- 验证：VS 18 CMake/Ninja `cmake --build build/engine --config Release --target combatsolver1-engine` 退出 0；`tools/solvertest/live-state-v1-check.ps1` 退出 0（Java class major 52、Java写入文件→C++恢复，14类C++拒绝、5类Java写入拒绝、非空卡牌/状态/遗物/药水、cursor顺序回归）；`tools/solvertest/run.ps1 -Command all` 退出 0（现有历史记录仍为70个差异、668/793字段匹配）；完整路径差分 `MATCH=148/MISMATCH=0/UNAVAILABLE=0/NOT_APPLICABLE=1`；`git diff --check` 退出 0。
- 阻断：`LiveBattleStateProbe` 仍只输出 diagnostic report，不产出完整可信实时协议。Field audit中的83 VERIFIED是源码/成员证据，不是Java运行时采集与完整序列化；真实端到端 Java live snapshot 接通仍为0。没有真实快照、没有C++真实战斗恢复、没有L3/L4。本轮未启动游戏、未部署JAR、未改存档；sendToDevs/、通知配置及既有工作区改动保留。结论：尚不具备实机采集条件。

### 2026-10-20：Java 实时协议生产链路接通评估（未达成，保持 fail-closed）

- 用户批准的目标是把实时游戏对象映射到现有 v1 协议；审查确认 `LiveBattleStateProbe.attempt()` 仍只调用 `captureSource()` 并输出 `CAPTURED_SOURCE_ONLY`，生产采集没有调用 `writeProtocol()`。离线 producer 只接受带 `OFFLINE_FIXTURE_NOT_REAL_GAME_STATE` 标记的合成 fixture，不能证明生产映射。
- 修复 `tools/solvertest/live-state-v1-full-diff.ps1` 的协议清单解析边界：它此前把审计文档附录中的 26 条历史字段行追加到协议差分，报告错误扩成 175 路径并分类成 `BLOCKED=26`。现在只读取 `Protocol path` 主表；专项回归固定检查 149 路径、REQUIRED=100 和分类统计。
- 生产映射仍有具体缺口，故未把任何诊断对象写成正式协议，也未构建/部署隔离 JAR：探针中的 `AbstractCard.cardID` 和 `misc` 尚无到 C++ `CardId`/`special_data` 的生产转换；Sentry `moveHistory` 数值没有转换为 C++ move enum；玩家 `strength/dexterity/focus/artifact`、状态 timing、部分战斗 counters 及 `last_targeted_monster` 没有完成可信来源或受限场景的无歧义推导。Writer 的 fixture 验证不弥补这些缺口。
- 本机 `desktop-1.0.jar` 字节码字段审计退出 0：84 个字段、4 个方法匹配，2 个已明确不可用（`AbstractRoom.name`、`AbstractPower.justApplied`）；该审计只覆盖探针实际声明的访问项，不等于 100 个协议字段映射完成。
- 验证：`tools/solvertest/live-state-v1-check.ps1` 退出 0，Java 8 major=52；合成 Java→文件→C++ 差分为 149 路径、148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE（`capture_id`）。`tools/solvertest/run.ps1 -Command all` 退出 0，历史实录诊断为 70 个差异、668/793 字段匹配。`tools/build/build-engine.ps1` 首次因 `cmake` 不在 PATH 失败(exit 1)，显式指定 VS 18 CMake 后退出 0，Ninja 无需重编。未启动游戏、未采集实机状态、未生成候选 JAR；不具备实机采集准备条件。

### 2026-10-20：卡牌身份/实例状态首个端到端切片（L2，非实机）

- Java `LiveBattleStateProbe.cards()` 现在将 STS1 `AbstractCard` 字段转换为协议卡牌实例；明确白名单仅 6 个经本机 `desktop-1.0.jar` 构造器字节码确认的原生 ID：`Strike_R`、`Defend_R`、`Bash`、`True Grit`、`Searing Blow`、`RitualDagger`。其余卡牌 ID fail-closed。`timesUpgraded` 仅接受 0/1；`cost/costForTurn` 做 int8 范围校验；完整 UUID、`freeToPlayOnce`、`retain` 直传。
- `special_data` 转换有卡牌区分：Searing Blow 取实时 `timesUpgraded` 映射到 C++ 的升级深度（要求 `misc==0`）；Ritual Dagger 将真实 `misc` 保留为 C++ `specialData`；其他白名单卡要求 `misc==0`。Rampage 被明确拒绝：游戏按 UUID 修改卡牌伤害，而当前 C++ `specialData` 的 live-state 等价来源未证实。
- C++ `RestoredState` 保存完整 UUID→dense ID 顺序关系，按 hand/draw/discard/exhaust 分组顺序恢复 `CardInstance::uniqueId`；`STATE0_DETAIL_CARD` 从恢复结果回读 UUID、card ID、堆位置、升级、special data、费用和费用回合字段。恢复器也拒绝白名单外卡牌、错误颜色、非支持的 special_data 和超范围费用。
- 新增离线源字段 fixture：7 个实例覆盖四牌堆、同 ID 不同 UUID、升级 Bash/True Grit/Searing Blow、Ritual Dagger 非零 misc。Java 写入协议文件后由 C++恢复并逐实例输出，7/7 卡牌状态 MATCH；完整协议差分149路径为148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 transport capture_id NOT_APPLICABLE。另有 Java 与 C++ 对 unknown ID、Rampage、升级越界、misc 越界、UUID 冲突和非法 special_data 的拒绝回归。所有输入均显式标为离线 fixture。
- 验证：`tools/build/build-engine.ps1 -CMake '<VS 18 CMake>'` 退出0，engine SHA-256 `8B1824AA6388B4B64794811F92CED31940350A283A8709F29E163CF6E0C26BFE`；`tools/solvertest/live-state-v1-check.ps1` 退出0，Java `--release 8` 且 class major=52；`tools/solvertest/run.ps1 -Command all` 退出0（历史实录仍70差异、668/793字段匹配）。没有启动游戏、构建/部署探针 JAR 或修改存档。
- 边界：这是离线卡牌序列化与 C++ 根状态回读切片，不是完整实时战斗协议生产链，也不证明六张卡的实机规则全覆盖。其他卡牌、Rampage 动态进度及其他实时字段仍阻断完整快照生成；不得据此进入实机采集或自动执行。
### 2026-10-20：玩家基础战斗字段映射切片（离线 L2；未实机验收）

- 目标限定为 `live_battle_state v1` 已定义的玩家生命/格挡/能量、回合、可行动阶段与队列字段，不扩展卡牌范围或接入执行。Java 探针新增协议字段切片：`hp`/`max_hp`/`block` 直接读 `AbstractCreature` 整数实例字段，`energy` 调用静态 `EnergyPanel.getCurrentEnergy()`，`energy_per_turn` 读 `EnergyManager.energyMaster`，`card_draw_per_turn` 读 `AbstractPlayer.gameHandSize`；原协议没有单独的 `max_energy` 字段，未新增或重命名。
- 稳定阶段继续要求房间 `COMBAT`、管理器 `WAITING_ON_USER`、未结束回合、无当前动作/出牌、三队列为空且无弹窗；另明确拒绝 `AbstractRoom.isBattleOver/skipMonsterTurn` 与 `AbstractPlayer.endTurnQueued`。一次采集中两次源快照会比较新增字段。检查实际字节码时发现并修正 `endTurnQueued` 的真实所有者为 `AbstractPlayer`（不是 `GameActionManager`）；ASM 字段审计现在对其及 room 两个边界 flag 执行描述符/实例属性核对。
- C++ 输入校验拒绝非法 HP、负格挡/能量；`STATE0_DETAIL` 对玩家基础值和回合字段从恢复后的 `BattleContext`/`Player` 回读。边界 `stage` 从实际 `inputState` 导出，动作与卡牌队列长度使用恢复结构的队列长度。native `monsterQueue` 本身不属于 C++ 队列模型，但 Java 端必须读取并确认它为空，传入的协议断言也必须为真。
- 回归新增正常和边界值、变更 HP/maxHP/block/energy/energy_per_turn/card_draw_per_turn/turn 后从 C++ 恢复结构逐字段回读，以及阶段、回合结束标记、队列非空和非法数值拒绝。player power 的 `just_applied` 仍无 STS1 可信来源；不会填默认值，完整快照仍须拒绝涉及该状态的输入。
- 证据：`powershell -ExecutionPolicy Bypass -File tools/live-state-validation/audit-fields.ps1` 退出 0，实际游戏 JAR 字段审计 `FIELD_AUDIT_RESULT=PASS failures=0`；`powershell -ExecutionPolicy Bypass -File tools/build/build-engine.ps1 -CMake '<VS CMake path>'` 退出 0，引擎 SHA-256 `7AC3D122924ADFFB5770D0757D963DA2BF7BF9AC6C91DFF6E59D84A50934111B`；`powershell -ExecutionPolicy Bypass -File tools/solvertest/live-state-v1-check.ps1` 退出 0（fixture 仍是合成数据，Java 8 major=52、既有六卡映射回归保持通过）。本段证据为字节码/离线 L2，不是实机采集或 L4。
- 范围字段计数：15 个协议字段有已确认来源并写入诊断协议切片（玩家 6 个数值、turn、stage、队列/边界 8 个）；其中 14 个恢复到 C++ 字段或可读回的内部状态，`queues_empty` 作为 Java 三队列稳定性断言被 C++ 校验，C++ 没有原生 monsterQueue 成员可单独回读。离线数值/边界测试匹配通过；player statuses 的 `just_applied` 仍不可用，不能计入完整映射。
- 最终验证：`tools/solvertest/run.ps1 -Command all` 退出 0（测试汇总“全部自检通过”，历史实录诊断仍为70差异、668/793匹配）；`git diff --check` 退出 0。没有游戏启动、候选 JAR、正式部署或存档更改。Java 实时对象未在本轮游戏运行中采样，因此以上是字节码与 L2 fixture 证据，不是 L3/L4；真实玩家 power 状态映射和完整 `live_battle_state` 实时生产仍未通过。

### 2026-10-20：玩家 Power 时序字段审计与协议最小修正（L2，非实机）

- 根因：`justApplied` 不属于通用 `AbstractPower`。本机 STS1 `desktop-1.0.jar` 字节码确认 `WeakPower`、`FrailPower`、`VulnerablePower`、`DoubleDamagePower`、`DrawReductionPower` 各自声明 private instance `boolean justApplied` 并覆写 `atEndOfRound()`；`IntangiblePlayerPower` 无此字段。Power ID 与 C++ canonical status 的映射在探针中显式校验，不按相似名称猜测。
- C++ 中该位改变结算：`Player::applyAtEndOfRoundPowers()` 对 WEAK/FRAIL/VULNERABLE/DOUBLE_DAMAGE 在 true 时仅清位、跳过一次递减，false 时递减；`BattleContext::afterMonsterTurns()` 对 DRAW_REDUCTION 在 true 时清位并保留状态，false 时移除并恢复每回合抽牌数。INTANGIBLE 按当前 C++ 模型无条件递减，不读取该位。
- 最小修改：Java 诊断采集器为五种已证实的具体类读取 private 字段并产出 canonical timing status，校验原生 ID；ASM 字节码审计新增 private/non-static/descriptor 检查及 `atEndOfRound()` 覆盖检查，并确认 `IntangiblePlayerPower` 无声明字段。Java writer 与 C++ parser 将 `just_applied` 改为仅对五个会读取该位的 status 必填；其他 status 携带该属性、敏感 status 缺失/类型错误、未知 ID、来源类/ID 不匹配均拒绝。C++ `STATE0_DETAIL` 对 INTANGIBLE 不回显伪造 false。
- 新增时序正反例回归：五种 Power 的刚施加/已过期分支共 10 例，验证状态是否保留/递减、位清除及抽牌恢复差异。合成 fixture 覆盖五个敏感 status 与不带该属性的 INTANGIBLE；保留六卡映射回归。
- 验证：`audit-fields.ps1` 退出 0，5 个具体 private instance 字段、5 个 `atEndOfRound()` 方法与 INTANGIBLE 字段缺失检查通过；C++ Release 构建退出 0，engine SHA-256 `B28EEE67532DF60359E04AA521CAA3D880ECAD1322F15B80694C039EA84367B4`；`live-state-v1-check.ps1` 退出 0，Java 8 `--release 8` / class major 52，149 路径离线差分 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 `capture_id` NOT_APPLICABLE，33 类 C++ 与 7 类 Java writer 拒绝通过，卡牌实例回归 7/7；`tools/solvertest/run.ps1 -Command all` 退出 0（全套自检通过，既有真实记录差异仍为 70 字段、668/793 匹配）；`git diff --check` 退出 0。
- 证据等级与限制：上述为实际 JAR 字节码静态证据和合成 L2；本轮未启动游戏、未读取真实玩家 Power 实例、未构建或部署 JAR。完整 Java 实时 Power 状态生产、真实 C++ 根恢复仍未验收。无正式部署或存档改动。

### 2026-10-20：Havoc 实时实例映射与卡牌缺口诊断（L2；未实机）

- 根因：旧 `CardIdWireMap` 只有六个条目，`cards()` 在读取每张牌时立即调用严格转换器；遇到 `Havoc` 即抛错，既未保留该卡状态，也中止同一快照剩余牌枚举。此前 17:51:46 报告 `source_snapshot={}`，所以无法从那份实机报告列出其他牌的身份、牌堆或升级情况。
- Havoc 状态映射：加入 `Havoc`→`HAVOC`。实时来源读完整 UUID、原生 ID、timesUpgraded、当前费用/costForTurn、misc、ethereal、retain/free 标志。只接受升级次数 0/1、`misc==0` 且 `ethereal==false`；协议维持 v1，不加 wire 字段。C++ 白名单加入 HAVOC，保留牌堆顺序、UUID↔dense ID 和现有 CardInstance 的 upgrade/cost 状态。
- 效果与搜索边界分离：Java 诊断为每张卡记录分类、来源映射结果、effect-model 状态、牌堆及位置；不支持的 ID/状态会被累计记录而非让扫描在第一张牌终止。`protocolFromSource` 和 `writeProtocol` 仍要求捕获映射完整且有显式 C++ 效果实现条目；C++ importer/search 独立白名单仍 fail-closed。Rampage 等不在映射中的牌不能进入搜索。Havoc 的 C++分支和 PlayTopCard/自动消耗路径存在，但本轮未实测真实游戏效果，不标为 L4。
- 字节码依据：本机 `desktop-1.0.jar` 的 JDK6 `javap -classpath ... -c -private com.megacrit.cardcrawl.cards.red.Havoc` 显示构造器原生 ID 为 `Havoc`、类型 SKILL/颜色 RED/基础费 1；`upgrade()` 调用 `upgradeBaseCost(0)`；`use()` 创建 `PlayTopCardAction(..., true)`，以 `AbstractDungeon.cardRandomRng` 取得随机怪物目标。C++ `BattleContext::playCard(HAVOC)`及 `playTopCardInDrawPile`有对应的随机目标、自动打出、free 与 exhaust 处理。此为静态路径对照，不是真实随机/效果差分。
- 回归：新增 Havoc 普通/升级实例在 hand/draw/discard/exhaust、同 ID 多 UUID、费用/升级/特殊字段读回、五实例 149 路径差分；非零 special_data、错误 ethereal 等拒绝；Rampage 作为未映射输入在 `mcts_state_search` 入口拒绝；离线诊断枚举 Havoc、未知 ID 与已知 ID 状态非法项，且不会输出协议快照。现有六卡及三 Sentry fixture 均保持通过。
- 验证：`tools/build/build-engine.ps1 -CMake '<VS 18 CMake>'` 退出0，引擎 SHA-256 `08778DFFD1770F954AB659CF3A840B10BF585C543156DD0FC1ED2F0CD5A17C5D`；`tools/solvertest/live-state-v1-check.ps1` 退出0，Havoc 5/5 实例、四牌堆和全字段离线差分通过，Java class major=52；`tools/live-state-validation/audit-fields.ps1` 退出0，真实 JAR 字段审计 `failures=0`；`tools/solvertest/run.ps1 -Command all` 退出0（历史实录仍70差异、668/793字段匹配）；`build-probe.ps1` 退出0，候选隔离 JAR SHA-256 `A10E14AF0D99BE2B60E3837BE2E7BB352D30196CBBB03E89736B819A940C34A9`；`git diff --check` 退出0，只有换行提示。
- 未启动游戏、未部署本轮候选探针、未覆盖正式 CombatSolver1.jar、未修改存档。实机快照、其他同时存在卡牌的映射结果、六流无推进及 C++真实根恢复仍未验证。下一次自然采集应能把所有牌的缺口列入报告；可能仍会因未映射的 Ironclad 卡、非零/多级 misc/upgrades、Ethereal 状态、非起始遗物/药水/Power、非空 limbo、Sentry Power/AI、非首回合或事件来源字段而拒绝。正式采集门控不放宽。

### 2026-10-20：三只原版 Sentry 字段映射闭环（L2，非实机）

- Java 实时探针将严格的 Sentry 对象映射接入诊断快照中的 `protocol_monsters`；离线 writer 复用同一转换函数，按原始 MonsterGroup 槽位、Sentry 身份、生命/格挡/逃跑标志、私有 AI 参数、招式历史及允许的 Power 生成协议对象。原版类名/Power ID 不匹配、未知招式或 Power、无效槽位、缺失时序值、`firstMove=true` 和越界数值均拒绝。未修改完整协议的生产发布门控。
- 实装 `desktop-1.0.jar` 字节码确认 Sentry 原生招式号 3 是 Dazed/Bolt、4 是 Beam；`setMove` 追加历史项，`lastMove` 读取末项。因此 Java 把历史末两项倒序映射到 C++ newest-first 两槽；不足两项时旧槽用显式 `INVALID`。C++ `Monster::setMove` 和 Sentry 的 `getMove`/`takeTurn` 与该顺序相符。
- Power 白名单为 `ArtifactPower/Artifact`、`WeakPower/Weakened`、`VulnerablePower/Vulnerable`。Weak/Vulnerable 的 `justApplied` 从对应实例读取；Artifact 使用无时序位的规范 false。C++ 对 Sentry 拒绝其他状态、重复状态及非零 unique-power/misc 内部计数；这些计数在受限 Sentry 行为分支中不被读取。已有玩家 Power 严格校验未改。
- L2 实际输出：Java→协议文件→C++ fixture 回读 Sentry 48 个 scalar/history 字段和 3 个完整 status 数组，合计 51 项 `MATCH`、0 `MISMATCH`、0 `UNAVAILABLE`；总体149路径仍为148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 capture_id NOT_APPLICABLE。源侧拒绝5例、C++ Sentry拒绝5例均通过。报告见 `build/live-state-validation/sentry-instance-interop.txt`、`sentry-bytecode-trace.txt` 和 `sentry-field-diff.txt`。
- 验证：`audit-fields.ps1` 退出0（含本机游戏 JAR 字段/方法审计）；`build-engine.ps1` 初次因 `cmake` 未在 PATH 退出1，显式指定 VS 18 CMake/Ninja 与既有 MinGW 编译器后构建退出0（engine SHA-256 `4B1EC3F1F348C446E8B77BFC130BF1B4E59D98FE4258FA601FBB31E03D02F13B`）；`live-state-v1-check.ps1` 退出0、Java class major=52；`tools/solvertest/run.ps1 -Command all` 退出0（历史实录诊断70项差异、668/793字段匹配）；`git diff --check` 退出0。
- 限制：fixture 是离线源形状，不是真实游戏对象采集；未启动游戏、未部署 JAR，也未修改存档。完整实时协议仍未连通，C++真实战斗根恢复及 L3/L4 未验收。本轮只完成 Sentry 切片，不代表整体求解闭环。

### 2026-10-20：实时状态采集器协议生产接线（L2；等待实机验收）

- 修正采集入口：稳定玩家操作阶段的 MTS `Postfix` 原先只采集 Power 时序诊断，未调用 `attempt()`；现接入一次性采集入口。仅在精确 vanilla Ironclad、地图 `MonsterRoomElite`、三只原版 Sentry、首回合未改变状态、队列稳定及所有已实现白名单通过时，才由真实对象组装并写出 `live_battle_state v1`；其他房间/机制明确拒绝，不自动导入或执行路线。
- 输出分离：诊断 `live-capture-report.json` 记录来源状态、接受/拒绝原因、协议路径、快照 SHA-256、双读一致及六流 RNG 不变标记；独立 `live-battle-state-v1.json` 仅在完整 Java 校验成功后生成。文档已更新；探针不读取 autosave、不调用 RNG 抽取、不修改游戏对象或存档。
- 协议边界修正：`last_targeted_monster` 改为条件字段，因为 STS1 无可信玩家级来源；无 SURROUNDED 时由 C++ 将其报告为 NOT_APPLICABLE，有 SURROUNDED 却缺字段时拒绝。字段分类当前为 149 路径形状：99 REQUIRED、35 CONDITIONAL、14 DERIVABLE、1 UNUSED。不得将此离线协议装配统计描述为 99 项真实实机映射已验收。
- 离线验证：`tools/solvertest/live-state-v1-check.ps1` 退出 0；Java 8 class major 52；合成 mock source→Java writer→C++ 恢复差分为 149 paths、142 MATCH、0 MISMATCH、0 UNAVAILABLE、7 NOT_APPLICABLE；完整 fixture 差分为 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE。专项包括卡牌 7/7、Sentry 48 字段及拒绝测试。该 mock source 仅是 L2，不是真实游戏采集。
- `tools/solvertest/run.ps1 -Command all` 退出 0（历史实录诊断仍为 70 个差异、668/793 匹配）；`tools/live-state-validation/build-probe.ps1` 退出 0，Java 8 major 52，隔离 JAR SHA-256 `3E2FA9640AFDDC656977A53E91D0E266ED9041BE7F4C787B2E6639876BA1D02D`；`git diff --check` 退出 0。曾有 `JsonArray.isEmpty()` 与本机 Gson API 不兼容，改用 `size()!=0` 后专项通过。
- 未启动游戏、未安装临时探针、未修改存档或正式 JAR。正式 `CombatSolver1.jar` 当前 SHA-256 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。真实源对象双读、RNG 无推进、C++ 对真实快照恢复仍未执行；因此 L3/L4 未通过，完整实时状态恢复仍被真实快照验收阻断。

### 2026-10-20：首次候选探针实机尝试（BLOCKED）

- 探针加载：判定未加载。MTS 本次 `sendToDevs/mts_process_launch.log` 的 Mod 初始化清单只列出 `CombatSolver1` 与 `com.combatsolver1.CombatSolver1Mod`，没有 `CS1LiveStateProbe`/`cs1livestateprobe`。探针 JAR 虽存在于 `mods`，但未在 MTS 本次选择/加载清单中；未发现探针执行证据。
- 遭遇场景：游戏 `sendToDevs/logs/SlayTheSpire.log` 记录 `CombatSolver1 battle start: EventRoom`，确认本场也不符合地图 `MonsterRoomElite` 三 Sentry 白名单。由于探针未加载，不能把这解释成探针拒绝结果；也没有字段采集失败结果。
- 时间与产物：`live-capture-report.json` 修改时间仍为 2026-10-19 21:52:44、SHA-256 `6C3E8B8B228BCD0D413A8859C47859DEB956D025C83D818F1DFF7659C529CD4D`，内容是历史 `CAPTURED_SOURCE_ONLY`；本次没有生成 `live-battle-state-v1.json`。故无真实快照及 C++ 根状态差分。
- 正式 `CombatSolver1.jar` SHA-256 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。当前 `saves/1_IRONCLAD.autosave` SHA-256 `A61FFF4112AF3511DF01D5712780D194834EC1D4B584BFAD75B542BC7325056B`、修改时间 2026-10-19 14:51:59，早于本次 MTS 启动；本轮未对存档执行写入。临时探针仍在 MTS mods 目录，SHA-256 `3E2FA9640AFDDC656977A53E91D0E266ED9041BE7F4C787B2E6639876BA1D02D`。结论为加载/遭遇阻断，不是字段采集或 RNG 失败。

### 2026-10-20：MTS 探针未选中配置修正（未启动游戏）

- 根因已从 MTS 3.30.3 `sendToDevs/mts_process_launch.log` 与 `%LOCALAPPDATA%\ModTheSpire\mod_lists.json` 交叉确认：启动记录只列出当次所选的 BaseMod、StSLib、CombatSolver1；默认列表也只有 `StSLib.jar`、`BaseMod.jar`、`CombatSolver1.jar`，没有隔离探针。未发现探针元数据错误、版本告警或探针加载异常记录。结论是探针未勾选/未加入当前默认选择列表，而不是坏包。
- 离线检查探针 JAR：路径为 MTS `mods\CS1LiveStateProbe.jar`，SHA-256 `3E2FA9640AFDDC656977A53E91D0E266ED9041BE7F4C787B2E6639876BA1D02D`；归档含根目录 `ModTheSpire.json`、`rngprobe/LiveBattleStateProbe.class` 和 `rngprobe/CardIdWireMap.class`。元数据的 `modid/name/version/sts_version/mts_version/dependencies` 均有值，版本为 `0.1.0` / STS `12-18-2022` / MTS `3.30.3`。包名 `rngprobe` 与 modid 不同不是已知阻断；历史已加载 RNG 探针同样使用 `rngprobe` 包与 `cs1rngprobe` modid。
- 最小修复：备份 `%LOCALAPPDATA%\ModTheSpire\mod_lists.json` 至 `.bak-cs1probe-20261020`，将 `CS1LiveStateProbe.jar` 加入原 `<Default>` mod 列表。配置 SHA-256 从 `274E82E12183B35B86B4A878786771AF3ABB61D7AC83F39CC430F3F53A605CD5` 变为 `9B7DD53B4FA29CFDB70C3AE4EA5D127A281986D24FE72C93D37932D815F4C44B`。这证明下次使用默认列表时 MTS 会将它作为已选文件处理；本轮未启动 MTS UI，故没有声称已目视确认选择窗口条目。
- 正式 JAR 保持 SHA-256 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。存档仍为 SHA-256 `A61FFF4112AF3511DF01D5712780D194834EC1D4B584BFAD75B542BC7325056B`、mtime `2026-10-19 14:51:59`；本轮未启动游戏或写存档。探针和 `sendToDevs/` 保留。下一次最小确认是在计划中的正常 MTS 选择界面检查该探针已勾选；只有自然出现地图 `MonsterRoomElite` 三 Sentry 才进行采集。

### 2026-10-20：Dead Adventurer 三哨卫战斗内兼容（离线 L2；真实验收待做）

- 在现有 live-state v1 与 `mcts_state` 上增量加入独立来源标记：地图 `MonsterRoomElite` 与 `EventRoom/DeadAdventurer` 分别保存，不以 `eliteTrigger` 冒充地图精英身份。事件输入严格要求 enemy index 0、遭遇名 `3 Sentries`、原版三只 Sentry；旧地图路径保持原校验。其他房间、事件、索引、遭遇名和阵容拒绝。
- 新增 `mcts_state_search <file> <budget>` 仅在恢复的 `BattleContext` 上执行现有战斗搜索，不创建 `GameContext`，胜利/失败终态后停止，检查路线无终态后动作；不调用事件回调、奖励或地图精英战后流程。输出明确标识 `COMBAT_ONLY` 和 `event_callback=NOT_INVOKED`。战斗胜利不表示完整路线或事件模拟完成。
- 离线 L2：`tools/solvertest/live-state-v1-check.ps1` 退出 0。事件源 assembler fixture 的 149 路径为 142 MATCH、0 MISMATCH、0 UNAVAILABLE、7 NOT_APPLICABLE；全部适用 REQUIRED 字段匹配。事件 fixture 搜索到 `PLAYER_VICTORY`，21 个动作、16 次模拟，终态停止且未调用事件回调。地图来源回归和事件/字段/阵容拒绝用例通过。此为合成 fixture，不是实际 Dead Adventurer 战斗快照或真实恢复证据。
- 构建/测试：显式指定 Visual Studio CMake 的 `tools/build/build-engine.ps1` 退出 0；初次未指定 CMake 的调用因 `cmake` 不在 PATH 退出 1。Java 探针 `tools/live-state-validation/build-probe.ps1` 退出 0，class major=52，隔离 JAR SHA-256 `A622DCEC26EB20853D2FDE2B9D10A0ECEADFF08975F2DD44AC9E1D7827C2F6A5`。`tools/solvertest/run.ps1 -Command all` 退出 0（历史实录仍报告70项差异、668/793字段匹配）；`git diff --check` 退出 0，仅有换行格式提示。
- 隔离探针已放入 MTS mods 目录，原临时探针备份为 `CS1LiveStateProbe.jar.bak-20261020-event-support`；本轮没有启动游戏，故尚无 MTS 加载证据、真实协议文件、真实 SHA-256 或 C++ 实战差分。正式 `CombatSolver1.jar` SHA-256 保持 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`；存档 SHA-256 保持 `A61FFF4112AF3511DF01D5712780D194834EC1D4B584BFAD75B542BC7325056B`。未改存档、未接入自动执行、未提交或推送；`sendToDevs/` 保留。
- 结论分项：离线来源兼容与战斗内终态搜索 L2 通过；真实快照恢复 BLOCKED；真实战斗内搜索未执行。下一步唯一需要的用户操作：从 Steam 正常进入 Play with Mods，确认隔离探针被选中/加载，并在自然出现的 Dead Adventurer（enemy 0，`3 Sentries`）战斗稳定可行动时停下；之后再对新鲜快照做 C++ 真实恢复差分。

### 2026-10-20：真实快照阻断项离线审计（PROTOCOL_REJECTED，等待实现批准）

- 按要求 SHA-256 核验唯一真实样本 **BC7E73EA6C783B115C4BB470E7FE3F7C5F21B9E0FE51658F5ADCA8339680FC36**；状态 PROTOCOL_REJECTED，首发原因是 draw 中七张卡全部 UNSUPPORTED_IDENTITY。C++ CardId/effect 分支存在不等于 live v1 允许。
- 审计发现后续仍有独立门：C++ v1 ID allowlist；Carnage/AscendersBane 的 ethereal 序列化拒绝；Doubt 已有 Weak 时 justApplied 缺陷；当前 4 个遗物超过协议的单一 Burning Blood 限制；空 Potion Slot 的 -1 槽位与协议索引不兼容。字段诊断 metadata 对 counters/Sentry timing/group cursor 过宽，详见报告。
- Map Elite 与 Dead Adventurer EventRoom 的来源类型、event class/encounter/index 必须分别保留；本次唯一真实样本是 EventRoom。C++ 结果限 COMBAT_ONLY，不覆盖事件回调/战后奖励；无 Map Elite 实机对照。
- 详审逐卡分类、源→协议→C++ restore/STATE0_DETAIL/search、字段置信等级及最小修复/验收顺序：[offline-audit-20261020.md](build/live-state-validation/offline-audit-20261020.md#L1)。当前无卡可列 VERIFIED_SUPPORTED。
- 本轮只读审计并复核报告哈希（Get-FileHash 退出 0）；未运行测试、未启动/干预游戏、未改代码/JAR/存档/生产搜索、未提交/推送。既有 148/149 MATCH 仅为 OFFLINE_FIXTURE L2，不是实机恢复证据；本轮无新增 L2/L3/L4。git 不可用，未独立验证工作区 diff/status。
- 结论：保持 fail-closed；不实施任何修复，停止并等待用户批准实现计划。

### 2026-10-20：Doubt / Weak 时序核查（原版与 C++ 一致；无需行为修复）

- 根因核实：本机 `desktop-1.0.jar` 字节码显示 `Doubt.triggerOnEndOfTurnForPlayingCard()` 将牌以 no-trigger 路径排入队列；`Doubt.use()` 构造 `WeakPower(player, 1, true)` 并提交 `ApplyPowerAction`。`WeakPower` 构造器只在新实例上按该参数设置 `justApplied=true`；`WeakPower` 没有覆写 `stackPower`，继承的 `AbstractPower.stackPower(int)` 仅执行 `amount += value`。`WeakPower.atEndOfRound()` 若标记为 true 就清标记且跳过衰减，否则减少 1 层/移除。
- C++ 对照：`callEndOfTurnActions()` 先排入 Doubt no-trigger 卡，`useNoTriggerCard()` 调用 `player.debuff<WEAK>(1, true)`；`Player::debuff` 只在 Weak 原先不存在时设置 just-applied。随后 `afterMonsterTurns()` 调用回合末状态处理，按该标记跳过一次或衰减 1 层。原版与 C++ 在新 Weak、已有 Weak 且标记 false、已有 Weak 且标记 true 三种情况的首次语义没有差异。旧审计将“已有 Weak 时不会刷新标记”判为缺陷，是把原版堆叠语义误读为应刷新。
- 最小修改：没有改动生产结算行为。删除 `BattleContext.cpp` 中未经证实且与字节码不符的 Doubt/Shame TODO 推测并为 Doubt 路径注明原版堆叠语义；在 `test.cpp` 增加 5 个 Doubt→Weak→回合末分支回归（无 Weak、1/2 层旧 Weak 与 just-applied true/false）；`live-state-v1-check.ps1` 校验新回归摘要。
- 验证：首次专项调用命中了未重编的旧引擎，因其输出缺少新测试摘要而失败；显式 C++ Release/Ninja 重建后 `live_state_power_timing_check` 退出 0（既有状态时序 10 案例 + Doubt/Weak 5 案例），`live-state-v1-check.ps1` 退出 0（Java 8 major=52；149 路径 fixture 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 capture_id NOT_APPLICABLE），`tools/solvertest/run.ps1 -Command all` 退出 0，`git diff --check` 退出 0。全套历史实录仍报告 70 个差异、668/793 字段匹配，该统计不是本次缺陷结果。
- 证据等级/限制：STS1 结论来自本机原版 JAR 静态字节码；回归为 C++ 离线 L2。未启动游戏、未修改存档、未构建或部署 JAR；无本次 L3/L4 验收。Doubt 行为仍受当前卡牌协议白名单限制，本轮未扩大准入范围。

### 2026-10-20：Shrug It Off 单卡 Java→C++ 状态映射（L2）

- 目标选择：对审计中的七张未映射牌比较后选 `Shrug It Off`。本机 STS1 `desktop-1.0.jar` 的 `javap -p -c com.megacrit.cardcrawl.cards.red.ShrugItOff` 确认构造器传入原生 ID `Shrug It Off`、`baseBlock=8`；`upgrade()` 增加 3 格挡；`use()` 依次排入使用实例 `block` 的 `GainBlockAction` 与 `DrawCardAction(1)`。C++ 已有 `useSkillCard` 的 8/11 格挡与抽 1 分支，并列于技能搜索候选。其他候选额外涉及弃牌堆选牌、Ethereal、活动 Power 或 Weak 时序，因此本轮不处理。
- 最小修改：`CardIdWireMap.java` 新增精确 `Shrug It Off`→`SHRUG_IT_OFF` 映射与效果准入；`apps/live_battle_state.h` 同步允许该 CardId。没有改搜索算法、效果结算或协议结构。通用序列化保留完整 UUID、费用、0/1 升级、`misc`、free-to-play 与 retain；仍拒绝缺字段、重复 UUID、非零 `misc`、Ethereal=true 和不支持升级状态。新增离线源 fixture，包含手牌普通/升级两张同 ID 不同 UUID 实例及抽牌堆第三实例，并在恢复后的 C++ 对象上通过 `STATE0_DETAIL_CARD` 和全路径 diff 比较。
- 七张卡审计结论：Shrug 本轮接通；Headbutt 有弃牌堆选牌路径；Carnage 与 Ascender's Bane 的源卡为 Ethereal、当前 serializer 拒绝；Metallicize 与 Corruption 的效果依赖当前尚不接入的活动 Power 根状态；Doubt 依赖 Weak/`justApplied` 时序（当前树中有单独的字节码审查和回归）。其余六张未映射牌本轮未改。
- 验证：Release C++ 构建脚本退出 0，协议 v2 校验通过；构建有既存 `Player::justAppliedBits` 移位告警，本轮未改该无关代码。`tools/solvertest/live-state-v1-check.ps1` 退出 0，Java `--release 8` 编译且 class major=52；Shrug 3/3 实例身份/堆序/升级/费用/特殊数据回读匹配，全协议为 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 `capture_id` NOT_APPLICABLE；5 种非法源字段及 C++ 非零 `special_data` 均拒绝。`tools/solvertest/run.ps1 -Command all` 退出 0；其中历史实录比较仍报告 70 个差异、668/793 字段匹配，这是既有 corpus 诊断，不是本次 Shrug fixture 失败。`git diff --check` 退出 0。
- 证据边界：以上为安装 JAR 静态字节码和合成 fixture 的离线 L2；未启动游戏、未部署任何 JAR、未修改存档，Shrug 在真实战斗中的效果与抽牌结果尚未验收。既有 Doubt/Weak、协议及 Sentry 工作区改动和 `sendToDevs/` 均保留；未提交/推送。

### 2026-10-20：Ironclad、药水、遗物与实时状态批量覆盖审计（仅规划）

- 新增 `docs/IRONCLAD_COVERAGE_BATCH_AUDIT_2026-10-20.md`。卡牌清单按 C++ Ironclad 池及起始牌列出 75 个 ID，逐项记录 `CardId`、Java→协议→C++ 状态映射、实例状态边界、C++ 分发分支及特殊机制；75/75 有 C++ 分发分支不代表语义已验证。当前 7/75 Ironclad 卡牌接入状态映射；另有 colorless `RitualDagger`，不计入职业数。映射仅涵盖 0/1 升级及显式 special-data 规则；未映射卡仍由生产器拒绝。没有完整逐卡 L4 效果结论。
- 分开审计药水与遗物：药水 C++ 有 42 个非哨兵 ID，Ironclad 池 33 个；Java 列表有 9 个职业池外 ID 和 `FearPotion`/`Swift Potion` 字符串不一致，且 `SmokeBomb` 有可被搜索枚举的空 TODO。药水协议只读 ID/槽位，不传 potency/实例状态。遗物有 180 个有效 enum ID，但当前位图/导入仅可表示 0–127，另有 52 个不可恢复；Java 快照生产器仍只接受 Burning Blood，C++ 仅解析七种命名 counter，完整遗物效果与触发顺序未验收。
- 实时状态独立阻断：149 个路径形状（99 REQUIRED、35 CONDITIONAL、14 DERIVABLE、1 UNUSED）；字段审计的 83/14/52 是源码成员证据标签，非端到端完成数。生产采集器代码已存在但只接受严格窄根（第一回合未行动、稳定队列、无玩家 powers、Burning Blood、支持卡牌/药水及限定遭遇）；当前没有新鲜真实快照通过 C++ 全字段 `STATE0_DETAIL`。历史六流无推进及 108 步 RNG 对照不能替代该闭环。
- 验证：`powershell -ExecutionPolicy Bypass -File tools/solvertest/live-state-v1-check.ps1` 退出 0（离线合成 fixture；149 路径 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 `capture_id` NOT_APPLICABLE，包含卡牌实例及 Sentry 回读）；`powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command all` 退出 0，但历史对照仍有 70 项差异、668/793 字段匹配、48 个动作差异，属于既有语料差异。无 L3/L4 新证据。
- 仅增加审计文档并更新本状态记录；未改生产逻辑、协议、JAR、存档或通知配置，未启动游戏/MTS，未提交或推送。既有工作区修改及 `sendToDevs/` 保留。
- 建议次序：先做离线能力清单/生成校验（不能由分发分支自动授予效果支持）；再选 `Cleave` 与 `Bludgeon` 做首个 A 类小批次，对照本机 STS1 构造器的普通/升级数值，并完成 Java→C++ 状态回读与效果负例；之后再按共用机制接入 B 类，独立实现 C 类，先解决 D 类状态门控后才扩大输入范围。时间不估算，因无批量吞吐量基线。完成本审计后停止，等待下一项批准。
- 范围限制：75 行是 C++ 卡牌 ID/分发与协议适配库存，不是对本机 `desktop-1.0.jar` 中 75 个构造器及每个普通/升级数值的完整反汇编；逐卡原版数值核对仍是准入门槛。矩阵明确把分支存在与效果回归、实机正确性分开。

### 2026-10-21：自动化真实状态验收第一阶段（L2 部分完成；L3 阻断）

- 将探针诊断输出独立为 `live_battle_diagnostic_snapshot v1`，保留源快照、卡牌原生 ID/完整 UUID/牌堆位置和字段状态；独立报告 `capture_supported`、`restore_supported`、`effect_supported`、`search_supported`。正式 `live_battle_state v1` writer 与 C++ 白名单未放宽。新增 `CS1_LIVE_STATE_OUTPUT_DIR`，可将探针产物写入单独测试目录。
- 增加离线拒绝回归：诊断成功仍不能作为生产协议或 C++ 搜索输入；未知卡牌保留原始字段但 `restore_supported` 标记为不支持。诊断 fixture 明确标记为离线证据。
- 验证：`powershell -ExecutionPolicy Bypass -File tools/solvertest/live-state-v1-check.ps1 -Engine build/engine/combatsolver1-engine.exe` 退出 0；149 路径 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE。`powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command all` 退出 0，历史录制语料仍有 70 项差异（668/793 字段匹配），与本次 fixture 结果分开。`tools/live-state-validation/build-probe.ps1` 退出 0、class major=52；`audit-fields.ps1` 退出 0、failures=0；`git diff --check` 退出 0。
- 未运行游戏、未部署探针、未改正式 JAR或存档。当前 MTS 运行目录包含玩家 `saves/`，并存在 `steam_autocloud.vdf`；在没有隔离 Steam 用户/云存档的可验证测试根目录前，不启动自动新战斗。故自动化场景创建、真实快照、真实 C++ 根恢复及 L3/L4 均未验收。`sendToDevs/` 和原有未提交改动保留。
- Release 引擎复核：专项脚本最终显式使用 `build/engine-mingw-unified-release/combatsolver1-engine.exe`（SHA-256 `C74E62F13A9612960C374AB89E23B8EA02CCAF82190EB96D55E6EFCDEB207A61`）重跑，退出 0；前一次使用 `build/engine/combatsolver1-engine.exe` 的成功结果不作为当前 Release 验收依据。

### 2026-10-21：自动化测试环境隔离预检（Stage A BLOCKED）

- 工作区基线保持：`main` / `8f93ae6d9cfb7e53d86ee25defb9d521adc4225c`。本次只读系统盘点，未触碰既有未提交源码和 `sendToDevs/`。
- 系统信息：注册表报告 `Windows 10 Home China`、`24H2`、build `26100.4946`；该 ProductName 与 build 标签不一致，且 WMI/CIM 查询拒绝访问，无法进一步确认固件虚拟化状态。`WindowsSandbox.exe`、Hyper-V 管理二进制、VirtualBox、VMware、Docker 均未发现。Sandbox/Hyper-V Windows 功能查询因需要提升权限而未能确认；本机只有 `wsl.exe`，不足以证明可运行隔离的 Windows Steam/MTS 游戏实例。
- 安装与写入路径：STS1 根目录为 `C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire`；游戏存在 `saves/`、`preferences/`、`runs/`、`logs/`、`sendToDevs/`。MTS、BaseMod、StSLib 来自 Steam Workshop `steamapps\workshop\content\646570\{1605060445,1605833019,1609158507}`；正式 Mod 与探针位于游戏 `mods/`。Steam userdata 中存在 app id `646570` 子目录；游戏 `saves/` 与 `preferences/` 均有 `steam_autocloud.vdf`。MTS `%LOCALAPPDATA%\ModTheSpire\mod_lists.json` 可见文件元数据，但读取/哈希因访问拒绝而未完成。
- 当前 Steam 客户端进程运行中，但没有 STS1/Java 游戏进程。本次未启动游戏、未创建新测试根目录、未部署 JAR、未修改 Steam Cloud 或玩家数据。正式 `CombatSolver1.jar` SHA-256 仍为 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`；四份 Ironclad autosave 与 `steam_autocloud.vdf` 的 SHA-256 均与本轮只读基线复核一致。
- 阶段结论：A 隔离 BLOCKED；B 自动启动 BLOCKED；C 真实采集 BLOCKED。没有隔离写保护和独立 Steam/Cloud 状态前，复制游戏目录、设置输出环境变量或仅切换 Windows 用户均不足以证明安全。未执行游戏测试、RNG 检查或 C++ 真实恢复。
- 最小后续前置条件：提供或启用一个独立 Windows 虚拟机/受支持的 Windows Sandbox 环境，使用有合法 STS1 授权的 Steam 客户端，游戏与用户数据置于独立虚拟磁盘，不映射宿主机可写目录，并在该隔离 Steam 环境内处理 Cloud（不改宿主机设置）。满足并验证前不自动启动游戏。

### 2026-10-21：自动化验收控制器离线实现（L2；L3/L4 BLOCKED）

- 新增 `tools/live-state-validation/automation/`：版本化 Ironclad/Jaw Worm 场景配置、Fail-Closed PowerShell 控制器和路径/VM 校验模块、独立 MTS 测试模组源码与 MTS 元数据、Java 8 构建脚本、VM 测试资产部署脚本及隔离部署说明。测试模组只通过原生新游戏/角色确认/Neow/地图房间生命周期推进；目标房间须是原版 `MonsterRoom` 且实际生成一个原版 Jaw Worm。未在真实游戏运行验证该导航链路，不宣称自动战斗已成功。
- 控制器支持 `DryRun`；`Run` 要求显式配置和隔离 attestation、VM 识别、独立固定磁盘、无映射/网络盘、无可见共享、Steam 与 userdata/game/mod/save/preferences/log/output/engine 全部位于显式测试根目录、空测试存档目录、无已运行 Steam 进程，并拒绝正式 `CombatSolver1.jar` 出现在测试 Mod 目录。配置或控制器脚本在测试根之外时拒绝运行。只将新鲜 `PROTOCOL_CAPTURED` 的完整 v1 快照送入 C++ `mcts_state`；诊断快照不会进入恢复或搜索。该阶段不调用搜索或自动出牌。
- 验证：4 个 PowerShell 文件解析通过；控制器自测 12/12 PASS；DryRun 退出 0；宿主机 `Run` 退出 3（预期 BLOCKED，无进程启动）；部署脚本拒绝仓库外测试 JAR 源（退出 3，无复制）。独立测试模组构建退出 0，Java class major 52，JAR SHA-256 `4bc5a1847767161ce26792f0f6b77ec72a4c0b062e9bf915163668b1370cbb55`。探针构建退出 0、major 52；STS1 字节码审计 76 项匹配、0 failure。
- 当前 Release 引擎 `build/engine-mingw-unified-release/combatsolver1-engine.exe` SHA-256 `c74e62f13a9612960c374ab89e23b8ea02ccaf82190eb96d55e6efcdeb207a61`。`tools/solvertest/live-state-v1-check.ps1 -Engine <该引擎>` 退出 0：离线协议路径形状 149 项，148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE；`tools/solvertest/run.ps1 -Command all` 退出 0，历史录制语料仍报告 70 项差异（668/793 字段匹配、48 个动作差异）；`git diff --check` 退出 0。以上均为离线证据，不代表真实游戏状态恢复或战斗验证。
- 未启动 Steam/STS1、未加载或部署候选 JAR、未修改游戏存档、正式 JAR 或 Steam 设置。正式 `CombatSolver1.jar` 前后 SHA-256 均为 `644e4f0e2457bbdc6a969b94302b317ed0904d91f761e590dff0a27e1432ec7f`。测试 JAR 仅位于仓库 `build/` 输出目录。`sendToDevs/` 及此前全部工作区改动保留。
- 当前结论：隔离阶段 A 仍 BLOCKED；自动启动阶段 B 和真实状态采集阶段 C 未执行。VM 配置、Steam Cloud 隔离、测试模组加载、原生地图导航、真实快照、真实 RNG 检查及 C++ 实机差分均待隔离 Windows 来宾环境验证。没有 L3/L4 证据。

### 2026-10-21：自动化验收基础设施提交前复验

- 为避免提交开发机路径，探针默认输出改为 `%USERPROFILE%\\.combatsolver1\\live-state-validation`；探针/测试模组构建、字节码审计和实时协议专项均改为显式接收游戏/JDK/ASM 路径或使用 `JAVA_HOME`/`PATH`。VM 配置仍只引用 `D:\\CS1AutomationVM` 来宾模板路径。
- 本轮命令：`build-probe.ps1`（退出 0，major 52）；`audit-fields.ps1 -GameDir ... -JavaHome ... -AsmJar ...`（退出 0，76 matches / 0 failures）；`automation/build-testmod.ps1`（退出 0，major 52）；控制器 `-SelfTest`（12/12）；`-Mode DryRun`（退出 0，`process_launched=false`）；宿主机 `-Mode Run` 输出 `BLOCKED` / `process_launched=false`；VM 部署拒绝根目录外 JAR 源；`live-state-v1-check.ps1` 指向统一 Release 引擎（退出 0，149 paths: 148 MATCH / 0 MISMATCH / 0 UNAVAILABLE / 1 NOT_APPLICABLE）；`run.ps1 -Command all`（退出 0）；`git diff --check`（退出 0）。
- 静态复核发现地图状态机缺少从原生 `MonsterRoom` 转入稳定战斗检查阶段的返回路径，已在隔离测试模组中补齐；重新编译退出 0、major 52，控制器自测仍为 12/12。独立测试模组仅在 `build/` 构建，最近一次 SHA-256 `72BD57E7A77D4AA55C03865AD379DBD119C4F8AEE462C2B7BCDF7A435FF4E61D`；没有部署到宿主机 MTS。正式 JAR SHA-256 仍为 `644E4F0E2457BBDC6A969B94302B317ED0904D91F761E590DFF0A27E1432EC7F`。本机路径/凭据扫描未发现自动化与协议工具中的开发者用户目录或凭据字符串；`sendToDevs/` 未纳入候选提交。
- `Solvertest all` 历史语料仍有 70 个字段差异（668/793 匹配）和 48 个动作差异，保持为未修复诊断。未启动游戏，未修改存档；L3/L4 仍 BLOCKED。提交与推送结果以本条后续更新为准。

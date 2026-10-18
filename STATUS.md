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

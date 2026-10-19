# V1.0 候选版验收记录

日期：2026-10-19。候选版本：1.0.0-rc1。审计基线：`78ca857`。

**结论：尚未达到正式发布与停止开发标准。** 当前交付 UI 候选版，等待用户实机结果；不继续扩机制或修 Continue。

## 最短交付清单

| 必须项 | 当前证据 | 剩余阻断 |
|---|---|---|
| 地图进入新战斗的 sts_lightspeed 根与路线 | STATE0、journal、根绑定已有实现与历史局部实机证据 | 当前版本的新战斗连续执行待验收 |
| 面板执行控制与身份安全 | FailureLatch、队列停止、卡牌/目标身份及过期拒绝保留，执行安全 11 项离线通过 | 用户报告面板执行/自动无响应、停止后无法继续；修复按钮命中区域按当前布局即时计算，并提供继续按钮。离线回归通过；部署候选 SHA-256 `B624475AA70EA40C063B381A099FC81A834F12C4F13B3379EB83DFE63AE40A34`，备份 `CombatSolver1.jar.bak-20261019-110015`，待实机复验 |
| 常用战斗机制 | 核心 solvertest 门禁及 C++ 消耗、格挡、Exhume 回归通过 | 不是全机制精确证明；由下面 10 场记录覆盖实际使用组合 |
| Exhume 多候选及后续行动 | v3 原生选择已实现，17 项离线通过；最新现场失败后的费用哨兵/队列修复尚未成功复测 | 至少两次多候选成功，需原生点击、选后校验及后续动作证据 |
| UI | 较大字体、深色铜金面板、分区、逐动作编号/换行/分页、次级设置、尺寸修复已编码 | 三种分辨率截图、交互、持久化待实机验证 |
| 10 场有效验收 | 当前可确认纳入新标准的通过数 **0/10**；不是声称历史从未成功运行 | 普通/精英、G/H/J、多回合、两次 Exhume 多候选 |
| 发布材料 | 候选 JAR、旧包备份、哈希与本记录 | 正式 1.0.0 JAR、正式版本/发布需等待上述验收 |

本轮只改 UI 展示和 `CombatController.render` 的只读展示转换，没有修改求解、执行、安全校验、记录器、真实存档或 C++ 规则。Computer Use 通知功能及未跟踪 `sendToDevs/` 保留。

Continue 读档外部求解、其他职业、罕见卡牌、其他原生选牌及特殊机制退出 V1.0 必需范围；拒绝不可信根或选牌身份的保护保持不变。内置 Java 路线继续明确标为近似；不会以它的成功替代外部求解验收。历史语料差异没有根因证据，不新增猜测式修复。

## UI 改动与边界

- 原字体正文 14、说明 12 改为 19、17；标题/摘要 22。文字不跟随背景透明度降低对比度。
- 原横向胶囊会合并动作并隐藏为“+N”；候选版逐动作编号，保留目标与药水/选牌标识，长名称换行，所有行可分页查看。回合结果明确显示 HP（含 0）与剩余能量。
- 预算、透明度、主题移入可收起的设置区。G 主按钮、H 自动开关、J 停止保持原动作映射。
- 默认左上，面板高度上限为逻辑屏幕高的 46%；首次面板底缘位于卡牌手牌上方，避免压住手牌。已有位置保持。修复像素/逻辑高度重复缩放、缩放后的拖拽增量、向下拉高面板、折叠残留按钮命中。圆角使用四分之一圆及实心中心像素。
- 新增的设置展开/页码仅属 renderer 会话状态；新计划重置页码。尺寸写回已有设置文件，不新增执行授权或异步状态。
- 失败锁定、暂无路线、路线过期及外部求解中由已有控制器字段转换；没有改动这些字段的写入/失效规则。

无本轮真实前后游戏截图：检查时游戏未运行，用户选择“完成候选版后给我最少测试步骤”。旧包保留供对比。UI 构建通过不代表布局/交互已验收；1280×720、1920×1080、2560×1440 均待实机确认。此类视觉问题无法用纯 Java 模型测试复现原生字体、渲染和鼠标命中，因此未伪造修复前实机失败测试。

## 本轮实际验证（均为 L2）

命令在仓库根执行，日志在本地 `build/v1-audit/`，不含私人战斗原始文件。

| 命令 / 检查 | 退出 / 结果 | 日志 |
|---|---|---|
| `powershell -File tools/solvertest/run.ps1 -Command all` | 0，强制门禁通过 | `solver.log` |
| `powershell -File tools/recordercheck/run-exhume-execution-check.ps1` | 0，17 项 | `exhume.log` |
| `powershell -File tools/recordercheck/run-engine-potion-protocol-check.ps1` | 0，8 项 | `potion.log` |
| `powershell -File tools/recordercheck/run-root-replay-diff.ps1 -SelfTest` | 0，30 项 | `root.log` |
| Java 8 编译后运行 `PanelModelCheck` / `ExecutionSafetyCheck` / `SettingsPersistenceCheck` | 0，分别 6/11/4 项，失败 0 | `ui-check.log` |
| `build/engine/combatsolver1-engine.exe exhume_check` | 0 | `engine-exhume.log` |
| 同 EXE `exhaust_trigger_check` / `block_modifier_check` | 0 / 0，格挡 9 组 | `engine-exhaust.log` / `engine-block.log` |
| `powershell -File tools/build/build-release.ps1` | 0，Java major 52，131 JAR 条目 | `build.log`（最终包见交付记录） |
| `powershell -File tools/patchcheck/run.ps1 -ModJar <候选JAR绝对路径>` | 0，MTS 3.30.3，9 类补丁 discovery/injection | `patch.log` |
| `git diff --check` | 0 | 命令输出 |

`solvertest all` 的历史语料是非门禁报告：70 动作中 48 动作有差异，字段 668/793 匹配。不能称“全部测试/实机精度通过”，也不能由旧 Java 语料直接判定当前外部路线失败。新增展示检查验证动作不合并、顺序/目标/原生选择及零 HP 展示、视图不修改可执行计划；不检验实际渲染。

展示/安全/持久化检查使用现有 JDK 的 `javac --release 8 -encoding UTF-8 -d build/v1-audit`，输入 `engine/*.java`、`search/*.java`、`ui/PanelModel.java`、`settings/SolverSettings.java`、`runtime/ExecutionSafety.java` 及对应三份 `tools/recordercheck/*Check.java`，再以该输出目录为 classpath 分别运行三类。首次仅引用 classes 的临时检查命令未找到类，改为显式编译上述生产源及测试后通过；没有为测试改断言或降低安全条件。

## 实机验收记账规则

历史原始 bundle 保持原样；本轮清点见本地 `bundle-inventory.json`。其中已标 victory 的 18 份均 dirty；其他样本还有未封存、interrupted、death 或旧字段缺失。只做元数据清点，没有把这些样本重新判作完整严格通过。

每个新有效案例记录：bundle 名、JAR/引擎哈希、地图进入方式、普通/精英、G/H/J 操作、终局、STATE0/journal/卡牌及目标核对、Exhume 候选数/选中卡/后续动作、截图路径、差异和验收结论。药水或选牌记录无法由现有差分器完整验证时单列验证受限，不能算严格通过；需要原生日志补齐可验证链，而非修改预期。

目前：10 场槽位全部待验收；Exhume 多候选 0/2；UI 分辨率 0/3。遇 P0/P1 立即停止并保留原始证据，按根因最小修复；P2/P3 只记录。若两轮修复无实质进展，暂停编码重新评估。

## 用户下一步（先只做一场）

1. 用候选版启动游戏，铁甲战士从地图进入一场普通战斗；等面板显示外部 sts_lightspeed 路线，截一张完整游戏画面。
2. 点击“执行本回合”；下一回合点击“自动执行”后马上点击“停止”，再点“继续”确认恢复。检查面板分页、拖动、折叠/展开和设置，截一张展开界面的画面。若停止/选牌异常，保持现场，不连续重试。
3. 告诉我测试时间并提供截图；本机记录我来读取。先复核这一场，再按缺口累计至 10 场及两次多候选 Exhume，不要求你分析源码或日志。

只有全部发布门槛通过后才生成正式 1.0.0、发布并停止主动开发；此时不因剩余 P2/P3 继续扩展。

## 候选包文件记录

候选版已安装供实机验收：`build/libs/CombatSolver1.jar`，版本 `1.0.0-rc1`，SHA-256 `A0B01326D855BFFBBE01CD0837241272CE71C6E9D489FE28706EB23AEB1D93E5`。游戏目录校验值一致。安装前游戏 JAR 备份为 `CombatSolver1.jar.bak-20261019-102110`（旧 SHA-256 `D11EF4BCBEA2DE2BC4E8BCCCFC00F2A454350F1888421DE980837A86A8B26E18`）；另有工作区副本 `build/v1-audit/CombatSolver1-installed-before-rc1.jar`。候选 JAR 副本在 `build/v1-audit/CombatSolver1-1.0.0-rc1.jar`。正式版尚未发布。

## 2026-10-19 用户截图/现场记录复核

- 截图匹配真实 bundle `battle-20261019-102938-114-1b883da4-4b72-4d5f-9749-d87f438490d8`：铁甲战士 HP 44/75、T1 能量 3、三哨卫 39/39、42/42、43/43、手牌含 Exhume。manifest 仍 `open/in_progress`、`journalDirty=false`、`actionCount=2`；活动中的 JSONL/journal 哈希会继续变化，不以 manifest 哈希不符判损坏。
- 根状态来源为 `sts_lightspeed`。manifest `rawAutosaveBindingStatus=engine-root-matched`，表明 HP/格挡/能量/手牌/怪物的 STATE0 与实机根匹配；完整的 engine initialization 与根配对仍 pending，故只算该场局部 L3 证据，不算严格 L4。
- 已记录 Bash（手牌槽 3）→`Sentry#3`，候选前后生命 43→35、能量 3→1；Defend（手牌槽 1）后格挡为 5、能量 0。两张动作顺序与截图上的当回合路线一致，身份/目标均 trusted。JSONL/journal 不记录按键来源，因而不能证明它们由 G 而非手动点击触发；还没有 H/J 或 Exhume 选择动作。
- 同日上一场 `battle-20261019-102851-177-e2a9e863-6ef6-46e6-88c1-6c2245c6ecf7` 为蘑菇兽胜利，但 manifest 记录 native read/decode mismatch，未准备 sts_lightspeed，最终动作无稳定 post 且 `journalDirty=true`。不计本版外部求解或完整验收。
- 本次截图也暴露 UI 将 Defend 的协议占位索引显示成“目标 1”。根因是只读路线投影对所有卡牌无条件显示非负 `targetIndex`，而外部协议会给无目标牌带占位下标；执行 journal 显示该牌实际为 self-target。已修复投影仅在有解析目标名时画箭头，并加回归断言“无目标牌不显示箭头”。Java 8 离线编译成功；`PanelModelCheck` 6 项、`ExecutionSafetyCheck` 11 项和 `SettingsPersistenceCheck` 4 项全部通过。
- 隐藏占位目标修复提交 `4aaf5c27a4a582f5357c998a0cbc1a553d27fdc0` 后重新构建并部署候选 JAR：Java major 52、131 个条目。工作区与游戏目录 SHA-256 一致：`B4D16023AAA375AB5AB0C9D342EE2BACA50A98178A2C56352A60903382AFFEA5`；MTS 3.30.3 discovery/injection 9 类通过。安装前版本备份为 `CombatSolver1.jar.bak-20261019-103945`。需重启游戏加载修复；重启后的 UI、G/H/J 和 Exhume 尚未实机验收。截图中的文字整体仍需玩家目测确认，不凭静态尺寸宣布 UI 通过。

# W3 逐张重采核对：接入主仓库运行路径的可执行清单（L2 离线前置）

> 用户新指令：实机部分由用户亲自执行，代理完成非实机部分后再通知。
> 本文件是「非实机可完成」的接入设计与证据；**未接入 `CombatController` 运行路径**
> （那会改变真机行为，属用户后续实机验收范围）。AGENTS.md 8.9 可执行技术债格式。

## 接入进度（2026-11-06 21:16 更新）

**已完成（W3-b，默认关闭、L2 离线 + L3 完整构建通过）**：
- `CombatController` 加 `perCardEnabled`（默认 `false`）、`perCardBudget`（每场一个，
  `resetPerCard()` 生命周期写入点）、`perCardPredicted`、`setPerCardPredicted`、
  包级 `evaluatePerCard(...)`；`deployer.update()` 后仅「开启且 `isSettled()`」时判定，
  REFUSED/EXHAUSTED 走 `deployer.abort(...)` → 复用现有 `finish` 与 `onDeployFinished`
  停机门，**不新增第二套停机机制**。
- `PlanDeployer.abort(String)` 为窄 public 入口，复用 private `finish`，未复制逻辑。
- 默认关闭时 `evaluatePerCard` 恒返回 PASS，逐卡路径与改动前**逐位一致**；
  W0 冻结指标不变（75.0% / 35.5、Q1-42 漏优 0.0%）；18+ 自检全绿；
  L3 完整构建 150 条目 major=52、`final_check` 248/248。

**已完成（W3-c，2026-11-06 21:21，L2/L3）**：
- 查明真值：`EngineBridge:353` 的 `Plan.Turn.endState` 是**回合结束边界** STATE 行
  （`endsTurn=true` 时写入），引擎**没有逐动作的逐步预测状态**。
  按 AGENTS.md 4.3「字段缺失必须单列验证受限」，**不编造逐步预测**。
- `PlanDeployer` 加只读 `currentEndState()` / `atTurnEnd()`；
  `CombatController` 判定块改为：仅「开启 + `isSettled()` + `atTurnEnd()`」时，
  取 `currentEndState()`（或手动 `setPerCardPredicted` 覆盖）作预测，
  调 5 参 `evaluatePerCard(settled, predicted, recaptured, now, auto)`；
  其余动作**不判定**，避免用回合末状态误判中间动作。
- L3 完整构建 150 条目 major=52、`final_check` 248/248；
  L2 全量自检全绿；W0 75.0%/35.5、Q1-42 漏优 0.0% 不变（默认关闭逐位一致）。

**仍受限（如实，不声称完成）**：
- 「逐动作预测 diff」因引擎不提供逐步 STATE 行而**验证受限**；当前实现只在
  回合末用 `endState` 判定一次。要做到真正逐张，需引擎侧输出每动作后的 STATE 行
  （任务书 W7 引擎路线主力化的一部分），非本仓库单方面可补。
- 默认关闭开关未开；L4 真机（用户做）：正常战斗全程不误停、注入不匹配停机、
  逐卡 diff 零差异、节流/次数上限在真机生效。

## 现状（本会话已核验）

| 项 | 位置 | 状态 |
|---|---|---|
| 判定器 `PerCardVerify.evaluate` | `runtime/PerCardVerify.java` | L2 12/12（W3-a），含 PASS/VERIFYING/REFUSED/EXHAUSTED + 2.5s 节流 + 每场 8 次上限 |
| 权威比对器 | `runtime/EngineRootStateBinding.matches` | 要求 `hp=/blk=/en=/hand=[/mons=` 且 `engineTurn+1==liveTurn` |
| 实机状态行 | `CombatController.liveStateLine()` | `STATE hp=.. blk=.. en=.. turn=.. hand=[..] mons=..` |
| 引擎预测状态行 | `EngineBridge:353` → `Plan.Turn.endState` | **可作预测状态**（本轮自检证明格式兼容） |
| 每回合状态签名 | `CombatSearch:90` → `Plan.Turn.stateSignature = SimState.signature()` | **紧凑键，不兼容** `EngineRootStateBinding`，**不能**当预测状态 |
| 稳定帧判定 | `CombatController.isSettled()` (950) | 依赖 `AbstractDungeon`，离线不可编译；需由调用方传入 `settled` |
| 停机门 | `ExecutionSafety.FailureLatch` (`executionFailure`) | 已存在，`CombatController.onDeployFinished` 里 `block()` |
| 逐卡钩子 | `PlanDeployer.acceptCardUsed()` (115) | 每张牌确认后调用；`CombatController.onCardUsed` (164) 转调 |

## 接入前置自检（本轮新增，L2 9/9）

`tools/solvertest/PerCardVerifyWiringCheck.java`：用真实格式证明
① STATE 行/liveStateLine 兼容；② 匹配→PASS；③ 未稳定→VERIFYING；
④ 注入不匹配→REFUSED 停机（W3 验收点）；⑤ 2.5s 节流；
⑥ 8 次上限→EXHAUSTED 且不自动恢复；⑦ 紧凑键被拒。
证据 `docs/W3_PER_CARD_WIRING_CHECK.txt`。

## 最小接入方案（下一项，纯代码、不需真机）

1. **状态生命周期**（AGENTS.md 8.3 要求写清）：
   - `PerCardVerify.Budget` 每场战斗一个实例；**写入点**：进入 COMBAT 新建 /
     `executionFailure.clear()` 处 `reset()`；**读取点**：每次逐卡核对；
     **失效**：离开 COMBAT 或 `budget.isRefused()` 后不再自动恢复；
     **约束**：不与现有 `FailureLatch` 双重停机——`evaluate` 返回 REFUSED/EXHAUSTED 时
     统一走 `onDeployFinished` 的 `executionFailure.block()` 路径。
2. **接入点**：`PlanDeployer.acceptCardUsed()` 每张牌确认后，由调用方（CombatController
   的 frame update）在 `isSettled()` 为真时取 `liveStateLine()` 作 `recaptured`，
   取该动作对应的 `Plan.Turn.endState`（或引擎逐步 STATE）作 `predicted`，
   调 `PerCardVerify.evaluate(settled, predicted, recaptured, budget, nowMs, autoReAuth)`。
3. **默认关闭开关**：加 `perCardVerifyEnabled`（默认 `false`），关闭时逐卡路径与现状逐位一致，
   W0 与真机行为不变；开启才生效，便于用户实机 A/B 对比。
4. **结果处理**：PASS → 继续下一动作；VERIFYING → 等下一帧重采；
   REFUSED/EXHAUSTED → 不继续出牌，取消剩余路线、`executionFailure.block()`、
   `suppressed=true`、`fullAuto=false`（复用 W3-a 已验证的停机语义）。
5. **禁止**：不得用 `stateSignature` 当 `predicted`；不得在 `!settled` 时放行；
   不得放宽 `EngineRootStateBinding.matches` 的字段/turn 判据。

## 验收（分层，不混用）

- **L2 离线（代理做）**：`PerCardVerifyWiringCheck` 9/9；新增「开启开关后不匹配→不继续出牌」
  的部署层自检；全量 18+ 自检、W0 两把尺子前后对照不变；L3 完整构建通过。
- **L3 真机启动（已做）**：CU 能启动游戏到 A20 角色确认（6 张截图）。
- **L4 真机（用户做）**：点 Embark 开局后，正常战斗全程不误停；注入式不匹配时确实停机；
  CombatRecorder 逐卡 diff 零差异；节流/次数上限在真机生效。

## 限制（如实）

- 本轮**未接入**运行路径；`CombatController`/`PlanDeployer` 零改动；W0 冻结指标不变。
- 真机不误停、逐卡 diff **未验证**，不声称 W3 完成。
- 接入会改变真机执行行为，按「一次一项、做完就停」单独一个单元交付。

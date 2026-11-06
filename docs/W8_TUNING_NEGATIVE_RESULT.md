# W8-d：Q44 针对性评分调参的阴性结果（L2 离线）

> 任务书 W8「做针对性先验/评分调参」。本轮按 W8-c 定位的根因做了一个
> **可关闭、可量化**的调参实验，结果是**阴性**：该调参不能让 Q44 不漏优。
> 按 AGENTS.md 3.C/5，如实记录阴性结果并停止猜测式修改，不继续叠加补丁。

## 实验设计

- 根因假设（W8-c）：`CombatSearch.expandTurn` 里药水动作有硬编码 `-15.0` 机会成本，
  且 `turnScore` 没有对「喝药后低费牌连打」给引导，导致 Q44 含油最优路线被剪掉。
- 新增 `W8PotionPrior`（默认 `enabled=false`），两个可关闭旋钮：
  1. `potionCostPenalty()`：关闭时 `15.0`（原值），开启时 `0.0`；
  2. `lowCostChainBonus(na)`：含药水动作时，对之后打出的牌按 `max(0,2-cost)*0.5` 加分。
- 接入：`CombatSearch.turnScore(List<Plan.Action>)` 加 `lowCostChainBonus`；
  两处 `-15.0` 改为 `W8PotionPrior.potionCostPenalty()`；
  `SearchQuality --w8prior` 为诊断开关（默认不传则 W0 基线逐位一致）。

## 实测结果（数字）

| 配置 | Q43 beam / 参照 | Q44 beam / 参照 | Q45 beam / 参照 | W8 漏优率 |
|---|---|---|---|---|
| 默认（关闭） | -188 / -173 | 995970 / 999970 | 999970 / 999970 | **2/3 = 66.7%** |
| `--w8prior` 开启 | -188 / -173 | 995970 / 999970 | 999970 / 999970 | **2/3 = 66.7%** |

- 开关自检（临时探针，非入库工具）：关闭时 `penalty=15.0 / bonus=0.0`，
  开启时 `penalty=0.0 / bonus=1.0`（含 1 张 0 费牌后），证明开关**确实生效**，
  不是无效代码。
- 但 Q43/Q44/Q45 的 PROVABLE beam 分数**三项完全不变**。

## 为什么没用（根因再定位，非猜测）

`turnScore` 只作用于 `expandTurn` 的**层内候选排序/剪枝**；PROVABLE 层报告里
`beam=995970` 与参照 `C(2)=999970` 的差距来自**终局评估** `CombatSearch.evaluate()`
（胜负项 1000000 量级、扣血 ×1000、回合 ×10），不是 `turnScore` 的排序分。
也就是说：beam 找到的路线是**真的终局更差**（多掉血/多耗回合），而不是被排序分压低。
因此任何只改 `turnScore` 的调参都改变不了这个结果。

真正的缺口在**动作生成/搜索结构**：W8-b 的 49 组宽度网格 Q44 全部漏优、
`PriorOracle` 先验开启无效、本轮 `W8PotionPrior` 也无效——三组独立实验一致指向
「搜索没有生成/保留住『喝油 → Cleave → Strike_R → Anger → Defend_R』这条 5 张牌路线」，
属于 beam 在回合内逐步扩展时被 `withinTurnWidth` 结构或去重提前截断，而非评分偏好。

## 结论与后续（如实）

- **反超仍未成立**：本实现在 Q43/Q44 仍漏优，没有「参考实现会做错、本实现做对」的证据。
- 本轮调参**未修复** Q44，且**未设为默认**（`enabled=false`），故 W0 基线不变。
- 按 AGENTS.md 5「两次针对同一假设的修复都失败则停止猜测式修改」，
  W8 评分调参到此为止；下一步应转向**动作生成/搜索结构**（P1），
  需要单独任务授权并做 W0 前后对照，本轮不擅自扩张。
- 证据等级：**L2 离线**（同一模拟器 beam vs 可穷举参照），非真机 L4。
- 复跑：`java -cp build/solvertest SearchQuality --only 43-45 --w8prior`
  应与默认分片 `docs/W8_ORACLE_SLICE_D.txt` 的分数完全一致。

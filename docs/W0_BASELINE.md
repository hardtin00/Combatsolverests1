# W0 求解质量基准（2026-11-06 冻结）

本文件是任务书 W0 的度量尺定义。此后任何**搜索 / 评估 / RNG / 药水**改动，交付必须附
「改动前后指标对照」；没有数字不得声称质量提升。

## 1. 评测集（冻结）

### ① Oracle 题库（42 题）

定义在 `tools/solvertest/SearchQuality.java` 的 `buildCorpus()`（Q1–Q42），参照求解器为
`tools/solvertest/Oracle.java`。Q13–Q42 为本轮新增 30 题，按机制分组：

| 组 | 题号 | 覆盖 |
|---|---|---|
| A 搜索机制 | Q13–Q20 | 能量/X 费、多段伤害、群体易伤、格挡成长(Body Slam)、斩杀阈值、虚弱链、过牌先后、双目标分配 |
| B 机制牌 | Q21–Q30 | Warcry(选牌)、True Grit(消耗)、Burning Pact、Headbutt、Dual Wield、Exhume、Feed、Havoc、Infernal Blade、Sword Boomerang(随机目标) |
| C 随机/顺序 | Q31–Q38 | Anger(复制)、Perfected Strike、Rampage、Whirlwind(X 费)、Pummel、Dropkick、Blood for Blood、残血药水 |
| D 边界/长线 | Q39–Q42 | 单敌长线、双敌齐射、零费 12 步上限、易伤+虚弱叠加 |

**口径约束**：全部题目使用 `CardLibrary` 已登记且 `approx=false` 的牌，`buildCorpus()` 末尾的
合法性检查会拒绝未登记/近似牌。`Oracle.java` 头部注释已明确：需要玩家选择的牌由引擎
启发式代选，不在搜索空间内；`approx` 牌不进题库，由 W5 真值夹具单独处理。

### ② 自动对局集（离线原型已跑通；真机 harness 仍缺）

任务书要求「≥200 局 A20 固定种子，复用 battleai-frontier 已验证的 auto-play 批量跑法」。
调查结论见 `docs/W0_AUTOPLAY_INVESTIGATION.md`：仓库与历史工作树**没有可复现的真机批量
harness**（只有 172 个不含结果数据的沙盒快照，且上游 auto-play 控制器无修改授权）。

本轮补了一个**离线原型** `tools/solvertest/BatchPlay.java`：固定 200 个种子（`20261106+i`），
用 `CombatSearch`(档位 1) 搜索整场计划，在**同一 `CombatEngine` 模拟器**上原样重放。
**口径声明**：这是「同模拟器自打」，**不是真机 auto-play(L4)，不是与参考项目对等的实机胜率**，
不可与真机胜率或 Oracle 漏优率混同。它只作为真机 harness 到位前一把可复现、可进 CI 的离线尺子。

200 局基线（2026-11-06，档位 1，节点/回合预算、墙钟关闭）：

| 指标 | 基线 |
|---|---|
| 局数 / 搜索出计划 / 重放成功 | 200 / 200 / 200 |
| 胜率 | **75.0% (150/200)** |
| 平均每场 HP 损失 | **35.5** |
| 每场搜索耗时 | 平均 **421ms**，最大 **1566ms** |
| 逐遭遇失血分布 | 15 个遭遇，见 `docs/W0_AUTOPLAY_BASELINE.txt` |

复跑：`java -cp build/solvertest BatchPlay --runs 200`。
任务书要求的**真机 ≥200 局 auto-play 仍未完成**，作为 W0 的下一项独立任务。

## 2. 指标定义

| 指标 | 定义 | 来源 |
|---|---|---|
| 胜率 / 平均每场 HP 损失 / 逐遭遇失血分布 | 自动对局集统计 | 离线原型 200 局：75.0% / 35.5 / 15 遭遇分布，**同模拟器自打口径，非真机 L4** |
| 漏优率(PROVABLE) | beam 分数低于「与 beam 实际展开深度相同的可穷举参照 C(d)」的场景数 / 参照 EXACT 场景数 | `SearchQuality.summarize()` |
| 漏优率(BUDGET) | 同上，但用玩家实际回合预算 maxTurns；CAPPED 场景只提示、不计入 | 同上 |
| 必胜漏报 | 参照存在完整获胜路线、beam 未给出获胜预测的场景数 | 同上 |
| 每场搜索耗时 | BUDGET 档 `Level.beamMs` 的平均/最大 | 本轮新增汇总行 |

**判定口径**：双方共用 `CombatEngine` 结算与 `CombatSearch.evaluate` 打分，唯一差别是搜索方式，
所以漏优只能来自搜索（截断、去重、预算），不来自规则差异。Oracle 是「同模拟器 + 同目标函数 +
同预算」内的上界，**不是现实游戏全局最优**；CAPPED 表示参照触顶，只报下界。

## 3. 基线数字（2026-11-06，档位 1「中」）

编译与运行：JDK 17 `--release 8`；`SolverSettings.applyTo(1)` →
`beamWidth=24 withinTurnWidth=32 maxTurns=10 maxNodes=60000 timeBudgetMs=8000`；
参照 `stateCap=300000 deadline=12000ms`。

| 指标 | 基线 |
|---|---|
| 场景数 | 42 |
| 参照自检 | 42 题 × 5 断言 = 通过 210 / 失败 0 |
| PROVABLE(2 回合) | EXACT 42，漏优 **0**，漏优率 **0.0%** |
| BUDGET(10 回合) | EXACT 2 / CAPPED 40，EXACT 子集漏优 **0** |
| 必胜漏报 | **1**（Q7 药水成本，历史已知：力量药水固定扣 15 分机会成本） |
| 每场 beam 耗时 | 分片 a(Q1-14) 平均 102ms/最大 372ms；b(Q15-28) 87ms/184ms；c(Q29-42) 94ms/224ms；42 题合计平均约 94ms、最大 372ms |
| 自动对局(离线原型) | 200 局，胜率 75.0%，平均失血 35.5，耗时平均 421ms/最大 1566ms（**非真机**） |

分片报告：`W0_BASELINE_slice-a.txt` / `-b.txt` / `-c.txt`；自检报告 `W0_BASELINE_selftest.txt`。

## 4. 复跑命令

```bash
# 编译（仓库根目录）
javac --release 8 -encoding UTF-8 -d build/w0 \
  src/main/java/com/combatsolver1/engine/*.java \
  src/main/java/com/combatsolver1/search/*.java \
  src/main/java/com/combatsolver1/settings/SolverSettings.java \
  tools/solvertest/*.java

# 参照自检（应 210/0）
java -cp build/w0 SearchQuality --selftest

# 既有离线自检（应退出 0）
java -cp build/w0 Solvertest all

# 全量报告分 3 片（单进程全量会超过一次 shell 超时；--only 只选报告子集，不改评测）
java -cp build/w0 SearchQuality --only 1-14  > slice-a.txt
java -cp build/w0 SearchQuality --only 15-28 > slice-b.txt
java -cp build/w0 SearchQuality --only 29-42 > slice-c.txt
```

`--only` 与耗时汇总行为本轮加入，**只影响报告呈现，不改变预算、评估、Oracle 或任何求解行为**，
因此不同分片与全量口径一致，可合并统计。

## 5. 已知限制（如实标注）

- 模拟器 RNG 非真实 RNG（W1 处理），跨回合未知抽牌/随机怪物行为只能视为近似。
- 需玩家选择的牌由启发式代选；`approx` 牌未纳入题库（W5 处理）。
- Oracle 触顶(CAPPED)场景的「未漏优」不能作为最优证明。
- 自动对局集 ② 的**真机 harness 未建立**；本轮数字来自离线原型（同模拟器自打），**不是 L4 实机胜率**。
- 本基准不等于现实最优，也不等于参考项目的实机表现。

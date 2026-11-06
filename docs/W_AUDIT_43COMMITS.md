# CombatSolver1 逐 W 真实进度审计（2026-11-06，HEAD `28a6017`，43 个本地提交）

> 依据用户新指令第三节：为每个 W 建立
> ①已完成的子项 ②现存证据及其 L 等级 ③尚未完成的子项 ④具体阻断 ⑤下一项最小可执行任务。
> 只陈述本会话工具结果证实的事实；L 等级按 AGENTS.md 3.C；**不得因未达 L4 否定有效 L2/L3 证据**。
> 本文件是**只读审计**，不改功能、不动真机/远程。

## 全局状态（本轮实跑核验）

- `git log -1` = `28a6017`；`git status --short` 干净；`git rev-list --count 98aa381..HEAD` = **43**。
- 远程 `main` 仍 `98aa381`（`git ls-remote`，**43 提交未推送**）。
- docs 证据按 W 计数：W0 7 / W1 13 / W2 1 / W3 1 / W4 2 / W5 3 / W6 1 / W7 6 / W8 20。
- `tools/solvertest/*Check.java` = **18 个自检类**；CI `name:` 步骤 **30**。
- 2026-11-06 19:48 独立复跑（从 `cfa0544` 全量重编）：selftest 225/0、Q1-42 PROVABLE
  漏优 0.0% / 必胜漏报 1、BatchPlay 200 局 75.0% / 35.5、18 项自检全 0、
  L3 构建 150 条目 major=52 + final_check 248/248、bundle 40/40 恢复。见 STATUS「历史报告数据复核」条。
- **本轮修正的文档不一致**（README 支持矩阵，唯一可信来源）：
  ①「Oracle 42 题 / selftest 210」→ **45 题 / 225**；
  ② W1 旧结论「随机取牌类天生只能近似」→ 更正为 W1-j/k/l 的 18 行流归属表 + 逐位对拍；
  ③「wrapper counter 语义与剩余三流映射未对齐」→ 已核对完毕，改为已列明缺口。

---

## W0 求解质量基准

① **已完成**：Oracle 45 题（Q1-42 冻结 + Q43-45 W8 专项）、PROVABLE/BUDGET 分层、
BatchPlay 200 局固定种子 `20261106+i` 档位1、指标冻结入 `docs/W0_BASELINE.md`、
2026-11-06 19:48 独立复跑全部复现。
② **证据与等级**：**L2**（同模拟器自打 + beam vs 可穷举参照）。
`docs/W0_BASELINE.md`、`W0_BASELINE_selftest.txt`、`W0_BASELINE_slice-{a,b,c}.txt`、
`W0_AUTOPLAY_BASELINE.txt`、`W0_AUTOPLAY_INVESTIGATION.md`、STATUS 复核条。
Q1-42 PROVABLE 0.0% 漏优 / 必胜漏报 1；BatchPlay 75.0% / 35.5 / 搜索 365ms(平均)。
③ **未完成**：**真机 A20 ≥200 局自动对局 harness**（历史 `battleai-frontier` 172 份快照无结果数据、
无上游许可，见 `W0_AUTOPLAY_INVESTIGATION.md`）；真实 A20 胜率/失血分布无 L4。
④ **阻断**：需用户授权重建 harness 或提供可复现的真机自动对局环境；离线 200 局是
「同模拟器自打」，**不得当作真机 L4 胜率**。
⑤ **下一项最小任务**：**无需重测**（本轮已复核）。若后续改求解行为，按任务书做前后指标对照即可。

## W1 真 RNG 闭环

① **已完成（12 个离线单元）**：`TrueRng` 六流数学层、`SimState` 六流容器、
19 处调用点映射、`RngStreams` 只读反射 + `LiveCapture` 注入、重放分支改真 RNG、
`JavaLcg` 洗牌 + `cardRandomRng` 插入位置接入、wrapper counter 语义固化、
18 行逐调用点流归属表（W1-j/k 闭合两个待核对）、Java 六流推进与 vendored C++ 源码
**逐位对拍 0 差异**（W1-l）。
② **证据与等级**：**L2**（13 份 W1 文档、8 个自检）；**L3** 完整编译已通过
（`RngStreams`+`LiveCapture` 带游戏 jar 编译 rc 0，见 STATUS W1-d）。
`docs/W1_JAVA_VS_CPP_TRACE.txt` / `W1_CPP_INDEPENDENT_TRACE.txt` / `W1_JAVA_LCG_TRACE.txt`、
`W1_RNG_CALLSITE_MAP.md`、`W1_RNG_STREAM_ATTRIBUTION.md`、`W1_RNG_STREAM_CHECK.txt`(8/8)、
`W1_RNG_STREAM_STATE_CHECK.txt`(12/12)、`W1_RNG_INJECTION_CHECK.txt`(5/5)、
`W1_RNG_STREAMS_READ_CHECK.txt`(6/6)、`W1_SHUFFLE_WIRE_CHECK.txt`(9/9)、
`W1_RNG_COUNTER_SEMANTICS_CHECK.txt`(5/5)。
③ **未完成**：**W1 全部 L4 真机项**——`RngStreams.read()` 在真机返回六流、
洗牌后牌序 / 随机目标 / 随机出招三类逐位一致、CombatRecorder diff 零差异、
新类打包进交付 jar。结构性缺口已列明：`cardRng` 未捕获、`monsterHpRng`/`miscRng`
未建模、`potionRng` 无调用点、3 处机制只能近似。
④ **阻断**：真机 L4 仍需「点 Embark 开局」——会写正式 `saves/1_IRONCLAD.autosave`，
属用户新指令明确要求单独授权的高风险项（备份 `_build/save-backup-r47-1959/` 已备，
回滚方案见 STATUS 20:17 条）。**CU 环境级障碍已排除**（见下）。
⑤ **下一项最小任务**：**授权后点 Embark 开一局、进第一场战斗、发牌后只读采集**
（截图 + `LIVE_SEARCH_TRACE.jsonl` + 六流 RNG）；完事从备份还原并核对指纹。
W1 离线侧已闭合，纯离线无可独立推进的实质项。

> **2026-11-06 20:23 更新（CU 实证，覆盖原文「游戏未启动 / 游戏内 UI 识别仍 L0」）**：
> 连续 4 轮实际使用 Computer Use 完成：启动 MTS+游戏 → 主菜单 → 模式选择 →
> Choose Your Character → **The Ironclad / Ascension 20 (Max Level) Double Boss** →
> **Enter a new Seed 对话框**（打开后 Cancel）。真实截图
> `docs/evidence/cu-launch/01..06.png`。故「CU 能识别/操作游戏内 UI」与
> 「A20 环境可达」「固定种子入口可打开」**已从 L0 升为 L2/L3 环境级实证**；
> 但**卡牌/怪物/药水/战斗结算界面识别仍 L0**，且未进战斗、无 L4。
> 全程 `autosave`/`mods jar` 指纹恒定、无残留进程。

## W2 实机验收

① **已完成**：代理侧准备物 `docs/W2_ACCEPTANCE_KIT.md`（10 固定种子、14 项逐场检查单、
离线复算命令）；`run-root-replay-diff.ps1 -SelfTest` 30/30。
② **证据与等级**：**L2**（准备物）；**新增 L2/L3 环境级实证**——CU 已能导航到
A20 铁甲战士确认画面并打开固定种子输入框（`docs/evidence/cu-launch/04..06.png`），
`docs/V1_ACCEPTANCE.md` 实战通过数仍 **0/10**。
③ **未完成**：10 场真机验收、Exhume 多候选 ×2、每场 bundle/截图/操作轨迹/终局核对、
`1.0.0-rc1` 转正评审。
④ **阻断**：**「CU 自动导航战斗」能力前置已排除**（环境级 L2/L3 已证）；
但点 Embark 开局会写正式存档，仍需用户单独授权「本次测试改写 autosave、完事从备份还原」。
⑤ **下一项最小任务**：获授权后按 `W2_ACCEPTANCE_KIT.md` 的 10 种子逐场自动导航；
**不得自动发布**。

## W3 逐张重采核对

① **已完成**：`PerCardVerify` 判定器（PASS/VERIFYING/REFUSED/EXHAUSTED、
2.5s 节流、每场 8 次上限）、注入式不匹配停机证明。
② **证据与等级**：**L2 12/12**（`docs/W3_PER_CARD_VERIFY_CHECK.txt`）。
③ **未完成**：接入 `CombatController` 逐动作调用点、真机稳定帧重采与预测 diff、
真机正常战斗全程不误停。
④ **阻断**：接入会改变运行路径与真机行为，需单独任务授权 + 真机验证。
⑤ **下一项最小任务**：W3-b 接入（需 W1 L4 前置与授权）。

## W4 引擎路径药水

① **已完成**：药水协议自检 11/11、`potion_actions_mode` 交付文档冲突核验
（引擎能规划 on、mod 执行路径闲置，是刻意降级非笔误）。
② **证据与等级**：**L2**（`docs/W4_POTION_MODE_CHECK.txt`、
`W4_DELIVERY_DOC_CONFLICT.md`、AUDIT §2425 历史 L4 记录，**不冒充本轮实测**）。
③ **未完成**：`potion_actions_mode=on` 的 mod 执行路径接回、含药路线真机全链证据、
交付 README/KNOWN_LIMITS 同步。
④ **阻断**：**依赖 W3 完成**（任务书顺序）；且改运行路径/交付物需授权。
⑤ **下一项最小任务**：W3-b 完成后再评估；当前不可推进。

## W5 真值夹具

① **已完成**：逐机制夹具 10/10、29 个历史真机局面夹具 10/10（AUDIT §2 数字一致）、
选牌屏字节码真值差异 6 张逐张登记（仍标未覆盖）、六流 RNG 对拍（W1-l）。
② **证据与等级**：**L2**（`docs/W5_MECHANISM_FIXTURE_CHECK.txt`、
`W5_HISTORICAL_29_CHECK.txt`、`W5_SELECT_SCREEN_TRUTH_CHECK.txt`、
`tools/solvertest/fixtures/historical_29/` 30 文件）。
③ **未完成**：每张近似牌/药水/选牌屏的逐项真值夹具全量化；
`monsterHpRng`/`miscRng` 机制夹具；选牌屏仍是代选近似。
④ **阻断**：非阻断项（可穿插），但需真值来源（字节码/真机）才能新增，不能编造。
⑤ **下一项最小任务**：可穿插做「Burning Pact 选牌屏真值夹具」，
但优先级低于 W1/W2 关键路径。

## W6 仓库卫生

① **已完成**：`LICENSE`(MIT)、找回 2 个缺失源文件、Release SHA256 清单
（`docs/W6_RELEASE_SHA256SUMS.txt`，本轮已注明 jar SHA 随构建时间变化、非可复现指标）、
可移植 bundle 40/40 恢复抽查、`git diff --check` 通过。
② **证据与等级**：**L2/L3**（`docs/V0.9_LOCAL_PACKAGE_SHA256SUMS.txt`、
`W6_RELEASE_SHA256SUMS.txt`、L3 完整构建、bundle verify）。
③ **未完成**：`git rm artifacts/**/*.jar` + 发布 GitHub Release + 改 README
「仓库不再存放 jar」措辞；**43 提交推送到 GitHub**。
④ **阻断**：删除已跟踪文件 + 远程操作，`AGENTS.md` 1.6 需明确授权；
直推 main 历史上有 `commit_refs_rules` 拒绝（需新分支 + API 快进 + 代理）。
⑤ **下一项最小任务**：刷新 bundle 到 43 提交（纯本地、无需授权，可在收尾轮做）。

## W7 引擎路线主力化

① **已完成 3/6 项**：出牌排序先验（默认关，W0 前后对照 75.0→76.0%）、
5 档预算分档基线（L2 5/5，高档不涨胜率只涨耗时）、
`PlanReducer` 有界删除（L2 8/8，**未接入**交付路径）。
② **证据与等级**：**L2**（`docs/W7_CARD_ORDER_PRIOR_CHECK.txt`、
`W7_PRIOR_BATCHPLAY_{OFF,ON}.txt`、`W7_BUDGET_PRESET_BASELINE.txt`、
`W7_PLAN_REDUCER_CHECK.txt`）。
③ **未完成**：引擎 worker 常驻、每场重导 RNG 并持续同步、
高预算 ≥20000 次模拟、无效路线/冗余动作清理接入、每个动作后重新规划；
引擎路线占比 / 重规划次数指标未建立。
④ **阻断**：**依赖 W1/W3/W4 满足前置**；且核心改动会动 W0 指标，需逐项前后对照授权。
⑤ **下一项最小任务**：前置未满足，当前不可推进。

## W8 反超点位

① **已完成**：3 道专项题 Q43-45（45 题 selftest 225/0）、
根因定位与**两次自我更正**（W8-b/d 误判「结构性缺陷」→ 实测为 `within=32` 太窄）、
全局宽度权衡（修好 Q43/Q44 但胜率 75.0→73.5%、耗时 ~4x，故**不改默认**）、
定向宽度（只对含药水状态加宽，修好 Q44、W0 不回退、默认关）、
Q44 具体对局差异与阴性调参记录。
② **证据与等级**：**L2**（20 份 W8 文档，含 `W8_W0_WIDTH_TRADEOFF.txt`、
`W8_F_TARGETED_WIDTH.txt`、`W8_Q44_ROOT_CAUSE.md`、`W8_TUNING_NEGATIVE_RESULT.md`）。
③ **未完成**：**反超未成立**——默认档位下 Q43/Q44 仍漏优 2/3=66.7%；
Q43（燃烧契约，不含药水）机制专属处理；含药水真机 L4；
「参考实现会做错、本实现做对」的同等条件对局证据。
④ **阻断**：需真机 L4 与参考实现同条件对比；Q43 修复需改搜索行为并做 W0 对照。
⑤ **下一项最小任务**：Q43 的「不含药水定向宽度」实验（纯离线、无需授权），
但须先决定默认档位是否改动；或按任务书留到基准稳定后。

---

## 审计结论（如实）

- **离线侧**：W0 复核属实、W1 数学层闭合、W5/W6 证据充分、W7 3/6、W8 有量化基线但反超未成立；
  全部为 **L2/L3**，**无 L4 真机证据**。
- **关键路径**：W1 L4 → W2 十场 → W3/W4 → W7；当前卡在 W1 L4 的「启动游戏」授权。
- **不得混用**：代码已实现 / 离线通过 / 实机通过 / 发布验收 四者严格区分；
  本文件不把任何离线通过说成实机通过。
- **本轮未做**：未使用 CU、未启动游戏、未做真机 L4、未推远程、未改游戏 mods、未碰存档。

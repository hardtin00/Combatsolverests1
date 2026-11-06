# W2 实机验收检查单（代理准备，用户执行）

> 任务书 W2：「10 场实机验收 + Exhume 多候选 ×2。按 docs/V1_ACCEPTANCE.md 清单执行。
> 代理准备检查单/复现种子/离线复算；用户只按步骤打游戏导记录。失败场次给根因与修复。
> 完成则 1.0.0-rc1 转正。」
>
> **本文件是代理交付的离线准备物（L2）。真正的实机 L4 由用户按下面步骤产生证据，代理不代打、不编造。**

## 0. 证据等级与边界

- 本文件的种子表、离线复算命令、逐场检查单是 **L2 离线**准备物。
- 用户实机导出的 bundle / 截图 / 日志才是 **L4** 证据；没有它们，不得把本文件当成验收通过。
- 内置 Java 路线仍标「近似」；**不得以 Java 离线结果替代 sts_lightspeed 外部引擎的实机验收**。
- 每场必须记录 JAR 与引擎的 SHA-256（见 §4），否则 bundle 不可比。

## 1. 复现种子（10 场，固定）

STS1 的 run seed 是长整型。下表 10 个种子固定，用户用「自定义种子 / seeded run」进入，A20 铁甲战士。
种子与 `tools/solvertest/BatchPlay.java` 的 `SEED0=20261106` 体系同源（第 i 场 = `20261106+i`），
便于离线复算对照；**离线复算只作同模拟器自打的参考，不是实机预期值**。

| # | seed | 遭遇类型 | 目标 | 操作覆盖 |
|---:|---:|---|---|---|
| 1 | 20261107 | 普通 · Jaw Worm | 单敌多回合 | G 逐步执行、H 自动、J 停止后继续 |
| 2 | 20261108 | 普通 · Cultist | 跨回合力量铺垫 | H 全自动打完整场 |
| 3 | 20261109 | 普通 · 2 Louse / 3 Louse | 多目标分配 | G 逐动作核对目标 |
| 4 | 20261110 | 普通 · Small Slimes | 分裂/多目标 | H + 截图 |
| 5 | 20261111 | 普通 · Gremlin Gang | 多怪 | J 中途停止 → 继续 |
| 6 | 20261112 | 精英 · Gremlin Nob | 高伤/易伤 | H 全自动 + 终局 HP 核对 |
| 7 | 20261113 | 精英 · Lagavulin | 睡眠/多回合 | 多回合路线与 STATE0 核对 |
| 8 | 20261114 | 精英 · 3 Sentries | 多目标 + Exhume 候选 | **Exhume 多候选 ×1** |
| 9 | 20261115 | BOSS · Hexaghost | 多回合 + 状态牌 | **Exhume 多候选 ×2** |
| 10 | 20261116 | BOSS · The Guardian | 模式切换 | H 全自动 + 全套记录 |

> Exhume 两场（#8/#9）必须**至少各一次**「原生选牌屏出现 ≥2 张已消耗牌候选、用户/求解器选中其中 1 张、随后动作继续执行」的完整证据链。

## 2. 每场逐项检查单（复制 10 份，逐场勾选）

```
场次 #___  seed=__________  日期=__________  操作者=__________
[ ] 1. 从地图节点进入新战斗（非 Continue 读档）
[ ] 2. 记录 JAR SHA-256：____________________  引擎 SHA-256：____________________
[ ] 3. 记录进入方式：普通 / 精英 / BOSS，遭遇名：__________
[ ] 4. 发牌后截图（面板可见、路线分区、逐动作编号）截图路径：__________
[ ] 5. G 执行本回合：动作 1..N 与面板路线逐条核对，目标正确
[ ] 6. H 自动执行：连续打完整场，无「稍等再点」停顿、无莫名停止
[ ] 7. J 停止 → 再启动：状态恢复、路线不过期
[ ] 8. 终局：胜/败，最终 HP = ____，与面板整场预测对照：预测 ____ / 实际 ____
[ ] 9. 导出 bundle：名称 __________，路径 __________
[ ] 10. STATE0 / journal / 卡牌 UUID / 目标引用 离线复算（§3）结果：__________
[ ] 11. 药水 / 选牌屏：是否出现 ____，是否原生点击 ____，证据路径 ____
[ ] 12. Exhume 多候选（仅 #8/#9）：候选数 ____，选中卡 ____，后续动作 ____，截图/日志 ____
[ ] 13. 差异单列：____________________（无法由现有差分器验证的写「验证受限」，不算严格通过）
[ ] 14. 验收结论：通过 / 失败（失败附根因与修复建议）
```

## 3. 离线复算命令（代理已备，用户对每个 bundle 执行）

仓库根目录执行。`<BUNDLE>` 为用户导出的实机 bundle 路径。

```powershell
# 3.1 根重放差分（核心：STATE0 → 引擎重放 → 逐动作字段对照）
#     接受 -BundlePath；-SelfTest 为复算器自检
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 `
    -BundlePath "<BUNDLE>" -EnginePath "build/engine/combatsolver1-engine.exe"

# 3.2 终端重放（终局一致性），接受 -BundlePath
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-terminal-replay-check.ps1 -BundlePath "<BUNDLE>"

# 3.3 复算器自身可信度自检（无 -BundlePath 参数，离线跑；#8/#9 前必跑）
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 -SelfTest
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-exhume-execution-check.ps1
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-card-recording-policy-check.ps1
# 注：run-journal-lock-check.ps1 用 -SourcePath（不是 bundle），run-exhume-execution-check.ps1
#     与 run-card-recording-policy-check.ps1 依赖本机 IntelliJ JBR 路径，若缺失需改脚本指向可用 JDK。

# 3.4 离线自打参考（同模拟器，非实机预期；用与 10 场同源码的种子体系）
java -cp build/solvertest BatchPlay --runs 10
```

每个复算输出必须记录到该场次目录：`SUMMARY ... firstDifference=none` 才算该链条通过；
`firstDifference!=none` 或 `verification limited` 必须单列，不得计严格通过（AGENTS.md 4.3）。

## 4. 版本指纹（用户执行前填写）

```
Mod JAR 路径：________________  SHA-256：____________________
引擎 EXE 路径：______________  SHA-256：____________________
ModTheSpire / BaseMod 版本：____________________
游戏版本 / 分辨率：____________________
```

## 5. 发布门槛（任务书 W2）

- 10 场中 **全部有效通过** 且 **Exhume 多候选 ≥2 次成功**，方可把 `1.0.0-rc1` 转正为 1.0.0。
- 任一失败场次：代理给根因与修复，修复后重跑该场；不得用离线通过替代。
- 完成后由代理更新 `STATUS.md`、README 支持矩阵，并把 `docs/V1_ACCEPTANCE.md` 的
  「10 场有效验收 0/10」改为实际数字。

## 6. 本轮未做 / 不声称

- 未运行任何真机战斗、未导出 bundle、未产生 L4 证据。
- 未修改任何求解/执行/UI/存档代码，W0 指标不变。
- 种子表是为可复现性设计的**建议值**；若游戏内 seeded run 的遭遇与表中类型不符，以实际遭遇为准并记录差异。

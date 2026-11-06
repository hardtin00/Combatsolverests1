# W0 自动对局集（≥200 局 A20）可行性调查（2026-11-06）

任务书 W0 ② 要求：「≥200 局 A20 固定种子，复用 battleai-frontier 实验已验证的 auto-play
批量跑法」。本文件记录定向调查结论，供决定下一步路径。**本轮未产出胜率/失血数字，不声称完成。**

## 1. 调查范围与命令

- 主仓库 `_build/repo-push`（GitHub `hardtin00/Combatsolverests1`，HEAD `461e9d9`）
- 历史工作树 `%TEMP%/cs1-battleai-frontier-worktree-20261023`
- 历史工作树 `%USERPROFILE%/.codex/worktrees/search-frontier-abc`
- 全盘检索 `battleai-frontier` / `auto-play` / `200 局` / `winrate` / `batch` 关键字

## 2. 发现

### ① 历史 auto-play 运行目录：172 个（不是 200）

`%TEMP%/cs1-battleai-frontier-worktree-20261023/test-data/real_backend_runs/` 下按批次：

| 批次 | 局数 |
|---|---:|
| all-encounters-B-5k | 61 |
| all-encounters-B-15k | 50 |
| search-quality-B-baseline-20261023 | 20 |
| victory-replay-diagnostic | 13 |
| search-quality-continue-after-victory-20261023 | 13 |
| search-key-optimized | 13 |
| small-slimes-comparator-fix-20261023 | 1 |
| replay-positive-control-20261023 | 1 |
| **合计** | **172** |

目录命名如 `a20-ironclad-auto-act1-exordium-thugs-seed-2026102407-B-15k`，跨 act1/act2/act3
各遭遇，含固定 seed。每局约 98 MB，是**完整沙盒快照**（`home/.javacpp/cache/FightPredictor.jar`、
`home/AppData/Local/ModTheSpire/*` 等），**不含可直接读取的战斗结果 json/csv/log**。
抽查一局的 `home/` 下除 DLL/JAR 和配置外没有结果文件。

### ② 没有可复现的批量 harness

两个历史工作树里都没有 `*.py` / `*.ps1` / `*.sh` 的批量跑法脚本；`search-frontier-abc`
是 **A/B/C 搜索策略实验**（`ab-results-20261023/frontier-abc/report.md`），不是 200 局 auto-play。
该报告自己声明历史根状态不合格（怪物意图不匹配 `MonsterAi`、缺史莱姆分裂、含未建模
`AscendersBane`/`FairyPotion`），「不能作为搜索质量证据」，并明确「A/B/C 是本地独立实现，
不修改或复现 BattleAiMod 1.4.4」。

### ③ 主仓库没有离线批量对局 runner

`tools/` 下是数据表/差分/录制器/补丁检查工具；`Solvertest all` 只跑既有断言与差分重放。
`CombatEngine`（纯 Java 模拟器）与 `CombatSearch`（beam）存在，理论上可构造离线批量对局，
但那是**另一套口径**（同模拟器自打），与任务书要求的「真机 auto-play 批量跑法」不同。

### ④ 权限与许可边界

`search-frontier-abc/report.md` 记载：AIPlaySpire 的 `scumthespire/pom.xml` 指向 BattleAiMod
1.4.4，未找到 LICENSE 或修改授权；上游源码的复制/再分发权限未确认。因此**不能直接移植
上游 auto-play 控制器**，任务书也规定「移植需单独授权」。

## 3. 结论（L0/未实现，附证据等级）

- W0 ② 的「≥200 局」目前**只有 172 个历史目录，且无结果数据、无 harness、无许可**，
  不能直接拿来当评测集或提取胜率。
- 三个可行方向（需用户选择，见交付说明）：
  1. **离线自建 runner**：用 `CombatEngine` + `CombatSearch` + 固定 200 种子跑完整场，
     统计胜率/失血/耗时。优点：可进 CI、可复现、无需真机；缺点：是「同模拟器自打」，
     不是实机 L4 胜率，口径必须在报告里如实标注，不得与真机混同。
  2. **重建真机 harness**：需要游戏本体 + ModTheSpire + 能驱动 auto-play 的入口，
     并按任务书逐场导记录。成本高、依赖用户授权与真机环境。
  3. **从 172 个沙盒里抢救结果**：需先确认结果是否写在沙盒别处（本轮抽查一局未找到）；
     即便找到也只有 172 局、且历史根状态被报告判为不合格。

## 4. 本轮未做 / 不声称

- 未产出任何胜率、平均每场 HP 损失、逐遭遇失血分布数字。
- 未运行任何真机 auto-play，未复制或移植上游代码。
- 未改动 W0 基线数字（Oracle 42 题与漏优率保持不变）。

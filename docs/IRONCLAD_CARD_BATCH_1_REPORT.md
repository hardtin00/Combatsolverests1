# CombatSolver1 Ironclad 卡牌批次 1

## 验收范围

仅审查 Cleave、Bludgeon 第一批，在独立 Worktree `combatsolver1-card-batch`、分支 `codex/card-batch`、基线 `9687e2f959e23313dd85834ded8563e666569b54`。本轮没有修改生产源码或共享测试脚本，没有启动游戏、部署 JAR 或修改存档。

本轮复验时间为 2026-10-20 22:47:06–22:47:32（Asia/Shanghai）。

## 引擎构建证据

引擎文件存在于 `C:\Users\3476\Documents\Codex\2026-10-15\jie\outputs\combatsolver1-card-batch\build\engine-mingw-card-batch\combatsolver1-engine.exe`。实际计算的 SHA-256 为 `500450B121218CE902C325A20129773F194FD7663E4CACE4830886C45F19B281`。Java、互操作和效果测试日志均写出相同规范路径及哈希。

- 完整 Release 构建日志 `build/engine-mingw-card-batch/release-build.log` 显示 CMake 配置退出码 0、构建退出码 0，编译器 GNU 16.2.0，生成器 Ninja。
- 本轮重新执行 `cmake --build build/engine-mingw-card-batch --config Release --parallel 2`，退出码 0；Ninja 输出 `no work to do`。本轮是已成功完整构建目录的增量确认，没有重新编译源文件。新日志 `build/engine-mingw-card-batch/verification/final-audit-20261020/00-release-build.log`。
- 本轮路径及哈希记录：`.../final-audit-20261020/00-engine-identity.txt`。

## 卡牌专项目标与结果（L2 离线合成 fixture）

| 卡牌 | Java 映射与序列化 | 本轮 C++ 状态恢复 | 效果回归 | 实机验证 |
|---|---|---|---|---|
| Cleave / CLEAVE | 通过；普通/升级版，费用 1 | 8/8 实例 MATCH，四牌堆各有两种升级状态；UUID、ID、升级、费用、牌堆位置、special_data、retain 均核对 | 2/2 通过；伤害 8/11 命中全部两名敌人，验证费用及出牌后实例进入 discard | 未执行 |
| Bludgeon / BLUDGEON | 通过；普通/升级版，费用 3 | 8/8 实例 MATCH，四牌堆各有两种升级状态；UUID、ID、升级、费用、牌堆位置、special_data、free_to_play_once 均核对 | 2/2 通过；伤害 32/42 只命中指定敌人，验证费用及出牌后实例进入 discard | 未执行 |

互操作实际经过 Java 卡牌源对象、Java 序列化生成的协议文件及本批 C++ 引擎恢复入口。总计 16/16 卡牌实例 MATCH、0 差异；完整协议字段回归 149 路径：148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE。拒绝用例 Java 5/5、C++ 4/4。日志：`.../final-audit-20261020/01-java8-mapping.log`、`02-java-protocol-cpp-interop.log`。差分摘要亦在该互操作日志报告的临时文件中；该临时文件不属于 Git 交付。

效果回归日志 `.../final-audit-20261020/03-card-effect-regression.log` 显示两张卡各有普通/升级两种变体通过，合计 4/4。它通过实际调用 `card_batch_effect_check`，不是仅依据映射或分发分支。

## 通用回归（与卡牌效果证据分开）

- `tools/solvertest/run.ps1 -Command all`：本轮执行退出码 0，日志 `.../final-audit-20261020/04-solvertest-all.log`。日志中历史实战样本的 70 个动作、668/793 字段匹配、48 个差异是该套件报告的既有样本诊断，不是本批卡牌专项结果。
- `git diff --check`：本轮执行退出码 0，日志 `.../final-audit-20261020/05-git-diff-check.log`。

## 共享脚本 Shrug 异常（单独记录）

先前报告记录过一次 `live-state-v1-check.ps1` 的 Shrug 非法 `special_data` 断言异常：脚本错误信息称 “C++ accepted”，但报告同时称 C++ 输出 `LIVE_STATE_REJECTED reason=unsupported card special_data: SHRUG_IT_OFF`。保留的旧日志 `build/engine-mingw-card-batch/verification/04-live-state-regression.log` 在 Shrug 结果前结束，没有该次断言的完整原始输出及退出码，因此该历史异常的真实触发条件无法从那份日志核实。

本轮以同一引擎单独重跑共享脚本，退出码 0；其输出明确为 `shrug_invalid_special_data_rejected=PASS`。新日志 `.../final-audit-20261020/06-shared-live-state-regression.log`。该共享回归不计入 Cleave/Bludgeon 专项通过数，本轮没有修改共享脚本。历史异常未复现，根因仍未核实。

## 证据边界与交付

Cleave、Bludgeon 的映射、Java→C++ 状态恢复和离线效果回归为 L2 证据。没有 L3/L4 实机验收。`build/` 下的引擎、日志和差分文件是忽略的本地构建产物，不等同于已纳入 Git 的交付文件；主窗口复核这些日志需读取当前 Worktree 的本地文件。


## 主工作区统一复验（2026-10-20）

本节是主工作区重新构建后的当前结果；上文卡牌 Worktree 的历史复验保留为历史记录。当前 MinGW g++ + Ninja Release 引擎由 `cmake -S vendor/sts_lightspeed -B build/engine-mingw-unified-release -G Ninja -DCMAKE_MAKE_PROGRAM=... -DCMAKE_CXX_COMPILER=... -DCMAKE_BUILD_TYPE=Release` 配置并通过 `cmake --build build/engine-mingw-unified-release --config Release --parallel 2` 完整构建，两个命令均退出 0。引擎：`C:\Users\3476\Documents\Codex\2026-10-15\jie\outputs\combatsolver1\build\engine-mingw-unified-release\combatsolver1-engine.exe`；SHA-256：`C74E62F13A9612960C374AB89E23B8EA02CCAF82190EB96D55E6EFCDEB207A61`。命令及身份记录位于 `build/engine-mingw-unified-release/verification/unified-20261020/`。

- `card-batch-interop-check.ps1 -Engine <上述引擎>`：退出 0，Java class major 52；16/16 卡牌实例匹配；149 个协议路径中 148 MATCH、0 MISMATCH、0 UNAVAILABLE、1 NOT_APPLICABLE；Java 拒绝 5/5、C++ 拒绝 4/4。测试日志明确记录了引擎完整路径和 SHA-256。
- `card-batch-manifest-check.ps1 -Engine <上述引擎>`：退出 0；Cleave/Bludgeon 普通与升级效果 4/4。
- `live-state-v1-check.ps1 -Engine <上述引擎>`：退出 0；Shrug 非法 `special_data` 用例记录为 REJECTED，协议、状态和拒绝回归均通过。历史误报无法从旧截断日志还原其当时条件；本次实际引擎退出码为 3 且拒绝原因匹配预期，当前断言检查了两者，未发现引擎缺陷。
- `powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command all`：Windows PowerShell 5.1 退出 0，最终日志 `build/engine-mingw-unified-release/verification/unified-20261020/11-solvertest-all-final.log`。另以运行器使用的 JetBrains JBR 21 执行 `SearchQuality --selftest`，60 项通过、0 失败，退出 0，日志 `build/engine-mingw-unified-release/verification/unified-20261020/10-search-quality-selftest-jbr.log`。运行器加入 UTF-8 BOM 以避免中文源文件解析乱码，并在 `$ErrorActionPreference=Stop` 下显式捕获 javac 原生 stderr 警告而不误判失败。日志显示自检与 recording validation 通过；历史样本诊断仍报告 70 个字段差异、668/793 匹配（84%）及 48 个动作差异，这些是旧 recording 的既有诊断，不属于本批卡牌回归通过数。
- `git diff --check`：最终文档更新后退出 0，日志 `build/engine-mingw-unified-release/verification/unified-20261020/12-git-diff-check-final.log`。

以上全部为 L2 离线/合成 fixture 证据。未启动游戏，未进行 L3/L4 实机验收。完整运行日志在本地忽略的 `build/` 目录中，不纳入提交；清单引用日志路径方便在当前工作区复核。

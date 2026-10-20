# 离线自检与差分验证工具

这套工具是「模拟保真度」的验证底座：**不依赖游戏进程、不需要游戏 jar**，
直接编译 `engine` + `search` 两个纯模拟包，对数据表、怪物行动和整场搜索做断言，
并把实机记录重放回模拟器逐字段比对。

## 快速开始

```powershell
powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1
```

CI（`.github/workflows/ci.yml`）会在每次提交时跑同一套命令。

## 命令

| 命令 | 作用 |
| --- | --- |
| `check` | 卡牌数据表断言：75 张全登记、关键效果标记、近似牌数量收敛、打击 / 防御数值 |
| `pattern` | 怪物行动模型断言：第一幕全怪物、具体序列（吟唱→黑暗打击、光束/射线交替、睡三回合）、仪式递增、狂怒惩罚 |
| `search` | 整场搜索断言：能给出路线、预测可击败、承伤范围、两次搜索完全一致（可复现） |
| `diff <文件>` | 读取实机记录 JSONL，重放每条动作并逐字段比对 |
| `summary` | 列出所有建模怪物“空过 8 回合”的累计掉血，便于人工核对数值 |
| `gamedata <cards.json>` | 用游戏自带卡牌文本核对「打出即消耗」等标记 |
| `all` | 前三条 + fixtures 里的差分重放 |

## 搜索质量基准（漏优率）

`Oracle.java` + `SearchQuality.java` 用来客观回答「内置 Beam Search 有没有错过更优的合法路线」。
`Oracle` 与 `CombatSearch` 共用 `CombatEngine` 结算和 `evaluate` 打分，只把搜索方式换成
回合内穷举 + 跨回合记忆化，因此参照值是同模型、同目标、同回合预算下的上界，也是可达值。

```powershell
# 编译（与自检同一条命令，新增工具会被自动纳入）
powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command search
# 先证明参照求解器自身可信（5 条断言），退出码 0 才可读漏优数字
java -cp build\solvertest SearchQuality --selftest
# 12 场景两个层级的漏优率与截断归因（约 5-6 分钟；--verbose 附路线与耗时）
# 预算一律取自 SolverSettings.applyTo（档位 0/1/2/3 = 低/中/高/极高），基准不再自带参数
java -cp build\solvertest SearchQuality --verbose --out build\p0-search-quality.txt
# 宽度网格：在 2 回合预算下扫描 (withinTurnWidth, beamWidth)，标出与参照同分的最小宽度组合
java -cp build\solvertest SearchQuality --sweep
# 深度成本 + 逐帧单步耗时（含 BIG 真实规模场景）：调宽前必看，因为它才是卡顿依据
java -cp build\solvertest SearchQuality --wide
```

口径与限制：漏优判定按 beam **实际展开深度 d** 取参照 C(d)，避免把「预算没用完」当成搜索缺陷；
参照触顶时结果只是下界（标 CAPPED），其「未漏优」不构成最优证明。本基准度量的是模拟器模型内
的质量，不等于现实全局最优：模拟器 RNG 非真实 RNG，需要玩家选择的牌由引擎启发式代选（这类牌
已从题库排除）。退出码 0=无漏优、1=参照自检失败（数字作废）、2=存在漏优。

## 实时协议离线互操作

该专项需要本机游戏 JAR 作为编译期 API，但不会启动游戏。显式传入游戏目录、当前 Release 引擎和 Java 工具；脚本不回退到固定宿主机安装路径：

```powershell
powershell -ExecutionPolicy Bypass -File tools/solvertest/live-state-v1-check.ps1 `
  -GameDir '<STS1 game directory>' `
  -Engine 'build/engine-mingw-unified-release/combatsolver1-engine.exe' `
  -Javac (Join-Path $env:JAVA_HOME 'bin\javac.exe') `
  -Java (Join-Path $env:JAVA_HOME 'bin\java.exe')
```

## 实机记录从哪里来

游戏内每次出牌与回合切换，`runtime/CombatRecorder` 会把
「出牌前状态 + 这张牌 + 出牌后实际状态」按 JSONL 追加写到
`~/.combatsolver1/recordings/combat-<时间戳>.jsonl`。

把文件路径交给本工具即可：

```powershell
powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command diff `
  -RecordFile "$env:USERPROFILE\.combatsolver1\recordings\combat-20261016-120000.jsonl"
```

输出形如：

```text
== 差分重放：... ==
第 2 行 [card] 出牌 Strike_R：一致
第 3 行 [card] 出牌 Bash：不一致
    怪物 1：实机 JawWorm:30:0:2 / 模拟 JawWorm:30:6:2
动作 3 条，字段匹配 23/24，存在差异的动作 1 条
```

## 比对口径

每条动作比较 10 个全局字段 + 每个怪物 1 个字段：

`生命 / 格挡 / 能量 / 玩家效果签名（减益、能力层数）/ 手牌（多集）/ 三个牌堆数量 / 抽牌堆牌身份 / 弃牌堆牌身份 / 每个怪物的血量:格挡:易伤:虚弱:力量:防御能力(:死亡)`

差分只覆盖记录中能捕获并且模拟器有对应字段的状态；未记录字段会按零值兼容旧 JSONL，因此老记录不能验证新字段。牌堆身份采用排序后的多重集合比较，**仍不验证牌堆顺序或游戏真实 RNG**。`root` 和 `pre`/`post` 都记录当前可捕获的效果值。

实机记录里没有目标下标时，重放器会对每个存活敌人各试一次，取与会话结果最吻合的目标，
因此“打错目标”不会被误报成模拟错误。

## 目录

```text
tools/solvertest/
├── run.ps1                 一键编译 + 运行
├── Json.java               极简 JSON 解析
├── States.java             记录状态 ↔ SimState 互转、可比较快照
├── DiffRunner.java         差分重放
├── Checks.java             数据表 / 怪物 / 搜索断言
├── Solvertest.java         命令行入口
└── fixtures/recordings/    样例记录（合成，用于 CI 回归）
```

## 相关工具

`tools/inspect/` 下三个工具都从游戏 `desktop-1.0.jar` 读数据，只在开发机上运行：

| 工具 | 用途 |
| --- | --- |
| `ExtractMonsterFacts` | 读出每个怪物类的真实 `ID` 常量与静态数值字段（血量、招式伤害等） |
| `DumpBytecode` | 打印某个方法的字节码（用来确认游戏内部实现，例如牌堆顶取哪一端） |
| `TraceMethod` | 按顺序打印某方法的常量与调用轨迹（含 `tableswitch` / `lookupswitch` 解码），用来还原 `getMove` / `takeTurn` 里的出招分支 |

```powershell
javac -encoding UTF-8 -d build/inspect tools/inspect/ExtractMonsterFacts.java tools/inspect/DumpBytecode.java tools/inspect/TraceMethod.java
java -cp build/inspect ExtractMonsterFacts "<游戏>/desktop-1.0.jar"
java -cp build/inspect TraceMethod "<游戏>/desktop-1.0.jar" com/megacrit/cardcrawl/monsters/city/Chosen getMove
```

卡牌文本对账用的 `cards.json` 可直接从游戏 jar 里取出（`localization/zhs/cards.json`）。

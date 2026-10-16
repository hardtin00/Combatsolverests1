# Combat Solver 1

面向《杀戮尖塔》（Slay the Spire 1）的战斗路线求解器 Mod，功能对齐
[Torch1230/CombatSolver](https://github.com/Torch1230/CombatSolver)（该项目的目标平台是
《杀戮尖塔 2》，C# / .NET / Godot 技术栈）。

一代是 Java + ModTheSpire + BaseMod 的完全不同的 Mod 体系，因此本项目是**功能对等的全新实现**，
不是对 CombatSolver 的代码移植。当前处于 **v0 骨架** 阶段。

## 架构（对标 CombatSolver）

与原项目一致的“预测优先”运行链：

1. 回合开始时，在主线程捕获一份稳定的战斗根状态（只读，不改动真实战斗）。
2. 在独立的分支状态上做**整场战斗**的 Beam Search：推演“本回合怎么打 → 结束回合 →
   怪物行动 → 抽新牌”，逐回合展开，直到分出胜负或用完预算。
3. 搜索结束后产出一条完整的只读路线（预测）；面板展示逐回合出牌与预计剩余生命。
4. **默认只预测不执行**：只有你按键时，才用游戏原生入口把路线中的“当前回合”部署到实机。

代码结构：

```text
engine/    纯模拟：可 fork 的战斗状态、卡牌效果模板、结算规则（不引用游戏对象）
search/    整场 Beam Search 与只读路线（Plan）
runtime/   实机桥接：捕获战斗根、部署当前回合、控制器
ui/        只读路线面板
```

## 已实现

- 整场战斗的预测搜索（Beam Search），输出逐回合路线、预计承伤、是否可击败。
- 卡牌效果建模：铁甲 75 张全部登记，伤害 / 格挡 / AOE / 易伤 / 虚弱 / 力量 / 过牌 / 能量 /
  消耗联动 / 牌库操作（复制、取回、生成、洗入状态牌）。选牌类卡牌（双持 / 头槌 / 掘墓 /
  燃烧契约 / 军备）按价值选最适合的目标，只有 2 张仍标近似。
- 怪物行动：**第一幕全部遭遇怪物（含 3 只精英与 3 个首领）+ 第二幕常见怪**逐招式建模 ——
  招式伤害与血量取自游戏本体数据，选择规则（概率权重、每招连续使用上限、起手限制）参考
  Bestiary（MIT）的 AI 数据并经字节码交叉核对；包含仪式（邪教徒）、睡眠与被打醒（拉格威林）、
  狂怒（地精大块头）、蓄力（地精巫师）、模式切换（守护者）、死亡触发（真菌兽孢子云）、
  飞行与击落（鸟）、双人组合（百夫长 + 秘术师）。其余怪物使用节奏表近似并在实战中自我校正。
  第三方来源与署名见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 牌堆语义与游戏一致：抽牌从牌堆顶取、洗牌整堆 Fisher–Yates、实机捕捉到的牌堆顺序直接沿用。
- 玩家侧减益真实生效：虚弱（伤害 −25%）、残废（格挡 −25%）、缠身（不能打攻击牌）。
- 遗物触发：钢笔尖 / 红骷髅 / 冰淇淋 / 水银沙漏 / 山铜护符 / 羊角扣 / 船长轮盘 / 青铜鳞片 /
  手里剑 / 苦无 / 双节棍 / 开信刀 / 卡戎之灰。
- **差分验证脚手架**：实机每次出牌与回合切换都会写结构化记录（JSONL），离线工具
  （`tools/solvertest`）把记录重放回模拟器并逐字段比对；CI 每次提交都会跑数据表、
  怪物行动、搜索与差分重放四组断言。
- 会识别击杀并把攻击分配到合适目标，避免溢出伤害。
- 右上角面板展示完整预测路线（逐回合出牌与预计剩余生命）。

### 可视化窗口

战斗中出现一个可拖动窗口（拖标题栏移动），展示整场预测路线，并提供三个按钮：

- **重新计算**：重新预测本场战斗。
- **执行本回合**：把预测路线的当前回合的牌打完，**不替你结束回合**（由你自己决定何时结束）。
- **全自动:开/关**：连续执行，每回合打完牌后**自动结束回合**并继续。

窗口中还有三行设置（点击该行切换，设置会保存到用户目录 `~/.combatsolver1/settings.properties`）：

| 设置 | 取值 |
| --- | --- |
| 搜索预算 | 低 / 中 / 高 / 极高 / 自定义（映射到 Beam 宽度、回合上限与节点上限） |
| 面板透明度 | 55% / 75% / 100% |
| 主题 | 深色 / 浅色 |

### 快捷键（与按钮等效）

| 按键 | 作用 |
| --- | --- |
| 不按键 | 仅查看路线：只预测并展示，不执行 |
| `G` | 执行本回合：只把牌打完，不结束回合 |
| `H` | 全自动：开关连续执行，打完牌后自动结束回合 |
| `J` | 停止 / 暂停：立即停止当前搜索，并暂停本场后续自动预测与执行 |
| `R` | 重新计算：解除暂停并重新预测 |

搜索是增量推进的，不会卡帧，也可以在搜索过程中随时用 `J` 中断。

### 跨回合搜索与路线复用

- 搜索会一直推演到分出胜负或用完预算，包含抽牌、洗牌、敌人行动与后续资源。
- 评估顺序为“生存 > 确认胜利 > 整场生命损失”。
- 每个回合会先核对实机状态与预测是否一致：一致则**复用既有路线**，不一致才重新搜索。

## 明确尚未实现

- 第二幕之后的怪物仍是“节奏近似 + 实战自我校正”，尚未逐招式建模；守护者 / 六火亡魂 /
  史莱姆首领的细节（防御模式回合数、分裂、招式权重）也是近似值。
- 灼热打击的成长（只来自反复升级）与少数能力牌的升级幅度仍按通用数值近似。
- 遗物以显式登记的高频项为准，剩余遗物中有一部分已由实机数值隐式包含；药水共 11 种可评估。
- 逐瓶药水策略、局外成长策略、全自动战斗、设置页、战前预测 API、问题反馈与在线统计。

详见 [ROADMAP.md](ROADMAP.md)。

## 差分验证与离线自检

```powershell
powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1            # 全部自检
powershell -ExecutionPolicy Bypass -File tools/solvertest/run.ps1 -Command diff `
  -RecordFile "$env:USERPROFILE\.combatsolver1\recordings\combat-20261016-120000.jsonl"
```

自检**不需要游戏本体**：只编译 `engine` 与 `search` 两个纯模拟包。CI 配置见
`.github/workflows/ci.yml`。实机记录落在 `~/.combatsolver1/recordings/`，
每条动作比对生命 / 格挡 / 能量 / 手牌 / 三个牌堆数量 / 每个怪物的血量格挡易伤。
详细说明见 [tools/solvertest/README.md](tools/solvertest/README.md)。

## 目录结构

```text
combatsolver1/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── .github/workflows/ci.yml      # 离线自检 CI
├── tools/solvertest/             # 离线自检 + 差分重放工具（纯 Java）
├── tools/inspect/                # 从游戏 jar 读取怪物 ID / 数值的核对工具
└── src/main/java/com/combatsolver1/
    ├── CombatSolver1Mod.java     # Mod 入口（@SpireInitializer）
    ├── engine/                   # 纯模拟：SimState / CombatEngine / CardLibrary /
    │                             #          MonsterAi / MonsterPatterns / PotionLibrary
    ├── search/                   # 整场 Beam Search 与只读路线（Plan）
    ├── runtime/                  # LiveCapture / PlanDeployer / CombatController /
    │                             # CombatRecorder（差分记录）/ EnemyModel
    ├── ui/                       # OverlayRenderer / PanelModel
    └── settings/                 # SolverSettings（预算 / 透明度 / 主题持久化）
```

## 构建

前置条件：

- JDK 8 或更高版本
- Gradle 7.x（或使用你的 IDE 自带的 Gradle）
- 《杀戮尖塔》安装目录，其中包含 `desktop-1.0.jar`、`ModTheSpire.jar` 与
  `mods/BaseMod.jar`

执行（把路径替换成你的实际安装目录）：

```powershell
gradlew jar -PstsDir="C:/Program Files (x86)/Steam/steamapps/common/SlayTheSpire"
```

或者设置环境变量 `STS_DIR` 后直接 `gradlew jar`。产物为
`build/libs/CombatSolver1.jar`。

## 安装与使用

1. 把 `CombatSolver1.jar` 放入游戏目录的 `mods/` 文件夹。
2. 用 ModTheSpire 启动游戏，勾选 `BaseMod` 与 `Combat Solver 1`。
3. 进入一场单人战斗，发牌结束后右上角即显示建议面板。
4. 按 `G` 执行当前建议路线；按 `H` 开启 / 关闭全自动。

## 许可

本项目与 CombatSolver、Random Foreseer 均基于 MIT License 精神开源；移植过程中如引入
他人代码会保留相应版权署名。

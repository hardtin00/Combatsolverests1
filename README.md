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
- 卡牌效果建模：伤害 / 格挡 / AOE / 易伤 / 虚弱 / 力量 / 过牌 / 能量（常见牌按数据表）。
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

- 怪物行动：当前回合按招式信息（单段伤害 × 段数）精确计算；后续回合按**行动循环表**判断
  “这一回合是否出手”（例如哨卫是攻击 / 塞牌交替、拉格威林是睡三回合后连打），
  未登记的怪物退回“这类怪实际每回合平均输出”的经验估计。
- 牌效覆盖：仅常见牌进入数据表，未知牌按类型保守推断或标记未建模。
- 遗物、药水、X 费、消耗等尚未接入；洗牌用确定性随机数采样。
- 跨回合 Beam Search、抽牌 / 洗牌 / 怪物 AI / RNG 分叉模拟。
- 逐瓶药水策略、局外成长策略、全自动战斗、设置页、战前预测 API、问题反馈与在线统计。

详见 [ROADMAP.md](ROADMAP.md)。

## 目录结构

```text
combatsolver1/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── src/main/java/com/combatsolver1/
    ├── CombatSolver1Mod.java     # Mod 入口（@SpireInitializer）
    ├── CombatController.java     # 战斗状态机
    ├── CombatLog.java
    ├── AutoPlayer.java           # 执行建议路线
    ├── OverlayRenderer.java      # 覆盖层渲染
    └── solver/
        ├── CombatSnapshot.java   # 战斗状态快照
        ├── Route.java            # 路线数据结构
        └── TurnSolver.java       # 本回合求解器（v0 启发式）
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

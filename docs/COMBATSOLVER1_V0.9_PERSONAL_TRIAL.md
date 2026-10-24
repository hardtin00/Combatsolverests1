# CombatSolver1 V0.9 — 个人试用基线

本交付冻结于客户端 CombatSolver1 SHA-256 `9FC8217D42AB93797F5940576FD8037E71F3310F189CA1A3527DD2B2D080D30A`。仓库中的 JAR 是原样归档的既有 9FC1 候选，没有重新构建。V0.9 是试用交付标签，不代表 JAR 内部版本号。

- 客户端 JAR：[`artifacts/CombatSolver1-V0.9/CombatSolver1.jar`](../artifacts/CombatSolver1-V0.9/CombatSolver1.jar)
- 本机完整运行包：`%LOCALAPPDATA%\CS1-AIPlay-Test\deliveries\CombatSolver1-V0.9-20261025`
- 完整组件与 MTS profile 的哈希：[V0.9_LOCAL_PACKAGE_SHA256SUMS.txt](V0.9_LOCAL_PACKAGE_SHA256SUMS.txt)
- 所有运行依赖和 profile 副本保留在本机完整运行包中；此仓库只归档 CombatSolver1 自有 JAR，不包含 MTS、BattleAiMod 或其他第三方 JAR。

## 安装与启动

只在隔离目录 `%LOCALAPPDATA%\CS1-AIPlay-Test\game-candidate-20261024` 中使用，不覆盖 Steam 正式游戏。使用隔离 MTS 配置 `%LOCALAPPDATA%\CombatSolver1\isolated-candidate-20261024\LocalAppData\ModTheSpire`：客户端选择 `CS1-Candidate-20261024` profile，服务端选择 `Server` profile。

完整的已记录 PowerShell 启动命令见本机完整运行包 `README.md`。先启动 Server profile，确认 `BATTLEAI_SERVER_READY port=5125 testMode=false` 后，再启动客户端 profile。客户端 profile 加载 CombatSolver1；Server profile 加载 BattleAiMod 与 ServerStatePatch。

战斗中点击 Overlay 的 **重新计算**，等待结果后查看路线、目标和当前状态。路线可能只有部分计划；“未找到完整胜利”不能当作胜利路线。仅当第一步是普通 CARD、当前状态/目标匹配且单步预检通过时，点击 **单步执行** 授权一张牌。等待稳定边界与后置校验成功后，旧路线失效；再次执行前重新计算。

## 已验证能力

- **L3：**隔离 STS1 中加载、战斗搜索和 Overlay 展示；Strike 卡牌身份预检。
- **L4：**一次真实 Cleave CARD 单步；严格后置状态 `MATCH`，RNG 校验通过；之后进行了只读重新搜索。
- 原始验收记录保存在 `%LOCALAPPDATA%\CS1-AIPlay-Test\backups\strike-minimal-778b-20261025-1000\` 和 `%LOCALAPPDATA%\CS1-AIPlay-Test\backups\strike-identity-fix-20261024-2358\`。仓库文档未把历史 Cultist 搜索胜利说成 9FC1 实机完整胜利。

## 不支持或未验证

整场自动执行、END 安全执行、药水执行、特殊选牌/选择 UI，以及其他未覆盖动作。不要开启“全自动”或“本回合”整回合执行；协议解析或搜索模拟支持某动作，不代表实机执行已验证。

V1.0 完整战斗执行和 END 安全验收未通过，已停止；不属于本 V0.9 试用范围。

## 源码可重建缺口

指定源码工作树缺少 `CombatSolver1-ServerStatePatch.jar` 对应源码及独立确定性构建步骤；源树也未冻结成可重建 9FC1 的提交。此仓库只保留已验证 JAR，不宣称它可由当前源码重复构建。该缺口留待未来独立处理。

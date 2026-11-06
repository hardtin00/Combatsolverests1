# W4 交付文档「药水 off」与引擎现状冲突的核验（待用户决策，L2 离线）

> W4-a 曾登记：「交付目录 README/KNOWN_LIMITS 仍写药水不纳入搜索，与引擎现状矛盾，待同步」。
> 本轮**先查清事实再决定**，结论是：不能擅自改文档，需要用户决策。本文件只记录证据，不改交付物。

## 核验到的三方事实（均有命令/路径）

1. **交付/游戏里的引擎 exe 是「新引擎」**：
   - 游戏 `cs1-engine/s2b0_search_3d.exe` 与仓库 `artifacts/CombatSolver1-1.0.0/s2b0_search_3d.exe`
     指纹相同 `a25f9afb27e604a08f641bbfbe4c2ea8e4006743b58f71c05b3dd89331eca8ac`。
   - 字节级搜索（Python 读 exe）命中 `potion_actions_mode`、`on`、`off`、`USE_POTION`、`plan_potions`
     → 与 `_build/cs1_search.cpp:775` 的 `out["potion_actions_mode"] = gLoadedPotions > 0 ? "on" : "off"` 一致。
2. **AUDIT 已有 L4 级实测**：`combatsolver1-integration/delivery/dev-notes/AUDIT_20261104.md:2425-2435`
   记录「用真实抓到的状态跑 cs1_search.exe → `potion_actions_mode: on`、路线前两步 POTION、
   terminal PLAYER_VICTORY」，并附实机截图确认面板正常。
   → **引擎侧药水规划能力存在且曾验证过**。
3. **但交付文档是「有意改回 off」的，不是遗漏**：
   - `_build/fix_potion_docs.py` 是一份历史补丁脚本，把 README/使用说明/KNOWN_LIMITS 从
     「21 瓶确定性药水已完成」**改成**「不纳入搜索、potion_actions_mode=off、F6 无可见效果」。
   - KNOWN_LIMITS 第 4/8 条明确写：「mod 端 `PotionExecPolicy`、`PotionVerifySelfTest`、F6 方案切换
     都还在，等以后把药水接进引擎再启用」「这一段执行路径当前是闲置的」。
   → 说明当时是**引擎能规划、但 mod 执行路径没接回**，所以把对外文档降级为「不纳入搜索」。

## 结论：这是「引擎能力 vs mod 执行路径」不一致，不是文档笔误

- 若只把 README 改成「on、已纳入」，会**虚报**：玩家在游戏里按 F6 / 看到路线时，
  mod 端执行路径仍闲置（KNOWN_LIMITS 第 4/8 条），喝药步骤未必能真正执行。
- 按 AGENTS.md 1.1（发现其他缺陷记录待办、不擅自改）、3.C（禁止虚报）、
  「改用户可见交付物需授权」，本轮**不改交付文档**。

## 需要用户决策（三选一）

1. **保持 off 文档不动**：承认当前 mod 执行路径不支持喝药步骤，引擎的 `on` 能力暂不对外。
   （最保守，与现状一致；W4 仍为「真机含药路线全链证据未做」。）
2. **授权把 mod 端药水执行路径接回**（`PotionExecPolicy` / F6 / `PlanDeployer` 的 POTION 步骤），
   真机验证一条含药路线全链，再同步把 README/KNOWN_LIMITS 改回 on —— 这是任务书 W4 的正解，
   但触及运行路径 + 需真机 L4，工作量大，须单独任务授权。
3. **只做文档精确化**（把「引擎回报 off」改为「引擎能规划但 mod 执行路径未接回，故当前不推荐喝药」）：
   不虚报、也不误导；改动仅交付目录文档，需你确认是否允许改交付物。

## 本轮未做

- 未改 `combatsolver1-integration/delivery/` 下任何文件、未重打包、未推远程、未改游戏 mods、未碰存档。
- 未做新的真机 L4 验证（引用的是 AUDIT 已有的历史 L4 记录，不冒充本轮实测）。
- 证据等级：**L2 离线**（exe 字节核验 + 历史日志核对），非本轮实机。

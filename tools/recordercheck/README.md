# Root replay diff

对已归档 battle bundle 使用同一 `engine-in.save` 连续调用 C++ `replay_trace`，输出 JSON 摘要和一行 `SUMMARY`：

```powershell
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 `
  -BundlePath C:\path\to\battle-bundle `
  -EnginePath build\engine\combatsolver1-engine.exe
```

工具检查 manifest 声明的三份文件是否存在且 SHA-256 匹配，保留 `journalDirty`，逐步探测最长未被 `REPLAY_INVALID` 拒绝的动作前缀，并只对可见的稳定 post 比较 HP、最大 HP、格挡、能量、回合映射、牌堆数量。`game turn = engine turn + 1` 是当前引擎初始化的映射。缺 post、脏 journal、非法动作、终局无稳定 post 和未输出字段都保留为限制，不会自动修正日志。`-SelfTest` 运行不需要游戏文件的 10 项边界回归。

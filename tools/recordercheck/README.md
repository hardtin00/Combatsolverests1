# 根状态回放差分

检查一份历史 battle bundle，不会修改归档内容：

```powershell
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 `
  -BundlePath "$env:USERPROFILE\.combatsolver1\recordings\battle-..." `
  -EnginePath build\engine\combatsolver1-engine.exe
```

命令先输出机器可读 JSON，再输出一行简要 `SUMMARY`。报告分别呈现文件与哈希、JSONL/journal 动作对齐及状态链、C++ 回放协议、稳定状态差分和最终证据等级。

工具检查 `manifest.json`、其中声明的三份文件与 SHA-256、`actionCount`、JSONL root/动作顺序、journal 动作类别、卡牌手牌下标和目标身份，以及终局未配对动作。当前 JSONL 没有足够字段核验药水槽位与目标，因此药水虽会被识别，但会截断严格动作对齐。文件缺失、哈希不符、JSON 解析失败或缺 root 时，不会启动引擎比较。动作不一致会把可比较动作前缀截断在第一处分歧。`journalDirty=true`、manifest 未封存、状态链断裂或终局动作无 post 时，结果只能是 `diagnostic_only`；工具不会清除 dirty，也不会改写 bundle。

工具为每个 journal 前缀从同一 `engine-in.save` 独立启动一次 `replay_trace`，并分别保存结果。只有退出码为 0、协议明确返回 `REPLAY_OK n=N`，且输出完整 `TRACE0..TRACEN` 时，前缀 N 才合法。`REPLAY_INVALID n=N` 会报告首次拒绝动作，即使引擎使用非零拒绝退出码。超时和其他非零退出属于执行错误；缺协议、协议标记冲突、格式错误或 TRACE 不完整属于协议错误。C++ CLI 中 `TRACE0` 是初始化根状态，`TRACEi` 是动作 i 执行后的状态；原生 post i 只与已单独确认的前缀 i 中的 `TRACEi` 比较。

可比较字段包括根/动作后的玩家生命、最大生命、格挡、能量、回合映射（`game turn = engine turn + 1`），以及抽牌堆、弃牌堆、消耗堆数量。回合边界观察将新回合第一个原生动作前状态，与引擎完成对应结束回合后的首个确认 TRACE 对齐。卡牌顺序、怪物状态、遗物、完整 powers 和 RNG 状态仍不可比较，因为 `replay_trace` 没有输出完整对应状态。如果可见字段一致但原生状态还包含未输出字段，状态标为 `partial_match_with_unavailable_fields`。瞬时状态、缺少稳定 post 或动作对应不可信时标为 `not_comparable` 或受限边界观察。journal 中只有编码、没有稳定 post 的终局动作标记为 `terminal_not_comparable`；manifest 胜负只单独报告，不代表终局全字段一致。

不需要游戏安装即可运行生产函数回归：

```powershell
powershell -ExecutionPolicy Bypass -File tools/recordercheck/run-root-replay-diff.ps1 -SelfTest
```

自测使用合成 bundle 和注入的引擎响应，直接调用生产门控、解析和比较函数，覆盖哈希门控、dirty、缺失/重复/错位动作、手牌与目标身份、终局及中间 post、成功/拒绝协议、超时/崩溃/格式错误、TRACE 与动作关联、回合映射、阶段门控、未知字段、首个 HP 差异，以及后续失败前缀不污染此前成功比较。

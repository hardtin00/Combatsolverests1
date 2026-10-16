# 第三方来源与署名

本项目的战斗模拟包含来自以下开源项目的数据与实现参考。按各自许可证要求保留署名。

## Bestiary

- 仓库：<https://github.com/casey-c/Bestiary>
- 作者：Casey C
- 许可证：MIT License（Copyright (c) 2021 Casey C）

**使用范围**：`src/main/java/com/combatsolver1/engine/MonsterAi.java` 中怪物招式的
选择规则（概率权重、每招连续使用上限、起手限制、招式集合与各自效果）依据该项目的
`src/main/resources/Bestiary/monsters.json` 整理；该文件覆盖全部原版怪物并区分进阶等级。

**核对方式**：数值与 ID 另行用游戏本体 `desktop-1.0.jar` 的类文件核对
（工具见 `tools/inspect/ExtractMonsterFacts.java`），部分怪物的分支结构用
`tools/inspect/TraceMethod.java` 读 `getMove` / `takeTurn` 字节码交叉验证
（例如鸟的飞行分支、被选者的起手顺序）。

MIT License 全文见 <https://github.com/casey-c/Bestiary/blob/master/LICENSE>。

## Bestiary 的许可原文

```
MIT License

Copyright (c) 2021 Casey C

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

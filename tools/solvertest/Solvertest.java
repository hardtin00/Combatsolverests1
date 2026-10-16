import java.io.File;
import java.util.List;

/**
 * 离线自检入口（不依赖游戏进程，也不需要游戏 jar）。
 *
 * 用法：
 *   java -cp <工具输出目录> Solvertest check            卡牌数据表断言
 *   java -cp <工具输出目录> Solvertest pattern          怪物行动模型断言
 *   java -cp <工具输出目录> Solvertest search           整场搜索断言
 *   java -cp <工具输出目录> Solvertest diff <记录.jsonl>  实机记录差分重放
 *   java -cp <工具输出目录> Solvertest summary          列出建模怪物的模拟输出
 *   java -cp <工具输出目录> Solvertest all [fixtures]   全部执行（默认路径见下）
 */
public final class Solvertest {

    private Solvertest() {
    }

    private static final String DEFAULT_RECORDING =
            "tools/solvertest/fixtures/recordings/sample.jsonl";

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "all";

        if ("diff".equals(command)) {
            String path = args.length > 1 ? args[1] : DEFAULT_RECORDING;
            int code = runDiff(path);
            System.exit(code);
        }
        if ("summary".equals(command)) {
            printSummary();
            System.exit(0);
        }

        boolean all = "all".equals(command);
        if ("check".equals(command) || all) {
            Checks.cardTable();
        }
        if ("pattern".equals(command) || all) {
            Checks.monsterAi();
        }
        if ("search".equals(command) || all) {
            Checks.search();
        }

        for (String line : Checks.log()) {
            System.out.println(line);
        }
        System.out.println();
        System.out.println(Checks.failures() == 0
                ? "全部自检通过。"
                : "自检失败 " + Checks.failures() + " 项。");

        int code = Checks.failures() == 0 ? 0 : 1;
        if (all) {
            File fixture = new File(args.length > 1 ? args[1] : DEFAULT_RECORDING);
            if (fixture.isFile()) {
                System.out.println();
                int diffCode = runDiff(fixture.getPath());
                if (diffCode != 0) {
                    code = diffCode;
                }
            }
        }
        System.exit(code);
    }

    private static int runDiff(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) {
            System.out.println("找不到记录文件：" + path);
            return 2;
        }
        DiffRunner.Result result = DiffRunner.run(path);
        System.out.println("== 差分重放：" + path + " ==");
        for (String line : result.report) {
            System.out.println(line);
        }
        System.out.println("动作 " + result.actions + " 条，字段匹配 "
                + result.matchedFields + "/" + result.totalFields
                + "，存在差异的动作 " + result.badActions + " 条");
        if (result.actions == 0) {
            System.out.println("提示：记录里没有可重放的动作。");
            return 2;
        }
        return result.badActions == 0 ? 0 : 1;
    }

    private static void printSummary() {
        System.out.println("== 各建模怪物：空过 8 回合的累计掉血（无格挡、玩家不出牌）==");
        List<String> ids = Checks.modeledMonsterIds();
        for (String id : ids) {
            System.out.println("  " + Checks.simulateSummary(id, 8));
        }
    }
}

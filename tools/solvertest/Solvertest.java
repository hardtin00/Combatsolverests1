import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 离线自检入口（不依赖游戏进程，也不需要游戏 jar）。
 *
 * 用法：
 *   java -cp <工具输出目录> Solvertest check            卡牌数据表断言
 *   java -cp <工具输出目录> Solvertest pattern          怪物行动模型断言
 *   java -cp <工具输出目录> Solvertest search           整场搜索断言
 *   java -cp <工具输出目录> Solvertest diff <记录.jsonl>  实机记录差分重放
 *   java -cp <工具输出目录> Solvertest summary          列出建模怪物的模拟输出
 *   java -cp <工具输出目录> Solvertest gamedata <游戏卡牌文本.json>
 *                                                     用游戏自带文本核对卡牌标记
 *   java -cp <工具输出目录> Solvertest all [fixtures]   全部执行（默认路径见下）
 */
public final class Solvertest {

    private Solvertest() {
    }

    private static final String DEFAULT_RECORDING =
            "tools/solvertest/fixtures/recordings/sample.jsonl";

    /** 真实对局语料目录：如实报告差异用于长期跟踪，但不作为“必须全绿”的门禁。 */
    private static final String CORPUS_DIR = "tools/solvertest/fixtures/corpus";

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
        if ("gamedata".equals(command)) {
            if (args.length < 2) {
                System.out.println("用法：Solvertest gamedata <游戏卡牌文本.json>");
                System.exit(2);
            }
            runGameData(args[1]);
            System.exit(Checks.failures() == 0 ? 0 : 1);
        }

        boolean all = "all".equals(command);
        if ("check".equals(command) || all) {
            Checks.cardTable();
        }
        if ("pattern".equals(command) || all) {
            Checks.monsterAi();
        }
        if ("pattern".equals(command) || all) {
            Checks.monsterPowers();
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
            RecordValidationChecks.run();
            File fixture = new File(args.length > 1 ? args[1] : DEFAULT_RECORDING);
            // 重放 fixtures 目录里的全部记录：把真实对局记录（脱敏后）直接放进去即可纳入回归
            File dir = fixture.getParentFile();
            File[] recordings = dir == null ? null : dir.listFiles();
            if (recordings != null && recordings.length > 0) {
                for (File recording : recordings) {
                    if (recording.isFile() && recording.getName().endsWith(".jsonl")) {
                        System.out.println();
                        int diffCode = runDiff(recording.getPath());
                        if (diffCode != 0) {
                            code = diffCode;
                        }
                    }
                }
            } else if (fixture.isFile()) {
                System.out.println();
                int diffCode = runDiff(fixture.getPath());
                if (diffCode != 0) {
                    code = diffCode;
                }
            }
            runCorpus();
        }
        System.exit(code);
    }

    /**
     * 真实对局语料的“体检”：逐份重放并汇总字段匹配率。
     *
     * 与 fixtures/recordings 的区别是**不判失败**——真实战斗里必然存在已知的精度差距
     * （未复制游戏真实 RNG、部分近似机制），把它们当门禁只会让 CI 长期变红、失去意义。
     * 这里要的是**可追踪的数字**：改动前后匹配率是升了还是降了。
     */
    private static void runCorpus() throws Exception {
        File dir = new File(CORPUS_DIR);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        System.out.println();
        System.out.println("== 真实对局语料体检（不判失败，只看数字）==");
        int actions = 0;
        int matched = 0;
        int total = 0;
        int bad = 0;
        int dataIssues = 0;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".jsonl")) {
                continue;
            }
            DiffRunner.Result result = DiffRunner.run(file.getPath());
            actions += result.actions;
            matched += result.matchedFields;
            total += result.totalFields;
            bad += result.badActions;
            dataIssues += result.dataIssues;
            System.out.println("  " + file.getName() + "：动作 " + result.actions
                    + "，字段 " + result.matchedFields + "/" + result.totalFields
                    + "，有差异 " + result.badActions
                    + (result.dataIssues > 0 ? "，记录自相矛盾 " + result.dataIssues : ""));
        }
        int rate = total == 0 ? 0 : (int) Math.round(matched * 100.0 / total);
        System.out.println("  合计：动作 " + actions + "，字段匹配 " + matched + "/" + total
                + "（" + rate + "%），有差异 " + bad
                + (dataIssues > 0 ? "，记录自相矛盾 " + dataIssues : ""));
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
        System.out.println("记录问题 " + result.dataIssues + "，跳过 " + result.skipped);
        if (result.actions == 0) {
            System.out.println("提示：记录里没有可重放的动作。");
            return 2;
        }
        return result.badActions == 0 && result.dataIssues == 0 && result.skipped == 0 ? 0 : 1;
    }

    private static void printSummary() {
        System.out.println("== 各建模怪物：空过 8 回合的累计掉血（无格挡、玩家不出牌）==");
        List<String> ids = Checks.modeledMonsterIds();
        for (String id : ids) {
            System.out.println("  " + Checks.simulateSummary(id, 8));
        }
    }

    /** 用游戏自带卡牌文本对账；文本可从 desktop-1.0.jar 的 localization 目录下 cards.json 取出。 */
    private static void runGameData(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        Checks.gameData((Map<String, Object>) parsed);
        for (String line : Checks.log()) {
            System.out.println(line);
        }
        System.out.println();
        System.out.println(Checks.failures() == 0 ? "对账通过。" : "对账失败 " + Checks.failures() + " 项。");
    }
}

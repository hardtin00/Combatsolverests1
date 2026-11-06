import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * W5-b 离线自检：29 个历史真机局面的引擎审计夹具。
 *
 * <p>来源：`_build/audit_out/live-battle-state-v1.seq1..29`（2026-11-04 对 29 个历史真机局面
 * 跑外部引擎的离线回归），摘要见 AUDIT_20261104.md §2：
 * `states=29 flagged=0 total_steps=255 phantom=0`、23 胜 6 负。
 * 本自检把那 29 份 `SEARCH_RESULT.audit.json` 作为**只读夹具**纳入主仓库，逐项复核：
 * 全部 SEARCHED、路线动作在 tree walk 中合法、独立重放一致、无 hand_slot=-1 幽灵步骤、
 * 无 route_truncated_not_admitted、总步数 255。
 *
 * <p>L2 离线（引擎审计输出），不是新的实机 L4 记录，不声称实机精度。
 */
public final class Historical29Check {

    private Historical29Check() { }

    private static int pass;
    private static int fail;

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    private static String str(String json, String key) {
        // 极小 JSON 取值：只针对 "key": "value" / "key": true / "key": 123
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return null;
        int c = json.indexOf(':', at);
        if (c < 0) return null;
        int s = c + 1;
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return null;
        if (json.charAt(s) == '"') {
            int e = json.indexOf('"', s + 1);
            return e < 0 ? null : json.substring(s + 1, e);
        }
        int e = s;
        while (e < json.length() && !",}]".contains(String.valueOf(json.charAt(e)))
                && !Character.isWhitespace(json.charAt(e))) e++;
        return json.substring(s, e);
    }

    private static int countObjects(String array) {
        // 统计 candidate_route 数组里的对象数（按 "action_bits" 或 "kind" 出现次数近似，
        // 用 manifest 里的 steps 字段为准，这里只做存在性检查）
        return 0;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("tools/solvertest/fixtures/historical_29");
        check(dir.isDirectory(), "夹具目录存在", dir.getPath());

        List<File> files = new ArrayList<File>();
        for (int i = 1; i <= 29; i++) {
            File f = new File(dir, String.format("seq%02d_SEARCH_RESULT.json", i));
            if (f.isFile()) files.add(f);
        }
        check(files.size() == 29, "29 份历史局面结果齐全", "count=" + files.size());

        int searched = 0, allValid = 0, replayOk = 0, ghost = 0, truncated = 0;
        int totalSteps = 0, victory = 0, loss = 0;
        for (File f : files) {
            String j = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if ("SEARCHED".equals(str(j, "status"))) searched++;
            if ("true".equals(str(j, "candidate_route_all_actions_valid_in_tree_walk"))) allValid++;
            if ("true".equals(str(j, "independent_replay_matches_tree_walk"))) replayOk++;
            String steps = str(j, "candidate_route_step_count");
            if (steps != null) {
                try { totalSteps += Integer.parseInt(steps); } catch (NumberFormatException ignored) { }
            }
            String outcome = str(j, "candidate_route_terminal_outcome");
            if ("PLAYER_VICTORY".equals(outcome)) victory++;
            if ("PLAYER_LOSS".equals(outcome)) loss++;
            // 幽灵步骤：route 里出现 hand_slot=-1
            if (j.contains("\"hand_slot\":-1") || j.contains("\"hand_slot\": -1")) ghost++;
            // route_truncated_not_admitted 应为 []（允许空格）；任何非空数组都算截断
            int ti = j.indexOf("\"route_truncated_not_admitted\"");
            if (ti >= 0) {
                int lb = j.indexOf('[', ti);
                int rb = lb < 0 ? -1 : j.indexOf(']', lb);
                String inside = lb >= 0 && rb > lb ? j.substring(lb + 1, rb).trim() : "?";
                if (!inside.isEmpty()) truncated++;
            }
        }
        check(searched == 29, "29/29 status=SEARCHED", "searched=" + searched);
        check(allValid == 29, "29/29 路线动作 tree-walk 合法", "valid=" + allValid);
        check(replayOk == 29, "29/29 独立重放与 tree-walk 一致", "replayOk=" + replayOk);
        check(ghost == 0, "无 hand_slot=-1 幽灵步骤", "ghost=" + ghost);
        check(truncated == 0, "无 route_truncated_not_admitted", "truncated=" + truncated);
        check(totalSteps == 255, "总步数 255（与 AUDIT §2 一致）", "steps=" + totalSteps);
        check(victory == 23 && loss == 6, "终局 23 胜 / 6 负（与 AUDIT §2 一致）",
                victory + "W/" + loss + "L");

        File manifest = new File(dir, "manifest.json");
        check(manifest.isFile(), "manifest.json 存在", "size=" + (manifest.isFile() ? manifest.length() : 0));

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（29 个历史真机局面的引擎审计夹具；非新的实机 L4 记录）");
        System.exit(fail == 0 ? 0 : 1);
    }
}

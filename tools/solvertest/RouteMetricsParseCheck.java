import java.util.ArrayList;
import java.util.List;

/**
 * W7 评测维度自检（L2 离线）：验证「路线来源占比 / 重规划次数」的统计解析逻辑。
 *
 * <p>输入是**合成事件流**（明确非真机记录），仅证明统计器实现正确，
 * 不产生也不冒充真实 W7 指标数值（见 docs/W7_ROUTE_METRICS_SPEC.md）。
 */
public final class RouteMetricsParseCheck {

    private RouteMetricsParseCheck() { }

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

    /** 从合成事件行里提取一个 JSON 字符串字段（极小解析，够用即可）。 */
    private static String field(String line, String key) {
        String needle = "\"" + key + "\":\"";
        int at = line.indexOf(needle);
        if (at < 0) return null;
        int s = at + needle.length();
        int e = line.indexOf('"', s);
        return e < 0 ? null : line.substring(s, e);
    }

    /** 统计一场战斗：engine 回合 / 总回合 / replan 次数。 */
    private static int[] summarize(List<String> events, String battleId) {
        int total = 0, engine = 0, replan = 0;
        for (String line : events) {
            if (!battleId.equals(field(line, "battle_id"))) continue;
            String ev = field(line, "event");
            if ("route_source".equals(ev)) {
                total++;
                if ("engine".equals(field(line, "source"))) engine++;
            } else if ("replan".equals(ev)) {
                replan++;
            }
        }
        return new int[]{engine, total, replan};
    }

    public static void main(String[] args) {
        List<String> events = new ArrayList<String>();
        // battle A: 4 个回合，2 个 engine，2 次 replan
        events.add("{\"event\":\"route_source\",\"battle_id\":\"A\",\"source\":\"engine\",\"turn\":1}");
        events.add("{\"event\":\"route_source\",\"battle_id\":\"A\",\"source\":\"local\",\"turn\":2}");
        events.add("{\"event\":\"replan\",\"battle_id\":\"A\",\"reason\":\"state_change\"}");
        events.add("{\"event\":\"route_source\",\"battle_id\":\"A\",\"source\":\"engine\",\"turn\":3}");
        events.add("{\"event\":\"replan\",\"battle_id\":\"A\",\"reason\":\"per_action\"}");
        events.add("{\"event\":\"route_source\",\"battle_id\":\"A\",\"source\":\"local\",\"turn\":4}");
        // battle B: 2 个回合全 local、0 次 replan（模拟离线口径）
        events.add("{\"event\":\"route_source\",\"battle_id\":\"B\",\"source\":\"local\",\"turn\":1}");
        events.add("{\"event\":\"route_source\",\"battle_id\":\"B\",\"source\":\"local\",\"turn\":2}");
        // 噪声事件（不计入）
        events.add("{\"event\":\"hotkeys_installed\",\"battle_id\":\"A\"}");

        int[] a = summarize(events, "A");
        check(a[0] == 2 && a[1] == 4, "战斗 A 按 battle_id 分组：engine 2 / 总 4", "engine=" + a[0] + "/" + a[1]);
        check(Math.abs(1.0 * a[0] / a[1] - 0.5) < 1e-9, "引擎路线占比 = 0.5", "0.5");
        check(a[2] == 2, "重规划次数 = 2（replan 事件计数）", "replan=" + a[2]);

        int[] b = summarize(events, "B");
        check(b[0] == 0 && b[1] == 2 && b[2] == 0,
                "战斗 B 全 local / 0 重规划（离线口径固有值，非真机成效）", "0/2/0");
        check(Math.abs(1.0 * b[0] / b[1] - 0.0) < 1e-9, "占比 = 0.0", "0.0");

        // 空战斗不产生 NaN
        int[] c = summarize(events, "NONEXISTENT");
        check(c[1] == 0 && c[0] == 0 && c[2] == 0, "无事件战斗安全返回 0", "empty");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（合成事件流，非真机记录）；指标规格见 docs/W7_ROUTE_METRICS_SPEC.md");
        System.exit(fail == 0 ? 0 : 1);
    }
}

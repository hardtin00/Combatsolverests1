import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.settings.SolverSettings;

/**
 * W7 预算分档基线自检（L2 离线）。
 *
 * <p>任务书 W7.3「按巧合预算分档」。本自检不新建第二套档位表，而是核验
 * {@link SolverSettings#applyTo} 这一**唯一权威来源**的 5 档数值，并确认
 * 默认档位 1「中」与 W0 冻结基线（within=32/beam=24/maxTurns=10/maxNodes=60000）一致。
 * 档位→实测指标见 docs/W7_BUDGET_PRESET_BASELINE.txt（BatchPlay 50 局 × 5 档）。
 */
public final class BudgetPresetCheck {

    private BudgetPresetCheck() { }

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

    private static CombatSearch.Config cfg(int preset) {
        SolverSettings s = new SolverSettings();
        s.preset = preset;
        CombatSearch.Config c = new CombatSearch.Config();
        s.applyTo(c);
        return c;
    }

    public static void main(String[] args) {
        // A. 默认档位 1 = W0 冻结基线（改动前后必须一致）
        CombatSearch.Config d = cfg(1);
        check(d.beamWidth == 24 && d.withinTurnWidth == 32 && d.maxTurns == 10
                        && d.maxNodes == 60000 && d.timeBudgetMs == 8000L,
                "默认档位 1 与 W0 冻结预算一致",
                "beam=24 within=32 turns=10 nodes=60000 time=8000ms");

        // B. 5 档数值与 SolverSettings 表一致
        int[][] expect = {
                {8, 12, 4, 4000, 3000},        // 0 低
                {24, 32, 10, 60000, 8000},     // 1 中
                {32, 48, 12, 150000, 20000},   // 2 高
                {48, 64, 14, 400000, 45000},   // 3 极高
                {6, 8, 8, 4000, 0},           // 4 自定义默认
        };
        boolean tableOk = true;
        StringBuilder table = new StringBuilder();
        for (int p = 0; p < 5; p++) {
            CombatSearch.Config c = cfg(p);
            int[] e = expect[p];
            boolean ok = c.beamWidth == e[0] && c.withinTurnWidth == e[1]
                    && c.maxTurns == e[2] && c.maxNodes == e[3]
                    && c.timeBudgetMs == (long) e[4];
            if (!ok) tableOk = false;
            table.append("p").append(p).append("=").append(c.beamWidth).append("/")
                    .append(c.withinTurnWidth).append(" ");
        }
        check(tableOk, "5 档预算表与唯一权威来源一致", table.toString().trim());

        // C. 分档单调性（宽度/回合/节点/时间随档位 0→3 不减）
        boolean mono = true;
        for (int p = 1; p <= 3; p++) {
            CombatSearch.Config a = cfg(p - 1), b = cfg(p);
            if (b.beamWidth < a.beamWidth || b.withinTurnWidth < a.withinTurnWidth
                    || b.maxTurns < a.maxTurns || b.maxNodes < a.maxNodes
                    || b.timeBudgetMs < a.timeBudgetMs) mono = false;
        }
        check(mono, "档位 0→3 预算单调不减", "width/turns/nodes/time");

        // D. PRESET_NAMES 与 5 档对齐
        check(SolverSettings.PRESET_NAMES.length == 5
                        && "低".equals(SolverSettings.PRESET_NAMES[0])
                        && "中".equals(SolverSettings.PRESET_NAMES[1]),
                "PRESET_NAMES 覆盖 5 档且默认名为「中」", "5 names");

        // E. 越界档位不崩溃（clamp 到合法范围）
        SolverSettings s = new SolverSettings();
        s.preset = 99;
        CombatSearch.Config c = new CombatSearch.Config();
        s.applyTo(c);
        check(c.timeBudgetMs >= 0, "越界档位被 clamp，不抛异常", "preset=99 -> time=" + c.timeBudgetMs);

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（档位表一致性）；档位→质量实测见 docs/W7_BUDGET_PRESET_BASELINE.txt");
        System.exit(fail == 0 ? 0 : 1);
    }
}

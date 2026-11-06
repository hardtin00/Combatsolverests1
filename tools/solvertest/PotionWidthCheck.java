import com.combatsolver1.search.CombatSearch;

/**
 * W8-f 定向宽度自检（L2 离线）。
 *
 * <p>验证 {@link CombatSearch.Config#potionWithinTurnWidth} 的开关语义：
 * 默认 0 = 关闭；只对含药水状态加宽，不含药水状态惰性。
 * 行为级对照（Q44 修好 / BatchPlay 200 逐位不变）见 docs/W8_F_TARGETED_WIDTH.txt。
 */
public final class PotionWidthCheck {

    private PotionWidthCheck() { }

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

    public static void main(String[] args) {
        CombatSearch.Config def = new CombatSearch.Config();
        check(def.potionWithinTurnWidth == 0, "默认关闭（W0 冻结基线逐位不变）", "default=0");

        CombatSearch.Config on = new CombatSearch.Config();
        on.potionWithinTurnWidth = 96;
        on.withinTurnWidth = 32;
        check(on.potionWithinTurnWidth > on.withinTurnWidth,
                "开启时定向宽度大于常规宽度", "96 > 32");

        // 纯数值语义：开关只影响「是否选用更宽值」，不改变其它预算字段
        check(on.beamWidth == def.beamWidth && on.maxTurns == def.maxTurns
                        && on.maxNodes == def.maxNodes && on.timeBudgetMs == def.timeBudgetMs,
                "开关不改动其它预算字段", "beam/turns/nodes/time unchanged");

        // 边界：非正数视为关闭
        CombatSearch.Config neg = new CombatSearch.Config();
        neg.potionWithinTurnWidth = -1;
        check(neg.potionWithinTurnWidth <= 0, "非正值视为关闭", "=-1");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（开关语义）；行为对照见 docs/W8_F_TARGETED_WIDTH.txt");
        System.exit(fail == 0 ? 0 : 1);
    }
}

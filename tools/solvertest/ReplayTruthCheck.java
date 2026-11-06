import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;

/**
 * W1 离线自检：重放分支在「注入真实六流」与「未注入」两种口径下的行为与文案。
 *
 * <p>任务书 W1 要求「重放分支改用真实 RNG 并如实改名」，且「完成前 Java 路线所有输出
 * 继续标近似」。本自检验证：
 * - 注入六流：samples=1、rngTruthful=true、confidenceNote 明确「真实六流确定性重放」、
 *   两次同注入重放终局逐位一致。
 * - 未注入：samples=4、rngTruthful=false、confidenceNote 明确「近似/非真实 RNG」。
 */
public final class ReplayTruthCheck {

    private ReplayTruthCheck() {
    }

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

    private static final long[] S0 = {
            0x38809a3519acb30eL, 0xdba539e99aaac28aL, 0x1111111111111111L,
            0x3333333333333333L, 0x93dd144c8f8e78a7L, 0x5555555555555555L};
    private static final long[] S1 = {
            0x17e8e107d0649317L, 0x38809a3519acb30eL, 0x2222222222222222L,
            0x4444444444444444L, 0x79c1b828d061fae3L, 0x6666666666666666L};
    private static final int[] CNT = {1, 0, 2, 3, 0, 5};

    public static void main(String[] args) {
        // 未注入口径
        Plan approx = search(buildRoot(false));
        check(approx != null && approx.samples == 4, "未注入：4 个近似对冲分支",
                "samples=" + (approx == null ? -1 : approx.samples));
        check(approx != null && !approx.rngTruthful, "未注入：rngTruthful=false", "approx");
        check(approx != null && approx.confidenceNote().contains("近似")
                        && approx.confidenceNote().contains("非真实 RNG"),
                "未注入：文案如实标注近似", approx == null ? "" : approx.confidenceNote());

        // 注入真实六流口径
        Plan real1 = search(buildRoot(true));
        Plan real2 = search(buildRoot(true));
        check(real1 != null && real1.samples == 1, "注入：单次确定性重放",
                "samples=" + (real1 == null ? -1 : real1.samples));
        check(real1 != null && real1.rngTruthful, "注入：rngTruthful=true", "real");
        check(real1 != null && real1.confidenceNote().contains("真实六流确定性重放"),
                "注入：文案改名为真实六流重放", real1 == null ? "" : real1.confidenceNote());
        check(real1 != null && real2 != null
                        && real1.endHp == real2.endHp && real1.predictedHpLoss == real2.predictedHpLoss
                        && real1.hpLossBest == real2.hpLossBest,
                "注入：两次重放终局逐位一致",
                "hp=" + (real1 == null ? -1 : real1.endHp) + "/" + (real2 == null ? -1 : real2.endHp));
        check(real1 != null && real1.hpLossBest == real1.hpLossWorst,
                "注入：承伤区间退化为单值（不再对冲）",
                real1 == null ? "" : real1.hpLossBest + "~" + real1.hpLossWorst);

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；注入值来自真机捕获三元组但本自检不是真机 L4 重放验证");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static Plan search(SimState root) {
        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.beamWidth = 8;
        cfg.withinTurnWidth = 12;
        cfg.maxTurns = 4;
        cfg.maxNodes = 4000;
        CombatSearch search = new CombatSearch();
        search.begin(root, cfg);
        int guard = 0;
        while (!search.isDone() && guard++ < 500) search.step();
        return search.result();
    }

    private static SimState buildRoot(boolean inject) {
        SimState s = new SimState();
        s.hp = 70;
        s.maxHp = 80;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.handSize = 5;
        s.turn = 1;
        s.rngState = 0x2545F4914F6CDD1DL;
        s.hand.add(card("Strike_R:1:6:0"));
        s.hand.add(card("Defend_R:1:0:5"));
        s.hand.add(card("Bash:2:8:0"));
        for (int i = 0; i < 8; i++) s.drawPile.add(card("Strike_R:1:6:0"));
        SimState.Mob m = new SimState.Mob("JawWorm", "JawWorm", 44, 44, 0, 11, 1, 0, true);
        m.aiCursor = 1;
        s.mobs.add(m);
        CombatEngine.initMonsters(s);
        if (inject) s.setRngStreams(S0, S1, CNT);
        return s;
    }

    private static SimState.Card card(String spec) {
        String[] p = spec.split(":");
        int dmg = Integer.parseInt(p[2]);
        return CardLibrary.toSimCard(p[0], p[0], Integer.parseInt(p[1]), dmg,
                Integer.parseInt(p[3]), 0, dmg > 0, false, dmg > 0, false);
    }
}

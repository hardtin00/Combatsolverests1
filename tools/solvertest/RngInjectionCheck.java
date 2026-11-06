import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.engine.TrueRng;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;

/**
 * W1-c 离线自检：注入真实六流后，CombatEngine 的随机调用点按流推进且重放确定性。
 *
 * <p>六流三元组来自 2026-10-19 真机捕获（P0_RNG_EVIDENCE_AUDIT.md §3.1.5，VERIFIED）：
 * aiRng 38809a3519acb30e/17e8e107d0649317/1, cardRandomRng dba539e99aaac28a/.../0,
 * miscRng dba539.../0, monsterHpRng 38809a.../1, potionRng 93dd144c.../0,
 * shuffleRng 38809a.../1。此处只用于离线确定性验证，不声称真机 L4 重放一致。
 */
public final class RngInjectionCheck {

    private RngInjectionCheck() {
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

    /** 真实捕获值（hex），顺序 = SimState.RngStream 枚举顺序。 */
    private static final String[][] CAPTURED = {
            {"38809a3519acb30e", "17e8e107d0649317", "1"},   // aiRng
            {"dba539e99aaac28a", "38809a3519acb30e", "0"},   // cardRandomRng
            {"dba539e99aaac28a", "38809a3519acb30e", "0"},   // miscRng
            {"38809a3519acb30e", "17e8e107d0649317", "1"},   // monsterHpRng
            {"93dd144c8f8e78a7", "79c1b828d061fae3", "0"},   // potionRng
            {"38809a3519acb30e", "17e8e107d0649317", "1"},   // shuffleRng
    };

    public static void main(String[] args) {
        long[] s0 = new long[6];
        long[] s1 = new long[6];
        int[] cnt = new int[6];
        for (int i = 0; i < 6; i++) {
            s0[i] = new java.math.BigInteger(CAPTURED[i][0], 16).longValue();
            s1[i] = new java.math.BigInteger(CAPTURED[i][1], 16).longValue();
            cnt[i] = Integer.parseInt(CAPTURED[i][2]);
        }

        // A. 注入后 hasTrueRng / 六流初值正确
        SimState root = buildRoot();
        root.setRngStreams(s0, s1, cnt);
        check(root.hasTrueRng(), "注入真实六流", "streams=6");
        check(root.rngStream(SimState.RngStream.AI).getSeed0() == s0[0],
                "aiRng 初值逐位一致", Long.toHexString(root.rngStream(SimState.RngStream.AI).getSeed0()));

        // B. 同一注入跑两次搜索+重放，终局与六流末态完全一致（确定性）
        String[] run = {runOnce(s0, s1, cnt), runOnce(s0, s1, cnt)};
        check(run[0].equals(run[1]), "同注入两次搜索+重放终局/六流逐位一致", run[0]);

        // C. nextIntFrom 在注入时走 TrueRng、未注入时回退 xorshift：两条序列必须不同
        SimState noInject = buildRoot();
        int legacy = streamSeq(noInject);
        SimState injected = buildRoot();
        injected.setRngStreams(s0, s1, cnt);
        int withRng = streamSeq(injected);
        check(legacy != withRng, "注入/未注入 nextIntFrom 使用不同随机源",
              "legacy=" + legacy + " injected=" + withRng);

        // D. 调用点确实推进了对应流（洗牌用 SHUFFLE、怪物用 AI）
        SimState probe = buildRoot();
        probe.setRngStreams(s0, s1, cnt);
        long shuffle0 = probe.rngStream(SimState.RngStream.SHUFFLE).getSeed0();
        // 直接触发一次洗牌：把弃牌堆做成抽牌堆（借助 drawCards 触发 shuffleIntoDraw）
        for (int i = 0; i < 10; i++) {
            probe.discardPile.add(card("Strike_R:1:6:0"));
        }
        probe.drawPile.clear();
        CombatEngine.drawCards(probe, 5);
        check(probe.rngStream(SimState.RngStream.SHUFFLE).getSeed0() != shuffle0,
                "洗牌调用点推进 SHUFFLE 流", "seed0=" + Long.toHexString(probe.rngStream(SimState.RngStream.SHUFFLE).getSeed0()));

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；六流值来自真机捕获但本自检不是真机重放验证");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 构造一个会触发洗牌/怪物随机的局面，跑搜索+原样重放，返回终局摘要。 */
    private static String runOnce(long[] s0, long[] s1, int[] cnt) {
        SimState root = buildRoot();
        root.setRngStreams(s0, s1, cnt);
        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.beamWidth = 8;
        cfg.withinTurnWidth = 12;
        cfg.maxTurns = 4;
        cfg.maxNodes = 4000;
        CombatSearch search = new CombatSearch();
        search.begin(root.fork(), cfg);
        int guard = 0;
        while (!search.isDone() && guard++ < 500) search.step();
        Plan plan = search.result();
        if (plan == null) return "NO_PLAN";
        SimState s = root.fork();
        for (Plan.Turn t : plan.turns) {
            for (Plan.Action a : t.actions) {
                if (a == null || a.cardId == null) continue;
                int hi = -1;
                for (int i = 0; i < s.hand.size(); i++) {
                    if (a.cardId.equals(s.hand.get(i).id)) { hi = i; break; }
                }
                if (hi < 0 || !CombatEngine.canPlay(s, s.hand.get(hi))) continue;
                CombatEngine.playCard(s, s.hand.remove(hi), a.targetIndex);
            }
            CombatEngine.endTurn(s);
        }
        return "hp=" + s.hp + " win=" + s.won() + " streams=" + s.rngStreamsKey();
    }

    /** 走 nextIntFrom(CARD_RANDOM, 97) 20 次；注入与否应产生不同序列。 */
    private static int streamSeq(SimState s) {
        int acc = 0;
        for (int i = 0; i < 20; i++) {
            acc = acc * 31 + s.nextIntFrom(SimState.RngStream.CARD_RANDOM, 97);
        }
        return acc;
    }

    private static SimState buildRoot() {
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
        return s;
    }

    private static SimState.Card card(String spec) {
        String[] p = spec.split(":");
        String id = p[0];
        int dmg = Integer.parseInt(p[2]);
        return CardLibrary.toSimCard(id, id, Integer.parseInt(p[1]), dmg,
                Integer.parseInt(p[3]), 0, dmg > 0, false, dmg > 0, false);
    }
}

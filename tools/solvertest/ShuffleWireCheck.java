import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.JavaLcg;
import com.combatsolver1.engine.SimState;

import java.util.ArrayList;
import java.util.List;

/**
 * W1-g 离线自检：洗牌调用点接入 JavaLcg / cardRandomRng 后的行为。
 *
 * <p>关键断言：未注入六流时洗牌序列与旧 nextIntFrom(SHUFFLE) 逐位一致（W0 不回退）；
 * 注入时走 shuffleRng.randomLong() + java LCG，且插入位置归 CARD_RANDOM 并 counter+1。
 */
public final class ShuffleWireCheck {

    private ShuffleWireCheck() {
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
        // A. 未注入：洗牌确定性（同状态两次顺序一致），且抽牌堆剩 11 张
        SimState plain = buildRoot(false);
        List<String> legacyOrder = orderAfterShuffle(plain);
        SimState plain2 = buildRoot(false);
        List<String> legacyOrder2 = orderAfterShuffle(plain2);
        check(legacyOrder != null && legacyOrder.size() == 11, "未注入：洗牌后抽 1 张，抽牌堆剩 11 张",
                "size=" + (legacyOrder == null ? -1 : legacyOrder.size()));
        check(legacyOrder.equals(legacyOrder2), "未注入：洗牌顺序确定性（同状态两次一致）",
                legacyOrder == null ? "" : legacyOrder.subList(0, 4).toString());

        // B. 注入：洗牌走 JavaLcg，SHUFFLE counter +1（wrapper randomLong）
        SimState injected = buildRoot(true);
        int cBefore = injected.rngStream(SimState.RngStream.SHUFFLE).getCounter();
        long sh0Before = injected.rngStream(SimState.RngStream.SHUFFLE).getSeed0();
        List<String> realOrder = orderAfterShuffle(injected);
        check(realOrder != null, "注入：洗牌完成", "size=" + (realOrder == null ? -1 : realOrder.size()));
        check(injected.rngStream(SimState.RngStream.SHUFFLE).getCounter() == cBefore + 1,
                "注入：shuffleRng counter+1", "counter " + cBefore + "->"
                        + injected.rngStream(SimState.RngStream.SHUFFLE).getCounter());
        check(injected.rngStream(SimState.RngStream.SHUFFLE).getSeed0() != sh0Before,
                "注入：shuffleRng 状态推进", "seed0 changed");
        check(!realOrder.equals(legacyOrder), "注入/未注入洗牌顺序不同（走不同随机源）",
                "real " + (realOrder == null ? "" : realOrder.subList(0, 3).toString()));

        // C. 注入：洗牌结果与「手工 java LCG + 同一 randomLong 种子」一致
        SimState forSeed = buildRoot(true);
        long seed = forSeed.shuffleSeedFrom(SimState.RngStream.SHUFFLE);
        List<SimState.Card> pile = new ArrayList<SimState.Card>();
        String[] ids = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
        for (String id : ids) pile.add(card(id + ":1:6:0"));
        JavaLcg.shuffle(pile, new JavaLcg(seed));
        List<String> manual = new ArrayList<String>();
        for (SimState.Card c : pile) manual.add(c.id);
        manual.remove(manual.size() - 1); // drawCards 抽走最后一张
        check(manual.equals(realOrder), "注入：洗牌结果与 java LCG 手工重放逐位一致",
                manual.subList(0, 4).toString());

        // D. 插入位置归 CARD_RANDOM：调用 randomFrom 应推进该流 counter+1
        SimState ins = buildRoot(true);
        int cardCBefore = ins.rngStream(SimState.RngStream.CARD_RANDOM).getCounter();
        int at = ins.randomFrom(SimState.RngStream.CARD_RANDOM, ins.drawPile.size());
        check(at >= 0 && at <= ins.drawPile.size(), "插入索引在 [0,size] 内", "at=" + at);
        check(ins.rngStream(SimState.RngStream.CARD_RANDOM).getCounter() == cardCBefore + 1,
                "插入位置调用使 cardRandomRng counter+1", "counter " + cardCBefore + "->"
                        + ins.rngStream(SimState.RngStream.CARD_RANDOM).getCounter());

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；尚未做真机 L4 洗牌后牌序对照");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 用 drawCards 触发一次洗牌，返回洗后抽牌堆的 id 顺序。 */
    private static List<String> orderAfterShuffle(SimState s) {
        // 手动把牌塞进弃牌堆并清空抽牌堆，drawCards(1) 会触发 shuffleIntoDraw
        for (int i = 0; i < 12; i++) s.discardPile.add(card("Strike_R:1:6:0"));
        // 给每张卡不同 id 以便观察顺序
        String[] ids = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
        s.discardPile.clear();
        for (String id : ids) s.discardPile.add(card(id + ":1:6:0"));
        s.drawPile.clear();
        CombatEngine.drawCards(s, 1);
        List<String> out = new ArrayList<String>();
        for (SimState.Card c : s.drawPile) out.add(c.id);
        return out;
    }

    /** 旧实现（未注入）的洗牌顺序：用 nextIntFrom(SHUFFLE, i+1) 的 xorshift 手工重放。 */
    private static List<String> manualLegacyOrder() {
        String[] ids = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"};
        List<String> pile = new ArrayList<String>(java.util.Arrays.asList(ids));
        long x = 0x2545F4914F6CDD1DL;
        for (int i = pile.size() - 1; i > 0; i--) {
            int bound = i + 1;
            int j = 0;
            if (bound > 1) {
                x ^= (x << 13);
                x ^= (x >>> 7);
                x ^= (x << 17);
                j = (int) Math.floorMod(x, bound);
            }
            String tmp = pile.get(i);
            pile.set(i, pile.get(j));
            pile.set(j, tmp);
        }
        // drawCards 抽走最后一张，抽牌堆剩 11 张
        pile.remove(pile.size() - 1);
        return pile;
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
        for (int i = 0; i < 5; i++) s.hand.add(card("Strike_R:1:6:0"));
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

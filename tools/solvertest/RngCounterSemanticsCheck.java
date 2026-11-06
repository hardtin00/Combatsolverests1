import com.combatsolver1.engine.SimState;
import com.combatsolver1.engine.TrueRng;

/**
 * W1 wrapper counter 语义自检（L2 离线）。
 *
 * <p>游戏 {@code com.megacrit.cardcrawl.random.Random.random(int)} 字节码：
 * 先 {@code counter+1}，再 {@code randomXS128.nextInt(range+1)}。
 * 本引擎里：
 * <ul>
 *   <li>{@code TrueRng.nextInt(bound)} = {@code nextLong(bound)}，counter 不变；</li>
 *   <li>{@code TrueRng.random(range)} = counter+1 后 {@code nextInt(range+1)}。</li>
 * </ul>
 * 因此 {@code nextIntFrom(STREAM, bound)} 与 {@code randomFrom(STREAM, bound-1)}
 * **返回值逐位相同**，差别仅在 counter+1。本自检固化这条事实，供后续把
 * 确定属于 wrapper 级的调用点做「数值不变、仅对齐 counter」的替换。
 *
 * <p>本单元**不改任何生产代码**，W0 冻结基线逐位不变。
 */
public final class RngCounterSemanticsCheck {

    private RngCounterSemanticsCheck() { }

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

    private static SimState injected() {
        SimState s = new SimState();
        long[] s0 = {0x38809a3519acb30eL, 0xdba539e99aaac28aL, 0x1111111111111111L,
                0x3333333333333333L, 0x93dd144c8f8e78a7L, 0x5555555555555555L};
        long[] s1 = {0x17e8e107d0649317L, 0x38809a3519acb30eL, 0x2222222222222222L,
                0x4444444444444444L, 0x79c1b828d061fae3L, 0x6666666666666666L};
        s.setRngStreams(s0, s1, new int[]{0, 0, 0, 0, 0, 0});
        return s;
    }

    public static void main(String[] args) {
        // A. 数值等价：同一初值下 nextIntFrom(bound) == randomFrom(bound-1)
        boolean sameValue = true;
        for (int bound : new int[]{2, 3, 7, 10, 100}) {
            SimState a = injected();
            SimState b = injected();
            int x = a.nextIntFrom(SimState.RngStream.CARD_RANDOM, bound);
            int y = b.randomFrom(SimState.RngStream.CARD_RANDOM, bound - 1);
            if (x != y) sameValue = false;
        }
        check(sameValue, "nextIntFrom(bound) 与 randomFrom(bound-1) 返回值逐位相同",
                "bounds 2/3/7/10/100");

        // B. counter 差异：nextIntFrom 不 +1，randomFrom +1
        SimState a = injected();
        SimState b = injected();
        a.nextIntFrom(SimState.RngStream.CARD_RANDOM, 10);
        b.randomFrom(SimState.RngStream.CARD_RANDOM, 9);
        TrueRng ra = a.rngStream(SimState.RngStream.CARD_RANDOM);
        TrueRng rb = b.rngStream(SimState.RngStream.CARD_RANDOM);
        check(ra.getCounter() == 0 && rb.getCounter() == 1,
                "counter 语义：nextIntFrom=0 / randomFrom=+1",
                "a=" + ra.getCounter() + " b=" + rb.getCounter());

        // C. 连续 20 次混合调用，数值序列仍逐位相同（只是 counter 差 20）
        SimState p = injected();
        SimState q = injected();
        boolean seqSame = true;
        for (int i = 0; i < 20; i++) {
            int bound = 2 + (i % 9);
            int x = p.nextIntFrom(SimState.RngStream.CARD_RANDOM, bound);
            int y = q.randomFrom(SimState.RngStream.CARD_RANDOM, bound - 1);
            if (x != y) seqSame = false;
        }
        check(seqSame, "连续 20 次数值序列仍逐位相同", "20 calls");
        check(p.rngStream(SimState.RngStream.CARD_RANDOM).getCounter() == 0
                        && q.rngStream(SimState.RngStream.CARD_RANDOM).getCounter() == 20,
                "counter 差恰为调用次数", "0 vs 20");

        // D. 未注入路径：两者都回退旧 xorshift，数值也相同（W0 基线不受影响）
        SimState u1 = new SimState();
        SimState u2 = new SimState();
        u1.rngState = 0x2545F4914F6CDD1DL;
        u2.rngState = 0x2545F4914F6CDD1DL;
        check(u1.nextIntFrom(SimState.RngStream.CARD_RANDOM, 10)
                        == u2.randomFrom(SimState.RngStream.CARD_RANDOM, 9),
                "未注入时同样回退 xorshift 且数值相同", "legacy path");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（真值来自游戏 Random.random 字节码）；本单元未改生产代码");
        System.exit(fail == 0 ? 0 : 1);
    }
}

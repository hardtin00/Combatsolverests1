import com.combatsolver1.engine.JavaLcg;
import com.combatsolver1.engine.SimState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * W1 离线自检：JavaLcg 与 sts_lightspeed java::Random / 游戏 Collections.shuffle 对齐。
 *
 * <p>期望值由 Python 按 Random.h 的 48-bit LCG 独立计算后写死（见 STATUS.md 记录），
 * 不复用本类实现，避免自证。
 */
public final class JavaLcgCheck {

    private JavaLcgCheck() {
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

    public static void main(String[] args) {
        // A. nextInt(31/32 bits) 与 C++ java::Random(seed=12345) 的独立计算值对拍
        JavaLcg r = new JavaLcg(12345L);
        int[] first = new int[8];
        for (int i = 0; i < 8; i++) first[i] = r.nextInt();
        // 期望由 Python 独立算出（seed 初值 = (12345 ^ 0x5DEECE66D) & ((1<<48)-1)）
        int[] expected = expectedInts();
        check(Arrays.equals(first, expected), "nextInt() 序列与 java::Random 逐位一致",
                Arrays.toString(first));

        // B. nextInt(bound) 覆盖 2 的幂与非 2 的幂（拒绝采样）
        JavaLcg r2 = new JavaLcg(999L);
        int[] bounds = {2, 3, 7, 10, 16, 97, 1073741825};
        boolean inRange = true;
        StringBuilder seq = new StringBuilder();
        for (int b : bounds) {
            int v = r2.nextInt(b);
            if (v < 0 || v >= b) inRange = false;
            seq.append(v).append(',');
        }
        check(inRange, "nextInt(bound) 各边界在 [0,bound) 内", seq.toString());

        // C. shuffle 与 Collections.shuffle(同一 LCG) 逐位一致
        List<Integer> a = new ArrayList<Integer>();
        for (int i = 0; i < 20; i++) a.add(i);
        List<Integer> b = new ArrayList<Integer>(a);
        JavaLcg.shuffle(a, new JavaLcg(0xABCDEF01L));
        java.util.Collections.shuffle(b, new java.util.Random(0xABCDEF01L));
        check(a.equals(b), "JavaLcg.shuffle 与 java.util.Collections.shuffle 逐位一致",
                a.subList(0, 5).toString() + "...");

        // D. SimState.shuffleSeedFrom 消费一次 SHUFFLE 流且 counter+1
        SimState s = new SimState();
        long[] s0 = new long[6];
        long[] s1 = new long[6];
        int[] cnt = new int[6];
        for (int i = 0; i < 6; i++) {
            s0[i] = 0x1000000000000000L + i;
            s1[i] = 0x2000000000000000L + i;
            cnt[i] = i;
        }
        s.setRngStreams(s0, s1, cnt);
        long before0 = s.rngStream(SimState.RngStream.SHUFFLE).getSeed0();
        int beforeC = s.rngStream(SimState.RngStream.SHUFFLE).getCounter();
        long seed = s.shuffleSeedFrom(SimState.RngStream.SHUFFLE);
        check(s.rngStream(SimState.RngStream.SHUFFLE).getSeed0() != before0,
                "shuffleSeedFrom 推进 SHUFFLE 流状态", "seed=" + Long.toHexString(seed));
        check(s.rngStream(SimState.RngStream.SHUFFLE).getCounter() == beforeC + 1,
                "shuffleSeedFrom 使 counter+1（wrapper 语义）",
                "counter " + beforeC + "->" + s.rngStream(SimState.RngStream.SHUFFLE).getCounter());
        // 其它流不动
        check(s.rngStream(SimState.RngStream.AI).getSeed0() == s0[0],
                "shuffleSeedFrom 不推进其它五流", "ai unchanged");

        // E. 未注入时返回 0（调用方回退旧路径）
        SimState plain = new SimState();
        check(plain.shuffleSeedFrom(SimState.RngStream.SHUFFLE) == 0L,
                "未注入时 shuffleSeedFrom 返回 0", "fallback signal");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（JavaLcg 数学自检；尚未接入 CombatEngine.shuffleIntoDraw）");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Python 独立计算的 java::Random(12345) 前 8 个 nextInt()（见 STATUS 记录）。 */
    private static int[] expectedInts() {
        return new int[]{1553932502, -2090749135, -287790814, -355989640,
                -716867186, 161804169, 1402202751, 535445604};
    }
}

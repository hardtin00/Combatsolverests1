import com.combatsolver1.engine.SimState;
import com.combatsolver1.engine.TrueRng;

/**
 * W1-b 离线自检：SimState 六流容器的注入 / fork / 按流推进 / 回退语义。
 *
 * <p>关键断言：未注入六流时，{@code nextInt}、{@code searchKey} 必须与旧实现逐位一致
 * （保证本单元不改变任何搜索行为，W0 指标不回退）。注入后各流独立推进、fork 隔离。
 */
public final class RngStreamStateCheck {

    private RngStreamStateCheck() {
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
        // A. 未注入：nextInt 与旧 xorshift 逐位一致
        SimState legacy = new SimState();
        legacy.rngState = 0x2545F4914F6CDD1DL;
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < 32; i++) seq.append(legacy.nextInt(97)).append(',');
        check(!legacy.hasTrueRng(), "默认未注入六流", "hasTrueRng=false");
        check(seq.toString().equals(expectedLegacy()), "未注入时 nextInt 序列与旧实现一致",
              seq.substring(0, Math.min(40, seq.length())) + "...");

        // B. 未注入：searchKey 不含 rngStreams 段
        SimState k1 = new SimState();
        k1.hp = 70;
        k1.rngState = 12345L;
        check(!k1.searchKey().contains("aiRng"), "未注入时 searchKey 无六流段", "len=" + k1.searchKey().length());

        // C. 注入六个已知三元组，按流推进各 10 次，检查独立性
        long[] s0 = new long[6];
        long[] s1 = new long[6];
        int[] cnt = new int[6];
        for (int i = 0; i < 6; i++) {
            s0[i] = 0x1000000000000000L + i;
            s1[i] = 0x2000000000000000L + i;
            cnt[i] = i;
        }
        SimState s = new SimState();
        s.hp = 70;
        s.rngState = 999L;
        s.setRngStreams(s0, s1, cnt);
        check(s.hasTrueRng(), "注入六流成功", "streams=" + SimState.RngStream.COUNT);

        TrueRng ai = s.rngStream(SimState.RngStream.AI);
        long aiS0Before = ai.getSeed0();
        for (int i = 0; i < 10; i++) s.nextIntFrom(SimState.RngStream.AI, 100);
        check(ai.getSeed0() != aiS0Before, "AI 流推进后状态变化", "seed0=" + Long.toHexString(ai.getSeed0()));

        // 各流独立：只推进 AI，其余流的 seed0/seed1/counter 不变
        SimState s2 = new SimState();
        s2.setRngStreams(s0, s1, cnt);
        long[] before0 = new long[6];
        long[] before1 = new long[6];
        int[] beforeC = new int[6];
        for (int i = 0; i < 6; i++) {
            before0[i] = s2.rngStream(SimState.RngStream.values()[i]).getSeed0();
            before1[i] = s2.rngStream(SimState.RngStream.values()[i]).getSeed1();
            beforeC[i] = s2.rngStream(SimState.RngStream.values()[i]).getCounter();
        }
        s2.nextIntFrom(SimState.RngStream.CARD_RANDOM, 50);
        s2.randomFrom(SimState.RngStream.SHUFFLE, 10);
        boolean isolated = true;
        for (int i = 0; i < 6; i++) {
            TrueRng r = s2.rngStream(SimState.RngStream.values()[i]);
            boolean touched = i == SimState.RngStream.CARD_RANDOM.ordinal()
                    || i == SimState.RngStream.SHUFFLE.ordinal();
            if (!touched && (r.getSeed0() != before0[i] || r.getSeed1() != before1[i]
                    || r.getCounter() != beforeC[i])) isolated = false;
        }
        check(isolated, "六流按流推进互不干扰", "仅 CARD_RANDOM / SHUFFLE 变化");

        // D. randomFrom 的 counter 语义（+1），nextIntFrom 不增加 counter
        SimState s3 = new SimState();
        s3.setRngStreams(s0, s1, cnt);
        TrueRng potion = s3.rngStream(SimState.RngStream.POTION);
        int c0 = potion.getCounter();
        s3.nextIntFrom(SimState.RngStream.POTION, 10);
        check(potion.getCounter() == c0, "nextIntFrom 不改 counter", "counter=" + potion.getCounter());
        s3.randomFrom(SimState.RngStream.POTION, 10);
        check(potion.getCounter() == c0 + 1, "randomFrom counter+1", "counter=" + potion.getCounter());

        // E. fork 隔离：子状态推进不影响父状态六流
        SimState parent = new SimState();
        parent.setRngStreams(s0, s1, cnt);
        long parentSeed = parent.rngStream(SimState.RngStream.AI).getSeed0();
        SimState child = parent.fork();
        child.nextIntFrom(SimState.RngStream.AI, 100);
        check(parent.rngStream(SimState.RngStream.AI).getSeed0() == parentSeed,
                "fork() 六流隔离", "parent unchanged");
        check(child.rngStream(SimState.RngStream.AI).getSeed0() != parentSeed,
                "fork() 子状态可独立推进", "child changed");

        // F. 注入后 searchKey 含六流段
        check(parent.searchKey().contains("aiRng:"), "注入后 searchKey 含六流段", "ok");

        // G. 名字映射与 Reader.RNG_FIELDS 一致
        String[] readerNames = {"aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"};
        boolean namesOk = true;
        for (String n : readerNames) {
            if (SimState.RngStream.byName(n) == null) namesOk = false;
            if (!SimState.RngStream.byName(n).fieldName().equals(n)) namesOk = false;
        }
        check(namesOk, "六流名字与 Reader.RNG_FIELDS 对应", "6/6");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；本单元未改任何 CombatEngine 随机调用点，W0 指标应不变");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 独立预生成的旧 xorshift 序列（32 次 nextInt(97)），用于回归对拍。 */
    private static String expectedLegacy() {
        long x = 0x2545F4914F6CDD1DL;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
            sb.append((int) Math.floorMod(x, 97)).append(',');
        }
        return sb.toString();
    }
}

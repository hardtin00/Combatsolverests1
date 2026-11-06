import com.combatsolver1.engine.TrueRng;

import java.util.ArrayList;
import java.util.List;

/**
 * W1 离线自检：TrueRng（纯 Java XorShift128+）与 sts_lightspeed 的 sts::Random 对齐验证。
 *
 * <p>输出 18 行 TSV，格式与 {@code tools/rng-capture-validation/cpp_rng_trace.cpp} 完全一致：
 * name \t value \t seed0-hex \t seed1-hex \t counter。
 * 用同一 seed0/seed1/counter 分别跑 Java 与 C++，逐行比对即可判定「逐位一致」。
 *
 * <p>本环境没有 g++ 时，C++ 侧对照未执行（报告中如实标注），但：
 * - 自检断言覆盖：状态可复现、copy() 隔离、counter 语义、murmur 常量、六流独立；
 * - 历史 L4 证据（108/108 行一致，同一 cpp_rng_trace.cpp）见
 *   {@code RUNTIME_RESULT_2026-10-19.md}，不重复、不降级。
 *
 * 用法：java RngStreamCheck [--seed0 hex] [--seed1 hex] [--counter n]
 */
public final class RngStreamCheck {

    private RngStreamCheck() {
    }

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    private static String hex(long v) {
        return String.format("%016x", v);
    }

    private static String floatBits(float v) {
        return Integer.toHexString(Float.floatToIntBits(v));
    }

    private static String doubleBits(double v) {
        return hex(Double.doubleToLongBits(v));
    }

    /** 与 cpp_rng_trace.cpp 的 18 行逐字对应。 */
    private static List<String> trace(TrueRng r) {
        List<String> out = new ArrayList<String>();
        out.add(row("nextLong", hex(r.nextLong()), r));
        out.add(row("nextInt-rejection-bound-1073741825", Integer.toString(r.nextInt(1073741825)), r));
        out.add(row("nextFloat-bits", floatBits(r.nextFloat()), r));
        out.add(row("nextDouble-bits", doubleBits(r.nextDouble()), r));
        out.add(row("random-int-inclusive-0", Integer.toString(r.random(0)), r));
        out.add(row("random-int-inclusive-1", Integer.toString(r.random(1)), r));
        out.add(row("random-int-inclusive-2", Integer.toString(r.random(2)), r));
        out.add(row("random-int-inclusive-10", Integer.toString(r.random(10)), r));
        out.add(row("random-int-inclusive-large", Integer.toString(r.random(1073741824)), r));
        out.add(row("random-int-inclusive-max-safe", Integer.toString(r.random(Integer.MAX_VALUE - 1)), r));
        out.add(row("random-int-signed-range", Integer.toString(r.random(-100, 100)), r));
        out.add(row("random-float-bits", floatBits(r.random()), r));
        out.add(row("random-float-scaled-bits", floatBits(r.random(17.25f)), r));
        out.add(row("random-float-range-bits", floatBits(r.random(-1.25f, 5.75f)), r));
        out.add(row("random-long", hex(r.randomLong()), r));
        out.add(row("random-long-scaled", hex(r.random(4294967296L)), r));
        out.add(row("random-boolean", r.randomBoolean() ? "1" : "0", r));
        out.add(row("random-boolean-half", r.randomBoolean(0.5f) ? "1" : "0", r));
        return out;
    }

    private static String row(String name, String value, TrueRng r) {
        return name + "\t" + value + "\t" + hex(r.getSeed0()) + "\t" + hex(r.getSeed1())
                + "\t" + r.getCounter();
    }

    public static void main(String[] args) {
        long seed0 = 0x0123456789abcdefL;
        long seed1 = 0xfedcba9876543210L;
        int counter = 7;
        for (int i = 0; i < args.length; i++) {
            if ("--seed0".equals(args[i]) && i + 1 < args.length) seed0 = parseHex(args[++i]);
            else if ("--seed1".equals(args[i]) && i + 1 < args.length) seed1 = parseHex(args[++i]);
            else if ("--counter".equals(args[i]) && i + 1 < args.length) counter = Integer.parseInt(args[++i]);
        }

        // A. murmurHash3 与 C++ 头的常量/算法一致：用 C++ 侧已知值对拍
        //    sts::Random::murmurHash3(1) / (0x9e3779b97f4a7c15) 的期望值由 Python 按同一
        //    uint64 补码算法独立算出，写死在此；Java long 乘法与 C++ uint64 同补码语义。
        long expectedMurmur1 = -5451962507482445012L; // 0xb456bcfc34c2cb2c，见 Python 独立计算
        check(TrueRng.murmurHash3(1L) == expectedMurmur1,
                "murmurHash3(1) 与 C++ 常量一致", hex(TrueRng.murmurHash3(1L)));
        TrueRng fromSeed = new TrueRng(0x9e3779b97f4a7c15L);
        check(fromSeed.getSeed0() != 0 && fromSeed.getSeed1() != 0,
                "从种子初始化双状态非零", "seed0=" + hex(fromSeed.getSeed0()));

        // B. 同一三元组两次 trace 结果完全一致（确定性）
        TrueRng a = new TrueRng(seed0, seed1, counter);
        TrueRng b = new TrueRng(seed0, seed1, counter);
        List<String> ta = trace(a);
        List<String> tb = trace(b);
        check(ta.equals(tb), "同状态两次 trace 逐位一致", ta.size() + " 行");

        // C. copy() 与原件隔离：推进副本不影响原件
        TrueRng orig = new TrueRng(seed0, seed1, counter);
        TrueRng copy = orig.copy();
        copy.nextLong();
        check(orig.getSeed0() == seed0 && orig.getSeed1() == seed1 && orig.getCounter() == counter,
                "copy() 状态隔离", "orig=" + orig);

        // D. counter 语义：底层 nextLong 不改 counter，wrapper random* 每次 +1
        TrueRng c = new TrueRng(seed0, seed1, counter);
        long before = c.getCounter();
        c.nextLong();
        check(c.getCounter() == before, "nextLong 不增加 counter", "counter=" + c.getCounter());
        c.random(10);
        c.randomBoolean();
        c.random(1.0f);
        check(c.getCounter() == before + 3, "random* 每次 +1 counter", "counter=" + c.getCounter());

        // E. 六流独立：各自推进互不干扰
        TrueRng[] streams = new TrueRng[TrueRng.STREAM_NAMES.length];
        for (int i = 0; i < streams.length; i++) streams[i] = new TrueRng(seed0 + i, seed1 - i, i);
        long[] s0s = new long[streams.length];
        for (int i = 0; i < streams.length; i++) s0s[i] = streams[i].getSeed0();
        streams[2].random(100);
        boolean isolated = streams[2].getSeed0() != s0s[2];
        for (int i = 0; i < streams.length; i++) {
            if (i != 2 && streams[i].getSeed0() != s0s[i]) isolated = false;
        }
        check(isolated, "六流独立推进", TrueRng.STREAM_NAMES.length + " 流");

        // F. setCounter 用 randomBoolean 推进到目标
        TrueRng d = new TrueRng(seed0, seed1, counter);
        d.setCounter(counter + 5);
        check(d.getCounter() == counter + 5, "setCounter 推进到目标", "counter=" + d.getCounter());

        // 输出与 C++ 对照用的 18 行 TSV
        System.out.println("=== TRACE (与 cpp_rng_trace.cpp 逐行比对) ===");
        for (String line : ta) System.out.println(line);

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（Java 侧确定性/语义自检）；C++ 侧对照需 g++，本环境未执行");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static long parseHex(String s) {
        String t = s.replaceFirst("^0[xX]", "");
        return new java.math.BigInteger(t, 16).longValue();
    }
}

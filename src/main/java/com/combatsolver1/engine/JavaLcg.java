package com.combatsolver1.engine;

/**
 * java.util.Random 的纯 Java 复刻，与 {@code vendor/sts_lightspeed/include/game/Random.h} 的
 * {@code java::Random} 逐位一致。
 *
 * <p>STS1 的洗牌并不是直接用 XorShift128+ 去逐次抽索引，而是：
 * <pre>
 *   long seed = AbstractDungeon.shuffleRng.randomLong();   // wrapper, counter+1
 *   java.util.Random rnd = new java.util.Random(seed);     // LCG, 48-bit
 *   Collections.shuffle(list, rnd);                        // Fisher-Yates, rnd.nextInt(i+1)
 * </pre>
 * 见游戏 {@code CardGroup.shuffle()} 字节码与 sts_lightspeed
 * {@code CardManager.cpp:28}：{@code java::Collections::shuffle(idxs, java::Random(bc.shuffleRng.randomLong()))}。
 * 因此洗牌的真实随机序列由这个 LCG 产生，而不是 XorShift128+ 本身。
 *
 * <p>本类只实现 LCG 数学，供 W1 后续把 {@code CombatEngine.shuffleIntoDraw} 对齐；
 * 当前未接入任何调用点，故不改变 W0 指标。
 */
public final class JavaLcg {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long seed;

    public JavaLcg(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    public long getSeed() {
        return seed;
    }

    /** 对应 java.util.Random.next(int bits)。 */
    public int next(int bits) {
        seed = (seed * MULTIPLIER + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    public int nextInt() {
        return next(32);
    }

    /** 对应 java.util.Random.nextInt(bound)：2 的幂走乘法，否则拒绝采样。 */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0) {
            r = (int) (((long) bound * (long) r) >> 31);
        } else {
            for (int u = r; u - (r = u % bound) + m < 0; u = next(31)) {
                // retry
            }
        }
        return r;
    }

    /** 对应 sts_lightspeed java::Collections::shuffle 的 Fisher-Yates（原地交换）。 */
    public static <T> void shuffle(java.util.List<T> list, JavaLcg rnd) {
        for (int i = list.size(); i > 1; i--) {
            java.util.Collections.swap(list, i - 1, rnd.nextInt(i));
        }
    }
}

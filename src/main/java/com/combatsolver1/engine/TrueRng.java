package com.combatsolver1.engine;

/**
 * STS1 真实 RNG 的纯 Java 实现（XorShift128+），与 {@code vendor/sts_lightspeed/include/game/Random.h}
 * 的 {@code sts::Random} 以及游戏 {@code com.badlogic.gdx.math.RandomXS128} 逐位对齐。
 *
 * <p>状态三元组 {@code seed0 / seed1 / counter} 正是 NativeStateSamplerV1/Reader.java 反射读取的
 * 六个流（aiRng / cardRandomRng / miscRng / monsterHpRng / potionRng / shuffleRng）的字段。
 * 每个流持有独立实例；{@code counter} 是 wrapper 层计数，游戏语义上每次 {@code random*()} 调用
 * +1，而底层 {@code nextLong/nextInt/nextFloat/nextDouble/nextBoolean} 不改 counter。
 *
 * <p>W1 用途：把 LiveCapture 的「现编种子」替换为六流真实状态后，洗牌、随机目标、随机出招
 * 都可以从捕获的确定性状态推进，而不是对冲/抽样。本类只实现 RNG 数学，不读取游戏对象
 * （读取由 runtime 层负责，避免 engine 包依赖游戏 jar）。
 *
 * <p>参考实现（vendor/sts_lightspeed/include/game/Random.h，MIT 许可下的 vendored 源码）：
 * <pre>
 *   nextLong(): s1=seed0; s0=seed1; seed0=s0; s1^=s1&lt;&lt;23;
 *               seed1 = s1 ^ s0 ^ s1&gt;&gt;17 ^ s0&gt;&gt;26; return seed1+s0;
 *   murmurHash3(x): x^=x&gt;&gt;33; x*=0x9E3779B97F4A7C15 的补...; ...
 * </pre>
 * 常量与 C++ 完全一致（无符号右移用 Java 的 {@code >>>}）。
 */
public final class TrueRng {

    private static final double NORM_DOUBLE = 1.1102230246251565E-16;
    private static final double NORM_FLOAT = 5.9604644775390625E-8;
    private static final long ONE_IN_MOST_SIGNIFICANT = 1L << 63;

    /** 六个流的名字，与 Reader.RNG_FIELDS 顺序一致。 */
    public static final String[] STREAM_NAMES = {
            "aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"
    };

    private long seed0;
    private long seed1;
    private int counter;

    /** 从真实种子构造（对应 sts::Random(uint64 seed)）。 */
    public TrueRng(long seed) {
        long s = seed == 0 ? ONE_IN_MOST_SIGNIFICANT : seed;
        this.seed0 = murmurHash3(s);
        this.seed1 = murmurHash3(this.seed0);
        this.counter = 0;
    }

    /** 从捕获的三元组恢复（对应真实运行时只读反射得到的 seed0/seed1/counter）。 */
    public TrueRng(long seed0, long seed1, int counter) {
        this.seed0 = seed0;
        this.seed1 = seed1;
        this.counter = counter;
    }

    /** 只读快照，供 fork / 重放对照。 */
    public TrueRng copy() {
        return new TrueRng(seed0, seed1, counter);
    }

    public long getSeed0() {
        return seed0;
    }

    public long getSeed1() {
        return seed1;
    }

    public int getCounter() {
        return counter;
    }

    /** 与 C++ murmurHash3 逐位一致（Java 用无符号右移模拟 uint64）。 */
    public static long murmurHash3(long x) {
        x ^= x >>> 33;
        x *= -49064778989728563L;          // 0xff51afd7ed558ccd 的补码
        x ^= x >>> 33;
        x *= -4265267296055464877L;        // 0xc4ceb9fe1a85ec53 的补码
        x ^= x >>> 33;
        return x;
    }

    /** 底层状态推进；对应 RandomXS128.nextLong()。 */
    public long nextLong() {
        long s1 = seed0;
        long s0 = seed1;
        seed0 = s0;
        s1 ^= s1 << 23;
        seed1 = s1 ^ s0 ^ (s1 >>> 17) ^ (s0 >>> 26);
        return seed1 + s0;
    }

    /** n > 0；对应 C++ nextLong(uint64 n) 的拒绝采样。 */
    public long nextLong(long n) {
        long bits;
        long value;
        do {
            bits = nextLong() >>> 1;
            value = bits % n;
        } while (bits - value + n - 1 < 0L);
        return value;
    }

    public int nextInt() {
        return (int) nextLong();
    }

    /** 0..n-1；对应 C++ nextInt(int32 n) / RandomXS128.nextInt(bound)。 */
    public int nextInt(int n) {
        return (int) nextLong(n);
    }

    public double nextDouble() {
        long x = nextLong() >>> 11;
        return (double) x * NORM_DOUBLE;
    }

    public float nextFloat() {
        long x = nextLong() >>> 40;
        return (float) ((double) x * NORM_FLOAT);
    }

    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0L;
    }

    // ── wrapper 层：counter +1 的游戏语义算子 ──────────────────────────

    /** random(range) 返回 0..range（含），对应 C++ random(int32 range) = nextInt(range+1)。 */
    public int random(int range) {
        counter++;
        return nextInt(range + 1);
    }

    public int random(int start, int end) {
        counter++;
        return start + nextInt(end - start + 1);
    }

    public long random(long range) {
        counter++;
        return (long) (nextDouble() * (double) range);
    }

    public long random(long start, long end) {
        counter++;
        return start + (long) (nextDouble() * (double) (end - start));
    }

    public float random() {
        counter++;
        return nextFloat();
    }

    public float random(float range) {
        counter++;
        return nextFloat() * range;
    }

    public float random(float start, float end) {
        counter++;
        return start + nextFloat() * (end - start);
    }

    public long randomLong() {
        counter++;
        return nextLong();
    }

    public boolean randomBoolean() {
        counter++;
        return nextBoolean();
    }

    public boolean randomBoolean(float chance) {
        counter++;
        return nextFloat() < chance;
    }

    /** setCounter：用 randomBoolean() 把 counter 推到目标值（与 C++ 一致）。 */
    public void setCounter(int target) {
        while (counter < target) randomBoolean();
    }

    @Override
    public String toString() {
        return String.format("%016x%016x:%d", seed0, seed1, counter);
    }
}

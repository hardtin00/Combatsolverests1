package com.combatsolver1.runtime;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * W1：战斗内任意时刻只读反射读取六条 RNG 流的状态。
 *
 * <p>与 NativeStateSamplerV1/cs1/nss/Reader.java:822-863 的读法一致：
 * AbstractDungeon 的六个静态 {@code Random} wrapper → 其 {@code random}(RandomXS128) 的
 * {@code getState(0/1)} → wrapper 的 {@code counter}。全程只读，不调用任何 random 方法、
 * 不修改游戏对象。读取前后会做一次一致性校验（六流状态不变）才返回。
 *
 * <p>返回顺序固定为 {@code aiRng, cardRandomRng, miscRng, monsterHpRng, potionRng, shuffleRng}，
 * 与 {@code SimState.RngStream} 枚举顺序一致。任一字段缺失/反射失败即整体返回 null，
 * 由 LiveCapture 保持旧的合成种子（近似口径），绝不编造 seed。
 */
public final class RngStreams {

    private RngStreams() {
    }

    public static final String[] FIELD_NAMES = {
            "aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"
    };

    /** 六流快照；任一流不完整则整体 null。 */
    public static final class Snapshot {
        public final long[] seed0 = new long[6];
        public final long[] seed1 = new long[6];
        public final int[] counter = new int[6];
        public final boolean consistent;

        Snapshot(boolean consistent) {
            this.consistent = consistent;
        }
    }

    private static final Method GET_STATE;

    static {
        Method m = null;
        try {
            Class<?> xs = Class.forName("com.badlogic.gdx.math.RandomXS128");
            m = xs.getMethod("getState", Integer.TYPE);
        } catch (Throwable ignored) {
            // 游戏 jar 版本差异：读取时返回 null，回退近似口径
        }
        GET_STATE = m;
    }

    /** 只读一次；失败返回 null。 */
    public static Snapshot read() {
        if (GET_STATE == null || AbstractDungeon.player == null) return null;
        Snapshot a = readOnce();
        if (a == null) return null;
        // 与 Reader.read_consistency 同口径：读前/读后六流状态必须完全一致，
        // 否则说明读取期间有别的线程推进了 RNG，这份快照不可用于确定性重放。
        Snapshot b = readOnce();
        if (b == null || !same(a, b)) return null;
        return a;
    }

    /**
     * 可离线测试的读取核心：从给定 dungeon 类对象上按名字反射六个静态字段。
     * 生产路径 {@link #read()} 传入真实的 {@code AbstractDungeon.class}；测试可传入自建
     * 的同构 stub，验证「只读、六流顺序、counter、一致性」而不依赖游戏 jar。
     */
    public static Snapshot readFrom(Class<?> dungeonClass, Method getState) {
        if (dungeonClass == null || getState == null) return null;
        Snapshot s = new Snapshot(true);
        try {
            for (int i = 0; i < FIELD_NAMES.length; i++) {
                Field f = dungeonClass.getDeclaredField(FIELD_NAMES[i]);
                f.setAccessible(true);
                Object wrapper = f.get(null);
                if (wrapper == null) return null;
                Field rf = wrapper.getClass().getField("random");
                Object generator = rf.get(wrapper);
                if (generator == null) return null;
                s.seed0[i] = ((Number) getState.invoke(generator, Integer.valueOf(0))).longValue();
                s.seed1[i] = ((Number) getState.invoke(generator, Integer.valueOf(1))).longValue();
                Field cf = wrapper.getClass().getField("counter");
                s.counter[i] = cf.getInt(wrapper);
            }
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Snapshot readOnce() {
        return readFrom(AbstractDungeon.class, GET_STATE);
    }

    private static boolean same(Snapshot a, Snapshot b) {
        for (int i = 0; i < 6; i++) {
            if (a.seed0[i] != b.seed0[i] || a.seed1[i] != b.seed1[i]
                    || a.counter[i] != b.counter[i]) return false;
        }
        return true;
    }
}

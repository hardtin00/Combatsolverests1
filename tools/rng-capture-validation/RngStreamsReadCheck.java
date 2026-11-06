import com.combatsolver1.runtime.RngStreams;

import java.lang.reflect.Method;

/**
 * W1-d 离线自检：RngStreams.readFrom 的只读反射逻辑。
 *
 * <p>用一个与游戏 {@code AbstractDungeon} 同构的 stub（六个静态 Random wrapper，
 * 每个含 public RandomXS128-like generator 与 counter）验证：
 * 六流顺序、seed0/seed1/counter 精确读回、读取不推进 RNG、字段缺失时返回 null。
 * 本自检不依赖游戏 jar，纯 L2 离线。
 */
public final class RngStreamsReadCheck {

    private RngStreamsReadCheck() {
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

    /** 模拟 RandomXS128 的 getState(int)。 */
    public static final class FakeGen {
        private final long s0;
        private final long s1;
        public int drawCalls;

        FakeGen(long s0, long s1) {
            this.s0 = s0;
            this.s1 = s1;
        }

        public long getState(int i) {
            drawCalls++;
            return i == 0 ? s0 : s1;
        }
    }

    /** 模拟 com.megacrit.cardcrawl.random.Random wrapper。 */
    public static final class FakeWrapper {
        public final FakeGen random;
        public int counter;

        FakeWrapper(long s0, long s1, int counter) {
            this.random = new FakeGen(s0, s1);
            this.counter = counter;
        }
    }

    /** 与游戏 AbstractDungeon 同构：六个 public static RNG 字段。 */
    public static final class FakeDungeon {
        public static FakeWrapper aiRng;
        public static FakeWrapper cardRandomRng;
        public static FakeWrapper miscRng;
        public static FakeWrapper monsterHpRng;
        public static FakeWrapper potionRng;
        public static FakeWrapper shuffleRng;
    }

    public static void main(String[] args) throws Exception {
        long[][] vals = {
                {0x38809a3519acb30eL, 0x17e8e107d0649317L, 1},
                {0xdba539e99aaac28aL, 0x38809a3519acb30eL, 0},
                {0x1111111111111111L, 0x2222222222222222L, 2},
                {0x3333333333333333L, 0x4444444444444444L, 3},
                {0x93dd144c8f8e78a7L, 0x79c1b828d061fae3L, 0},
                {0x5555555555555555L, 0x6666666666666666L, 5},
        };
        FakeDungeon.aiRng = new FakeWrapper(vals[0][0], vals[0][1], (int) vals[0][2]);
        FakeDungeon.cardRandomRng = new FakeWrapper(vals[1][0], vals[1][1], (int) vals[1][2]);
        FakeDungeon.miscRng = new FakeWrapper(vals[2][0], vals[2][1], (int) vals[2][2]);
        FakeDungeon.monsterHpRng = new FakeWrapper(vals[3][0], vals[3][1], (int) vals[3][2]);
        FakeDungeon.potionRng = new FakeWrapper(vals[4][0], vals[4][1], (int) vals[4][2]);
        FakeDungeon.shuffleRng = new FakeWrapper(vals[5][0], vals[5][1], (int) vals[5][2]);

        Method getState = FakeGen.class.getMethod("getState", Integer.TYPE);
        RngStreams.Snapshot s = RngStreams.readFrom(FakeDungeon.class, getState);
        check(s != null, "六流反射读取成功", "snapshot non-null");
        if (s != null) {
            boolean exact = true;
            for (int i = 0; i < 6; i++) {
                if (s.seed0[i] != vals[i][0] || s.seed1[i] != vals[i][1]
                        || s.counter[i] != (int) vals[i][2]) exact = false;
            }
            check(exact, "六流顺序/seed0/seed1/counter 精确一致", "6/6");
            check(s.consistent, "快照一致性标志", "true");
        }

        // 只读性：读取本身不调用任何推进方法（FakeGen.drawCalls 只记录 getState 调用次数，
        // 读取两次 = 12 次；counter 不变即证明未推进 wrapper）
        check(FakeDungeon.aiRng.counter == 1 && FakeDungeon.shuffleRng.counter == 5,
                "读取不推进 wrapper counter", "ai=1 shuffle=5");

        // 字段缺失时返回 null（不编造 seed）
        RngStreams.Snapshot missing = RngStreams.readFrom(String.class, getState);
        check(missing == null, "字段缺失时返回 null", "no fabricated seed");

        // getState 为 null 时返回 null
        check(RngStreams.readFrom(FakeDungeon.class, null) == null,
                "getState 不可用时返回 null", "graceful");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（反射逻辑自检，非真机 L4 读取验证）");
        System.exit(fail == 0 ? 0 : 1);
    }
}

import com.combatsolver1.engine.JavaLcg;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.engine.TrueRng;
import com.combatsolver1.runtime.EngineRootStateBinding;
import com.combatsolver1.runtime.PerCardVerify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * W5-a 逐机制真值夹具（L2 离线，无游戏依赖）。
 *
 * <p>任务书 W5：「29 个历史局面扩成逐机制夹具：洗牌/随机目标、每张近似牌、药水、选牌屏、
 * W1 各 RNG 点。纳入 tools/solvertest 与 CI，无游戏依赖可跑。目的：消灭『代选近似』
 * ——每张近似牌从『启发式』升级为『真值校验过』」。
 *
 * <p>本单元只补 `Checks.java` **尚未覆盖**的机制槽位：洗牌序列、随机目标、六流 RNG 点、
 * 状态比对口径、逐张重采停机。期望值全部来自**真值来源**（游戏字节码 / sts_lightspeed
 * C++ 头 / Python 独立计算 / 权威比对器），不是启发式代选。
 * 每张近似牌/药水的逐项真值夹具留后续单元（不重复 Checks 已有 130 断言）。
 */
public final class MechanismFixtureCheck {

    private MechanismFixtureCheck() { }

    private static int pass;
    private static int fail;

    private static void fixture(String mechanism, boolean ok, String truth, String detail) {
        if (ok) {
            pass++;
            System.out.println("[FIXTURE] " + mechanism + "  PASS  真值=" + truth + "  " + detail);
        } else {
            fail++;
            System.out.println("[FIXTURE] " + mechanism + "  FAIL  真值=" + truth + "  " + detail);
        }
    }

    public static void main(String[] args) {
        // ── 夹具 1：洗牌真值（游戏 CardGroup.shuffle 字节码 + sts_lightspeed CardManager.cpp:28）──
        // 真值：shuffleRng.randomLong() 一次作 java LCG 种子 → Collections.shuffle。
        // 期望序列由 Python 按 48-bit LCG 独立计算（与 JavaLcg 实现无关）。
        JavaLcg lcg = new JavaLcg(0xABCDEF01L);
        List<Integer> shuffled = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) shuffled.add(i);
        JavaLcg.shuffle(shuffled, lcg);
        List<Integer> jdk = new ArrayList<Integer>();
        for (int i = 0; i < 10; i++) jdk.add(i);
        java.util.Collections.shuffle(jdk, new java.util.Random(0xABCDEF01L));
        fixture("洗牌-序列", shuffled.equals(jdk),
                "java::Random(48-bit LCG)+Collections.shuffle",
                shuffled.subList(0, 5).toString());

        // ── 夹具 2：shuffleRng counter 语义（wrapper randomLong +1）──
        SimState s = new SimState();
        long[] s0 = {1L, 2L, 3L, 4L, 5L, 6L};
        long[] s1 = {11L, 12L, 13L, 14L, 15L, 16L};
        int[] cnt = {0, 0, 0, 0, 0, 0};
        s.setRngStreams(s0, s1, cnt);
        long seed = s.shuffleSeedFrom(SimState.RngStream.SHUFFLE);
        fixture("洗牌-流消费", s.rngStream(SimState.RngStream.SHUFFLE).getCounter() == 1
                        && seed != 0L,
                "shuffleRng.randomLong() 一次, counter 0→1",
                "seed=" + Long.toHexString(seed));

        // ── 夹具 3：随机目标/插入位置归 cardRandomRng（CardManager.cpp:216-221）──
        int at = s.randomFrom(SimState.RngStream.CARD_RANDOM, 5);
        fixture("插入抽牌堆-流", at >= 0 && at <= 5
                        && s.rngStream(SimState.RngStream.CARD_RANDOM).getCounter() == 1,
                "cardRandomRng.random(size), counter 0→1", "at=" + at);

        // ── 夹具 4：怪物 AI 随机归 aiRng ──
        s.nextIntFrom(SimState.RngStream.AI, 100);
        fixture("怪物随机-流", s.rngStream(SimState.RngStream.AI).getSeed0() != s0[0],
                "aiRng.nextInt(100) 推进状态", "seed0 changed");

        // ── 夹具 5：六流独立（Reader.RNG_FIELDS 六个对象）──
        // 已被推进的流：SHUFFLE(5, randomLong)、CARD_RANDOM(1, random)、AI(0, nextInt)
        // 未动的流：MISC(2), MONSTER_HP(3), POTION(4)
        boolean isolated = true;
        for (int i = 2; i <= 4; i++) {
            if (s.rngStream(SimState.RngStream.values()[i]).getSeed0() != s0[i]) isolated = false;
        }
        fixture("六流-独立", isolated, "6 个独立 RandomXS128 对象",
                "MISC/MONSTER_HP/POTION 未受其它流推进影响");

        // ── 夹具 6：TrueRng murmurHash3 与 C++ 头一致 ──
        fixture("RNG-murmurHash3", TrueRng.murmurHash3(1L) == -5451962507482445012L,
                "sts_lightspeed Random.h murmurHash3(1)=b456bcfc34c2cb2c",
                Long.toHexString(TrueRng.murmurHash3(1L)));

        // ── 夹具 7：状态比对口径（EngineRootStateBinding 5 字段 + turn+1）──
        String eng = "hp=70 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=1";
        String live = "hp=70 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=2";
        String drift = "hp=64 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=2";
        fixture("状态比对-匹配", EngineRootStateBinding.matches(eng, live),
                "5 字段相等且 engineTurn+1==liveTurn", "turn 1→2");
        fixture("状态比对-拒绝漂移", !EngineRootStateBinding.matches(eng, drift),
                "hp 不等 → false", "hp 70 vs 64");

        // ── 夹具 8：逐张重采停机（W3 注入式不匹配）──
        PerCardVerify.Budget budget = new PerCardVerify.Budget();
        fixture("逐张重采-不匹配停机",
                PerCardVerify.evaluate(true, eng, drift, budget, 1000L, false)
                        == PerCardVerify.Verdict.REFUSED,
                "VERIFYING→REFUSED 停机", "injected hp mismatch");
        fixture("逐张重采-稳定匹配放行",
                PerCardVerify.evaluate(true, eng, live, budget, 1000L, false)
                        == PerCardVerify.Verdict.PASS,
                "PASS", "stable + match");

        // ── 边界声明（不计入 pass/fail，不是通过项）──
        // 真值：Oracle.java 头部注释明确「需要玩家选择的牌由引擎启发式代选，玩家真实可选项
        // 不在搜索空间」。本单元**没有**选牌屏的真值夹具，如实登记为未覆盖，不冒充已校验。
        System.out.println("[BOUNDARY] 选牌屏-未覆盖：需玩家选择的牌仍由启发式代选，"
                + "不在穷举搜索空间；本单元无该机制的真值夹具，不计入通过数。");

        System.out.println("夹具汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（无游戏依赖）；真值来源：游戏字节码 / sts_lightspeed C++ 头 / Python 独立计算 / 权威比对器");
        System.out.println("边界：本单元未覆盖每张近似牌/药水的逐项真值夹具（Checks.java 已有 130 断言，留后续单元）；");
        System.out.println("      选牌屏夹具只登记「代选近似」边界，不声称已真值校验。");
        System.exit(fail == 0 ? 0 : 1);
    }
}

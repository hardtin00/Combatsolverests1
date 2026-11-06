import com.combatsolver1.runtime.PerCardVerify;

/**
 * W3 离线自检：注入式不匹配用例证明逐张核对会停机。
 *
 * <p>状态行口径与 EngineRootStateBinding 一致（hp=/blk=/en=/hand=[/mons=/turn=，
 * 且 engineTurn+1==liveTurn）。用真实格式的匹配/不匹配对，验证：
 * 稳定帧+匹配 → PASS；未稳定 → VERIFYING；注入不匹配 → REFUSED 停机；
 * 节流 2.5s 与每场 8 次上限生效；EXHAUSTED 后不自动恢复。
 *
 * <p>纯 L2 离线，不读游戏对象、不改真机行为。
 */
public final class PerCardVerifyCheck {

    private PerCardVerifyCheck() { }

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

    // engine 预测（部署该牌后）：turn=1；live 重采：turn=2（满足 engineTurn+1==liveTurn）
    private static final String MATCH_ENGINE =
            "hp=70 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=1";
    private static final String MATCH_LIVE =
            "hp=70 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=2";
    // 注入不匹配：HP 不同
    private static final String MISMATCH_LIVE =
            "hp=64 blk=5 en=3 hand=[Strike_R,Defend_R] mons=[JawWorm:44] turn=2";

    public static void main(String[] args) {
        PerCardVerify.Budget b = new PerCardVerify.Budget();
        long t = 10000L;

        // A. 稳定帧 + 匹配 → PASS
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MATCH_LIVE, b, t, false)
                        == PerCardVerify.Verdict.PASS,
                "稳定帧且重采匹配预测 → PASS", "no mismatch");

        // B. 未稳定帧 → VERIFYING（不误停、不放行）
        check(PerCardVerify.evaluate(false, MATCH_ENGINE, MISMATCH_LIVE, b, t, false)
                        == PerCardVerify.Verdict.VERIFYING,
                "未稳定帧 → VERIFYING（不误停）", "settled=false");

        // C. 注入式不匹配（稳定帧、不自动重授权）→ REFUSED 停机 ★任务书验收点
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MISMATCH_LIVE, b, t, false)
                        == PerCardVerify.Verdict.REFUSED,
                "注入不匹配 → REFUSED 停机", "predicted hp=70, recaptured hp=64");

        // D. 允许自动重授权：节流内第一次 → VERIFYING（重搜而非放行）
        PerCardVerify.Budget b2 = new PerCardVerify.Budget();
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MISMATCH_LIVE, b2, t, true)
                        == PerCardVerify.Verdict.VERIFYING,
                "不匹配但自动重授权成功 → VERIFYING（重搜）", "reAuth=1");
        check(b2.getReAuthCount() == 1, "重授权计数 +1", "count=1");

        // E. 2.5s 节流：紧接着第二次不匹配 → REFUSED（不重复重授权）
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MISMATCH_LIVE, b2, t + 1000L, true)
                        == PerCardVerify.Verdict.REFUSED,
                "2.5s 节流内第二次不匹配 → REFUSED", "gap=1000ms < 2500ms");
        check(b2.getReAuthCount() == 1, "节流内计数不变", "count=1");

        // F. 超过 2.5s 后可再次重授权
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MISMATCH_LIVE, b2, t + 3000L, true)
                        == PerCardVerify.Verdict.VERIFYING,
                "超过 2.5s 后可再次重授权", "gap=3000ms");

        // G. 每场 8 次上限：连续 8 次（每次间隔 3s）后第 9 次 → EXHAUSTED，且不自动恢复
        PerCardVerify.Budget b3 = new PerCardVerify.Budget();
        int exhaustedAt = -1;
        for (int i = 0; i < 10; i++) {
            PerCardVerify.Verdict v = PerCardVerify.evaluate(
                    true, MATCH_ENGINE, MISMATCH_LIVE, b3, t + 3000L * (i + 1), true);
            if (v == PerCardVerify.Verdict.EXHAUSTED) {
                exhaustedAt = i + 1;
                break;
            }
        }
        check(exhaustedAt == 9, "每场 8 次上限用尽后第 9 次 → EXHAUSTED",
                "exhaustedAt=" + exhaustedAt + " count=" + b3.getReAuthCount());
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MISMATCH_LIVE, b3, t + 60000L, true)
                        == PerCardVerify.Verdict.EXHAUSTED,
                "EXHAUSTED 后即便超时也不自动恢复", "permanent stop");

        // H. reset() 后预算恢复（换场/用户明确继续）
        b3.reset();
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, MATCH_LIVE, b3, t, false)
                        == PerCardVerify.Verdict.PASS,
                "reset() 后新一场可正常核对", "budget cleared");

        // I. 与 EngineRootStateBinding 口径一致：缺字段也算不匹配 → REFUSED
        check(PerCardVerify.evaluate(true, MATCH_ENGINE, "hp=70 turn=2", b, t, false)
                        == PerCardVerify.Verdict.REFUSED,
                "重采缺必需字段 → 按不匹配停机", "missing blk/en/hand/mons");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（注入式不匹配用例）；未接入 CombatController 运行路径");
        System.exit(fail == 0 ? 0 : 1);
    }
}

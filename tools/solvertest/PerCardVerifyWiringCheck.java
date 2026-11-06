import com.combatsolver1.runtime.EngineRootStateBinding;
import com.combatsolver1.runtime.PerCardVerify;

/**
 * W3 接入前置自检（L2 离线）：验证「引擎 STATE 行 / liveStateLine 行」喂进
 * {@link PerCardVerify#evaluate} 能正确判定，明确哪些 Plan 字段可作预测状态。
 *
 * <p>真值来源（本会话已核验）：
 * <ul>
 *   <li>{@code CombatController.liveStateLine()} = "STATE hp=.. blk=.. en=.. turn=.. hand=[..] mons=.."</li>
 *   <li>{@code EngineBridge:353} 写 {@code Plan.Turn.endState = s}（引擎 STATE 行）→ 可作预测；</li>
 *   <li>{@code CombatSearch:90} 写 {@code stateSignature = SimState.signature()}（紧凑键）
 *       → **不兼容** {@code EngineRootStateBinding}，不能喂给 evaluate。</li>
 * </ul>
 * 本自检固化「哪些能用、哪些不能用」，避免接入时误用字段（AGENTS.md 4.3）。
 * 本单元**不接入运行路径**、不改生产代码，W0 指标不变。
 */
public final class PerCardVerifyWiringCheck {

    private PerCardVerifyWiringCheck() { }

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

    // 引擎 STATE 行（预测，turn=1）与 liveStateLine 行（重采，turn=2）
    // EngineRootStateBinding 要求 engineTurn + 1 == liveTurn
    private static final String PREDICTED =
            "STATE hp=70 blk=5 en=3 turn=1 hand=[Strike_R,Defend_R] mons=[JawWorm:44]";
    private static final String RECAPTURED_MATCH =
            "STATE hp=70 blk=5 en=3 turn=2 hand=[Strike_R,Defend_R] mons=[JawWorm:44]";
    private static final String RECAPTURED_DRIFT =
            "STATE hp=64 blk=5 en=3 turn=2 hand=[Strike_R,Defend_R] mons=[JawWorm:44]";

    public static void main(String[] args) {
        // A. 格式兼容性：liveStateLine 格式确实能被权威比对器接受
        check(EngineRootStateBinding.matches(PREDICTED, RECAPTURED_MATCH),
                "引擎 STATE 行 / liveStateLine 行格式与权威比对器兼容", "turn 1->2");
        check(!EngineRootStateBinding.matches(PREDICTED, RECAPTURED_DRIFT),
                "hp 漂移时权威比对器判 false", "hp 70 vs 64");

        // B. evaluate 用真实格式：稳定帧+匹配 -> PASS
        PerCardVerify.Budget b1 = new PerCardVerify.Budget();
        check(PerCardVerify.evaluate(true, PREDICTED, RECAPTURED_MATCH, b1, 1000L, false)
                        == PerCardVerify.Verdict.PASS,
                "稳定帧+匹配 -> PASS（继续下一动作）", "no mismatch");

        // C. 未稳定帧 -> VERIFYING（不误停、不放行）
        PerCardVerify.Budget b2 = new PerCardVerify.Budget();
        check(PerCardVerify.evaluate(false, PREDICTED, RECAPTURED_DRIFT, b2, 1000L, false)
                        == PerCardVerify.Verdict.VERIFYING,
                "未稳定帧 -> VERIFYING", "settled=false");

        // D. 稳定帧但不匹配、不自动重授权 -> REFUSED 停机（W3 验收点）
        PerCardVerify.Budget b3 = new PerCardVerify.Budget();
        check(PerCardVerify.evaluate(true, PREDICTED, RECAPTURED_DRIFT, b3, 1000L, false)
                        == PerCardVerify.Verdict.REFUSED,
                "注入式不匹配 -> REFUSED 停机", "predicted hp=70 / recaptured hp=64");

        // E. 自动重授权节流：2.5s 内第二次不匹配仍 REFUSED，超 2.5s 可再授权
        PerCardVerify.Budget b4 = new PerCardVerify.Budget();
        PerCardVerify.Verdict v1 = PerCardVerify.evaluate(
                true, PREDICTED, RECAPTURED_DRIFT, b4, 1000L, true);
        PerCardVerify.Verdict v2 = PerCardVerify.evaluate(
                true, PREDICTED, RECAPTURED_DRIFT, b4, 2000L, true);
        PerCardVerify.Verdict v3 = PerCardVerify.evaluate(
                true, PREDICTED, RECAPTURED_DRIFT, b4, 4000L, true);
        check(v1 == PerCardVerify.Verdict.VERIFYING && v2 == PerCardVerify.Verdict.REFUSED
                        && v3 == PerCardVerify.Verdict.VERIFYING,
                "2.5s 节流 + 每场 8 次上限生效", "t=1s/2s/4s -> VERIFYING/REFUSED/VERIFYING");

        // F. 每场 8 次上限用尽 -> EXHAUSTED 且不自动恢复
        PerCardVerify.Budget b5 = new PerCardVerify.Budget();
        int exhaustedAt = -1;
        for (int i = 0; i < 10; i++) {
            if (PerCardVerify.evaluate(true, PREDICTED, RECAPTURED_DRIFT,
                    b5, 10000L + 3000L * i, true) == PerCardVerify.Verdict.EXHAUSTED) {
                exhaustedAt = i + 1;
                break;
            }
        }
        check(exhaustedAt == 9, "每场 8 次上限用尽后第 9 次 EXHAUSTED", "exhaustedAt=9");
        check(PerCardVerify.evaluate(true, PREDICTED, RECAPTURED_MATCH, b5, 60000L, true)
                        == PerCardVerify.Verdict.EXHAUSTED,
                "EXHAUSTED 后即便状态匹配也不自动恢复（需 reset）", "permanent stop");

        // G. 明确「stateSignature 不兼容」：紧凑键喂进去会被判不匹配（不是 PASS）
        String compactKey = "hp70|blk5|en3|turn2|hand2|mons1";
        check(!EngineRootStateBinding.matches(PREDICTED, compactKey),
                "SimState.signature() 紧凑键不兼容，不能当预测状态", "compactKey rejected");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（接入前置）；PerCardVerify 仍未接入 CombatController 运行路径");
        System.exit(fail == 0 ? 0 : 1);
    }
}

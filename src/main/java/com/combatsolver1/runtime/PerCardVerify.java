package com.combatsolver1.runtime;

/**
 * W3：逐张牌部署后的「重采 vs 预测」判定器（纯逻辑，可离线注入用例）。
 *
 * <p>任务书 W3：部署每张牌后稳定帧重采与预测 diff（与 EngineRootStateBinding 对齐），
 * 不符 → VERIFYING → REFUSED 停机；自动重授权节流（2.5s、每场上限）。
 *
 * <p>本类**只做判定**，不读游戏对象、不写 session 状态、不发搜索请求。
 * 状态比对严格复用 {@link EngineRootStateBinding#matches}，不另起一套（AGENTS.md 8.4）。
 * 接入 CombatController 的逐动作调用点属于下一单元（会改变真机行为，单独验收）。
 */
public final class PerCardVerify {

    private PerCardVerify() { }

    public enum Verdict {
        /** 稳定帧且重采与预测一致：继续下一动作。 */
        PASS,
        /** 尚未到稳定帧：保持 VERIFYING，等待下一帧重采。 */
        VERIFYING,
        /** 稳定帧但重采与预测不一致：REFUSED，停机。 */
        REFUSED,
        /** 本场重授权次数已用尽：停机，不允许自动恢复。 */
        EXHAUSTED
    }

    /** 任务书 W3：2.5s 节流、每场上限。 */
    public static final long REAUTH_GAP_MS = 2500L;
    public static final int MAX_REAUTH_PER_BATTLE = 8;

    /** 一场战斗内的节流状态；由调用方每场新建/fork。 */
    public static final class Budget {
        private long lastReAuthAt = Long.MIN_VALUE;
        private int reAuthCount;
        private boolean refused;

        public boolean isRefused() { return refused; }
        public int getReAuthCount() { return reAuthCount; }

        /**
         * 尝试自动重授权。仅在「距上次 ≥2.5s 且本场未超上限」时允许，
         * 且一旦本场被判 EXHAUSTED 就永久停机（不自动恢复）。
         */
        public boolean tryReAuth(long nowMs) {
            if (refused) return false;
            if (reAuthCount >= MAX_REAUTH_PER_BATTLE) {
                refused = true;
                return false;
            }
            if (lastReAuthAt != Long.MIN_VALUE && nowMs - lastReAuthAt < REAUTH_GAP_MS) {
                return false;
            }
            lastReAuthAt = nowMs;
            reAuthCount++;
            return true;
        }

        /** 人工/显式授权重置本场预算（换场或用户明确继续时调用）。 */
        public void reset() {
            lastReAuthAt = Long.MIN_VALUE;
            reAuthCount = 0;
            refused = false;
        }
    }

    /**
     * 单个动作后的判定。
     *
     * @param settled      当前是否稳定帧（动作队列空、无 pending action）
     * @param predicted    引擎预测的状态行（部署该牌后）
     * @param recaptured   稳定帧重采的状态行
     * @param budget       本场节流预算
     * @param nowMs        当前时间（用于节流）
     * @param autoReAuth   是否允许自动重授权（false = 人工逐次授权）
     */
    public static Verdict evaluate(boolean settled, String predicted, String recaptured,
                                   Budget budget, long nowMs, boolean autoReAuth) {
        if (budget == null) budget = new Budget();
        if (budget.isRefused()) return Verdict.EXHAUSTED;
        if (!settled) return Verdict.VERIFYING;
        if (EngineRootStateBinding.matches(predicted, recaptured)) {
            return Verdict.PASS;
        }
        // 不匹配：先尝试节流内自动重授权；不允许或预算耗尽则 REFUSED/EXHAUSTED 停机。
        if (autoReAuth && budget.tryReAuth(nowMs)) {
            return Verdict.VERIFYING;   // 重搜后继续核对，不放行旧路线
        }
        if (budget.getReAuthCount() >= MAX_REAUTH_PER_BATTLE) {
            budget.refused = true;
            return Verdict.EXHAUSTED;
        }
        return Verdict.REFUSED;
    }
}

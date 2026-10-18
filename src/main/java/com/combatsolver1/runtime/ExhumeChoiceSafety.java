package com.combatsolver1.runtime;

import com.combatsolver1.search.Plan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fail-closed identity matching and non-blocking state machine for the native Exhume grid. */
final class ExhumeChoiceSafety {
    private ExhumeChoiceSafety() { }

    static boolean supports(Plan.Action action, String planSource) {
        return "engine".equals(planSource) && action != null && action.isSelect()
                && action.selectProtocolVersion == 3 && "EXHUME".equals(action.selectTask)
                && "exhaust".equals(action.selectSource) && !action.selectMulti && !action.selectSkip
                && action.selectCandidates.size() > 0 && action.selectCards.size() == 1;
    }

    static Plan.ChoiceCard descriptor(String id, int upgrades, int cost) {
        return new Plan.ChoiceCard(id, upgrades, cost);
    }

    static int nativeEnergyCost(int costForTurn, boolean freeToPlay, boolean corruptionSkill, int energy) {
        if (freeToPlay || corruptionSkill || costForTurn == -9) return 0;
        return costForTurn < 0 ? Math.max(0, energy) : Math.max(0, costForTurn);
    }

    static final class Match {
        final Plan.ChoiceCard selected;
        final int liveIndex;
        final String failure;
        Match(Plan.ChoiceCard selected, int liveIndex, String failure) {
            this.selected = selected;
            this.liveIndex = liveIndex;
            this.failure = failure;
        }
        boolean matched() { return selected != null; }
    }

    /** Order may differ, but the complete legal candidate multiset must match. */
    static Match match(Plan.Action action, List<Plan.ChoiceCard> liveCandidates) {
        if (action == null || !action.isSelect() || action.selectProtocolVersion != 3
                || !"EXHUME".equals(action.selectTask) || !"exhaust".equals(action.selectSource)
                || action.selectMulti || action.selectSkip || action.selectCandidates.isEmpty()
                || action.selectCards.size() != 1 || liveCandidates == null) {
            return new Match(null, -1, "Exhume 选择协议缺少可信候选身份");
        }
        Map<String, Integer> expected = counts(action.selectCandidates);
        Map<String, Integer> actual = counts(liveCandidates);
        if (!expected.equals(actual)) return new Match(null, -1, "原生消耗堆候选与计划不一致");
        Plan.ChoiceCard selected = action.selectCards.get(0);
        String key = key(selected);
        if (expected.get(key) == null || expected.get(key) != 1 || actual.get(key) != 1) {
            return new Match(null, -1, "所选卡牌实例身份不唯一");
        }
        int liveIndex = -1;
        for (int i = 0; i < liveCandidates.size(); i++) {
            if (key(liveCandidates.get(i)).equals(key(selected))) liveIndex = i;
        }
        if (liveIndex < 0) return new Match(null, -1, "所选卡牌实例已不存在");
        return new Match(selected, liveIndex, null);
    }

    private static Map<String, Integer> counts(List<? extends Plan.ChoiceCard> cards) {
        Map<String, Integer> result = new HashMap<String, Integer>();
        for (Plan.ChoiceCard card : cards) {
            if (card == null || card.id == null) return new HashMap<String, Integer>();
            String key = key(card);
            Integer count = result.get(key);
            result.put(key, count == null ? 1 : count + 1);
        }
        return result;
    }

    private static String key(Plan.ChoiceCard card) {
        return card.id + "\u0000" + card.upgrades + "\u0000" + card.cost;
    }

    enum Phase { WAITING_FOR_SCREEN, PRESS_PENDING, RELEASE_PENDING, WAITING_FOR_RESULT,
        COMPLETE, FAILED, CANCELLED }
    enum Pulse { NONE, PRESS, RELEASE }

    static final class Gate {
        static final long TIMEOUT_MILLIS = 10000L;
        private final long deadline;
        private boolean nativeMayResolveWithoutScreen;
        private Phase phase = Phase.WAITING_FOR_SCREEN;
        private String failure;
        private int idleWithoutScreen;

        Gate(long now, boolean nativeMayResolveWithoutScreen) {
            deadline = now + TIMEOUT_MILLIS;
            this.nativeMayResolveWithoutScreen = nativeMayResolveWithoutScreen;
        }

        void allowNativeAutoResolution(boolean allowed) { nativeMayResolveWithoutScreen = allowed; }

        Phase phase() { return phase; }
        String failure() { return failure; }
        boolean done() { return phase == Phase.COMPLETE || phase == Phase.FAILED || phase == Phase.CANCELLED; }

        void observeScreen(boolean open, boolean expectedExhumeGrid, boolean poolMatches,
                           boolean preselected, long now) {
            if (done()) return;
            if (expired(now)) return;
            if (!open) return;
            if (!expectedExhumeGrid) { fail("当前界面不是预期的 Exhume 消耗堆选择"); return; }
            if (!poolMatches) { fail("原生消耗堆候选与计划不一致"); return; }
            if (preselected) { fail("检测到玩家已改变选牌状态"); return; }
            idleWithoutScreen = 0;
            if (phase == Phase.WAITING_FOR_SCREEN) phase = Phase.PRESS_PENDING;
        }

        Pulse beforeGridUpdate(boolean expectedExhumeGrid, boolean poolMatches,
                               boolean externalInput, boolean cancelRequested, long now) {
            if (done()) return Pulse.NONE;
            if (expired(now)) return Pulse.NONE;
            if (cancelRequested) { phase = Phase.CANCELLED; failure = "用户取消选牌执行"; return Pulse.NONE; }
            if (phase != Phase.PRESS_PENDING && phase != Phase.RELEASE_PENDING) return Pulse.NONE;
            if (!expectedExhumeGrid) { fail("选牌界面在选择期间发生变化"); return Pulse.NONE; }
            if (!poolMatches) { fail("原生消耗堆候选在选择期间发生变化"); return Pulse.NONE; }
            if (externalInput) { fail("检测到玩家手动操作选牌界面"); return Pulse.NONE; }
            if (phase == Phase.PRESS_PENDING) {
                phase = Phase.RELEASE_PENDING;
                return Pulse.PRESS;
            }
            phase = Phase.WAITING_FOR_RESULT;
            return Pulse.RELEASE;
        }

        void observeResult(boolean screenOpen, boolean gameAlive, boolean stable,
                           boolean resultMatches, long now) {
            if (done()) return;
            if (expired(now)) return;
            if (!gameAlive) { fail("战斗结束或画面切换，停止 Exhume 路线"); return; }
            if (screenOpen) {
                if (phase == Phase.WAITING_FOR_RESULT) return;
                return;
            }
            if (!stable) return;
            idleWithoutScreen++;
            if (resultMatches && (phase == Phase.WAITING_FOR_RESULT || nativeMayResolveWithoutScreen)) {
                phase = Phase.COMPLETE;
                return;
            }
            if (phase != Phase.WAITING_FOR_SCREEN || idleWithoutScreen >= 3) {
                fail(phase == Phase.WAITING_FOR_SCREEN ? "预期的 Exhume 选牌界面未打开" : "Exhume 选择后状态与计划不一致");
            }
        }

        void cancel() {
            if (!done()) { phase = Phase.CANCELLED; failure = "用户取消选牌执行"; }
        }

        private boolean expired(long now) {
            if (now <= deadline) return false;
            fail("Exhume 原生选牌等待超时");
            return true;
        }

        private void fail(String reason) { phase = Phase.FAILED; failure = reason; }
    }
}

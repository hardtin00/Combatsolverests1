package com.combatsolver1.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Offline failure injection for execution authorization and cancellation gates. */
public final class ExecutionSafetyCheck {
    private static int passed;

    public static void main(String[] args) {
        fetchFromBeforeManualActionIsRejected();
        dirtyJournalRejectsPendingReplayFetch();
        onlyCurrentTurnCanExecute();
        staleOrUnvalidatedPlanIsRejected();
        pauseAndSuppressionRejectExecution();
        repeatedKeyHoldDoesNotCreateRepeatedRequest();
        explicitGAndFullAutoHCanExecuteValidatedPlan();
        repeatedAutomaticFailureIsLatched();
        deadAndShiftedTargetsAreRejected();
        compactSlotRegression();
        cancellationRemovesOnlyOwnedQueuedActions();
        System.out.println("ExecutionSafetyCheck passed=" + passed + " failed=0");
    }

    private static void fetchFromBeforeManualActionIsRejected() {
        check(!ExecutionSafety.fetchMatchesJournal(0, 1), "stale initial engine fetch was accepted after a manual action");
        check(ExecutionSafety.fetchMatchesJournal(1, 1), "matching replay fetch was rejected");
        check(ExecutionSafety.fetchMatchesJournal(-1, 1), "unversioned fetch compatibility changed");
        pass();
    }

    private static void dirtyJournalRejectsPendingReplayFetch() {
        check(!ExecutionSafety.canAdoptReplayFetch(false, 4, 4),
                "pending engine replay based on a dirty journal was adopted");
        check(ExecutionSafety.canAdoptReplayFetch(true, 4, 4),
                "fresh replay from a clean journal was rejected");
        check(!ExecutionSafety.canAdoptReplayFetch(true, 3, 4),
                "stale replay from an older journal was adopted");
        pass();
    }

    private static void onlyCurrentTurnCanExecute() {
        check(ExecutionSafety.turnMatches(2, 2), "current turn rejected");
        check(!ExecutionSafety.turnMatches(3, 2), "future turn accepted");
        check(!ExecutionSafety.turnMatches(1, 2), "expired turn accepted");
        check(ExecutionSafety.turnMatches(-1, 2), "legacy missing turn rejected");
        pass();
    }

    private static void staleOrUnvalidatedPlanIsRejected() {
        check(!may(true, false, false, false, false, true, false, "s", "s"), "unvalidated plan accepted");
        check(!may(true, true, true, false, false, true, false, "s", "s"), "stale plan accepted");
        check(!may(true, true, false, false, false, true, false, "old", "new"), "changed live signature accepted");
        pass();
    }

    private static void pauseAndSuppressionRejectExecution() {
        check(!may(true, true, false, false, true, true, false, "s", "s"), "paused plan accepted");
        check(!may(true, true, false, true, false, false, true, "s", "s"), "suppressed auto plan accepted");
        check(!may(true, true, false, false, false, false, false, "s", "s"), "plan executed without G or H");
        check(!may(true, true, false, false, false, true, false, null, "s"), "plan executed without a validation signature");
        pass();
    }

    private static void explicitGAndFullAutoHCanExecuteValidatedPlan() {
        check(may(true, true, false, false, false, true, false, "s", "s"), "valid explicit G was rejected");
        check(may(true, true, false, false, false, false, true, "s", "s"), "valid full-auto H was rejected");
        pass();
    }

    private static void repeatedAutomaticFailureIsLatched() {
        ExecutionSafety.FailureLatch latch = new ExecutionSafety.FailureLatch();
        latch.block("turn4|same-root");
        boolean fullAuto = false; // production closes H after an invalidated deployment
        int deployments = 0;
        for (int i = 0; i < 200; i++) {
            latch.observe("turn4|same-root");
            fullAuto = true; // fault injection: repeated re-plan/reuse tries to resume H
            latch.authorize();
            check(latch.isBlocked(), "same-state retry " + i + " escaped the failure latch");
            if (may(true, true, false, false, false, false, fullAuto, "turn4|same-root", "turn4|same-root")
                    && !latch.isBlocked()) deployments++;
        }
        check(deployments == 0, "same-state failed route was deployed " + deployments + " more times");
        latch.observe("turn5|new-stable-root");
        check(latch.isBlocked(), "natural state transition bypassed the fresh authorization requirement");
        latch.authorize();
        check(!latch.isBlocked(), "fresh authorization after verified state change did not release the latch");
        if (may(true, true, false, false, false, false, true,
                "turn5|new-stable-root", "turn5|new-stable-root") && !latch.isBlocked()) deployments++;
        check(deployments == 1, "a fresh state and H authorization did not permit exactly one deployment");
        pass();
    }

    private static void repeatedKeyHoldDoesNotCreateRepeatedRequest() {
        check(ExecutionSafety.risingEdge(false, true), "first G/H key-down was lost");
        check(!ExecutionSafety.risingEdge(true, true), "held G/H key generated a duplicate request");
        check(!ExecutionSafety.risingEdge(true, false), "key release generated a request");
        check(ExecutionSafety.risingEdge(false, true), "new key press after release was lost");
        pass();
    }

    private static void deadAndShiftedTargetsAreRejected() {
        check(ExecutionSafety.targetSlotUsable(1, 2, true), "valid selected slot rejected");
        check(!ExecutionSafety.targetSlotUsable(1, 2, false), "dead requested target was accepted");
        check(!ExecutionSafety.targetSlotUsable(2, 2, true), "out-of-range slot was accepted after roster change");
        check(!ExecutionSafety.targetSlotUsable(-1, 2, true), "missing target fell through to another monster");
        pass();
    }

    private static void compactSlotRegression() {
        Object aliveA = new Object(), deadB = new Object(), aliveC = new Object();
        List<Object> full = new ArrayList<>();
        full.add(aliveA); full.add(deadB); full.add(aliveC);
        int localTargetIndex = 1;
        ExecutionSafety.TargetSlots<Object> local = ExecutionSafety.TargetSlots.capture(
                "local", full, target -> target != deadB);
        check(local.resolve(full, localTargetIndex, target -> target != deadB) == aliveC,
                "local compact index 1 selected dead native slot 1 instead of living C");
        check(ExecutionSafety.TargetSlots.fixedIndex(full, aliveC) == 2,
                "local potion target was not encoded as native fixed slot 2");
        ExecutionSafety.TargetSlots<Object> engine = ExecutionSafety.TargetSlots.capture(
                "engine", full, target -> target != deadB);
        check(engine.resolve(full, 2, target -> target != deadB) == aliveC,
                "engine fixed target 2 changed meaning");
        check(engine.resolve(full, 1, target -> target != deadB) == null,
                "engine fixed target 1 fell through to living C");
        check(local.resolve(full, 2, target -> target != deadB) == null,
                "out-of-range local target was accepted");
        check(ExecutionSafety.TargetSlots.capture("unknown", full, target -> true) == null,
                "unknown source guessed an index space");
        List<Object> shifted = new ArrayList<>();
        shifted.add(aliveA); shifted.add(aliveC);
        check(local.resolve(shifted, 1, target -> true) == aliveC,
                "earlier monster removal changed the selected object");
        check(ExecutionSafety.TargetSlots.fixedIndex(shifted, aliveC) == 1,
                "potion journal did not follow the current native slot");
        check(local.resolve(shifted, 1, target -> target != aliveC) == null,
                "dead or escaped selected object was silently replaced");
        check(local.resolve(Collections.singletonList(aliveA), 1, target -> true) == null,
                "missing original object was silently replaced");
        class Named { final String name; Named(String n) { name = n; } }
        Named first = new Named("Sentry"), second = new Named("Sentry");
        List<Named> sameName = new ArrayList<>();
        sameName.add(first); sameName.add(second);
        ExecutionSafety.TargetSlots<Named> duplicates = ExecutionSafety.TargetSlots.capture(
                "local", sameName, target -> true);
        check(duplicates.resolve(sameName, 1, target -> true) == second,
                "same-name targets were matched by name instead of identity");
        pass();
    }

    private static void cancellationRemovesOnlyOwnedQueuedActions() {
        Object planned = new Object(), unrelated = new Object();
        List<Object> queue = new ArrayList<>();
        queue.add(planned); queue.add(unrelated);
        Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        owned.add(planned);
        check(ExecutionSafety.isOwnedByIdentity(owned, planned), "current owned action was not identified for cancellation");
        check(!ExecutionSafety.isOwnedByIdentity(owned, unrelated), "unrelated current action was classified as owned");
        int removed = ExecutionSafety.removeOwnedByIdentity(queue, owned);
        check(removed == 1 && queue.size() == 1 && queue.get(0) == unrelated,
                "cancel did not remove exactly the scheduled action objects");
        List<String> cardQueue = new ArrayList<>();
        cardQueue.add("planned-card"); cardQueue.add("manual-card");
        int cardsRemoved = ExecutionSafety.removeMatching(cardQueue, "planned-card"::equals);
        check(cardsRemoved == 1 && cardQueue.equals(Collections.singletonList("manual-card")),
                "cancel did not preserve unrelated card queue items");
        pass();
    }

    private static boolean may(boolean plan, boolean validated, boolean stale, boolean suppressed,
                               boolean paused, boolean requested, boolean fullAuto, String checked, String live) {
        return ExecutionSafety.mayExecute(plan, validated, stale, suppressed, paused,
                requested, fullAuto, checked, live);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass() { passed++; }
}

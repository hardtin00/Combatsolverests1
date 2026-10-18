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
        onlyCurrentTurnCanExecute();
        staleOrUnvalidatedPlanIsRejected();
        pauseAndSuppressionRejectExecution();
        repeatedKeyHoldDoesNotCreateRepeatedRequest();
        explicitGAndFullAutoHCanExecuteValidatedPlan();
        deadAndShiftedTargetsAreRejected();
        cancellationRemovesOnlyOwnedQueuedActions();
        System.out.println("ExecutionSafetyCheck passed=" + passed + " failed=0");
    }

    private static void fetchFromBeforeManualActionIsRejected() {
        check(!ExecutionSafety.fetchMatchesJournal(0, 1), "stale initial engine fetch was accepted after a manual action");
        check(ExecutionSafety.fetchMatchesJournal(1, 1), "matching replay fetch was rejected");
        check(ExecutionSafety.fetchMatchesJournal(-1, 1), "unversioned fetch compatibility changed");
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

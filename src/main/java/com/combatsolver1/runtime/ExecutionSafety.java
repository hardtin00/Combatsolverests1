package com.combatsolver1.runtime;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Small fail-closed policies shared by live execution and offline fault-injection checks. */
final class ExecutionSafety {
    private ExecutionSafety() { }

    static boolean fetchMatchesJournal(int capturedSize, int currentSize) {
        return capturedSize < 0 || capturedSize == currentSize;
    }

    static boolean mayExecute(boolean hasPlan, boolean validated, boolean stale,
                              boolean suppressed, boolean paused, boolean requested,
                              boolean fullAuto, String validatedSignature, String liveSignature) {
        return hasPlan && validated && !stale && !suppressed && !paused
                && (requested || fullAuto)
                && validatedSignature != null && validatedSignature.equals(liveSignature);
    }

    static boolean targetSlotUsable(int index, int rosterSize, boolean alive) {
        return index >= 0 && index < rosterSize && alive;
    }

    static boolean turnMatches(int plannedTurn, int liveTurn) {
        return plannedTurn <= 0 || plannedTurn == liveTurn;
    }

    static boolean risingEdge(boolean wasDown, boolean isDown) {
        return isDown && !wasDown;
    }

    static <T> int removeMatching(List<T> queue, Predicate<T> predicate) {
        int removed = 0;
        for (Iterator<T> iterator = queue.iterator(); iterator.hasNext();) {
            if (predicate.test(iterator.next())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    static <T> int removeOwnedByIdentity(List<?> queue, Set<T> owned) {
        return removeMatching(queue, item -> isOwnedByIdentity(owned, item));
    }

    static boolean isOwnedByIdentity(Set<?> values, Object target) {
        for (Object value : values) if (value == target) return true;
        return false;
    }
}

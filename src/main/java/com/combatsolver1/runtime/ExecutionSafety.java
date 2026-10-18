package com.combatsolver1.runtime;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Small fail-closed policies shared by live execution and offline fault-injection checks. */
final class ExecutionSafety {
    private ExecutionSafety() { }

    static boolean fetchMatchesJournal(int capturedSize, int currentSize) {
        return capturedSize < 0 || capturedSize == currentSize;
    }

    static boolean canAdoptReplayFetch(boolean journalUsable, int capturedSize, int currentSize) {
        return journalUsable && fetchMatchesJournal(capturedSize, currentSize);
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

    /** A plan's target numbers belong to its source, never to an inferred current roster. */
    static final class TargetSlots<T> {
        private final boolean localCompact;
        private final List<T> initialLiving;

        private TargetSlots(boolean localCompact, List<T> initialLiving) {
            this.localCompact = localCompact;
            this.initialLiving = Collections.unmodifiableList(new ArrayList<>(initialLiving));
        }

        static <T> TargetSlots<T> capture(String source, List<T> roster, Predicate<T> alive) {
            if ("engine".equals(source)) return new TargetSlots<>(false, Collections.<T>emptyList());
            if (!"local".equals(source) || roster == null || alive == null) return null;
            List<T> living = new ArrayList<>();
            for (T item : roster) if (item != null && alive.test(item)) living.add(item);
            return new TargetSlots<>(true, living);
        }

        List<T> initialLiving() { return initialLiving; }

        T resolve(List<T> roster, int targetIndex, Predicate<T> alive) {
            if (roster == null || alive == null || targetIndex < 0) return null;
            T chosen;
            if (localCompact) {
                if (targetIndex >= initialLiving.size()) return null;
                chosen = initialLiving.get(targetIndex);
                if (fixedIndex(roster, chosen) < 0) return null;
            } else {
                if (targetIndex >= roster.size()) return null;
                chosen = roster.get(targetIndex);
            }
            return chosen != null && alive.test(chosen) ? chosen : null;
        }

        static <T> int fixedIndex(List<T> roster, T chosen) {
            if (roster == null || chosen == null) return -1;
            for (int i = 0; i < roster.size(); i++) if (roster.get(i) == chosen) return i;
            return -1;
        }
    }

    static boolean turnMatches(int plannedTurn, int liveTurn) {
        return plannedTurn <= 0 || plannedTurn == liveTurn;
    }

    static boolean risingEdge(boolean wasDown, boolean isDown) {
        return isDown && !wasDown;
    }

    /** Blocks automatic retries until a stable state change and a fresh player authorization occur. */
    static final class FailureLatch {
        private boolean blocked;
        private boolean stateChanged;
        private boolean authorized;
        private String failureState;

        void block(String stateSignature) {
            blocked = true;
            stateChanged = false;
            authorized = false;
            failureState = stateSignature;
        }

        void observe(String stableStateSignature) {
            if (!blocked || stableStateSignature == null) return;
            if (failureState == null) failureState = stableStateSignature;
            else if (!Objects.equals(failureState, stableStateSignature)) stateChanged = true;
            releaseIfReady();
        }

        void authorize() {
            if (!blocked) return;
            // Authorization made against the same failed state does not permit a retry.
            if (!stateChanged) return;
            authorized = true;
            releaseIfReady();
        }

        boolean isBlocked() { return blocked; }
        boolean hasStateChange() { return stateChanged; }

        void clear() {
            blocked = false;
            stateChanged = false;
            authorized = false;
            failureState = null;
        }

        private void releaseIfReady() {
            if (stateChanged && authorized) {
                blocked = false;
                failureState = null;
            }
        }
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

    static <T> int removeMatchingExcept(List<T> queue, Predicate<T> predicate,
                                       Predicate<T> preserve) {
        int removed = 0;
        for (Iterator<T> iterator = queue.iterator(); iterator.hasNext();) {
            T item = iterator.next();
            if (predicate.test(item) && !preserve.test(item)) {
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

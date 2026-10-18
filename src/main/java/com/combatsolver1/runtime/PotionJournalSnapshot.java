package com.combatsolver1.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Tracks real potion objects without treating an empty UI slot as a consumed potion. */
final class PotionJournalSnapshot {
    private PotionJournalSnapshot() { }

    static final class Observation<T> {
        final List<T> snapshot;
        final T missing;

        Observation(List<T> snapshot, T missing) {
            this.snapshot = snapshot;
            this.missing = missing;
        }
    }

    static <T> Observation<T> observe(List<T> previous, List<T> current,
                                      boolean playerReady, Predicate<T> realPotion) {
        // Before the first player-ready frame, load/startup may replace placeholder objects.
        if (previous == null && !playerReady) return new Observation<>(null, null);
        T missing = null;
        if (previous != null) {
            for (T candidate : previous) {
                if (candidate == null || !realPotion.test(candidate)) continue;
                boolean retained = false;
                for (T now : current) {
                    if (now == candidate) { retained = true; break; }
                }
                if (!retained) { missing = candidate; break; }
            }
        }
        return new Observation<>(new ArrayList<>(current), missing);
    }
}

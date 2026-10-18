package com.combatsolver1.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reproduces the battle-start potion-slot false dirty without loading the game. */
public final class PotionJournalSnapshotCheck {
    private static final class Potion {
        final String name;
        final boolean slot;
        Potion(String name, boolean slot) { this.name = name; this.slot = slot; }
    }

    public static void main(String[] args) {
        Potion slot = new Potion("药水栏", true);
        List<Potion> before = new ArrayList<>();
        before.add(slot);
        List<Potion> after = Collections.emptyList();
        PotionJournalSnapshot.Observation<Potion> preReady = PotionJournalSnapshot.observe(
                null, before, false, p -> !p.slot);
        check(preReady.snapshot == null && preReady.missing == null,
                "battle initialization established a potion baseline before the player could act");
        PotionJournalSnapshot.Observation<Potion> firstReady = PotionJournalSnapshot.observe(
                preReady.snapshot, before, true, p -> !p.slot);
        PotionJournalSnapshot.Observation<Potion> slotRemoved = PotionJournalSnapshot.observe(
                firstReady.snapshot, after, true, p -> !p.slot);
        check(slotRemoved.missing == null,
                "battle-start empty potion slot was marked as an unrecorded use");

        Potion real = new Potion("类固醇药水", false);
        PotionJournalSnapshot.Observation<Potion> realBefore = PotionJournalSnapshot.observe(
                null, Collections.singletonList(real), true, p -> !p.slot);
        PotionJournalSnapshot.Observation<Potion> consumed = PotionJournalSnapshot.observe(
                realBefore.snapshot, Collections.singletonList(slot), true, p -> !p.slot);
        check(consumed.missing == real, "an actual consumed potion was ignored");
        Potion sameNameOtherObject = new Potion("类固醇药水", false);
        PotionJournalSnapshot.Observation<Potion> replaced = PotionJournalSnapshot.observe(
                realBefore.snapshot, Collections.singletonList(sameNameOtherObject), true, p -> !p.slot);
        check(replaced.missing == real, "same-name replacement masked a missing potion object");
        PotionJournalSnapshot.Observation<Potion> retained = PotionJournalSnapshot.observe(
                realBefore.snapshot, Collections.singletonList(real), true, p -> !p.slot);
        check(retained.missing == null, "unchanged actual potion was marked missing");
        System.out.println("PotionJournalSnapshotCheck passed=5 failed=0");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

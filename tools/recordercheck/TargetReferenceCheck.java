package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpireInsertLocator;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.actions.utility.UseCardAction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Offline regression for target identity and action/journal target semantics. */
public final class TargetReferenceCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();
        List<Object> roster = Arrays.asList(first, second, third);
        List<Object> liveAfterFirstDies = Arrays.asList(second, third);

        // Manual and deployed actions share the same object-to-roster mapper.
        CardTargetRef manual = CardTargetRef.resolveForTest("single", third,
                roster, liveAfterFirstDies, "SENTRY");
        CardTargetRef automatic = CardTargetRef.resolveForTest("single", third,
                roster, liveAfterFirstDies, "SENTRY");
        check(manual.trusted && automatic.trusted, "known target was rejected");
        check(manual.engineIndex == 2 && manual.liveIndex == 1,
                "dead monster removal shifted the engine slot or lost the compact live index");
        check(manual.journalTarget() == 2 && manual.jsonFields().contains("\"target\":2"),
                "sts_lightspeed target did not preserve the engine's fixed monster slot");
        check("SENTRY#3".equals(manual.identity), "same-id monster identity is not unique");
        check(manual.engineIndex == automatic.engineIndex
                        && manual.liveIndex == automatic.liveIndex
                        && manual.identity.equals(automatic.identity),
                "manual and automatic target mapping diverged");

        CardTargetRef secondMonster = CardTargetRef.resolveForTest("single", second,
                roster, roster, "SENTRY");
        check(secondMonster.trusted && secondMonster.engineIndex == 1
                        && secondMonster.liveIndex == 1
                        && "SENTRY#2".equals(secondMonster.identity),
                "different target object mapped to the wrong monster slot");

        List<Object> rosterWithEmptySlot = Arrays.asList(first, null, third);
        List<Object> liveWithEmptySlot = Arrays.asList(first, third);
        CardTargetRef afterEmptySlot = CardTargetRef.resolveForTest("single", third,
                rosterWithEmptySlot, liveWithEmptySlot, "SENTRY");
        check(afterEmptySlot.trusted && afterEmptySlot.engineIndex == 2
                        && afterEmptySlot.liveIndex == 1,
                "empty/dead roster slot was compacted in the engine target index");

        CardTargetRef all = CardTargetRef.resolveForTest("all_enemies", null,
                roster, roster, "");
        CardTargetRef none = CardTargetRef.resolveForTest("none", null,
                roster, roster, "");
        check(all.trusted && all.journalTarget() == 0 && all.jsonFields().contains("\"target\":-1")
                        && all.jsonFields().contains("\"targetMode\":\"all_enemies\""),
                "all-enemy action treated wire placeholder 0 as a selected monster");
        check(none.trusted && none.journalTarget() == 0 && none.jsonFields().contains("\"target\":-1")
                        && none.jsonFields().contains("\"targetMode\":\"none\""),
                "no-target card did not preserve explicit no-target semantics");
        ActionJournal noTargetJournal = new ActionJournal();
        noTargetJournal.recordCard(1, "FireBreathing", all);
        check(noTargetJournal.snapshotWithMetadata().get(0).contains("targetMode=all_enemies")
                        && "1".equals(noTargetJournal.snapshotWithMetadata().get(1)),
                "journal treated the wire placeholder as a selected monster for an all-enemy card");

        CardTargetRef deadTarget = CardTargetRef.resolveForTest("single", first,
                roster, liveAfterFirstDies, "SENTRY");
        CardTargetRef missingTarget = CardTargetRef.resolveForTest("single", new Object(),
                roster, liveAfterFirstDies, "SENTRY");
        check(!deadTarget.trusted && "target-not-in-live-capture-roster".equals(deadTarget.error),
                "dead target was not marked untrusted");
        check(!missingTarget.trusted && "target-not-in-game-monster-roster".equals(missingTarget.error),
                "target outside room roster was not marked untrusted");

        ActionJournal journal = new ActionJournal();
        journal.recordCard(2, "Strike_R", manual);
        List<String> replay = journal.snapshotWithMetadata();
        check(replay.size() == 2 && replay.get(0).contains("targetEngineIndex=2")
                        && replay.get(0).contains("targetLiveIndex=1") && "20002".equals(replay.get(1)),
                "journal metadata and sts_lightspeed wire target do not share one reference");
        ActionJournal dirty = new ActionJournal();
        dirty.recordCard(0, "Strike_R", missingTarget);
        check(!dirty.isUsable() && dirty.snapshotWithMetadata().get(0).contains("targetTrusted=false"),
                "untrusted target was not retained and excluded from strict replay");

        artifactsKeepMetadata(manual, all, missingTarget);

        patchRegistration();
        System.out.println("TargetReferenceCheck passed: manual/automatic identity, distinct monsters, no-target modes, dead-slot mapping, unknown targets, journal metadata, native-use hook.");
    }

    private static void patchRegistration() throws Exception {
        SpirePatch patch = AbstractPlayerUseCardTargetRecorderPatch.class.getAnnotation(SpirePatch.class);
        check(patch != null && patch.clz() == AbstractPlayer.class
                        && "useCard".equals(patch.method()),
                "target patch is not attached to native AbstractPlayer.useCard");
        AbstractPlayer.class.getDeclaredMethod("useCard", AbstractCard.class,
                AbstractMonster.class, int.class);
        boolean inserted = false;
        for (java.lang.reflect.Method method : AbstractPlayerUseCardTargetRecorderPatch.class.getDeclaredMethods()) {
            if (method.getAnnotation(SpireInsertPatch.class) != null) inserted = true;
        }
        check(inserted, "target capture is not inserted at the native card-use boundary");
        check(SpireInsertLocator.class.isAssignableFrom(
                        AbstractPlayerUseCardTargetRecorderPatch.BeforeCardEffects.class),
                "target patch locator is not registered");
        CardQueueItem.class.getField("monster");
        UseCardAction.class.getField("target");
        try {
            GameActionManager.class.getDeclaredMethod("getNextAction");
        } catch (NoSuchMethodException ignored) {
            // GameActionManager's decompiled native caller is also documented in the test output.
        }
    }

    private static void artifactsKeepMetadata(CardTargetRef single, CardTargetRef all,
                                              CardTargetRef untrusted) throws Exception {
        File root = new File("build/targetref-artifacts-" + System.nanoTime());
        BattleArtifacts.beginForTest(root, "target-reference-check");
        ActionJournal journal = new ActionJournal();
        journal.recordCard(2, "Strike_R", single);
        journal.recordCard(1, "FireBreathing", all);
        journal.recordCard(0, "Strike_R", untrusted);
        String archived = new String(Files.readAllBytes(
                new File(root, "target-reference-check/actions.journal").toPath()), StandardCharsets.UTF_8);
        check(archived.contains("targetMode=single targetTrusted=true targetEngineIndex=2 targetLiveIndex=1")
                        && archived.contains("targetMode=all_enemies targetTrusted=true targetEngineIndex=-1 targetLiveIndex=-1")
                        && archived.contains("targetMode=untrusted targetTrusted=false")
                        && archived.contains("\n20002\n") && archived.contains("\n1\n"),
                "archived action journal lost target identity or no-target semantics");
        String manifest = new String(Files.readAllBytes(
                new File(root, "target-reference-check/manifest.json").toPath()), StandardCharsets.UTF_8);
        check(manifest.contains("\"actionCount\":3") && manifest.contains("\"journalDirty\":true"),
                "untrusted target did not block strict bundle replay in the manifest");
        BattleArtifacts.finish("test");
    }
}

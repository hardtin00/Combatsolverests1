package com.combatsolver1.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Offline checks for battle-bundle identity, freshness rejection, and copy failures. */
public final class BattleArtifactsCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void root(File directory, String id) throws Exception {
        root(directory, id, true);
    }

    private static void root(File directory, String id, boolean consistent) throws Exception {
        String rootState = "{\"turn\":1,\"energy\":0,\"hand\":[]}";
        String actionPre = consistent ? rootState : "{\"turn\":1,\"energy\":3,\"hand\":[{\"id\":\"Flex\"}]}";
        String content = "{\"type\":\"root\",\"battleKey\":\"" + id + "\",\"state\":" + rootState + "}\n"
                + "{\"type\":\"card\",\"pre\":" + actionPre + ",\"post\":" + actionPre + "}\n";
        Files.write(new File(directory, "combat.jsonl").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void prepareEngine(BattleArtifacts.Session session, File temp) throws Exception {
        File engineDir = BattleArtifacts.engineDirectory(session.id);
        File prepared = new File(engineDir, "engine-in.save");
        Files.write(prepared.toPath(), new byte[]{4, 5, 6});
        File executable = source(temp, "engine-" + session.id + ".exe", System.currentTimeMillis());
        BattleArtifacts.recordEnginePreparation(session.id, prepared, "test-engine", executable);
    }

    private static File source(File root, String name, long modifiedAt) throws Exception {
        File file = new File(root, name);
        Files.write(file.toPath(), ("private-save-" + name).getBytes(StandardCharsets.UTF_8));
        check(file.setLastModified(modifiedAt), "could not set fixture save timestamp");
        return file;
    }

    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("combat-artifacts-check").toFile();
        try {
            long now = System.currentTimeMillis();

            BattleArtifacts.Session death = BattleArtifacts.beginForTest(temp, "battle-test-death");
            Files.write(new File(death.directory, "combat.jsonl").toPath(),
                    ("{\"type\":\"root\",\"battleKey\":\"" + death.id + "\",\"state\":{}}\n"
                            + "{\"type\":\"card\",\"paired\":true,\"pre\":{},\"post\":{}}\n")
                            .getBytes(StandardCharsets.UTF_8));
            check(BattleArtifacts.appendAction("0"), "death action journal append failed");
            check(BattleArtifacts.appendAction("1"), "pending death action journal append failed");
            BattleArtifacts.incompleteFinalAction("Bash", "battle ended before stable post");
            byte[] deathJournal = Files.readAllBytes(death.journal.toPath());
            BattleArtifacts.finish("death");
            String deathManifest = new String(Files.readAllBytes(
                    new File(death.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(deathManifest.contains("\"status\":\"closed\"")
                            && deathManifest.contains("\"outcome\":\"death\""),
                    "death outcome did not close and classify the bundle");
            check(deathManifest.contains("\"finalActionStatus\":\"unpaired-no-stable-post\"")
                            && deathManifest.contains("\"journalDirty\":true"),
                    "interrupted final action was not explicitly preserved as unpaired");
            check(java.util.Arrays.equals(deathJournal, Files.readAllBytes(death.journal.toPath())),
                    "death closure changed the recorded action journal");
            check(deathManifest.contains(BattleArtifacts.sha256(death.jsonl))
                            && deathManifest.contains(BattleArtifacts.sha256(death.journal)),
                    "death closure did not refresh JSONL and journal hashes");
            String deathJsonl = new String(Files.readAllBytes(death.jsonl.toPath()), StandardCharsets.UTF_8);
            check(deathJsonl.indexOf("\"type\":\"card\"")
                            == deathJsonl.lastIndexOf("\"type\":\"card\""),
                    "death closure fabricated an additional card post-state");
            BattleArtifacts.finish("victory");
            deathManifest = new String(Files.readAllBytes(
                    new File(death.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(deathManifest.contains("\"outcome\":\"death\""),
                    "repeated closure overwrote the original death outcome");

            BattleArtifacts.Session victory = BattleArtifacts.beginForTest(temp, "battle-test-victory");
            Files.write(new File(victory.directory, "combat.jsonl").toPath(),
                    ("{\"type\":\"root\",\"battleKey\":\"" + victory.id + "\",\"state\":{}}\n")
                            .getBytes(StandardCharsets.UTF_8));
            BattleArtifacts.finish("victory");
            String victoryManifest = new String(Files.readAllBytes(
                    new File(victory.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(victoryManifest.contains("\"status\":\"closed\"")
                            && victoryManifest.contains("\"outcome\":\"victory\""),
                    "victory outcome did not close and classify the bundle");

            BattleArtifacts.Session interrupted = BattleArtifacts.beginForTest(temp, "battle-test-interrupted");
            BattleArtifacts.Session replacement = BattleArtifacts.beginForTest(temp, "battle-test-replacement");
            String interruptedManifest = new String(Files.readAllBytes(
                    new File(interrupted.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(interruptedManifest.contains("\"status\":\"closed\"")
                            && interruptedManifest.contains("\"outcome\":\"interrupted\""),
                    "replaced active battle was not closed as interrupted");
            BattleArtifacts.finish("unknown");

            BattleArtifacts.Session first = BattleArtifacts.beginForTest(temp, "battle-test-a");
            root(first.directory, first.id);
            check(BattleArtifacts.appendAction("0"), "first action journal append failed");
            File currentSave = source(temp, "current.autosave", now);
            check(BattleArtifacts.captureAutosave(first.id, currentSave, now - 5000L) != null,
                    "fresh save was not archived");
            prepareEngine(first, temp);
            check(!BattleArtifacts.validate(first.directory).valid
                            && BattleArtifacts.validate(first.directory).reason.contains("not closed"),
                    "open bundle was not rejected with a specific reason");
            BattleArtifacts.finish("victory");
            check(BattleArtifacts.validate(first.directory).valid, "valid bundle rejected");
            byte[] validJsonl = Files.readAllBytes(new File(first.directory, "combat.jsonl").toPath());
            Files.write(new File(first.directory, "combat.jsonl").toPath(), new byte[]{'{', '}'});
            check(BattleArtifacts.validate(first.directory).reason.contains("combat JSONL SHA-256 mismatch"),
                    "changed JSONL hash was not rejected specifically: " + BattleArtifacts.validate(first.directory).reason);
            Files.write(new File(first.directory, "combat.jsonl").toPath(), validJsonl);
            File validRaw = new File(first.directory, "raw-autosave.save");
            byte[] rawBytes = Files.readAllBytes(validRaw.toPath());
            Files.delete(validRaw.toPath());
            check(BattleArtifacts.validate(first.directory).reason.contains("raw autosave is missing"),
                    "missing raw autosave was not rejected specifically");
            Files.write(validRaw.toPath(), rawBytes);
            check(BattleArtifacts.validate(first.directory).valid, "restored valid bundle rejected");

            BattleArtifacts.Session journalFailure = BattleArtifacts.beginForTest(temp, "battle-test-journal-failure");
            java.lang.reflect.Field journalField = BattleArtifacts.Session.class.getDeclaredField("journal");
            journalField.setAccessible(true);
            journalField.set(journalFailure, new File(journalFailure.directory, "missing-parent/actions.journal"));
            check(!BattleArtifacts.replaceJournal(java.util.Collections.singletonList("0")),
                    "journal replacement unexpectedly succeeded with a missing parent directory");
            check(!BattleArtifacts.journalFailureReason().isEmpty(),
                    "journal replacement failure did not preserve the underlying IOException");
            BattleArtifacts.finish();

            BattleArtifacts.Session inconsistent = BattleArtifacts.beginForTest(temp, "battle-test-root-mismatch");
            root(inconsistent.directory, inconsistent.id, false);
            File inconsistentSave = source(temp, "root-mismatch.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(inconsistent.id, inconsistentSave,
                    System.currentTimeMillis() - 5000L) != null, "root-mismatch save was not archived");
            prepareEngine(inconsistent, temp);
            BattleArtifacts.finish("victory");
            check(BattleArtifacts.validate(inconsistent.directory).reason.contains("root state does not match"),
                    "self-contradictory root was not rejected specifically");

            BattleArtifacts.Session dirty = BattleArtifacts.beginForTest(temp, "battle-test-dirty-journal");
            root(dirty.directory, dirty.id);
            File dirtySave = source(temp, "dirty.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(dirty.id, dirtySave,
                    System.currentTimeMillis() - 5000L) != null, "dirty-journal save was not archived");
            prepareEngine(dirty, temp);
            BattleArtifacts.markJournalDirty("unrecorded potion action");
            BattleArtifacts.finish();
            check(BattleArtifacts.validate(dirty.directory).reason.contains("action journal is dirty"),
                    "dirty action journal was accepted for strict validation");

            BattleArtifacts.Session noSave = BattleArtifacts.beginForTest(temp, "battle-test-no-save");
            root(noSave.directory, noSave.id);
            check(BattleArtifacts.captureAutosave(noSave.id, null, System.currentTimeMillis() - 5000L) == null,
                    "missing autosave unexpectedly succeeded");
            BattleArtifacts.engineNotConfigured(noSave.id);
            String noSaveManifest = new String(Files.readAllBytes(
                    new File(noSave.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(noSaveManifest.contains("\"rawAutosaveStatus\":\"failed_missing_source\""),
                    "missing autosave was not reported");
            check(noSaveManifest.contains("\"engineCombatSaveStatus\":\"not_configured\""),
                    "unconfigured external engine was not explicitly reported");
            BattleArtifacts.finish();
            check(BattleArtifacts.validate(noSave.directory).reason.contains("raw autosave was not captured"),
                    "bundle without an autosave was accepted");

            BattleArtifacts.Session localOnly = BattleArtifacts.beginForTest(temp, "battle-test-local-only");
            root(localOnly.directory, localOnly.id);
            File localSave = source(temp, "local-only.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(localOnly.id, localSave,
                    System.currentTimeMillis() - 5000L) != null, "local-only save was not archived");
            BattleArtifacts.engineNotConfigured(localOnly.id);
            BattleArtifacts.finish();
            check(BattleArtifacts.validate(localOnly.directory).reason.contains("engine combat save was not prepared"),
                    "local fallback bundle was accepted as an external-engine bundle");

            BattleArtifacts.Session lateCapture = BattleArtifacts.beginForTest(temp, "battle-test-late-capture");
            root(lateCapture.directory, lateCapture.id);
            check(BattleArtifacts.initialCaptureStarted(lateCapture.id), "initial capture was not marked pending");
            BattleArtifacts.finish("death");
            check(BattleArtifacts.validate(lateCapture.directory).reason.contains("not closed"),
                    "bundle closing with capture still pending was accepted");
            File lateSave = source(temp, "late.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(lateCapture.id, lateSave,
                    System.currentTimeMillis() - 5000L) != null,
                    "battle end discarded an in-flight autosave capture");
            BattleArtifacts.engineNotConfigured(lateCapture.id);
            BattleArtifacts.initialCaptureFinished(lateCapture.id);
            String lateManifest = new String(Files.readAllBytes(
                    new File(lateCapture.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(lateManifest.contains("\"status\":\"closed\""),
                    "deferred capture did not close its bundle after completion");
            check(lateManifest.contains("\"rawAutosaveStatus\":\"captured\""),
                    "deferred capture did not preserve raw autosave");
            check(lateManifest.contains("\"outcome\":\"death\""),
                    "deferred death closure lost its outcome");

            BattleArtifacts.Session second = BattleArtifacts.beginForTest(temp, "battle-test-b");
            root(second.directory, second.id);
            check(BattleArtifacts.appendAction("1"), "second action journal append failed");
            File secondSave = source(temp, "second.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(first.id, secondSave, System.currentTimeMillis() - 5000L) == null,
                    "save from a different battle id was accepted");
            check(BattleArtifacts.captureAutosave(second.id, secondSave, System.currentTimeMillis() - 5000L) != null,
                    "second battle save was not archived");
            BattleArtifacts.finish();
            check(!first.id.equals(second.id), "different battles reused an identity");
            Files.copy(new File(second.directory, "actions.journal").toPath(),
                    new File(first.directory, "actions.journal").toPath(), StandardCopyOption.REPLACE_EXISTING);
            check(!BattleArtifacts.validate(first.directory).valid,
                    "journal from a different battle was accepted");

            BattleArtifacts.Session stale = BattleArtifacts.beginForTest(temp, "battle-test-stale");
            root(stale.directory, stale.id);
            File oldSave = source(temp, "old.autosave", System.currentTimeMillis() - 120000L);
            check(BattleArtifacts.captureAutosave(stale.id, oldSave, System.currentTimeMillis() - 3000L) == null,
                    "stale save was accepted");
            check(!BattleArtifacts.validate(stale.directory).valid,
                    "bundle with a rejected old save was accepted");
            check(new String(Files.readAllBytes(new File(stale.directory, "manifest.json").toPath()), StandardCharsets.UTF_8)
                    .contains("\"rawAutosaveStatus\":\"rejected_stale\""),
                    "stale-save rejection was not recorded");
            BattleArtifacts.finish();

            BattleArtifacts.Session failed = BattleArtifacts.beginForTest(temp, "battle-test-copy-fail");
            root(failed.directory, failed.id);
            File copySource = source(temp, "copy.autosave", System.currentTimeMillis());
            File blockedTarget = new File(failed.directory, "raw-autosave.save");
            check(blockedTarget.mkdir(), "could not prepare copy-failure fixture");
            Files.write(new File(blockedTarget, "keep").toPath(), new byte[]{1});
            check(BattleArtifacts.captureAutosave(failed.id, copySource, System.currentTimeMillis() - 5000L) == null,
                    "copy failure unexpectedly succeeded");
            String manifest = new String(Files.readAllBytes(new File(failed.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(manifest.contains("\"rawAutosaveStatus\":\"failed_copy\""),
                    "copy failure was not written to manifest");
            check(!BattleArtifacts.validate(failed.directory).valid,
                    "bundle with a failed save copy was accepted");
            BattleArtifacts.finish();

            BattleArtifacts.Session conversionFailed = BattleArtifacts.beginForTest(temp, "battle-test-conversion-fail");
            root(conversionFailed.directory, conversionFailed.id);
            File conversionSave = source(temp, "conversion.autosave", System.currentTimeMillis());
            check(BattleArtifacts.captureAutosave(conversionFailed.id, conversionSave,
                    System.currentTimeMillis() - 5000L) != null, "conversion fixture save was not archived");
            BattleArtifacts.enginePreparationFailed(conversionFailed.id, "Engine combat save preparation failed.");
            String conversionManifest = new String(Files.readAllBytes(
                    new File(conversionFailed.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            check(conversionManifest.contains("\"engineCombatSaveStatus\":\"failed\""),
                    "engine conversion failure status was not written");
            check(conversionManifest.contains("Engine combat save preparation failed."),
                    "engine conversion failure reason was not written");
            BattleArtifacts.finish();
            check(!BattleArtifacts.validate(conversionFailed.directory).valid,
                    "bundle with failed engine conversion was accepted");

            Files.write(new File(first.directory, "raw-autosave.save").toPath(),
                    Files.readAllBytes(new File(second.directory, "raw-autosave.save").toPath()));
            check(!BattleArtifacts.validate(first.directory).valid,
                    "a substituted raw save passed the manifest SHA-256 check");
            System.out.println("BattleArtifacts regression passed: closure outcomes, idempotency, interrupted final action without synthetic post, hashes, identity/freshness/copy checks.");
        } finally {
            deleteTree(temp);
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}

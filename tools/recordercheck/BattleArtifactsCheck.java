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
        Files.write(new File(directory, "combat.jsonl").toPath(),
                ("{\"type\":\"root\",\"battleKey\":\"" + id + "\",\"state\":{}}\n").getBytes(StandardCharsets.UTF_8));
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
            BattleArtifacts.Session first = BattleArtifacts.beginForTest(temp, "battle-test-a");
            root(first.directory, first.id);
            check(BattleArtifacts.appendAction("0"), "first action journal append failed");
            File currentSave = source(temp, "current.autosave", now);
            check(BattleArtifacts.captureAutosave(first.id, currentSave, now - 5000L) != null,
                    "fresh save was not archived");
            File engineDir = BattleArtifacts.engineDirectory(first.id);
            File prepared = new File(engineDir, "engine-in.save");
            Files.write(prepared.toPath(), new byte[]{4, 5, 6});
            File executable = source(temp, "engine.exe", System.currentTimeMillis());
            BattleArtifacts.recordEnginePreparation(first.id, prepared, "test-engine", executable);
            BattleArtifacts.finish();
            check(BattleArtifacts.validate(first.directory).valid, "valid bundle rejected");

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
            System.out.println("BattleArtifacts regression passed: unique identity, cross-battle rejection, stale-save rejection, copy/conversion failure reporting, SHA-256 substitution rejection.");
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

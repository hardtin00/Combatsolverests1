package com.combatsolver1.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** Worker used by the PowerShell Windows sharing-violation regression. */
public final class BattleArtifactsLockCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("root ready signal");
        File root = new File(args[0]);
        File ready = new File(args[1]);
        File signal = new File(args[2]);
        BattleArtifacts.Session session = BattleArtifacts.beginForTest(root, "battle-journal-lock-check");
        if (!BattleArtifacts.appendAction("0")) throw new AssertionError("initial append failed");
        Files.write(ready.toPath(), session.journal.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 10000L;
        while (!signal.isFile() && System.currentTimeMillis() < deadline) Thread.sleep(5L);
        if (!signal.isFile()) throw new AssertionError("lock harness did not release the writer");
        boolean replaced = BattleArtifacts.replaceJournal(Collections.singletonList("1"));
        if (!replaced) {
            String reason = BattleArtifacts.journalFailureReason();
            BattleArtifacts.markJournalDirty("journal replacement failed: " + reason);
            BattleArtifacts.finish("interrupted");
            String manifest = new String(Files.readAllBytes(
                    new File(session.directory, "manifest.json").toPath()), StandardCharsets.UTF_8);
            if (!manifest.contains("\"journalDirty\":true")
                    || !manifest.contains("\"journalDirtyReason\":\"journal replacement failed:")
                    || !manifest.contains("\"journalError\":\"java.nio.file.")) {
                throw new AssertionError("permanent lock failure was not retained as dirty with its cause");
            }
            System.err.println("JOURNAL_REPLACE_FAILED_DIRTY " + reason);
            return;
        }
        System.out.println("JOURNAL_REPLACE_OK");
        BattleArtifacts.finish("interrupted");
    }
}

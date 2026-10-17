package com.combatsolver1.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Durable, per-battle evidence files. Raw saves are copied and never edited here. */
public final class BattleArtifacts {
    private static Session active;

    private BattleArtifacts() { }

    static synchronized String begin() throws IOException {
        finish();
        File root = new File(System.getProperty("user.home", "."), ".combatsolver1/recordings");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        String id = "battle-" + stamp + "-" + UUID.randomUUID().toString();
        active = new Session(root, id);
        return id;
    }

    static synchronized Session beginForTest(File root, String id) throws IOException {
        finish();
        active = new Session(root, id);
        return active;
    }

    static synchronized boolean isActive(String id) {
        return active != null && active.id.equals(id) && !active.closed;
    }

    static synchronized String currentId() { return active == null ? "" : active.id; }
    static synchronized File jsonlFile() { return active == null ? null : active.jsonl; }
    static synchronized File sessionDirectory() { return active == null ? null : active.directory; }
    static synchronized File engineDirectory(String expectedId) {
        if (active == null || !active.id.equals(expectedId) || active.closed) return null;
        File dir = new File(active.directory, "engine");
        return dir.mkdirs() || dir.isDirectory() ? dir : null;
    }

    static synchronized void rootCaptured(String phase) throws IOException {
        if (active != null) {
            active.rootPhase = phase;
            active.rootCapturedAt = System.currentTimeMillis();
            active.writeManifest();
        }
    }

    static synchronized boolean appendAction(String action) {
        if (active == null || active.closed) return true;
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(active.journal, true), StandardCharsets.UTF_8)) {
            writer.write(action);
            writer.write('\n');
            active.actionCount++;
            active.writeManifest();
            return true;
        } catch (IOException e) {
            active.journalError = e.toString();
            active.writeManifestQuietly();
            return false;
        }
    }

    static synchronized boolean replaceJournal(Iterable<String> actions) {
        if (active == null || active.closed) return false;
        File temp = new File(active.directory, "actions.journal.tmp");
        int count = 0;
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            writer.write("# battleId=" + active.id + "\n");
            for (String action : actions) {
                writer.write(action);
                writer.write('\n');
                count++;
            }
            move(temp, active.journal);
            active.actionCount = count;
            active.writeManifest();
            return true;
        } catch (IOException e) {
            temp.delete();
            active.journalError = e.toString();
            active.writeManifestQuietly();
            return false;
        }
    }

    static synchronized void markJournalDirty(String reason) {
        if (active == null) return;
        active.journalDirty = true;
        active.journalDirtyReason = reason == null ? "" : reason;
        active.writeManifestQuietly();
    }

    static synchronized File captureAutosave(String expectedId, File source, long notOlderThanMillis) {
        if (active == null || active.closed || !active.id.equals(expectedId)) return null;
        if (source == null || !source.isFile()) {
            active.saveStatus = "failed_missing_source";
            active.saveError = "No autosave file was available for this battle.";
            active.writeManifestQuietly();
            return null;
        }
        long modifiedBefore = source.lastModified();
        if (modifiedBefore < notOlderThanMillis) {
            active.saveStatus = "rejected_stale";
            active.saveSourcePath = source.getAbsolutePath();
            active.saveModifiedAt = modifiedBefore;
            active.saveError = "Autosave is older than the battle capture threshold.";
            active.writeManifestQuietly();
            return null;
        }
        File temp = new File(active.directory, "raw-autosave.save.tmp");
        try {
            String sourceHashBefore = sha256(source);
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (source.lastModified() != modifiedBefore || !sourceHashBefore.equals(sha256(source))
                    || !sourceHashBefore.equals(sha256(temp))) {
                throw new IOException("Autosave changed while it was being copied.");
            }
            if (temp.length() == 0) throw new IOException("Autosave copy is empty.");
            move(temp, active.rawSave);
            active.saveSourcePath = source.getAbsolutePath();
            active.saveModifiedAt = modifiedBefore;
            active.saveCapturedAt = System.currentTimeMillis();
            active.saveSha256 = sha256(active.rawSave);
            active.saveStatus = "captured";
            active.savePhase = active.rootCapturedAt == 0
                    ? "source-captured-before-root; source stage is not assumed to equal root"
                    : (modifiedBefore >= active.rootCapturedAt
                        ? "source-modified-at-or-after-root-capture"
                        : "source-modified-before-root-capture");
            active.saveError = "";
            active.writeManifest();
            return active.rawSave;
        } catch (IOException e) {
            temp.delete();
            active.saveStatus = "failed_copy";
            active.saveError = e.toString();
            active.writeManifestQuietly();
            return null;
        }
    }

    static synchronized void recordEnginePreparation(String expectedId, File stagedSave, String description, File executable) {
        if (active == null || active.closed || !active.id.equals(expectedId)) return;
        active.engineDescription = description == null ? "" : description;
        try {
            if (executable != null && executable.isFile()) active.engineSha256 = sha256(executable);
            if (stagedSave != null && stagedSave.isFile()) {
                active.engineSavePath = relative(active.directory, stagedSave);
                active.engineSaveSha256 = sha256(stagedSave);
                active.engineSaveStatus = "prepared";
            } else {
                active.engineSaveStatus = "failed";
            }
        } catch (IOException e) {
            active.engineSaveStatus = "failed_hash";
            active.engineError = e.toString();
        }
        active.writeManifestQuietly();
    }

    static synchronized void enginePreparationFailed(String expectedId, String reason) {
        if (active == null || active.closed || !active.id.equals(expectedId)) return;
        active.engineSaveStatus = "failed";
        active.engineError = reason == null ? "" : reason;
        active.writeManifestQuietly();
    }

    static synchronized void finish() {
        if (active == null) return;
        active.closed = true;
        active.status = "closed";
        active.writeManifestQuietly();
        active = null;
    }

    /** Check that all supplied evidence belongs to one manifest and has not been substituted. */
    public static Validation validate(File directory) {
        try {
            File manifest = new File(directory, "manifest.json");
            File jsonl = new File(directory, "combat.jsonl");
            File journal = new File(directory, "actions.journal");
            String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
            String id = field(text, "battleId");
            if (id.isEmpty()) return Validation.fail("manifest has no battleId");
            if (!"closed".equals(field(text, "status"))) return Validation.fail("battle bundle is not closed");
            if (!"captured".equals(field(text, "rawAutosaveStatus"))) return Validation.fail("raw autosave was not captured");
            File raw = new File(directory, "raw-autosave.save");
            if (!raw.isFile()) return Validation.fail("raw autosave is missing");
            String expectedHash = field(text, "rawAutosaveSha256");
            if (expectedHash.isEmpty() || !expectedHash.equals(sha256(raw))) return Validation.fail("raw autosave SHA-256 mismatch");
            if ("prepared".equals(field(text, "engineCombatSaveStatus"))) {
                String engineRelative = field(text, "engineCombatSaveFile");
                File engineSave = new File(directory, engineRelative);
                if (engineRelative.isEmpty() || !engineSave.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) {
                    return Validation.fail("engine combat save path escapes the battle bundle");
                }
                String engineHash = field(text, "engineCombatSaveSha256");
                if (!engineSave.isFile() || engineHash.isEmpty() || !engineHash.equals(sha256(engineSave))) {
                    return Validation.fail("engine combat save SHA-256 mismatch");
                }
            } else return Validation.fail("engine combat save was not prepared");
            if (!jsonl.isFile()) return Validation.fail("combat JSONL is missing");
            String recordingHash = field(text, "recordingSha256");
            if (recordingHash.isEmpty() || !recordingHash.equals(sha256(jsonl))) return Validation.fail("combat JSONL SHA-256 mismatch");
            boolean rootMatches = false;
            int rootCount = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(jsonl), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"type\":\"root\"")) {
                        rootCount++;
                        if (line.contains("\"battleKey\":\"" + id + "\"")) rootMatches = true;
                        else return Validation.fail("JSONL contains a root from a different battle");
                    }
                }
            }
            if (rootCount != 1) return Validation.fail("JSONL must contain exactly one root");
            if (!rootMatches) return Validation.fail("JSONL root battleId does not match manifest");
            if (!journal.isFile()) return Validation.fail("action journal is missing");
            String journalHash = field(text, "actionJournalSha256");
            if (journalHash.isEmpty() || !journalHash.equals(sha256(journal))) return Validation.fail("action journal SHA-256 mismatch");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(journal), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (!("# battleId=" + id).equals(header)) return Validation.fail("action journal battleId does not match manifest");
            }
            return Validation.ok(id);
        } catch (IOException e) {
            return Validation.fail("bundle read failed: " + e);
        }
    }

    static final class Validation {
        final boolean valid;
        final String battleId;
        final String reason;
        private Validation(boolean valid, String battleId, String reason) { this.valid = valid; this.battleId = battleId; this.reason = reason; }
        static Validation ok(String id) { return new Validation(true, id, ""); }
        static Validation fail(String reason) { return new Validation(false, "", reason); }
    }

    static final class Session {
        final String id;
        final File directory;
        final File jsonl;
        final File journal;
        final File rawSave;
        boolean closed;
        String status = "open";
        String rootPhase = "";
        long rootCapturedAt;
        String saveStatus = "pending";
        String savePhase = "capture-requested-at-battle-start; source stage is unverified";
        String saveSourcePath = "";
        long saveModifiedAt;
        long saveCapturedAt;
        String saveSha256 = "";
        String saveError = "";
        int actionCount;
        boolean journalDirty;
        String journalDirtyReason = "";
        String journalError = "";
        String engineDescription = "";
        String engineSha256 = "";
        String engineSavePath = "";
        String engineSaveSha256 = "";
        String engineSaveStatus = "pending";
        String engineError = "";
        final long startedAt = System.currentTimeMillis();

        Session(File root, String id) throws IOException {
            this.id = id;
            directory = new File(root, id);
            if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create battle artifact directory: " + directory);
            jsonl = new File(directory, "combat.jsonl");
            journal = new File(directory, "actions.journal");
            rawSave = new File(directory, "raw-autosave.save");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(journal), StandardCharsets.UTF_8)) {
                writer.write("# battleId=" + id + "\n");
            }
            writeManifest();
        }

        void writeManifestQuietly() {
            try { writeManifest(); } catch (IOException ignored) { }
        }

        void writeManifest() throws IOException {
            File temp = new File(directory, "manifest.json.tmp");
            String journalHash = journal.isFile() ? sha256(journal) : "";
            String recordingHash = jsonl.isFile() ? sha256(jsonl) : "";
            String value = "{\n"
                    + "  \"schemaVersion\":1,\n"
                    + "  \"battleId\":\"" + esc(id) + "\",\n"
                    + "  \"status\":\"" + esc(status) + "\",\n"
                    + "  \"startedAtEpochMs\":" + startedAt + ",\n"
                    + "  \"recordingFile\":\"combat.jsonl\",\n"
                    + "  \"recordingSha256\":\"" + recordingHash + "\",\n"
                    + "  \"actionJournalFile\":\"actions.journal\",\n"
                    + "  \"actionJournalSha256\":\"" + journalHash + "\",\n"
                    + "  \"contentPairingStatus\":\"pending-root-vs-engine-initialization-comparison\",\n"
                    + "  \"rootCapturePhase\":\"" + esc(rootPhase) + "\",\n"
                    + "  \"rootCapturedAtEpochMs\":" + rootCapturedAt + ",\n"
                    + "  \"rawAutosaveFile\":\"raw-autosave.save\",\n"
                    + "  \"rawAutosaveStatus\":\"" + esc(saveStatus) + "\",\n"
                    + "  \"rawAutosaveSourcePath\":\"" + esc(saveSourcePath) + "\",\n"
                    + "  \"rawAutosaveSourceModifiedAtEpochMs\":" + saveModifiedAt + ",\n"
                    + "  \"rawAutosaveCapturedAtEpochMs\":" + saveCapturedAt + ",\n"
                    + "  \"rawAutosaveCapturePhase\":\"" + esc(savePhase) + "\",\n"
                    + "  \"rawAutosaveSha256\":\"" + esc(saveSha256) + "\",\n"
                    + "  \"rawAutosaveError\":\"" + esc(saveError) + "\",\n"
                    + "  \"engineDescription\":\"" + esc(engineDescription) + "\",\n"
                    + "  \"engineProtocol\":\"sts_lightspeed protocol v2\",\n"
                    + "  \"engineBinarySha256\":\"" + esc(engineSha256) + "\",\n"
                    + "  \"engineCombatSaveFile\":\"" + esc(engineSavePath) + "\",\n"
                    + "  \"engineCombatSaveStatus\":\"" + esc(engineSaveStatus) + "\",\n"
                    + "  \"engineCombatSaveSha256\":\"" + esc(engineSaveSha256) + "\",\n"
                    + "  \"engineError\":\"" + esc(engineError) + "\",\n"
                    + "  \"actionCount\":" + actionCount + ",\n"
                    + "  \"journalDirty\":" + journalDirty + ",\n"
                    + "  \"journalDirtyReason\":\"" + esc(journalDirtyReason) + "\",\n"
                    + "  \"journalError\":\"" + esc(journalError) + "\"\n"
                    + "}\n";
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                writer.write(value);
            }
            move(temp, new File(directory, "manifest.json"));
        }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String relative(File root, File child) {
        return root.toURI().relativize(child.toURI()).getPath();
    }

    private static void move(File from, File to) throws IOException {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String field(String json, String key) {
        String token = "\"" + key + "\":\"";
        int at = json.indexOf(token);
        if (at < 0) return "";
        int start = at + token.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static String esc(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.toString();
    }
}

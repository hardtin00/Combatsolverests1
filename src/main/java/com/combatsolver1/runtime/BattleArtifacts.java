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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Durable, per-battle evidence files. Raw saves are copied and never edited here. */
public final class BattleArtifacts {
    private static Session active;
    private static final Map<String, Session> sessions = new HashMap<String, Session>();

    private BattleArtifacts() { }

    static synchronized String begin() throws IOException {
        finish("interrupted");
        File root = new File(System.getProperty("user.home", "."), ".combatsolver1/recordings");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        String id = "battle-" + stamp + "-" + UUID.randomUUID().toString();
        active = new Session(root, id);
        sessions.put(id, active);
        return id;
    }

    static synchronized Session beginForTest(File root, String id) throws IOException {
        finish("interrupted");
        active = new Session(root, id);
        sessions.put(id, active);
        return active;
    }

    static synchronized boolean initialCaptureStarted(String expectedId) {
        if (active == null || active.closed || !active.id.equals(expectedId)) return false;
        active.capturePending = true;
        return true;
    }

    static synchronized void initialCaptureFinished(String expectedId) {
        Session session = sessions.get(expectedId);
        if (session == null) return;
        session.capturePending = false;
        if (session.closeRequested) closeSession(session);
    }

    static synchronized boolean isActive(String id) {
        return active != null && active.id.equals(id) && !active.closed;
    }

    static synchronized boolean canCapture(String id) {
        Session session = sessions.get(id);
        return session != null && session.capturePending && !session.closed;
    }

    static synchronized String currentId() { return active == null ? "" : active.id; }
    static synchronized File jsonlFile() { return active == null ? null : active.jsonl; }
    static synchronized File sessionDirectory() { return active == null ? null : active.directory; }
    static synchronized String journalFailureReason() {
        return active == null ? "no active battle artifact" : active.journalError;
    }
    static synchronized File engineDirectory(String expectedId) {
        Session session = sessions.get(expectedId);
        if (session == null || session.closed) return null;
        File dir = new File(session.directory, "engine");
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
        return appendAction(action, "");
    }

    static synchronized boolean appendAction(String action, String metadata) {
        if (active == null || active.closed) return true;
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(active.journal, true), StandardCharsets.UTF_8)) {
            if (metadata != null && !metadata.isEmpty()) {
                if (!metadata.startsWith("#")) throw new IOException("Action metadata must be a comment line.");
                writer.write(metadata);
                writer.write('\n');
            }
            writer.write(action);
            writer.write('\n');
            active.actionCount++;
            active.journalError = "";
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
                String trimmed = action == null ? "" : action.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) count++;
            }
            move(temp, active.journal);
            active.actionCount = count;
            active.journalError = "";
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

    /** Preserve an action attempt without inventing a post-state when battle termination interrupts settlement. */
    static synchronized void incompleteFinalAction(String actionId, String reason) {
        if (active == null || active.closed) return;
        active.finalActionStatus = "unpaired-no-stable-post";
        active.finalActionId = actionId == null ? "" : actionId;
        active.finalActionReason = reason == null ? "" : reason;
        active.journalDirty = true;
        active.journalDirtyReason = "battle ended before the final action had a stable post-state";
        active.writeManifestQuietly();
    }

    static synchronized File captureAutosave(String expectedId, File source, long notOlderThanMillis) {
        Session session = sessions.get(expectedId);
        if (session == null || session.closed) return null;
        if (source == null || !source.isFile()) {
            session.saveStatus = "failed_missing_source";
            session.saveError = "No autosave file was available for this battle.";
            session.writeManifestQuietly();
            return null;
        }
        long modifiedBefore = source.lastModified();
        if (modifiedBefore < notOlderThanMillis) {
            session.saveStatus = "rejected_stale";
            session.saveSourcePath = source.getAbsolutePath();
            session.saveModifiedAt = modifiedBefore;
            session.saveError = "Autosave is older than the battle capture threshold.";
            session.writeManifestQuietly();
            return null;
        }
        File temp = new File(session.directory, "raw-autosave.save.tmp");
        try {
            String sourceHashBefore = sha256(source);
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (source.lastModified() != modifiedBefore || !sourceHashBefore.equals(sha256(source))
                    || !sourceHashBefore.equals(sha256(temp))) {
                throw new IOException("Autosave changed while it was being copied.");
            }
            if (temp.length() == 0) throw new IOException("Autosave copy is empty.");
            move(temp, session.rawSave);
            session.saveSourcePath = source.getAbsolutePath();
            session.saveModifiedAt = modifiedBefore;
            session.saveCapturedAt = System.currentTimeMillis();
            session.saveSha256 = sha256(session.rawSave);
            session.saveStatus = "captured";
            session.savePhase = session.rootCapturedAt == 0
                    ? "source-captured-before-root; source stage is not assumed to equal root"
                    : (modifiedBefore >= session.rootCapturedAt
                        ? "source-modified-at-or-after-root-capture"
                        : "source-modified-before-root-capture");
            session.saveError = "";
            session.writeManifest();
            return session.rawSave;
        } catch (IOException e) {
            temp.delete();
            session.saveStatus = "failed_copy";
            session.saveError = e.toString();
            session.writeManifestQuietly();
            return null;
        }
    }

    static synchronized void recordEnginePreparation(String expectedId, File stagedSave, String description, File executable) {
        Session session = sessions.get(expectedId);
        if (session == null || session.closed) return;
        session.engineDescription = description == null ? "" : description;
        try {
            if (executable != null && executable.isFile()) session.engineSha256 = sha256(executable);
            if (stagedSave != null && stagedSave.isFile()) {
                session.engineSavePath = relative(session.directory, stagedSave);
                session.engineSaveSha256 = sha256(stagedSave);
                session.engineSaveStatus = "prepared";
            } else {
                session.engineSaveStatus = "failed";
            }
        } catch (IOException e) {
            session.engineSaveStatus = "failed_hash";
            session.engineError = e.toString();
        }
        session.writeManifestQuietly();
    }

    static synchronized void enginePreparationFailed(String expectedId, String reason) {
        Session session = sessions.get(expectedId);
        if (session == null || session.closed) return;
        session.engineSaveStatus = "failed";
        session.engineError = reason == null ? "" : reason;
        session.writeManifestQuietly();
    }

    static synchronized void engineNotConfigured(String expectedId) {
        Session session = sessions.get(expectedId);
        if (session == null || session.closed) return;
        session.engineSaveStatus = "not_configured";
        session.engineError = "sts_lightspeed is not configured; raw autosave archived without engine conversion.";
        session.writeManifestQuietly();
    }

    static synchronized void finish() {
        finish("unknown");
    }

    static synchronized void finish(String outcome) {
        if (active == null) return;
        Session session = active;
        active = null;
        session.outcome = outcome == null || outcome.isEmpty() ? "unknown" : outcome;
        if (session.capturePending) {
            session.closeRequested = true;
            session.status = "closing";
            session.writeManifestQuietly();
        } else closeSession(session);
    }

    private static void closeSession(Session session) {
        session.closed = true;
        session.status = "closed";
        session.writeManifestQuietly();
        sessions.remove(session.id);
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
            if (text.contains("\"journalDirty\":true")) {
                return Validation.fail("action journal is dirty: " + field(text, "journalDirtyReason"));
            }
            if (!jsonl.isFile()) return Validation.fail("combat JSONL is missing");
            String recordingHash = field(text, "recordingSha256");
            if (recordingHash.isEmpty() || !recordingHash.equals(sha256(jsonl))) return Validation.fail("combat JSONL SHA-256 mismatch");
            boolean rootMatches = false;
            int rootCount = 0;
            String rootState = null;
            String firstActionType = "";
            String firstActionPre = null;
            boolean firstLine = true;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(jsonl), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("\"type\":\"root\"")) {
                        rootCount++;
                        if (!firstLine) return Validation.fail("JSONL root is not the first record");
                        if (line.contains("\"battleKey\":\"" + id + "\"")) rootMatches = true;
                        else return Validation.fail("JSONL contains a root from a different battle");
                        rootState = jsonObject(line, "state");
                    } else if (rootCount == 1 && firstActionType.isEmpty()) {
                        firstActionType = field(line, "type");
                        firstActionPre = jsonObject(line, "pre");
                    }
                    firstLine = false;
                }
            }
            if (rootCount != 1) return Validation.fail("JSONL must contain exactly one root");
            if (!rootMatches) return Validation.fail("JSONL root battleId does not match manifest");
            if ("card".equals(firstActionType) && rootState != null && firstActionPre != null
                    && !rootState.equals(firstActionPre)) {
                return Validation.fail("root state does not match the first card pre-state");
            }
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
        boolean capturePending;
        boolean closeRequested;
        String status = "open";
        String outcome = "in_progress";
        String finalActionStatus = "none";
        String finalActionId = "";
        String finalActionReason = "";
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
                    + "  \"outcome\":\"" + esc(outcome) + "\",\n"
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
                    + "  \"journalError\":\"" + esc(journalError) + "\",\n"
                    + "  \"finalActionStatus\":\"" + esc(finalActionStatus) + "\",\n"
                    + "  \"finalActionId\":\"" + esc(finalActionId) + "\",\n"
                    + "  \"finalActionReason\":\"" + esc(finalActionReason) + "\"\n"
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

    private static String jsonObject(String json, String key) {
        String token = "\"" + key + "\":";
        int at = json.indexOf(token);
        if (at < 0) return null;
        int start = json.indexOf('{', at + token.length());
        if (start < 0) return null;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(start, i + 1);
        }
        return null;
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

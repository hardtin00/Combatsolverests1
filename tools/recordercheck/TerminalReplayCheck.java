import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline terminal-action verifier. It never changes a captured bundle. */
public final class TerminalReplayCheck {
    private static final Pattern TRACE = Pattern.compile(
            "^TRACE(\\d+) hp=(\\d+) max=(\\d+) blk=(\\d+) en=(\\d+) turn=(\\d+) draw=(\\d+) disc=(\\d+) exh=(\\d+) hand=\\[([^]]*)] mons=([^\\s]+)$");
    private static final Pattern META = Pattern.compile("(?:^|\\s)(\\w+)=([^\\s]*)");
    private static final Pattern PLANEND = Pattern.compile("(?:^|\\s)PLANEND\\s+[^\\r\\n]*?\\boutcome=(\\d+)\\b");
    private static final String TERMINAL_DIRTY_REASON =
            "battle ended before the final action had a stable post-state";

    private TerminalReplayCheck() { }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: TerminalReplayCheck <battle-bundle> [sts_lightspeed-engine]");
            System.exit(2);
        }
        try {
            File bundle = new File(args[0]).getCanonicalFile();
            Map<String, Object> manifest = readObject(new File(bundle, "manifest.json"));
            File engine = args.length == 2 ? new File(args[1]).getCanonicalFile()
                    : engineFromDescription(Json.str(manifest, "engineDescription", ""));
            Report report = verify(bundle, manifest, engine);
            report.print();
            if ("NOT_COVERED".equals(report.terminalOutcome)) System.exit(4);
            if (report.failed) System.exit(1);
        } catch (CheckFailure failure) {
            System.err.println("archive_seal_hash=" + ("archive".equals(failure.stage) ? "FAIL" : "NOT_RUN"));
            System.err.println("nonterminal_continuity=" + ("continuity".equals(failure.stage) ? "FAIL" : "NOT_RUN"));
            System.err.println("action_encoding_target_trust=" + ("actions".equals(failure.stage) ? "FAIL" : "NOT_RUN"));
            System.err.println("terminal_outcome=" + ("terminal-outcome".equals(failure.stage) ? "FAIL" : "NOT_RUN"));
            System.err.println("terminal_all_fields=NOT_COMPARED");
            System.err.println("TERMINAL_REPLAY_CHECK=FAIL stage=" + failure.stage
                    + " reason=" + safe(failure.getMessage()));
            System.exit(1);
        } catch (Exception e) {
            System.err.println("TERMINAL_REPLAY_CHECK=FAIL stage=internal reason=" + safe(e.toString()));
            System.exit(1);
        }
    }

    private static Report verify(File bundle, Map<String, Object> manifest, File engine) throws Exception {
        Report report = new Report();
        verifyArchive(bundle, manifest, engine);
        report.archive = "PASS closed=true all-manifest-files-hash-match journalDirty="
                + Json.bool(manifest, "journalDirty", false);

        String outcome = Json.str(manifest, "outcome", "");
        if (!"victory".equals(outcome)) {
            report.actions = "NOT_RUN (terminal outcome is not a supported victory sample)";
            report.continuity = "NOT_RUN";
            report.terminalOutcome = "NOT_COVERED";
            report.terminalFields = "NOT_COMPARED (no captured terminal post-state)";
            report.strict = "NOT_PASSED";
            report.reason = "death terminal replay has no real-bundle regression yet";
            return report;
        }

        BundleData data = readBundle(bundle, manifest);
        validateTerminalEnvelope(manifest, data);
        List<String> classified = verifyJsonlContinuity(data);
        verifyActionEncoding(data);
        report.actions = "PASS jsonlPaired=" + data.actions.size() + " journal=" + data.bits.size()
                + " cardTargets=trusted finalAction=trusted-and-matches-last-post";

        String prefixOutput = runEngine(engine, "replay_trace", data.engineSave,
                writePrefix(data.bits, data.actions.size()), String.valueOf(data.actions.size()));
        requireReplayOk(prefixOutput, data.actions.size(), "nonterminal replay_trace");
        int summaryGroups = compareTraceSummary(prefixOutput, data);
        String boundaryText = classified.isEmpty() ? "none" : String.join(";", classified);
        report.continuity = "PASS_WITH_CLASSIFIED_NATIVE_TRANSITION compared=" + summaryGroups
                + " matched=" + summaryGroups + " fields (hp,maxHp,block,energy,turn,draw/discard/exhaust counts,hand order,live monster hp/block); boundary=" + boundaryText;

        String terminalOutput = runEngine(engine, "replay_search", data.engineSave,
                data.journal, "1");
        requireReplayOk(terminalOutput, data.bits.size(), "terminal replay_search");
        int expectedOutcome = 1; // sts_lightspeed Outcome::PLAYER_VICTORY
        int actualOutcome = terminalOutcome(terminalOutput);
        if (actualOutcome != expectedOutcome) {
            throw new CheckFailure("terminal-outcome", "manifest=victory expects outcome=1; engine returned "
                    + actualOutcome + " output=" + compact(terminalOutput));
        }
        report.terminalOutcome = "PASS terminal-outcome-match manifest=victory engine=PLAYER_VICTORY(1)"
                + " evidence=" + terminalLine(terminalOutput);
        report.terminalFields = "NOT_COMPARED (no real final post; unavailable: player hp/maxHp/block/energy/turn, hand and pile contents, card instances, monster hp/status/intent, relic state, RNG state)";
        boolean dirty = Json.bool(manifest, "journalDirty", false);
        report.strict = dirty ? "NOT_PASSED journalDirty=true finalActionStatus=unpaired-no-stable-post"
                : "NOT_PASSED terminal final post absent";
        report.reason = "terminal outcome comparison only; no full terminal-state comparison";
        return report;
    }

    private static void verifyArchive(File bundle, Map<String, Object> manifest, File engine) throws Exception {
        validateClosedStatus(Json.str(manifest, "status", ""));
        if (!engine.isFile()) throw new CheckFailure("archive", "engine executable is missing: " + engine);
        if (!"sts_lightspeed protocol v2".equals(Json.str(manifest, "engineProtocol", ""))
                || !Json.str(manifest, "engineDescription", "").startsWith("sts_lightspeed(")) {
            throw new CheckFailure("archive", "bundle does not identify sts_lightspeed protocol v2");
        }
        checkFileHash(bundle, Json.str(manifest, "recordingFile", "combat.jsonl"),
                Json.str(manifest, "recordingSha256", ""), "combat JSONL");
        checkFileHash(bundle, Json.str(manifest, "actionJournalFile", "actions.journal"),
                Json.str(manifest, "actionJournalSha256", ""), "action journal");
        if (!"captured".equals(Json.str(manifest, "rawAutosaveStatus", ""))) {
            throw new CheckFailure("archive", "raw autosave was not captured");
        }
        checkFileHash(bundle, Json.str(manifest, "rawAutosaveFile", "raw-autosave.save"),
                Json.str(manifest, "rawAutosaveSha256", ""), "raw autosave");
        if (!"prepared".equals(Json.str(manifest, "engineCombatSaveStatus", ""))) {
            throw new CheckFailure("archive", "engine combat save was not prepared");
        }
        checkFileHash(bundle, Json.str(manifest, "engineCombatSaveFile", ""),
                Json.str(manifest, "engineCombatSaveSha256", ""), "engine combat save");
        String declaredEngineHash = Json.str(manifest, "engineBinarySha256", "");
        if (declaredEngineHash.isEmpty() || !declaredEngineHash.equalsIgnoreCase(sha256(engine))) {
            throw new CheckFailure("archive", "engine executable SHA-256 mismatch");
        }
    }

    private static void checkFileHash(File bundle, String relative, String expected, String label) throws Exception {
        if (relative.isEmpty() || expected.isEmpty()) throw new CheckFailure("archive", label + " manifest entry is missing");
        File file = new File(bundle, relative).getCanonicalFile();
        String base = bundle.getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(base)) throw new CheckFailure("archive", label + " path escapes bundle");
        if (!file.isFile()) throw new CheckFailure("archive", label + " file is missing");
        if (!expected.equalsIgnoreCase(sha256(file))) throw new CheckFailure("archive", label + " SHA-256 mismatch");
    }

    private static void validateClosedStatus(String status) throws CheckFailure {
        if (!"closed".equals(status)) throw new CheckFailure("archive", "manifest.status is not closed");
    }

    private static BundleData readBundle(File bundle, Map<String, Object> manifest) throws Exception {
        BundleData data = new BundleData();
        data.bundle = bundle;
        data.manifest = manifest;
        data.engineSave = new File(bundle, Json.str(manifest, "engineCombatSaveFile", "engine/engine-in.save"));
        data.journal = new File(bundle, Json.str(manifest, "actionJournalFile", "actions.journal"));
        File jsonl = new File(bundle, Json.str(manifest, "recordingFile", "combat.jsonl"));
        for (String line : Files.readAllLines(jsonl.toPath(), StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) data.rows.add(Json.asMap(Json.parse(line)));
        }
        List<String> journalLines = Files.readAllLines(data.journal.toPath(), StandardCharsets.UTF_8);
        if (journalLines.isEmpty() || !("# battleId=" + Json.str(manifest, "battleId", "")).equals(journalLines.get(0))) {
            throw new CheckFailure("actions", "journal battleId header does not match manifest");
        }
        for (String line : journalLines) {
            if (line.startsWith("# actionMeta ")) {
                Map<String, String> meta = parseMeta(line.substring("# actionMeta ".length()));
                data.metadata.put(intValue(meta.get("index"), -1), meta);
            } else if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
                data.bits.add(line.trim());
            }
        }
        if (data.rows.isEmpty() || data.bits.isEmpty()) throw new CheckFailure("actions", "bundle has no root or actions");
        if (!data.engineSave.isFile()) throw new CheckFailure("archive", "engine combat save is missing");
        for (int i = 1; i < data.rows.size(); i++) data.actions.add(data.rows.get(i));
        if (data.bits.size() != Json.num(manifest, "actionCount", -1)) {
            throw new CheckFailure("actions", "journal action count differs from manifest");
        }
        return data;
    }

    private static void validateTerminalEnvelope(Map<String, Object> manifest, BundleData data) throws Exception {
        if (!"victory".equals(Json.str(manifest, "outcome", ""))) {
            throw new CheckFailure("terminal-outcome", "only real victory terminal regression is currently supported");
        }
        if (!Json.bool(manifest, "journalDirty", false)
                || !"unpaired-no-stable-post".equals(Json.str(manifest, "finalActionStatus", ""))) {
            throw new CheckFailure("actions", "expected explicit unpaired terminal action with journalDirty retained");
        }
        if (!TERMINAL_DIRTY_REASON.equals(Json.str(manifest, "journalDirtyReason", ""))) {
            throw new CheckFailure("actions", "journalDirty is not solely the expected terminal-post interruption");
        }
        int finalIndex = data.bits.size();
        if (data.actions.size() + 1 != finalIndex) {
            throw new CheckFailure("actions", "expected exactly one final unpaired action after paired JSONL actions");
        }
        Map<String, String> finalMeta = data.metadata.get(finalIndex);
        if (finalMeta == null || !"card".equals(finalMeta.get("type"))) {
            throw new CheckFailure("actions", "final action metadata is missing or is not a card");
        }
        if (!token(Json.str(manifest, "finalActionId", "")).equals(finalMeta.get("card"))) {
            throw new CheckFailure("actions", "manifest finalActionId differs from final journal metadata");
        }
        Map<String, Object> lastPaired = data.actions.get(data.actions.size() - 1);
        validateTerminalAction(data.bits.get(finalIndex - 1), lastPaired, finalMeta, finalIndex,
                Json.str(manifest, "finalActionId", ""));
    }

    private static void verifyActionEncoding(BundleData data) throws Exception {
        if (data.rows.size() != data.bits.size()) {
            throw new CheckFailure("actions", "paired JSONL action count must equal journal actions minus one terminal action");
        }
        for (int i = 0; i < data.actions.size(); i++) {
            Map<String, Object> row = data.actions.get(i);
            int actionNumber = i + 1;
            if ("card".equals(Json.str(row, "type", ""))) {
                Map<String, String> meta = data.metadata.get(actionNumber);
                if (meta == null) throw new CheckFailure("actions", "card action " + actionNumber + " lacks journal metadata");
                validateAction(data.bits.get(i), row, meta, actionNumber);
            } else if ("endTurn".equals(Json.str(row, "type", ""))) {
                if (parseBits(data.bits.get(i)) != (4L << 29)) {
                    throw new CheckFailure("actions", "action " + actionNumber + " is endTurn in JSONL but not in journal");
                }
            } else {
                throw new CheckFailure("actions", "unsupported paired JSONL action type at index " + actionNumber);
            }
        }
    }

    private static void validateAction(String bits, Map<String, Object> row, Map<String, String> meta,
                                       int actionNumber) throws Exception {
        long value = parseBits(bits);
        int type = (int) ((value >>> 29) & 7);
        int target = (int) ((value >>> 16) & 0x1fff);
        int source = (int) (value & 0xffff);
        if (type != 0 || !"card".equals(Json.str(row, "type", ""))) {
            throw new CheckFailure("actions", "action " + actionNumber + " is not encoded as the recorded card action");
        }
        Map<String, Object> card = Json.obj(row, "card");
        Map<String, Object> pre = Json.obj(row, "pre");
        List<Object> hand = Json.list(pre, "hand");
        if (source >= hand.size()) throw new CheckFailure("actions", "action " + actionNumber + " hand index is out of range");
        String cardId = Json.str(card, "id", "");
        String selected = Json.str(Json.asMap(hand.get(source)), "id", "");
        if (!selected.equals(cardId)) {
            throw new CheckFailure("actions", "action " + actionNumber + " hand index points to " + selected + " rather than " + cardId);
        }
        if (!Integer.toString(source).equals(meta.get("hand")) || !token(cardId).equals(meta.get("card"))) {
            throw new CheckFailure("actions", "action " + actionNumber + " journal metadata disagrees with hand index/card id");
        }
        if (!"true".equals(meta.get("targetTrusted")) || !Json.bool(row, "targetTrusted", false)) {
            throw new CheckFailure("actions", "action " + actionNumber + " has an untrusted target");
        }
        String mode = Json.str(row, "targetMode", "");
        if (!mode.equals(meta.get("targetMode"))) throw new CheckFailure("actions", "action " + actionNumber + " target mode differs");
        if ("single".equals(mode)) {
            int engineIndex = Json.num(row, "targetEngineIndex", -1);
            int liveIndex = Json.num(row, "targetLiveIndex", -1);
            String identity = Json.str(row, "targetIdentity", "");
            if (!row.containsKey("target") || Json.num(row, "target", -1) != engineIndex
                    || target != engineIndex || target != intValue(meta.get("targetEngineIndex"), -1)
                    || liveIndex != intValue(meta.get("targetLiveIndex"), -1)
                    || !identity.equals(meta.get("targetIdentity"))) {
                throw new CheckFailure("actions", "action " + actionNumber + " encoded target differs from trusted target metadata");
            }
            List<Object> mobs = Json.list(pre, "mobs");
            if (liveIndex < 0 || liveIndex >= mobs.size()) throw new CheckFailure("actions", "action " + actionNumber + " live target index is out of range");
            String mobId = Json.str(Json.asMap(mobs.get(liveIndex)), "id", "");
            if (!(mobId + "#" + (engineIndex + 1)).equals(identity)) {
                throw new CheckFailure("actions", "action " + actionNumber + " target identity does not resolve in the recorded live roster");
            }
            Map<String, Object> targetMob = Json.asMap(mobs.get(liveIndex));
            if (!Json.bool(targetMob, "alive", false) || Json.num(targetMob, "hp", 0) <= 0) {
                throw new CheckFailure("actions", "action " + actionNumber + " target was not alive in its recorded pre-state");
            }
        } else if ("none".equals(mode) || "self".equals(mode) || "all_enemies".equals(mode)) {
            if (!row.containsKey("target") || Json.num(row, "target", -1) != 0
                    || target != 0 || Json.num(row, "targetEngineIndex", -2) != -1
                    || Json.num(row, "targetLiveIndex", -2) != -1
                    || !Json.str(row, "targetIdentity", "").isEmpty()) {
                throw new CheckFailure("actions", "action " + actionNumber + " no-target mode contains a selected target");
            }
        } else throw new CheckFailure("actions", "action " + actionNumber + " has unsupported target mode " + mode);
    }

    /** Validate an unpaired terminal card against the previous real post, without inventing a post. */
    private static void validateTerminalAction(String bits, Map<String, Object> previousAction,
                                               Map<String, String> meta, int actionNumber,
                                               String manifestCardId) throws Exception {
        long value = parseBits(bits);
        int type = (int) ((value >>> 29) & 7);
        int target = (int) ((value >>> 16) & 0x1fff);
        int source = (int) (value & 0xffff);
        if (type != 0 || !"card".equals(meta.get("type"))) {
            throw new CheckFailure("actions", "terminal action " + actionNumber + " is not encoded as a card");
        }
        Map<String, Object> previousPost = Json.obj(previousAction, "post");
        List<Object> hand = Json.list(previousPost, "hand");
        if (source >= hand.size()) throw new CheckFailure("actions", "terminal action hand index is out of range");
        String selected = Json.str(Json.asMap(hand.get(source)), "id", "");
        if (!token(selected).equals(token(manifestCardId))
                || !Integer.toString(source).equals(meta.get("hand"))
                || !token(manifestCardId).equals(meta.get("card"))) {
            throw new CheckFailure("actions", "terminal action hand index/card id differs from last real post and journal metadata");
        }
        if (!"true".equals(meta.get("targetTrusted"))) {
            throw new CheckFailure("actions", "terminal action has an untrusted target");
        }
        String mode = meta.get("targetMode");
        if ("single".equals(mode)) {
            int engineIndex = intValue(meta.get("targetEngineIndex"), -1);
            int liveIndex = intValue(meta.get("targetLiveIndex"), -1);
            String identity = meta.get("targetIdentity");
            if (target != engineIndex) throw new CheckFailure("actions", "terminal action encoded target differs from trusted target metadata");
            List<Object> mobs = Json.list(previousPost, "mobs");
            if (liveIndex < 0 || liveIndex >= mobs.size()) throw new CheckFailure("actions", "terminal live target index is out of range");
            String mobId = Json.str(Json.asMap(mobs.get(liveIndex)), "id", "");
            if (!(mobId + "#" + (engineIndex + 1)).equals(identity)) {
                throw new CheckFailure("actions", "terminal target identity does not resolve in the last real roster");
            }
            Map<String, Object> targetMob = Json.asMap(mobs.get(liveIndex));
            if (!Json.bool(targetMob, "alive", false) || Json.num(targetMob, "hp", 0) <= 0) {
                throw new CheckFailure("actions", "terminal target was not alive in the last real post");
            }
        } else if ("none".equals(mode) || "self".equals(mode) || "all_enemies".equals(mode)) {
            if (target != 0 || intValue(meta.get("targetEngineIndex"), -2) != -1
                    || intValue(meta.get("targetLiveIndex"), -2) != -1
                    || (meta.get("targetIdentity") != null && !meta.get("targetIdentity").isEmpty())) {
                throw new CheckFailure("actions", "terminal no-target mode contains a selected target");
            }
        } else throw new CheckFailure("actions", "terminal action has unsupported target mode " + mode);
    }

    private static List<String> verifyJsonlContinuity(BundleData data) throws Exception {
        Map<String, Object> rootRow = data.rows.get(0);
        if (!"root".equals(Json.str(rootRow, "type", ""))) throw new CheckFailure("continuity", "first JSONL row is not root");
        String battleId = Json.str(data.manifest, "battleId", "");
        if (!battleId.equals(Json.str(rootRow, "battleKey", ""))) throw new CheckFailure("continuity", "root battleKey differs from manifest");
        Map<String, Object> root = Json.obj(rootRow, "state");
        if (data.actions.isEmpty() || !"card".equals(Json.str(data.actions.get(0), "type", ""))) {
            throw new CheckFailure("continuity", "first paired action must be a card for this terminal verifier");
        }
        List<String> classified = new ArrayList<String>();
        Map<String, Object> previous = root;
        for (int i = 0; i < data.actions.size(); i++) {
            Map<String, Object> action = data.actions.get(i);
            Map<String, Object> pre = Json.obj(action, "pre");
            Map<String, Object> post = Json.obj(action, "post");
            if ("card".equals(Json.str(action, "type", "")) && !Json.bool(action, "paired", false)) {
                throw new CheckFailure("continuity", "nonterminal card action " + (i + 1) + " is not paired");
            }
            List<Diff> diffs = differences(previous, pre, "");
            if (!diffs.isEmpty()) {
                String classification = classifyTargetDamageRefresh(previous, pre, action, data.metadata.get(i + 1), diffs, i + 2);
                if (classification == null) {
                    throw new CheckFailure("continuity", "nonterminal boundary before JSONL row " + (i + 2)
                            + " differs: " + formatDiffs(diffs));
                }
                classified.add(classification);
            }
            if (post.isEmpty()) throw new CheckFailure("continuity", "paired action " + (i + 1) + " has no post-state");
            previous = post;
        }
        return classified;
    }

    private static String classifyTargetDamageRefresh(Map<String, Object> previous, Map<String, Object> next,
                                                       Map<String, Object> action, Map<String, String> meta,
                                                       List<Diff> diffs, int jsonLine) {
        if (diffs.size() != 1 || meta == null) return null;
        Matcher path = Pattern.compile("^hand\\[(\\d+)]\\.damage$").matcher(diffs.get(0).path);
        if (!path.matches() || !"card".equals(Json.str(action, "type", ""))
                || !"single".equals(Json.str(action, "targetMode", ""))
                || !"true".equals(meta.get("targetTrusted"))) return null;
        int index = Integer.parseInt(path.group(1));
        List<Object> beforeHand = Json.list(previous, "hand");
        List<Object> nextHand = Json.list(next, "hand");
        if (index >= beforeHand.size() || index >= nextHand.size()
                || intValue(meta.get("hand"), -1) != index) return null;
        Map<String, Object> beforeCard = Json.asMap(beforeHand.get(index));
        Map<String, Object> nextCard = Json.asMap(nextHand.get(index));
        String cardId = Json.str(Json.obj(action, "card"), "id", "");
        if (!cardId.equals(Json.str(nextCard, "id", ""))
                || !cardId.equals(Json.str(beforeCard, "id", ""))
                || Json.num(Json.obj(action, "card"), "damage", Integer.MIN_VALUE)
                        != Json.num(nextCard, "damage", Integer.MAX_VALUE)) return null;
        int engineIndex = Json.num(action, "targetEngineIndex", -1);
        int liveIndex = Json.num(action, "targetLiveIndex", -1);
        List<Object> mobs = Json.list(next, "mobs");
        if (liveIndex < 0 || liveIndex >= mobs.size()) return null;
        Map<String, Object> target = Json.asMap(mobs.get(liveIndex));
        if (!(Json.str(target, "id", "") + "#" + (engineIndex + 1)).equals(Json.str(action, "targetIdentity", ""))) return null;
        if (Json.num(next, "playerWeak", 0) <= 0 || Json.num(target, "vuln", 0) <= 0) return null;
        return "row=" + jsonLine + " hand[" + index + "].damage " + diffs.get(0).before + "->"
                + diffs.get(0).after + " checked-as-native-target-recalculation target="
                + Json.str(action, "targetIdentity", "");
    }

    private static int compareTraceSummary(String output, BundleData data) throws Exception {
        Map<Integer, TraceState> trace = parseTraces(output);
        int groups = 0;
        Map<String, Object> root = Json.obj(data.rows.get(0), "state");
        groups += compareSummary(trace.get(0), root, "root");
        if (trace.size() < data.actions.size() + 1) throw new CheckFailure("continuity", "replay_trace omitted a required action state");
        for (int i = 0; i < data.actions.size(); i++) {
            groups += compareSummary(trace.get(i + 1), Json.obj(data.actions.get(i), "post"), "action " + (i + 1));
        }
        return groups;
    }

    private static int compareSummary(TraceState engine, Map<String, Object> game, String label) throws Exception {
        if (engine == null) throw new CheckFailure("continuity", label + " trace state missing");
        compare(label + ".hp", engine.hp, Json.num(game, "hp", Integer.MIN_VALUE));
        compare(label + ".maxHp", engine.maxHp, Json.num(game, "maxHp", Integer.MIN_VALUE));
        compare(label + ".block", engine.block, Json.num(game, "block", Integer.MIN_VALUE));
        compare(label + ".energy", engine.energy, Json.num(game, "energy", Integer.MIN_VALUE));
        compare(label + ".turn", engine.turn + 1, Json.num(game, "turn", Integer.MIN_VALUE));
        compare(label + ".drawCount", engine.draw, Json.list(game, "drawPile").size());
        compare(label + ".discardCount", engine.discard, Json.list(game, "discardPile").size());
        compare(label + ".exhaustCount", engine.exhaust, Json.list(game, "exhaustPile").size());
        List<String> gameHand = new ArrayList<String>();
        for (Object card : Json.list(game, "hand")) gameHand.add(normalize(Json.str(Json.asMap(card), "id", "")));
        compare(label + ".handOrder", engine.hand.toString(), gameHand.toString());
        List<String> gameMobs = new ArrayList<String>();
        for (Object object : Json.list(game, "mobs")) {
            Map<String, Object> mob = Json.asMap(object);
            if (Json.bool(mob, "alive", true) && Json.num(mob, "hp", 0) > 0) {
                gameMobs.add(Json.num(mob, "hp", -1) + ":" + Json.num(mob, "block", -1));
            }
        }
        compare(label + ".liveMonsterHpBlockOrder", engine.liveMonsters.toString(), gameMobs.toString());
        return 10;
    }

    private static void compare(String field, Object engine, Object game) throws CheckFailure {
        if (!String.valueOf(engine).equals(String.valueOf(game))) {
            throw new CheckFailure("continuity", field + " differs: engine=" + engine + " game=" + game);
        }
    }

    private static Map<Integer, TraceState> parseTraces(String output) throws CheckFailure {
        Map<Integer, TraceState> traces = new LinkedHashMap<Integer, TraceState>();
        for (String line : output.split("\\r?\\n")) {
            Matcher m = TRACE.matcher(line.trim());
            if (!m.matches()) continue;
            TraceState state = new TraceState();
            int index = Integer.parseInt(m.group(1));
            state.hp = Integer.parseInt(m.group(2)); state.maxHp = Integer.parseInt(m.group(3));
            state.block = Integer.parseInt(m.group(4)); state.energy = Integer.parseInt(m.group(5));
            state.turn = Integer.parseInt(m.group(6)); state.draw = Integer.parseInt(m.group(7));
            state.discard = Integer.parseInt(m.group(8)); state.exhaust = Integer.parseInt(m.group(9));
            if (!m.group(10).isEmpty()) for (String id : m.group(10).split(",")) state.hand.add(normalize(id));
            if (!m.group(11).isEmpty()) for (String pair : m.group(11).split("\\|")) {
                String[] values = pair.split(":");
                if (values.length == 2 && Integer.parseInt(values[0]) > 0) state.liveMonsters.add(values[0] + ":" + values[1]);
            }
            traces.put(index, state);
        }
        if (traces.isEmpty()) throw new CheckFailure("continuity", "engine emitted no TRACE states");
        return traces;
    }

    private static String runEngine(File engine, String mode, File save, File actions, String count) throws Exception {
        List<String> command = Arrays.asList(engine.getPath(), mode, save.getPath(), actions.getPath(), count);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), bytes));
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new CheckFailure("engine", mode + " timed out");
        }
        reader.join(5000L);
        String output = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new CheckFailure("engine", mode + " exited " + process.exitValue() + ": " + compact(output));
        return output;
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (InputStream in = input) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) output.write(buffer, 0, n);
        } catch (Exception ignored) { }
    }

    private static File writePrefix(List<String> bits, int count) throws Exception {
        File file = File.createTempFile("combatsolver1-terminal-prefix-", ".journal");
        List<String> prefix = new ArrayList<String>(bits.subList(0, count));
        Files.write(file.toPath(), prefix, StandardCharsets.UTF_8);
        file.deleteOnExit();
        return file;
    }

    private static void requireReplayOk(String output, int count, String label) throws CheckFailure {
        if (output.contains("REPLAY_INVALID")) throw new CheckFailure("actions", label + " rejected action: " + compact(output));
        if (!output.contains("REPLAY_OK n=" + count)) throw new CheckFailure("actions", label + " did not confirm all actions: " + compact(output));
    }

    private static int terminalOutcome(String output) throws CheckFailure {
        Matcher m = PLANEND.matcher(output);
        if (!m.find()) throw new CheckFailure("terminal-outcome", "engine output has no PLANEND terminal result");
        return Integer.parseInt(m.group(1));
    }

    private static String terminalLine(String output) {
        for (String line : output.split("\\r?\\n")) if (line.startsWith("PLANEND ")) return line.trim();
        return "missing";
    }

    private static long parseBits(String hex) throws CheckFailure {
        try {
            long value = Long.parseLong(hex, 16);
            if (value < 0 || value > 0xffffffffL) throw new NumberFormatException("outside 32-bit range");
            return value;
        } catch (NumberFormatException e) { throw new CheckFailure("actions", "invalid action bits: " + hex); }
    }

    private static int decodeType(String hex) throws CheckFailure { return (int) ((parseBits(hex) >>> 29) & 7); }

    private static List<Diff> differences(Object left, Object right, String path) {
        List<Diff> diffs = new ArrayList<Diff>();
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> a = (Map<?, ?>) left; Map<?, ?> b = (Map<?, ?>) right;
            List<String> keys = new ArrayList<String>();
            for (Object key : a.keySet()) keys.add(String.valueOf(key));
            for (Object key : b.keySet()) if (!keys.contains(String.valueOf(key))) keys.add(String.valueOf(key));
            Collections.sort(keys);
            for (String key : keys) {
                String child = path.isEmpty() ? key : path + "." + key;
                diffs.addAll(differences(a.get(key), b.get(key), child));
            }
        } else if (left instanceof List && right instanceof List) {
            List<?> a = (List<?>) left; List<?> b = (List<?>) right;
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) diffs.addAll(differences(i < a.size() ? a.get(i) : null,
                    i < b.size() ? b.get(i) : null, path + "[" + i + "]"));
        } else if (!same(left, right)) diffs.add(new Diff(path, left, right));
        return diffs;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        return a == null ? b == null : a.equals(b);
    }

    private static String formatDiffs(List<Diff> diffs) {
        List<String> values = new ArrayList<String>();
        for (Diff diff : diffs) values.add(diff.path + "=" + diff.before + "->" + diff.after);
        return String.join(",", values);
    }

    private static Map<String, Object> readObject(File file) throws Exception {
        return Json.asMap(Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
    }

    private static Map<String, String> parseMeta(String line) {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        Matcher m = META.matcher(line);
        while (m.find()) fields.put(m.group(1), m.group(2));
        return fields;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static File engineFromDescription(String description) {
        int open = description.lastIndexOf('('); int close = description.lastIndexOf(')');
        if (open < 0 || close <= open) return new File("build/engine/combatsolver1-engine.exe");
        return new File(description.substring(open + 1, close));
    }

    private static String token(String id) { return id.replace(' ', '_'); }
    private static String normalize(String id) { return id.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT); }
    private static int intValue(String text, int fallback) { try { return Integer.parseInt(text); } catch (Exception e) { return fallback; } }
    private static String safe(String text) { return text.replace('\n', ' ').replace('\r', ' '); }
    private static String compact(String text) { return safe(text).replaceAll("\\s+", " ").trim(); }

    static void validateActionForTest(String bits, Map<String, Object> row, Map<String, String> meta) throws Exception {
        validateAction(bits, row, meta, 1);
    }

    static void validateOutcomeForTest(String expected, String output) throws Exception {
        int expectedCode = "victory".equals(expected) ? 1 : "death".equals(expected) ? 2 : -1;
        if (expectedCode < 0 || terminalOutcome(output) != expectedCode) {
            throw new CheckFailure("terminal-outcome", "outcome mismatch");
        }
    }

    static void validateEndTurnForTest(String bits) throws Exception {
        if (parseBits(bits) != (4L << 29)) throw new CheckFailure("actions", "endTurn bits differ");
    }

    static void validateArchiveStatusForTest(String status) throws Exception { validateClosedStatus(status); }

    static void validateFileHashForTest(File bundle, String relative, String expected) throws Exception {
        checkFileHash(bundle, relative, expected, "test file");
    }

    static void validateTerminalActionForTest(String bits, Map<String, Object> previousAction,
                                              Map<String, String> meta, String cardId) throws Exception {
        validateTerminalAction(bits, previousAction, meta, 9, cardId);
    }

    static List<String> continuityForTest(Map<String, Object> previous, Map<String, Object> next,
                                          Map<String, Object> action, Map<String, String> meta) throws Exception {
        List<Diff> diffs = differences(previous, next, "");
        if (diffs.isEmpty()) return Collections.emptyList();
        String classified = classifyTargetDamageRefresh(previous, next, action, meta, diffs, 7);
        if (classified == null) throw new CheckFailure("continuity", formatDiffs(diffs));
        return Collections.singletonList(classified);
    }

    static final class CheckFailure extends Exception {
        final String stage;
        CheckFailure(String stage, String message) { super(message); this.stage = stage; }
    }

    private static final class BundleData {
        File bundle, engineSave, journal;
        Map<String, Object> manifest;
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        final List<String> bits = new ArrayList<String>();
        final Map<Integer, Map<String, String>> metadata = new LinkedHashMap<Integer, Map<String, String>>();
    }

    private static final class TraceState {
        int hp, maxHp, block, energy, turn, draw, discard, exhaust;
        final List<String> hand = new ArrayList<String>();
        final List<String> liveMonsters = new ArrayList<String>();
    }

    private static final class Diff {
        final String path; final Object before, after;
        Diff(String path, Object before, Object after) { this.path = path; this.before = before; this.after = after; }
    }

    private static final class Report {
        String archive = "NOT_RUN", continuity = "NOT_RUN", actions = "NOT_RUN";
        String terminalOutcome = "NOT_RUN", terminalFields = "NOT_RUN", strict = "NOT_RUN", reason = "";
        boolean failed;
        void print() {
            System.out.println("archive_seal_hash=" + archive);
            System.out.println("nonterminal_continuity=" + continuity);
            System.out.println("action_encoding_target_trust=" + actions);
            System.out.println("terminal_outcome=" + terminalOutcome);
            System.out.println("terminal_all_fields=" + terminalFields);
            System.out.println("strict_full_state_acceptance=" + strict);
            System.out.println("terminal_replay_result=" + (terminalOutcome.startsWith("PASS") ? "TERMINAL_OUTCOME_MATCH_ONLY" : terminalOutcome));
            if (!reason.isEmpty()) System.out.println("reason=" + reason);
        }
    }
}

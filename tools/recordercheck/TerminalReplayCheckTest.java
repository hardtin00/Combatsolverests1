import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small protocol regressions for the offline terminal verifier; no captured data is modified. */
public final class TerminalReplayCheckTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        acceptsTerminalCardAgainstLastRealPost();
        rejectsUntrustedOrdinaryTarget();
        rejectsWrongOrdinaryHandEncoding();
        rejectsJsonlTargetEncodingMismatch();
        rejectsNoncanonicalEndTurnBits();
        rejectsBrokenNonterminalBoundary();
        classifiesOnlySupportedTargetDamageRefresh();
        rejectsWrongTerminalOutcome();
        rejectsUntrustedTerminalTarget();
        rejectsOpenArchive();
        rejectsHashMismatch();
        System.out.println("TerminalReplayCheckTest passed=" + passed + " failed=0");
    }

    private static void acceptsTerminalCardAgainstLastRealPost() throws Exception {
        Map<String, Object> previous = object("{\"post\":{\"hand\":[{\"id\":\"Defend_R\"},{\"id\":\"Strike_R\"}],"
                + "\"mobs\":[{\"id\":\"AcidSlime_S\",\"alive\":true,\"hp\":1}]}} ");
        Map<String, String> meta = meta("card", "Strike_R", "1", true, "single", "0", "0", "AcidSlime_S#1");
        TerminalReplayCheck.validateTerminalActionForTest("00000001", previous, meta, "Strike_R");
        pass();
    }

    private static void rejectsUntrustedOrdinaryTarget() throws Exception {
        Map<String, Object> row = object("{\"type\":\"card\",\"card\":{\"id\":\"Strike_R\"},"
                + "\"pre\":{\"hand\":[{\"id\":\"Strike_R\"}],\"mobs\":[{\"id\":\"AcidSlime_S\"}]},"
                + "\"targetTrusted\":false,\"targetMode\":\"single\",\"targetEngineIndex\":0,"
                + "\"targetLiveIndex\":0,\"targetIdentity\":\"AcidSlime_S#1\"}");
        Map<String, String> meta = meta("card", "Strike_R", "0", false, "single", "0", "0", "AcidSlime_S#1");
        reject(() -> TerminalReplayCheck.validateActionForTest("00000000", row, meta));
    }

    private static void rejectsWrongOrdinaryHandEncoding() throws Exception {
        Map<String, Object> row = object("{\"type\":\"card\",\"card\":{\"id\":\"Strike_R\"},"
                + "\"pre\":{\"hand\":[{\"id\":\"Strike_R\"}],\"mobs\":[]},"
                + "\"targetTrusted\":true,\"targetMode\":\"none\",\"targetEngineIndex\":-1,"
                + "\"targetLiveIndex\":-1,\"targetIdentity\":\"\"}");
        Map<String, String> meta = meta("card", "Strike_R", "0", true, "none", "-1", "-1", "");
        reject(() -> TerminalReplayCheck.validateActionForTest("00000001", row, meta));
    }

    private static void rejectsJsonlTargetEncodingMismatch() throws Exception {
        Map<String, Object> row = object("{\"type\":\"card\",\"card\":{\"id\":\"Strike_R\"},\"target\":1,"
                + "\"pre\":{\"hand\":[{\"id\":\"Strike_R\"}],\"mobs\":[{\"id\":\"AcidSlime_S\",\"alive\":true,\"hp\":1}]},"
                + "\"targetTrusted\":true,\"targetMode\":\"single\",\"targetEngineIndex\":0,"
                + "\"targetLiveIndex\":0,\"targetIdentity\":\"AcidSlime_S#1\"}");
        Map<String, String> meta = meta("card", "Strike_R", "0", true, "single", "0", "0", "AcidSlime_S#1");
        reject(() -> TerminalReplayCheck.validateActionForTest("00000000", row, meta));
    }

    private static void rejectsNoncanonicalEndTurnBits() throws Exception {
        reject(() -> TerminalReplayCheck.validateEndTurnForTest("80000001"));
        TerminalReplayCheck.validateEndTurnForTest("80000000");
        pass();
    }

    private static void rejectsBrokenNonterminalBoundary() throws Exception {
        Map<String, Object> before = object("{\"hp\":48,\"hand\":[]}");
        Map<String, Object> after = object("{\"hp\":47,\"hand\":[]}");
        Map<String, Object> action = object("{\"type\":\"card\",\"card\":{\"id\":\"Strike_R\"}}");
        Map<String, String> meta = meta("card", "Strike_R", "0", true, "single", "0", "0", "AcidSlime_S#1");
        reject(() -> TerminalReplayCheck.continuityForTest(before, after, action, meta));
    }

    private static void classifiesOnlySupportedTargetDamageRefresh() throws Exception {
        Map<String, Object> before = object("{\"hand\":[{\"id\":\"Strike_R\",\"damage\":4}],"
                + "\"mobs\":[{\"id\":\"SpikeSlime_M\",\"vuln\":2}],\"playerWeak\":1}");
        Map<String, Object> after = object("{\"hand\":[{\"id\":\"Strike_R\",\"damage\":6}],"
                + "\"mobs\":[{\"id\":\"SpikeSlime_M\",\"vuln\":2}],\"playerWeak\":1}");
        Map<String, Object> action = object("{\"type\":\"card\",\"card\":{\"id\":\"Strike_R\",\"damage\":6},"
                + "\"targetMode\":\"single\",\"targetLiveIndex\":0,\"targetEngineIndex\":0,"
                + "\"targetIdentity\":\"SpikeSlime_M#1\"}");
        Map<String, String> meta = meta("card", "Strike_R", "0", true, "single", "0", "0", "SpikeSlime_M#1");
        if (TerminalReplayCheck.continuityForTest(before, after, action, meta).size() != 1) {
            throw new AssertionError("expected one explicitly classified target damage refresh");
        }
        pass();
    }

    private static void rejectsWrongTerminalOutcome() throws Exception {
        reject(() -> TerminalReplayCheck.validateOutcomeForTest("victory", "PLANEND hp=0 outcome=2 mons=0:0"));
    }

    private static void rejectsUntrustedTerminalTarget() throws Exception {
        Map<String, Object> previous = object("{\"post\":{\"hand\":[{\"id\":\"Strike_R\"}],"
                + "\"mobs\":[{\"id\":\"AcidSlime_S\",\"alive\":true,\"hp\":1}]}} ");
        Map<String, String> meta = meta("card", "Strike_R", "0", false, "single", "0", "0", "AcidSlime_S#1");
        reject(() -> TerminalReplayCheck.validateTerminalActionForTest("00000000", previous, meta, "Strike_R"));
    }

    private static void rejectsOpenArchive() throws Exception {
        reject(() -> TerminalReplayCheck.validateArchiveStatusForTest("open"));
    }

    private static void rejectsHashMismatch() throws Exception {
        Path temp = Files.createTempDirectory("terminal-check-hash-");
        Path file = temp.resolve("capture.bin");
        Files.write(file, new byte[] { 1, 2, 3 });
        try {
            reject(() -> TerminalReplayCheck.validateFileHashForTest(temp.toFile(), "capture.bin", "00"));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, String> meta(String type, String card, String hand, boolean trusted,
                                            String mode, String engineIndex, String liveIndex, String identity) {
        Map<String, String> value = new LinkedHashMap<String, String>();
        value.put("type", type); value.put("card", card); value.put("hand", hand);
        value.put("targetTrusted", String.valueOf(trusted)); value.put("targetMode", mode);
        value.put("targetEngineIndex", engineIndex); value.put("targetLiveIndex", liveIndex);
        value.put("targetIdentity", identity);
        return value;
    }

    private static Map<String, Object> object(String json) { return Json.asMap(Json.parse(json)); }

    private static void reject(CheckedRunnable action) throws Exception {
        try { action.run(); }
        catch (TerminalReplayCheck.CheckFailure expected) { pass(); return; }
        throw new AssertionError("expected verifier rejection");
    }

    private static void pass() { passed++; }

    private interface CheckedRunnable { void run() throws Exception; }
}

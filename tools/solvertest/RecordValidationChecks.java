import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Invalid input must not inflate simulation error or success counts. */
public final class RecordValidationChecks {
    public static void run() throws Exception {
        List<String> sample = Files.readAllLines(java.nio.file.Paths.get(
                "tools/solvertest/fixtures/recordings/sample.jsonl"), StandardCharsets.UTF_8);
        String action = sample.get(1);
        // Change the action's card identity only, leaving pre/post unchanged.
        int cardKey = action.indexOf("\"card\":");
        if (cardKey < 0) throw new AssertionError("fixture has no card");
        String invalid = action.substring(0, cardKey)
                + action.substring(cardKey).replaceFirst("Strike_R", "MissingCard");
        Path file = Files.createTempFile("combatsolver-record-validation-", ".jsonl");
        try {
            Files.write(file, java.util.Arrays.asList(invalid, "{invalid-json"), StandardCharsets.UTF_8);
            DiffRunner.Result r = DiffRunner.run(file.toString());
            if (r.dataIssues != 2 || r.badActions != 0 || r.totalFields != 0) {
                throw new AssertionError("invalid records counted as simulation outcomes");
            }
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.println("Record validation regression passed.");
    }
}

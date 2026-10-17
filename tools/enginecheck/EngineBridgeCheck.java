import com.combatsolver1.runtime.EngineBridge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Compile against the full Mod classes; no live game or UI required. */
public final class EngineBridgeCheck {
    public static void main(String[] args) throws Exception {
        EngineBridge.configure(args[0], "");
        EngineBridge.Fetch first = EngineBridge.fetchPlan(args[1], 100);
        if (first == null || first.plan == null || first.state0 == null)
            throw new AssertionError("search output did not parse");
        EngineBridge.Fetch replay = EngineBridge.fetchReplayPlan(args[1], args[2], 100);
        if (replay == null || replay.plan == null || replay.replayActions != 3)
            throw new AssertionError("three-action replay did not parse");
        Path invalid = Files.createTempFile("combatsolver-invalid-replay-", ".txt");
        try {
            Files.write(invalid, java.util.Collections.singletonList("not-hex"), StandardCharsets.US_ASCII);
            if (EngineBridge.fetchReplayPlan(args[1], invalid.toString(), 1) != null)
                throw new AssertionError("invalid replay accepted");
        } finally { Files.deleteIfExists(invalid); }
        System.out.println("Engine bridge: search, replay and invalid-output rejection passed.");
    }
}

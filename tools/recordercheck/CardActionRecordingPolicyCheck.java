import com.combatsolver1.runtime.CardActionRecordingPolicy;

public final class CardActionRecordingPolicyCheck {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(!CardActionRecordingPolicy.shouldRecord("CURSE", false),
                "unplayable curse must not become a player journal action");
        check(CardActionRecordingPolicy.shouldRecord("CURSE", true),
                "Blue Candle makes a curse a legitimate recorded player action");
        check(CardActionRecordingPolicy.shouldRecord("ATTACK", false),
                "ordinary attacks must remain recorded");
        check(CardActionRecordingPolicy.shouldRecord("SKILL", false),
                "ordinary skills must remain recorded");
        check(CardActionRecordingPolicy.shouldRecord("POWER", false),
                "ordinary powers must remain recorded");
        check(!CardActionRecordingPolicy.shouldRecord(null, false),
                "unknown card types must fail closed");
        System.out.println("CardActionRecordingPolicyCheck passed=6 failed=0");
    }
}

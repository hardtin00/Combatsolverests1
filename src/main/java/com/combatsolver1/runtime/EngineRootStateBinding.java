package com.combatsolver1.runtime;

/** Strict STATE0/STATE to live-root gate shared by initial and replay plan adoption. */
public final class EngineRootStateBinding {
    private static final String[] REQUIRED = {"hp=", "blk=", "en=", "hand=[", "mons="};

    private EngineRootStateBinding() { }

    public static boolean matches(String engineState, String liveState) {
        if (engineState == null || liveState == null) return false;
        for (String key : REQUIRED) {
            String engineValue = field(engineState, key);
            String liveValue = field(liveState, key);
            if (engineValue == null || liveValue == null || !engineValue.equals(liveValue)) return false;
        }
        int engineTurn = intField(engineState, "turn=", -99);
        int liveTurn = intField(liveState, "turn=", -99);
        return engineTurn >= 0 && liveTurn >= 1 && engineTurn + 1 == liveTurn;
    }

    private static String field(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) return null;
        int start = at + key.length();
        int end = start;
        if (key.endsWith("[")) {
            while (end < line.length() && line.charAt(end) != ']') end++;
            if (end < line.length()) end++;
        } else {
            while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        }
        return end == start ? null : line.substring(start, end);
    }

    private static int intField(String line, String key, int fallback) {
        String value = field(line, key);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}

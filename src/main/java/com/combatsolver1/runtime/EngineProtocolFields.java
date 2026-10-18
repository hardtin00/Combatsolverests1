package com.combatsolver1.runtime;

/** Parsers for protocol fields whose values may contain spaces. */
final class EngineProtocolFields {
    private EngineProtocolFields() { }

    /** Reads the full C++ PLANPOTION ID between its delimiters; null means malformed protocol. */
    static String potionId(String line) {
        if (line == null || !line.startsWith("PLANPOTION ")) return null;
        int id = line.indexOf(" id=");
        if (id < 0) return null;
        int start = id + 4;
        int target = line.indexOf(" tgt=", start);
        if (target <= start) return null;
        String value = line.substring(start, target).trim();
        return value.isEmpty() ? null : value;
    }

    static boolean samePotionId(String liveId, String plannedId) {
        return plannedId != null && plannedId.equals(liveId);
    }
}

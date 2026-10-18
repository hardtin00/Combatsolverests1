package com.combatsolver1.runtime;

import java.util.Locale;

/** Pure policy for binding a save observed in the native Continue loader to one battle root. */
public final class LoadedSaveEvidence {
    private LoadedSaveEvidence() { }

    public static final class Facts {
        public final String sourcePath;
        public final String expectedPath;
        public final String sourceSha256;
        public final boolean loadedContentVerified;
        public final Object saveObjectIdentity;
        public final String character;
        public final int floor;
        public final long seed;
        public final String room;
        public final String encounter;
        public final int hp;
        public final int maxHp;
        public final String deck;
        public final String relics;
        public final String potions;

        public Facts(String sourcePath, String expectedPath, String sourceSha256,
                     boolean loadedContentVerified, Object saveObjectIdentity,
                     String character, int floor, long seed, String room, String encounter,
                     int hp, int maxHp, String deck, String relics, String potions) {
            this.sourcePath = value(sourcePath);
            this.expectedPath = value(expectedPath);
            this.sourceSha256 = value(sourceSha256);
            this.loadedContentVerified = loadedContentVerified;
            this.saveObjectIdentity = saveObjectIdentity;
            this.character = value(character);
            this.floor = floor;
            this.seed = seed;
            this.room = value(room);
            this.encounter = value(encounter);
            this.hp = hp;
            this.maxHp = maxHp;
            this.deck = value(deck);
            this.relics = value(relics);
            this.potions = value(potions);
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        private Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    /** One-shot claim so a Continue-load token cannot be reused for a later battle. */
    public static final class Lease {
        private final Facts facts;
        private boolean consumed;

        public Lease(Facts facts) { this.facts = facts; }

        public synchronized Decision consume(Facts live, Object activeSaveObject) {
            if (consumed) return deny("native load evidence already consumed");
            consumed = true;
            return validateForBattle(facts, live, activeSaveObject);
        }
    }

    /**
     * A stale timestamp is deliberately irrelevant here: acceptance requires proof that this exact
     * source was read by CardCrawlGame's native loader and that its saved run/root still matches.
     */
    public static Decision validateForBattle(Facts loaded, Facts live, Object activeSaveObject) {
        if (loaded == null) return deny("no native save-load evidence");
        if (!loaded.loadedContentVerified) return deny("native loaded bytes were not verified");
        if (loaded.sourceSha256.length() != 64 || !isHex(loaded.sourceSha256)) {
            return deny("native loaded save hash is missing or invalid");
        }
        if (!trustedSourcePath(loaded.sourcePath, loaded.expectedPath)) {
            return deny("native loaded source path does not match the active character save slot");
        }
        if (loaded.saveObjectIdentity == null || loaded.saveObjectIdentity != activeSaveObject
                || live == null || live.saveObjectIdentity != activeSaveObject) {
            return deny("active SaveFile identity differs from the natively loaded object");
        }
        if (!loaded.character.equals(live.character)) return deny("character mismatch");
        if (!"IRONCLAD".equals(loaded.character)) return deny("external sts_lightspeed input supports IRONCLAD only");
        if (loaded.floor != live.floor) return deny("floor mismatch");
        if (loaded.seed != live.seed) return deny("seed mismatch");
        String encounterKey = encounterKey(live.room);
        String sourceEncounter = encounterForKey(loaded.encounter, encounterKey);
        String liveEncounter = encounterForKey(live.encounter, encounterKey);
        if (loaded.room.isEmpty() || encounterKey == null || sourceEncounter == null || sourceEncounter.isEmpty()
                || liveEncounter == null || liveEncounter.isEmpty() || !sourceEncounter.equals(liveEncounter)) {
            return deny("monster encounter mismatch or unavailable");
        }
        if (loaded.hp != live.hp || loaded.maxHp != live.maxHp) return deny("player HP root mismatch");
        if (loaded.deck.isEmpty() || !loaded.deck.equals(live.deck)) return deny("master deck mismatch or unavailable");
        if (loaded.relics.isEmpty() || !loaded.relics.equals(live.relics)) return deny("relic state mismatch or unavailable");
        if (!loaded.potions.equals(live.potions)) return deny("potion state mismatch");
        return new Decision(true, loaded.room.equals(live.room)
                ? "native loaded save and live battle identity matched"
                : "native loaded run root rebound to current battle room; live root must still match engine STATE0");
    }

    private static String encounterKey(String room) {
        if (room.endsWith("MonsterRoomElite")) return "elite";
        if (room.endsWith("MonsterRoomBoss")) return "boss";
        if (room.endsWith("MonsterRoom")) return "normal";
        return null;
    }

    private static String encounterForKey(String encoded, String key) {
        if (encoded == null || key == null) return null;
        String prefix = key + "=";
        String[] entries = encoded.split(";");
        for (String entry : entries) if (entry.startsWith(prefix)) return entry.substring(prefix.length());
        return null;
    }

    private static boolean trustedSourcePath(String source, String expected) {
        String actual = normalize(source);
        String main = normalize(expected);
        actual = actual.toLowerCase(Locale.ROOT);
        main = main.toLowerCase(Locale.ROOT);
        if (main.isEmpty()) return false;
        if (actual.equals(main) || actual.equals(main + ".backup")) return true;
        String equivalentPrefix = "native-equivalent:";
        if (!actual.startsWith(equivalentPrefix)) return false;
        String[] candidates = actual.substring(equivalentPrefix.length()).split("\\|", -1);
        if (candidates.length != 2) return false;
        return candidates[0].equals(main) && candidates[1].equals(main + ".backup");
    }

    private static String normalize(String value) {
        String normalized = value(value).replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }
    private static Decision deny(String reason) { return new Decision(false, reason); }
}

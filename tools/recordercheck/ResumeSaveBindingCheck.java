package com.combatsolver1.runtime;

/** Regression for binding a stale-on-disk Continue save to one live battle root. */
public final class ResumeSaveBindingCheck {
    private ResumeSaveBindingCheck() { }

    public static void main(String[] args) {
        Object saveObject = new Object();
        LoadedSaveEvidence.Facts loaded = facts("saves/IRONCLAD.autosave", "saves/IRONCLAD.autosave",
                repeat('a', 64), true, saveObject, "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.EventRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");
        LoadedSaveEvidence.Facts live = facts("", "", "", false, saveObject, "IRONCLAD", 12,
                123456789L, "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");

        // No timestamp is part of this trust decision. Native byte-read evidence and all run/root
        // fields match, so a valid loaded save from before battle start is accepted.
        allow(loaded, live, saveObject, "verified Continue save with old mtime");
        LoadedSaveEvidence.Lease oneShot = new LoadedSaveEvidence.Lease(loaded);
        allow(oneShot.consume(live, saveObject), "first battle claim on native load token");
        rejectDecision(oneShot.consume(live, saveObject), "native load token reused for another battle", "consumed");
        LoadedSaveEvidence.Facts backup = facts("saves/IRONCLAD.autosave.backUp",
                "saves/IRONCLAD.autosave", repeat('b', 64), true, saveObject, "IRONCLAD", 12,
                123456789L, "com.megacrit.cardcrawl.rooms.EventRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");
        allow(backup, live, saveObject, "native corrupt-save backup and pre-battle room transition");
        LoadedSaveEvidence.Facts identicalSlotPayload = facts(
                "native-equivalent:saves/IRONCLAD.autosave|saves/IRONCLAD.autosave.backUp",
                "saves/IRONCLAD.autosave", repeat('d', 64), true, saveObject, "IRONCLAD", 12,
                123456789L, "com.megacrit.cardcrawl.rooms.EventRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");
        allow(identicalSlotPayload, live, saveObject,
                "byte-identical primary/backup payload records source-set evidence");

        reject(null, live, saveObject, "no native loaded-save token", "native");
        reject(facts("saves/IRONCLAD.autosave", "saves/IRONCLAD.autosave", "", false,
                saveObject, "IRONCLAD", 12, 123456789L, "com.megacrit.cardcrawl.rooms.MonsterRoom",
                "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1"),
                live, saveObject, "path/mtime without verified native bytes", "verified");
        reject(facts("saves/SILENT.autosave", "saves/IRONCLAD.autosave", repeat('a', 64), true,
                saveObject, "IRONCLAD", 12, 123456789L, "com.megacrit.cardcrawl.rooms.MonsterRoom",
                "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1"),
                live, saveObject, "wrong save slot", "path");
        rejectWithLiveChange(loaded, live, saveObject, "character", "SILENT", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1");
        LoadedSaveEvidence.Facts silentLoaded = facts("saves/SILENT.autosave", "saves/SILENT.autosave",
                repeat('c', 64), true, saveObject, "SILENT", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");
        LoadedSaveEvidence.Facts silentLive = facts("", "", "", false, saveObject, "SILENT", 12,
                123456789L, "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75,
                "deck-v1", "relics-v1", "potions-v1");
        reject(silentLoaded, silentLive, saveObject, "same-root unsupported character", "IRONCLAD only");
        rejectWithLiveChange(loaded, live, saveObject, "floor", "IRONCLAD", 13, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "seed", "IRONCLAD", 12, 123456788L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "unsupported room", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.EventRoom", "Sentries", 54, 75, "deck-v1", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "monster encounter", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "GremlinGang", 54, 75, "deck-v1", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "HP", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 53, 75, "deck-v1", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "deck", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "other-deck", "relics-v1", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "relic", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "deck-v1", "other-relics", "potions-v1");
        rejectWithLiveChange(loaded, live, saveObject, "potion", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "Sentries", 54, 75, "deck-v1", "relics-v1", "other-potions");
        reject(loaded, live, new Object(), "changed active SaveFile object", "identity");
        rejectWithLiveChange(loaded, live, saveObject, "missing encounter", "IRONCLAD", 12, 123456789L,
                "com.megacrit.cardcrawl.rooms.MonsterRoom", "", 54, 75, "deck-v1", "relics-v1", "potions-v1");

        System.out.println("ResumeSaveBindingCheck passed: valid stale native loads accepted; "
                + "identical main/backup source set accepted explicitly; missing provenance and 12 wrong-source/root cases rejected.");
    }

    private static void allow(LoadedSaveEvidence.Facts loaded, LoadedSaveEvidence.Facts live,
                              Object activeSaveObject, String scenario) {
        LoadedSaveEvidence.Decision decision = LoadedSaveEvidence.validateForBattle(loaded, live, activeSaveObject);
        if (!decision.allowed) throw new AssertionError(scenario + " rejected: " + decision.reason);
    }

    private static void reject(LoadedSaveEvidence.Facts loaded, LoadedSaveEvidence.Facts live,
                               Object activeSaveObject, String scenario, String reasonPart) {
        LoadedSaveEvidence.Decision decision = LoadedSaveEvidence.validateForBattle(loaded, live, activeSaveObject);
        if (decision.allowed) throw new AssertionError(scenario + " accepted");
        if (!decision.reason.toLowerCase(java.util.Locale.ROOT)
                .contains(reasonPart.toLowerCase(java.util.Locale.ROOT))) {
            throw new AssertionError(scenario + " did not report " + reasonPart + ": " + decision.reason);
        }
    }

    private static void allow(LoadedSaveEvidence.Decision decision, String scenario) {
        if (!decision.allowed) throw new AssertionError(scenario + " rejected: " + decision.reason);
    }

    private static void rejectDecision(LoadedSaveEvidence.Decision decision, String scenario, String reasonPart) {
        if (decision.allowed || !decision.reason.toLowerCase(java.util.Locale.ROOT)
                .contains(reasonPart.toLowerCase(java.util.Locale.ROOT))) {
            throw new AssertionError(scenario + " was not safely rejected: " + decision.reason);
        }
    }

    private static void rejectWithLiveChange(LoadedSaveEvidence.Facts loaded, LoadedSaveEvidence.Facts baseline,
            Object identity, String scenario, String character, int floor, long seed, String room,
            String encounter, int hp, int maxHp, String deck, String relics, String potions) {
        LoadedSaveEvidence.Facts live = facts("", "", "", false, identity, character, floor, seed,
                room, encounter, hp, maxHp, deck, relics, potions);
        String expectedReason = scenario.toLowerCase(java.util.Locale.ROOT).contains("encounter")
                || "unsupported room".equals(scenario)
                ? "encounter" : scenario;
        reject(loaded, live, identity, "live " + scenario + " mismatch", expectedReason);
    }

    private static LoadedSaveEvidence.Facts facts(String sourcePath, String expectedPath, String hash,
            boolean verified, Object saveObject, String character, int floor, long seed, String room,
            String encounter, int hp, int maxHp, String deck, String relics, String potions) {
        String encodedEncounter;
        if (verified) {
            encodedEncounter = "normal=" + encounter + ";elite=Elites;boss=Bosses";
        } else if (room.endsWith("MonsterRoomElite")) {
            encodedEncounter = "elite=" + encounter;
        } else if (room.endsWith("MonsterRoomBoss")) {
            encodedEncounter = "boss=" + encounter;
        } else if (room.endsWith("MonsterRoom")) {
            encodedEncounter = "normal=" + encounter;
        } else {
            encodedEncounter = "";
        }
        return new LoadedSaveEvidence.Facts(sourcePath, expectedPath, hash, verified, saveObject,
                character, floor, seed, room, encodedEncounter, hp, maxHp, deck, relics, potions);
    }

    private static String repeat(char c, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(c);
        return result.toString();
    }
}

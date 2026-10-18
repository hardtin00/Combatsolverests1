package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.saveAndContinue.SaveAndContinue;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;
import com.megacrit.cardcrawl.saveAndContinue.SaveFileObfuscator;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Captures only bytes read by the native Continue loader and binds them once to the next battle. */
public final class NativeSaveLoadCapture {
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();
    private static final NativeReadEvidence.ReadScopes FILE_READ_SCOPES = new NativeReadEvidence.ReadScopes();
    private static volatile LoadedSource pending;
    private static volatile boolean loadObserved;
    private static volatile String loadFailure = "";

    private NativeSaveLoadCapture() { }

    public static final class Resolution {
        public final boolean nativeLoadObserved;
        public final LoadedSource source;
        public final String reason;

        private Resolution(boolean observed, LoadedSource source, String reason) {
            this.nativeLoadObserved = observed;
            this.source = source;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final class LoadedSource {
        public final byte[] bytes;
        public final String sourcePath;
        public final String sourceSha256;
        public final long sourceModifiedAt;
        public final String evidenceId;
        private final LoadedSaveEvidence.Lease lease;
        private final LoadedSaveEvidence.Facts facts;

        private LoadedSource(byte[] bytes, String sourcePath, String sha256, long modifiedAt,
                             String evidenceId, LoadedSaveEvidence.Facts facts) {
            this.bytes = bytes.clone();
            this.sourcePath = sourcePath;
            this.sourceSha256 = sha256;
            this.sourceModifiedAt = modifiedAt;
            this.evidenceId = evidenceId;
            this.facts = facts;
            this.lease = new LoadedSaveEvidence.Lease(facts);
        }
    }

    private static final class Attempt {
        final String expectedPath;
        final String expectedAbsolutePath;
        final String backupAbsolutePath;
        final String playerClass;
        final NativeReadEvidence.Transaction transaction;
        String failure = "native SaveAndContinue read was not captured";

        Attempt(AbstractPlayer.PlayerClass playerClass) {
            this.playerClass = playerClass == null ? "" : playerClass.name();
            expectedPath = SaveAndContinue.getPlayerSavePath(playerClass);
            expectedAbsolutePath = canonicalSavePath(expectedPath);
            backupAbsolutePath = canonicalSavePath(expectedPath + ".backUp");
            transaction = new NativeReadEvidence.Transaction(expectedAbsolutePath, backupAbsolutePath);
        }
    }

    /** Called by the Prefix of CardCrawlGame.loadPlayerSave(AbstractPlayer). */
    public static synchronized void beginNativeLoad() {
        FILE_READ_SCOPES.clear();
        pending = null;
        loadObserved = true;
        loadFailure = "native load did not produce a verified SaveFile";
        if (CardCrawlGame.chosenCharacter == null) {
            CURRENT.remove();
            loadFailure = "native load player argument unavailable";
            return;
        }
        try {
            Attempt attempt = new Attempt(CardCrawlGame.chosenCharacter);
            CURRENT.set(attempt);
        } catch (Throwable t) {
            CURRENT.remove();
            loadFailure = "native save path unavailable: " + t.getClass().getSimpleName();
        }
    }

    /** Called by the Postfix of the exact private loadSaveString(String) overload. */
    public static void observeNativeRead(String loadedText) {
        Attempt attempt = CURRENT.get();
        if (attempt == null) return;
        attempt.transaction.returned(loadedText, raw -> SaveFileObfuscator.isObfuscated(raw)
                ? SaveFileObfuscator.decode(raw, "key") : raw);
        attempt.failure = attempt.transaction.failure();
    }

    /** Called by FileHandle.read() Prefix; Postfix pops this exact invocation's path. */
    public static void beforeNativeFileRead(com.badlogic.gdx.files.FileHandle handle) {
        Attempt attempt = CURRENT.get();
        String actualPath = null;
        if (attempt != null && handle != null) {
            try { actualPath = handle.file().getCanonicalPath(); }
            catch (Throwable t) { attempt.failure = "native source path unavailable: " + t.getClass().getSimpleName(); }
        }
        FILE_READ_SCOPES.begin(attempt == null ? null : attempt.transaction, actualPath);
    }

    /** Called by FileHandle.read() Postfix; each return pairs with its LIFO Prefix context. */
    public static InputStream wrapNativeRead(InputStream input) {
        try {
            return FILE_READ_SCOPES.finish(input);
        } catch (Throwable t) {
            Attempt attempt = CURRENT.get();
            if (attempt != null) attempt.failure = "native FileHandle.read capture failed: " + t.getClass().getSimpleName();
            return input;
        }
    }

    /** Called by the Postfix of CardCrawlGame.loadPlayerSave after saveFile was assigned. */
    public static synchronized void finishNativeLoad() {
        Attempt attempt = CURRENT.get();
        CURRENT.remove();
        FILE_READ_SCOPES.clear();
        if (attempt == null) {
            loadFailure = "native load transaction was not active";
            return;
        }
        SaveFile save = CardCrawlGame.saveFile;
        attempt.transaction.sealSuccessfulLoad(save);
        NativeReadEvidence.CapturedSave captured = attempt.transaction.parsedSave();
        if (captured == null || save == null) {
            String failure = attempt.transaction.failure();
            loadFailure = failure.isEmpty()
                    ? (attempt.failure.isEmpty() ? "native SaveFile assignment or source bytes unavailable" : attempt.failure)
                    : failure;
            return;
        }
        if (captured.saveObject != save) {
            loadFailure = "native load transaction mismatch: parsed SaveFile differs from active SaveFile";
            return;
        }
        try {
            String capturedHash = sha256(captured.bytes);
            String room = save.current_room;
            String encounter = allEncounterSignatures(save);
            String deck = signatureCardsFromSave(save.cards);
            String relics = signatureRelicsFromSave(save.relics, save.relic_counters);
            String potions = signatureStrings(save.potions);
            if (room == null || encounter == null || deck == null || relics == null || potions == null) {
                loadFailure = "native SaveFile lacks a verifiable room, encounter, deck, relic, or potion field";
                return;
            }
            LoadedSaveEvidence.Facts facts = new LoadedSaveEvidence.Facts(
                    captured.path, attempt.expectedAbsolutePath, capturedHash, true,
                    save, attempt.playerClass, save.floor_num, save.seed, room, encounter,
                    save.current_health, save.max_health, deck, relics, potions);
            pending = new LoadedSource(captured.bytes, captured.path,
                    capturedHash, modifiedAt(captured.path), UUID.randomUUID().toString(), facts);
            loadFailure = "";
            CombatLog.info("native Continue save captured: character=" + attempt.playerClass
                    + " floor=" + save.floor_num + " source=" + sourceKind(captured.path,
                    attempt.expectedAbsolutePath, attempt.backupAbsolutePath)
                    + " sourcePath=" + captured.path
                    + " bytes=" + captured.bytes.length + " sha256=" + capturedHash);
        } catch (Throwable t) {
            loadFailure = "native SaveFile metadata could not be bound: " + t.getClass().getSimpleName();
            CombatLog.error("native Continue save binding failed", t);
        }
    }

    /** Consume the observed load exactly once. A present but invalid load never falls back to disk scanning. */
    public static synchronized Resolution consumeForBattle(AbstractPlayer player, AbstractRoom room) {
        boolean observed = loadObserved;
        LoadedSource source = pending;
        String failure = loadFailure;
        loadObserved = false;
        pending = null;
        loadFailure = "";
        if (!observed) return new Resolution(false, null, "");
        if (source == null) return new Resolution(true, null,
                failure.isEmpty() ? "native loaded save evidence unavailable" : failure);
        LoadedSaveEvidence.Facts live = currentFacts(player, room, source.facts.saveObjectIdentity);
        LoadedSaveEvidence.Decision decision = source.lease.consume(live, CardCrawlGame.saveFile);
        if (!decision.allowed) return new Resolution(true, null, decision.reason);
        return new Resolution(true, source, decision.reason);
    }

    private static LoadedSaveEvidence.Facts currentFacts(AbstractPlayer player, AbstractRoom room, Object saveIdentity) {
        if (player == null || room == null || AbstractDungeon.actionManager == null
                || AbstractDungeon.currMapNode == null || AbstractDungeon.getCurrRoom() != room
                || AbstractDungeon.player != player || Settings.seed == null) return null;
        String roomName = room.getClass().getName();
        String encounter = activeEncounterSignature(roomName, currentEncounters(roomName));
        String deck = signatureCardsLive(player.masterDeck == null ? null : player.masterDeck.group);
        String relics = signatureRelicsLive(player.relics);
        String potions = signaturePotionsLive(player.potions);
        if (encounter == null || deck == null || relics == null || potions == null) return null;
        return new LoadedSaveEvidence.Facts("", "", "", false, saveIdentity,
                player.chosenClass == null ? "" : player.chosenClass.name(), AbstractDungeon.floorNum,
                Settings.seed.longValue(), roomName, encounter, player.currentHealth, player.maxHealth,
                deck, relics, potions);
    }

    private static String allEncounterSignatures(SaveFile save) {
        String normal = signatureStrings(save.monster_list);
        String elite = signatureStrings(save.elite_monster_list);
        String boss = signatureStrings(save.boss_list);
        if (normal == null || elite == null || boss == null) return null;
        return "normal=" + normal + ";elite=" + elite + ";boss=" + boss;
    }

    private static ArrayList<?> currentEncounters(String roomName) {
        if (roomName.endsWith("MonsterRoomElite")) return AbstractDungeon.eliteMonsterList;
        if (roomName.endsWith("MonsterRoomBoss")) return AbstractDungeon.bossList;
        if (roomName.endsWith("MonsterRoom")) return AbstractDungeon.monsterList;
        return null;
    }

    private static String activeEncounterSignature(String roomName, ArrayList<?> entries) {
        String key = encounterKey(roomName);
        String signature = signatureStrings(entries);
        return key == null || signature == null ? null : key + "=" + signature;
    }

    private static String encounterKey(String roomName) {
        if (roomName == null) return null;
        if (roomName.endsWith("MonsterRoomElite")) return "elite";
        if (roomName.endsWith("MonsterRoomBoss")) return "boss";
        if (roomName.endsWith("MonsterRoom")) return "normal";
        return null;
    }

    private static String signatureCardsFromSave(List<?> cards) {
        if (cards == null) return null;
        List<String> values = new ArrayList<>();
        for (Object object : cards) {
            if (!(object instanceof Map)) return null;
            Map<?, ?> card = (Map<?, ?>) object;
            Object id = card.get("id");
            Object upgrades = card.get("upgrades");
            Object misc = card.get("misc");
            if (!(id instanceof String) || ((String) id).isEmpty()) return null;
            values.add(id + ":" + intValue(upgrades, 0) + ":" + intValue(misc, 0));
        }
        return sorted(values);
    }

    private static String signatureCardsLive(List<AbstractCard> cards) {
        if (cards == null) return null;
        List<String> values = new ArrayList<>();
        for (AbstractCard card : cards) {
            if (card == null || card.cardID == null) return null;
            values.add(card.cardID + ":" + card.timesUpgraded + ":" + card.misc);
        }
        return sorted(values);
    }

    private static String signatureRelicsFromSave(List<?> ids, List<?> counters) {
        if (ids == null || counters == null || ids.size() != counters.size()) return null;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!(ids.get(i) instanceof String)) return null;
            values.add(ids.get(i) + ":" + intValue(counters.get(i), 0));
        }
        return sorted(values);
    }

    private static String signatureRelicsLive(List<AbstractRelic> relics) {
        if (relics == null) return null;
        List<String> values = new ArrayList<>();
        for (AbstractRelic relic : relics) {
            if (relic == null || relic.relicId == null) return null;
            values.add(relic.relicId + ":" + relic.counter);
        }
        return sorted(values);
    }

    private static String signaturePotionsLive(List<AbstractPotion> potions) {
        if (potions == null) return null;
        List<String> values = new ArrayList<>();
        for (AbstractPotion potion : potions) {
            if (potion == null || potion.ID == null || "Potion Slot".equals(potion.ID)) continue;
            values.add(potion.ID);
        }
        return sorted(values);
    }

    private static String signatureStrings(List<?> entries) {
        if (entries == null) return null;
        List<String> values = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String)) return null;
            String value = (String) entry;
            if (!value.isEmpty() && !"INVALID".equals(value) && !"Potion Slot".equals(value)) values.add(value);
        }
        return sorted(values);
    }

    private static String sorted(List<String> values) {
        Collections.sort(values, Comparator.naturalOrder());
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('|');
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    private static int intValue(Object value, int defaultValue) {
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    private static String canonicalSavePath(String path) {
        try {
            return new File(com.badlogic.gdx.Gdx.files.local(path).file().getPath()).getCanonicalPath();
        } catch (Throwable t) {
            return "";
        }
    }

    private static long modifiedAt(String path) {
        try { return new File(path).lastModified(); }
        catch (Throwable ignored) { return 0L; }
    }

    private static String sourceKind(String sourcePath, String expectedPath, String backupPath) {
        String actual = NativeReadEvidence.normalize(sourcePath);
        if (actual.equals(NativeReadEvidence.normalize(backupPath))) return "backup";
        if (actual.equals(NativeReadEvidence.normalize(expectedPath))) return "primary";
        return "unexpected-path";
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}

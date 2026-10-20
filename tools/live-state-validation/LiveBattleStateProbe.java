package rngprobe;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated, read-only runtime capture. A protocol file is emitted only after strict v1 validation. */
@SpirePatch(clz = AbstractDungeon.class, method = "update")
public final class LiveBattleStateProbe {
    private static final String OUTPUT = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\outputs\\combatsolver1\\build\\live-state-validation\\live-capture-report.json";
    private static final String PROTOCOL_OUTPUT = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\outputs\\combatsolver1\\build\\live-state-validation\\live-battle-state-v1.json";
    private static final String POWER_TIMING_OUTPUT = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\outputs\\combatsolver1\\build\\live-state-validation\\player-power-timing-observations.jsonl";
    private static final String[] RNG_FIELDS = {"aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"};
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
    private static boolean finished;
    private static boolean stableBoundaryObserved;
    private static long lastAttempt;
    private static String boundaryEvidence = "";
    private static final IdentityHashMap<Object, String> LAST_POWER_STATES = new IdentityHashMap<Object, String>();
    private static final IdentityHashMap<Object, Integer> POWER_INSTANCE_IDS = new IdentityHashMap<Object, Integer>();
    private static final Set<String> OBSERVED_TIMING_CLASSES = new HashSet<String>();
    private static int nextPowerInstanceId = 1;
    private static long lastPowerObservation;
    private static boolean powerTimingFailed;

    private LiveBattleStateProbe() { }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object get(Object o, String n) throws Exception { return field(o.getClass(), n).get(o); }
    private static Object stat(Class<?> c, String n) throws Exception { return field(c, n).get(null); }
    private static int integer(Object o, String n) throws Exception { return ((Number)get(o,n)).intValue(); }
    private static boolean bool(Object o, String n) throws Exception { return ((Boolean)get(o,n)).booleanValue(); }
    private static String str(Object o, String n) throws Exception { Object v=get(o,n); return v == null ? null : String.valueOf(v); }
    private static JsonObject object() { return new JsonObject(); }
    private static JsonArray array() { return new JsonArray(); }
    private static boolean playerStatusUsesJustApplied(String id) {
        return "DOUBLE_DAMAGE".equals(id) || "DRAW_REDUCTION".equals(id) || "FRAIL".equals(id)
                || "VULNERABLE".equals(id) || "WEAK".equals(id);
    }
    private static void put(JsonObject o, String k, String v) { if (v == null) o.add(k, null); else o.addProperty(k,v); }
    private static Object required(Object v, String label) {
        if (v == null) throw new IllegalStateException("required source is null: " + label);
        return v;
    }
    private static JsonObject value(Object o, String... fields) throws Exception {
        JsonObject j=object();
        for (String n:fields) {
            Object v=get(o,n);
            if (v == null) throw new IllegalStateException("required source is null: " + o.getClass().getName() + "." + n);
            else if (v instanceof Number) j.addProperty(n,(Number)v);
            else if (v instanceof Boolean) j.addProperty(n,(Boolean)v);
            else j.addProperty(n,String.valueOf(v));
        }
        return j;
    }
    private static JsonArray cards(Object group, String pileName) throws Exception {
        Object members=required(get(group,"group"),"CardGroup.group");
        if (!(members instanceof List)) throw new IllegalStateException("card group is not a List");
        JsonArray result=array();
        int pileIndex = 0;
        for (Object item:(List<?>)members) {
            Object c=required(item,"CardGroup card instance");
            JsonObject source=object();
            JsonArray readErrors = array();
            captureCardSourceField(source, readErrors, c, "uuid", "uuid", "STRING");
            captureCardSourceField(source, readErrors, c, "cardID", "card_id", "STRING");
            captureCardSourceField(source, readErrors, c, "timesUpgraded", "upgrades", "INT");
            captureCardSourceField(source, readErrors, c, "cost", "cost", "INT");
            captureCardSourceField(source, readErrors, c, "costForTurn", "cost_for_turn", "INT");
            captureCardSourceField(source, readErrors, c, "misc", "misc", "INT");
            captureCardSourceField(source, readErrors, c, "freeToPlayOnce", "free_to_play_once", "BOOLEAN");
            captureCardSourceField(source, readErrors, c, "retain", "retain", "BOOLEAN");
            captureCardSourceField(source, readErrors, c, "isEthereal", "ethereal", "BOOLEAN");
            source.add("source_read_errors", readErrors);
            JsonObject entry = diagnoseSourceCard(source);
            entry.addProperty("pile", pileName); entry.addProperty("pile_index", pileIndex++);
            result.add(entry);
        }
        return result;
    }

    private static void captureCardSourceField(JsonObject source, JsonArray errors, Object card,
                                               String nativeField, String key, String kind) {
        try {
            Object value = required(get(card, nativeField), "AbstractCard." + nativeField);
            if ("STRING".equals(kind)) source.addProperty(key, String.valueOf(value));
            else if ("INT".equals(kind)) source.addProperty(key, ((Number)value).intValue());
            else if ("BOOLEAN".equals(kind)) source.addProperty(key, ((Boolean)value).booleanValue());
            else throw new IllegalArgumentException("unknown card source kind: " + kind);
        } catch (Throwable t) {
            JsonObject failure = object(); failure.addProperty("field", nativeField);
            failure.addProperty("error", t.getClass().getName() + ": " + String.valueOf(t.getMessage())); errors.add(failure);
        }
    }

    /** Captures one card's identity/state independently, so one unmapped card
     * cannot hide later cards in the same read-only pile enumeration. This is
     * diagnostic data only; protocolFromSource still rejects any non-ready item. */
    static JsonObject diagnoseSourceCard(JsonObject source) {
        JsonObject entry = GSON.fromJson(source, JsonObject.class);
        JsonObject diagnostic = object();
        String sourceId = source.has("card_id") && source.get("card_id").isJsonPrimitive()
                ? source.get("card_id").getAsString() : "<missing>";
        String engineId = CardIdWireMap.toEngineId(sourceId);
        boolean readFailed = source.has("source_read_errors") && source.get("source_read_errors").isJsonArray()
                && source.getAsJsonArray("source_read_errors").size() != 0;
        diagnostic.addProperty("native_card_id", sourceId);
        diagnostic.addProperty("engine_card_id", engineId == null ? "" : engineId);
        if (engineId == null) {
            diagnostic.addProperty("classification", "UNSUPPORTED_IDENTITY");
            diagnostic.addProperty("capture_status", "UNSUPPORTED_IDENTITY");
            diagnostic.addProperty("effect_status", "NOT_ASSESSED");
            diagnostic.addProperty("reason", "native card ID has no verified canonical mapping");
        } else {
            try {
                if (readFailed) throw new IllegalStateException("one or more AbstractCard source fields could not be read");
                JsonObject mapped = serializeSourceCard(source);
                entry.add("protocol_card", mapped);
                diagnostic.addProperty("capture_status", "CAPTURED");
                String effectStatus = CardIdWireMap.effectModelStatus(engineId);
                diagnostic.addProperty("effect_status", effectStatus);
                diagnostic.addProperty("classification", "CXX_IMPLEMENTATION_PRESENT_NOT_L4_VERIFIED".equals(effectStatus)
                        ? "CAPTURABLE_AND_CXX_EFFECT_PRESENT" : "STATE_CAPTURED_EFFECT_UNMODELED");
                diagnostic.addProperty("reason", "identity and supported instance fields mapped");
            } catch (RuntimeException e) {
                diagnostic.addProperty("classification", "IDENTITY_KNOWN_STATE_UNMAPPED");
                diagnostic.addProperty("capture_status", "IDENTITY_KNOWN_STATE_UNMAPPED");
                diagnostic.addProperty("effect_status", "NOT_ASSESSED");
                diagnostic.addProperty("reason", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            }
        }
        entry.add("mapping_diagnostic", diagnostic);
        return entry;
    }

    private static String cardMappingFailures(JsonObject source) {
        StringBuilder failures = new StringBuilder();
        for (String pile : new String[]{"hand", "draw", "discard", "exhaust", "limbo"}) {
            if (!source.has("piles") || !source.get("piles").isJsonObject()
                    || !source.getAsJsonObject("piles").has(pile)
                    || !source.getAsJsonObject("piles").get(pile).isJsonArray()) continue;
            JsonArray cards = source.getAsJsonObject("piles").getAsJsonArray(pile);
            for (int i = 0; i < cards.size(); i++) {
                JsonObject card = cards.get(i).getAsJsonObject();
                JsonObject mapping = card.has("mapping_diagnostic") && card.get("mapping_diagnostic").isJsonObject()
                        ? card.getAsJsonObject("mapping_diagnostic") : null;
                if (mapping == null || !"CAPTURABLE_AND_CXX_EFFECT_PRESENT".equals(
                        mapping.has("classification") ? mapping.get("classification").getAsString() : "")) {
                    if (failures.length() > 0) failures.append("; ");
                    String id = mapping != null && mapping.has("native_card_id") ? mapping.get("native_card_id").getAsString() : "<unknown>";
                    String reason = mapping != null && mapping.has("reason") ? mapping.get("reason").getAsString() : "missing card mapping diagnostic";
                    failures.append(pile).append('[').append(i).append("] ").append(id).append(": ").append(reason);
                }
            }
        }
        return failures.toString();
    }

    /** Exact vanilla power class to the subset of C++ player statuses whose
     * transition logic reads the one-round justApplied flag. */
    static String playerTimingStatusId(String className) {
        if ("com.megacrit.cardcrawl.powers.WeakPower".equals(className)) return "WEAK";
        if ("com.megacrit.cardcrawl.powers.FrailPower".equals(className)) return "FRAIL";
        if ("com.megacrit.cardcrawl.powers.VulnerablePower".equals(className)) return "VULNERABLE";
        if ("com.megacrit.cardcrawl.powers.DoubleDamagePower".equals(className)) return "DOUBLE_DAMAGE";
        if ("com.megacrit.cardcrawl.powers.DrawReductionPower".equals(className)) return "DRAW_REDUCTION";
        return null;
    }

    private static String expectedGamePowerId(String canonicalId) {
        if ("WEAK".equals(canonicalId)) return "Weakened";
        if ("FRAIL".equals(canonicalId)) return "Frail";
        if ("VULNERABLE".equals(canonicalId)) return "Vulnerable";
        if ("DOUBLE_DAMAGE".equals(canonicalId)) return "Double Damage";
        if ("DRAW_REDUCTION".equals(canonicalId)) return "Draw Reduction";
        return null;
    }

    /** Converts the exact vanilla power instances that the restored Sentry model represents. */
    static JsonObject serializeSourceMonsterPower(JsonObject source) {
        String className = requiredString(source, "class_name");
        String sourceId = requiredString(source, "id");
        String id;
        boolean timingRequired;
        if ("com.megacrit.cardcrawl.powers.ArtifactPower".equals(className) && "Artifact".equals(sourceId)) {
            id = "ARTIFACT";
            timingRequired = false;
        } else if ("com.megacrit.cardcrawl.powers.WeakPower".equals(className) && "Weakened".equals(sourceId)) {
            id = "WEAK";
            timingRequired = true;
        } else if ("com.megacrit.cardcrawl.powers.VulnerablePower".equals(className) && "Vulnerable".equals(sourceId)) {
            id = "VULNERABLE";
            timingRequired = true;
        } else {
            throw new IllegalStateException("unsupported Sentry power class/ID: " + className + " ID=" + sourceId);
        }
        int amount = requiredCardInt(source, "amount");
        if (amount < 0 || ("ARTIFACT".equals(id) && amount > 127))
            throw new IllegalStateException("Sentry power amount outside C++ storage range: " + id + "=" + amount);
        JsonObject result = object();
        result.addProperty("id", id);
        result.addProperty("amount", amount);
        if (timingRequired) {
            if (!source.has("just_applied") || !source.get("just_applied").isJsonPrimitive()
                    || !source.getAsJsonPrimitive("just_applied").isBoolean())
                throw new IllegalStateException("missing/invalid Sentry power justApplied: " + className);
            result.add("just_applied", source.get("just_applied"));
        } else {
            // Artifact has no C++ timing transition; false is the protocol's neutral value.
            result.addProperty("just_applied", false);
        }
        return result;
    }

    private static JsonObject serializeMonsterPower(Object power) throws Exception {
        String className = power.getClass().getName();
        JsonObject source = object();
        source.addProperty("class_name", className);
        source.addProperty("id", String.valueOf(required(get(power, "ID"), "AbstractPower.ID")));
        source.addProperty("amount", integer(power, "amount"));
        if ("com.megacrit.cardcrawl.powers.WeakPower".equals(className)
                || "com.megacrit.cardcrawl.powers.VulnerablePower".equals(className))
            source.addProperty("just_applied", bool(power, "justApplied"));
        return serializeSourceMonsterPower(source);
    }

    /** Maps native AbstractMonster.moveHistory (oldest to newest) to the engine's newest-first pair. */
    static JsonObject serializeSourceMonster(JsonObject source, int expectedSlot) {
        if (!"Sentry".equals(requiredString(source, "id")))
            throw new IllegalStateException("unsupported Sentry identity: " + source.get("id"));
        int slot = requiredCardInt(source, "slot");
        if (slot != expectedSlot || slot < 0 || slot > 2)
            throw new IllegalStateException("Sentry slot does not preserve original MonsterGroup order: " + slot);
        int hp = requiredCardInt(source, "currentHealth");
        int maxHp = requiredCardInt(source, "maxHealth");
        int block = requiredCardInt(source, "currentBlock");
        if (hp < 0 || maxHp <= 0 || hp > maxHp || block < 0)
            throw new IllegalStateException("invalid Sentry health/block state at slot " + slot);
        for (String key : new String[]{"isEscaping", "halfDead", "escapeNext", "firstMove"})
            if (!source.has(key) || !source.get(key).isJsonPrimitive() || !source.getAsJsonPrimitive(key).isBoolean())
                throw new IllegalStateException("missing/invalid Sentry flag: " + key);
        if (source.get("firstMove").getAsBoolean())
            throw new IllegalStateException("unsupported Sentry firstMove=true");
        int beam = requiredCardInt(source, "beamDmg");
        int dazed = requiredCardInt(source, "dazedAmt");

        JsonArray nativeHistory = requiredArray(source, "move_history");
        if (nativeHistory.size() == 0) throw new IllegalStateException("Sentry move history is empty");
        JsonArray history = array();
        for (int h = 0; h < 2; h++) {
            int fromEnd = h;
            if (fromEnd >= nativeHistory.size()) {
                history.add("INVALID");
                continue;
            }
            com.google.gson.JsonElement value = nativeHistory.get(nativeHistory.size() - 1 - fromEnd);
            final int nativeMove;
            try { nativeMove = value.getAsInt(); }
            catch (RuntimeException e) { throw new IllegalStateException("invalid native Sentry move-history value", e); }
            if (nativeMove == 3) history.add("SENTRY_BOLT");
            else if (nativeMove == 4) history.add("SENTRY_BEAM");
            else throw new IllegalStateException("unsupported native Sentry move id: " + nativeMove);
        }

        JsonArray nativePowers = requiredArray(source, "powers");
        JsonArray statuses = array();
        Set<String> seenStatuses = new HashSet<String>();
        for (com.google.gson.JsonElement power : nativePowers) {
            JsonObject status = serializeSourceMonsterPower(power.getAsJsonObject());
            if (!seenStatuses.add(status.get("id").getAsString()))
                throw new IllegalStateException("duplicate Sentry status: " + status.get("id"));
            statuses.add(status);
        }

        JsonObject result = object();
        result.addProperty("slot", slot);
        result.addProperty("id", "SENTRY");
        result.addProperty("hp", hp);
        result.addProperty("max_hp", maxHp);
        result.addProperty("block", block);
        result.add("escaping", source.get("isEscaping"));
        result.add("half_dead", source.get("halfDead"));
        result.add("escape_next", source.get("escapeNext"));
        result.add("move_history", history);
        result.add("statuses", statuses);
        // The supported Sentry C++ branch has no unique-power or misc counter use.
        result.addProperty("unique_power0", 0);
        result.addProperty("unique_power1", 0);
        result.addProperty("misc_info", 0);
        result.addProperty("beam_damage", beam);
        result.addProperty("dazed_amount", dazed);
        result.addProperty("first_move", false);
        return result;
    }

    private static JsonArray requiredArray(JsonObject source, String key) {
        if (!source.has(key) || !source.get(key).isJsonArray())
            throw new IllegalStateException("missing/invalid array: " + key);
        return source.getAsJsonArray(key);
    }

    /** Serialize only a power whose exact runtime class has a verified field. */
    static JsonObject serializePlayerPowerTiming(Object power) throws Exception {
        String id = playerTimingStatusId(power.getClass().getName());
        if (id == null) throw new IllegalStateException("unsupported player power timing source: " + power.getClass().getName());
        String sourceId = String.valueOf(required(get(power, "ID"), "AbstractPower.ID"));
        if (!expectedGamePowerId(id).equals(sourceId))
            throw new IllegalStateException("player power class/ID mismatch: " + power.getClass().getName() + " ID=" + sourceId);
        JsonObject status = object();
        status.addProperty("id", id);
        status.addProperty("amount", integer(power, "amount"));
        status.addProperty("just_applied", bool(power, "justApplied"));
        return status;
    }

    /** Converts real AbstractCard fields to the engine's canonical CardId and instance state. */
    static JsonObject serializeSourceCard(JsonObject source) {
        String sourceId=requiredString(source,"card_id");
        String engineId=CardIdWireMap.toEngineId(sourceId);
        if(engineId==null) throw new IllegalStateException("unsupported vanilla Ironclad card id: "+sourceId);
        int upgrades=requiredCardInt(source,"upgrades");
        if(upgrades<0 || upgrades>1) throw new IllegalStateException("unsupported card upgrade count/state: "+sourceId+" upgrades="+upgrades);
        int misc=requiredCardInt(source,"misc");
        if (!source.has("ethereal") || source.get("ethereal").isJsonNull()
                || !source.get("ethereal").isJsonPrimitive() || !source.getAsJsonPrimitive("ethereal").isBoolean())
            throw new IllegalStateException("missing or invalid card field: ethereal");
        if (source.get("ethereal").getAsBoolean())
            throw new IllegalStateException("unsupported ethereal state for canonical card mapping: " + sourceId);
        int specialData=0;
        if("SEARING_BLOW".equals(engineId)) {
            // C++ stores this card's upgrade depth in Card.misc; STS1's live AbstractCard stores it in timesUpgraded.
            if(misc!=0) throw new IllegalStateException("unsupported nonzero Searing Blow misc: "+misc);
            specialData=upgrades;
        } else if("RITUAL_DAGGER".equals(engineId)) {
            if(misc<0 || misc>Short.MAX_VALUE) throw new IllegalStateException("unsupported card special_data: "+sourceId+" misc="+misc);
            specialData=misc;
        } else if(misc!=0) {
            throw new IllegalStateException("unsupported nonzero AbstractCard.misc for "+sourceId+": "+misc);
        }
        String uuid=requiredString(source,"uuid");
        if(!uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("invalid full card UUID: "+uuid);
        JsonObject card=object();
        card.addProperty("uuid",uuid); card.addProperty("id",engineId); card.addProperty("special_data",specialData);
        int cost=requiredCardInt(source,"cost"), costForTurn=requiredCardInt(source,"cost_for_turn");
        if(cost<Byte.MIN_VALUE||cost>Byte.MAX_VALUE||costForTurn<Byte.MIN_VALUE||costForTurn>Byte.MAX_VALUE)
            throw new IllegalStateException("card cost outside C++ int8 range: "+sourceId);
        card.addProperty("cost",cost); card.addProperty("cost_for_turn",costForTurn);
        card.addProperty("upgraded",upgrades==1); card.addProperty("upgrade_count",upgrades);
        for(String key:new String[]{"free_to_play_once","retain"}) {
            if(!source.has(key)||source.get(key).isJsonNull()||!source.get(key).isJsonPrimitive()||!source.getAsJsonPrimitive(key).isBoolean())
                throw new IllegalStateException("missing or invalid card field: "+key);
            card.add(key,source.get(key));
        }
        return card;
    }

    private static int requiredCardInt(JsonObject o,String key) {
        if(!o.has(key)||o.get(key).isJsonNull()||!o.get(key).isJsonPrimitive()||!o.getAsJsonPrimitive(key).isNumber())
            throw new IllegalStateException("missing or invalid card field: "+key);
        return o.get(key).getAsInt();
    }
    private static com.google.gson.JsonElement jsonRequired(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull())
            throw new IllegalStateException("missing required source field: " + key);
        return o.get(key);
    }
    private static JsonObject roomContext(Object room) throws Exception {
        JsonObject x=object();
        String className=room.getClass().getName();
        Object event=get(room,"event");
        x.addProperty("kind",event==null?"MAP_MONSTER":"EVENT");
        x.addProperty("class",className);
        x.addProperty("elite_trigger",bool(room,"eliteTrigger"));
        if(event==null) { x.add("event_class",null); x.add("event_encounter",null); x.add("event_enemy_index",null); }
        else {
            x.addProperty("event_class",event.getClass().getName());
            if("com.megacrit.cardcrawl.events.exordium.DeadAdventurer".equals(event.getClass().getName())) {
                x.addProperty("event_enemy_index",integer(event,"enemy"));
                Method getMonster=event.getClass().getDeclaredMethod("getMonster");
                getMonster.setAccessible(true);
                x.addProperty("event_encounter",String.valueOf(getMonster.invoke(event)));
            } else {
                x.add("event_enemy_index",null); x.add("event_encounter",null);
            }
        }
        return x;
    }
    private static void validateRoomContext(JsonObject room) {
        String kind=requiredString(room,"kind");
        String roomClass=requiredString(room,"class");
        if(!room.has("elite_trigger") || !room.get("elite_trigger").getAsBoolean())
            throw new IllegalStateException("unsupported room context: eliteTrigger must be true");
        if("MAP_MONSTER".equals(kind)) {
            if(!"com.megacrit.cardcrawl.rooms.MonsterRoomElite".equals(roomClass)
                    || !room.has("event_class") || !room.get("event_class").isJsonNull()
                    || !room.has("event_encounter") || !room.get("event_encounter").isJsonNull()
                    || !room.has("event_enemy_index") || !room.get("event_enemy_index").isJsonNull())
                throw new IllegalStateException("unsupported map room provenance");
            return;
        }
        if("EVENT".equals(kind)) {
            if(!"com.megacrit.cardcrawl.rooms.EventRoom".equals(roomClass)
                    || !"com.megacrit.cardcrawl.events.exordium.DeadAdventurer".equals(requiredString(room,"event_class"))
                    || !"3 Sentries".equals(requiredString(room,"event_encounter"))
                    || requiredCardInt(room,"event_enemy_index")!=0)
                throw new IllegalStateException("unsupported event provenance; only Dead Adventurer enemy 0 (3 Sentries) is accepted");
            return;
        }
        throw new IllegalStateException("unsupported room provenance kind: "+kind);
    }
    static JsonArray cardIdentityMap(JsonObject piles) {
        JsonArray mapping=array(); Set<String> seen=new HashSet<String>(); int denseId=0;
        for(String pile:new String[]{"hand","draw","discard","exhaust"}) {
            JsonArray cards=piles.getAsJsonArray(pile);
            for(int i=0;i<cards.size();i++) {
                JsonObject card=cards.get(i).getAsJsonObject();
                String uuid=card.has("uuid") && card.get("uuid").isJsonPrimitive() ? card.get("uuid").getAsString() : "<missing>";
                String normalized=uuid.toLowerCase(Locale.ROOT);
                boolean valid=uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
                boolean unique=valid && seen.add(normalized);
                JsonObject x=object(); x.addProperty("uuid",uuid);
                if(unique) x.addProperty("dense_id",denseId++);
                x.addProperty("pile",pile); x.addProperty("pile_index",i);
                x.addProperty("card_id",card.has("card_id") ? card.get("card_id").getAsString() : "<missing>");
                x.addProperty("identity_status",!valid?"INVALID_UUID":unique?"UNIQUE":"DUPLICATE_UUID"); mapping.add(x);
            }
        }
        return mapping;
    }
    private static String cardIdentityFailures(JsonObject source) {
        StringBuilder failures = new StringBuilder();
        JsonArray identities = source.has("card_identity_map") && source.get("card_identity_map").isJsonArray()
                ? source.getAsJsonArray("card_identity_map") : new JsonArray();
        for (com.google.gson.JsonElement element : identities) {
            JsonObject identity = element.getAsJsonObject();
            String status = identity.has("identity_status") ? identity.get("identity_status").getAsString() : "UNAVAILABLE";
            if (!"UNIQUE".equals(status)) {
                if (failures.length() > 0) failures.append("; ");
                failures.append(identity.get("pile").getAsString()).append('[').append(identity.get("pile_index").getAsInt())
                        .append("] ").append(identity.get("uuid").getAsString()).append(": ").append(status);
            }
        }
        return failures.toString();
    }
    private static JsonObject rng(Object r, String streamName) throws Exception {
        JsonObject x=object(); Object xs=required(get(r,"random"),"Random.random");
        Method state=xs.getClass().getMethod("getState",Integer.TYPE);
        long s0=((Long)state.invoke(xs,Integer.valueOf(0))).longValue();
        long s1=((Long)state.invoke(xs,Integer.valueOf(1))).longValue();
        int counter=integer(r,"counter");
        x.addProperty("seed0",String.format("%016x",Long.valueOf(s0)));
        x.addProperty("seed1",String.format("%016x",Long.valueOf(s1)));
        x.addProperty("counter",counter);
        x.addProperty("stream_id",streamName);
        x.addProperty("object_identity",Integer.toHexString(System.identityHashCode(r)));
        return x;
    }
    private static JsonObject rngs() throws Exception {
        JsonObject all=object(); Class<?> d=AbstractDungeon.class;
        for(String name:RNG_FIELDS) { Object r=required(stat(d,name),"AbstractDungeon."+name); all.add(name,rng(r,name)); }
        return all;
    }
    private static JsonArray playerTimingPowers() throws Exception {
        JsonArray powers=array();
        Object player=required(AbstractDungeon.player,"AbstractDungeon.player");
        Object list=required(get(player,"powers"),"AbstractCreature.powers");
        if(!(list instanceof Iterable)) throw new IllegalStateException("AbstractCreature.powers is not iterable");
        for(Object power:(Iterable<?>)list) {
            String className=power.getClass().getName();
            String canonical=playerTimingStatusId(className);
            if(canonical==null) continue;
            JsonObject status=serializePlayerPowerTiming(power);
            String nativeId=String.valueOf(required(get(power,"ID"),"AbstractPower.ID"));
            Integer instanceId=POWER_INSTANCE_IDS.get(power);
            if(instanceId==null) { instanceId=Integer.valueOf(nextPowerInstanceId++); POWER_INSTANCE_IDS.put(power,instanceId); }
            JsonObject entry=object();
            entry.addProperty("instance_id",instanceId.intValue());
            entry.addProperty("runtime_class",className);
            entry.addProperty("native_power_id",nativeId);
            entry.addProperty("mapped_status_id",canonical);
            entry.addProperty("mapping_result","MATCH_CXX_STATUS_ID");
            entry.add("status",status);
            powers.add(entry);
        }
        return powers;
    }
    private static JsonArray pileUuids(Object player, String fieldName) throws Exception {
        JsonArray ids=array(); Object pile=required(get(player,fieldName),"AbstractPlayer."+fieldName);
        Object members=required(get(pile,"group"),"CardGroup.group");
        if(!(members instanceof Iterable)) throw new IllegalStateException("CardGroup.group is not iterable: "+fieldName);
        for(Object card:(Iterable<?>)members) ids.add(String.valueOf(required(get(card,"uuid"),"AbstractCard.uuid")));
        return ids;
    }
    private static JsonArray powerIdAmounts(Object creature) throws Exception {
        JsonArray powers=array(); Object list=required(get(creature,"powers"),"AbstractCreature.powers");
        if(!(list instanceof Iterable)) throw new IllegalStateException("AbstractCreature.powers is not iterable");
        for(Object power:(Iterable<?>)list) {
            JsonObject value=object(); value.addProperty("id",String.valueOf(required(get(power,"ID"),"AbstractPower.ID")));
            value.addProperty("amount",integer(power,"amount")); powers.add(value);
        }
        return powers;
    }
    private static JsonObject powerReadBoundary() throws Exception {
        JsonObject boundary=object();
        Object room=required(AbstractDungeon.getCurrRoom(),"AbstractDungeon.getCurrRoom()");
        Object player=required(AbstractDungeon.player,"AbstractDungeon.player");
        Object manager=required(stat(AbstractDungeon.class,"actionManager"),"AbstractDungeon.actionManager");
        boundary.addProperty("room_class",room.getClass().getName());
        boundary.addProperty("room_identity",Integer.toHexString(System.identityHashCode(room)));
        boundary.addProperty("turn",((Number)stat(Class.forName("com.megacrit.cardcrawl.actions.GameActionManager"),"turn")).intValue());
        boundary.addProperty("character",String.valueOf(get(player,"chosenClass")));
        boundary.addProperty("hp",integer(player,"currentHealth"));
        boundary.addProperty("max_hp",integer(player,"maxHealth"));
        boundary.addProperty("block",integer(player,"currentBlock"));
        boundary.addProperty("energy",((Number)Class.forName("com.megacrit.cardcrawl.ui.panels.EnergyPanel").getMethod("getCurrentEnergy").invoke(null)).intValue());
        JsonObject piles=object();
        for(String pileName:new String[]{"hand","drawPile","discardPile","exhaustPile"}) piles.add(pileName,pileUuids(player,pileName));
        boundary.add("piles",piles);
        boundary.add("player_power_ids_amounts",powerIdAmounts(player));
        JsonArray relics=array();
        for(Object relic:(Iterable<?>)required(get(player,"relics"),"AbstractPlayer.relics")) {
            JsonObject value=object(); value.addProperty("id",String.valueOf(required(get(relic,"relicId"),"AbstractRelic.relicId")));
            value.addProperty("counter",integer(relic,"counter")); relics.add(value);
        }
        boundary.add("relics",relics);
        JsonArray potions=array();
        for(Object potion:(Iterable<?>)required(get(player,"potions"),"AbstractPlayer.potions")) {
            if(potion==null) { potions.add((String)null); continue; }
            JsonObject value=object(); value.addProperty("id",String.valueOf(required(get(potion,"ID"),"AbstractPotion.ID")));
            value.addProperty("slot",integer(potion,"slot")); potions.add(value);
        }
        boundary.add("potions",potions);
        Object roster=required(get(room,"monsters"),"AbstractRoom.monsters");
        JsonArray monsters=array();
        for(Object monster:(Iterable<?>)required(get(roster,"monsters"),"MonsterGroup.monsters")) {
            if(monster==null) { monsters.add((String)null); continue; }
            JsonObject value=object();
            for(String fieldName:new String[]{"id","currentHealth","maxHealth","currentBlock","isDead","isEscaping","halfDead","escapeNext","nextMove","intent"}) {
                Object fieldValue=required(get(monster,fieldName),"AbstractMonster."+fieldName);
                value.addProperty(fieldName,String.valueOf(fieldValue));
            }
            value.add("powers",powerIdAmounts(monster)); monsters.add(value);
        }
        boundary.add("monsters",monsters);
        boundary.add("queue_boundary",protocolBoundary(room,player,manager));
        return boundary;
    }
    private static void appendPowerTimingObservation(JsonObject observation) throws Exception {
        File output=new File(POWER_TIMING_OUTPUT); File parent=output.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create power observation directory");
        OutputStreamWriter writer=new OutputStreamWriter(new FileOutputStream(output,true),StandardCharsets.UTF_8);
        try { GSON.toJson(observation,writer); writer.write("\n"); } finally { writer.close(); }
    }
    private static void observePlayerPowerTiming() throws Exception {
        if(OBSERVED_TIMING_CLASSES.size()==5) return;
        long now=System.currentTimeMillis(); if(now-lastPowerObservation<250L || powerTimingFailed)return;
        lastPowerObservation=now;
        if(!stable() || !"IRONCLAD".equals(String.valueOf(get(AbstractDungeon.player,"chosenClass")))) return;
        JsonArray powersBefore=playerTimingPowers();
        if(powersBefore.size()==0) return;
        JsonObject boundaryBefore=powerReadBoundary(); JsonObject rngBefore=rngs();
        JsonArray powersAfter=playerTimingPowers(); JsonObject rngAfter=rngs(); JsonObject boundaryAfter=powerReadBoundary();
        boolean powersStable=powersBefore.equals(powersAfter);
        boolean rngStable=rngBefore.equals(rngAfter);
        boolean boundaryStable=boundaryBefore.equals(boundaryAfter) && stable();
        boolean readOnlyMatch=powersStable && rngStable && boundaryStable;
        JsonArray changed=array();
        for(com.google.gson.JsonElement element:powersAfter) {
            JsonObject item=element.getAsJsonObject();
            int instanceId=item.get("instance_id").getAsInt();
            String state=item.get("status").toString();
            Object powerObject=null;
            for(Object candidate:(Iterable<?>)get(AbstractDungeon.player,"powers")) {
                Integer candidateId=POWER_INSTANCE_IDS.get(candidate);
                if(candidateId!=null && candidateId.intValue()==instanceId) { powerObject=candidate; break; }
            }
            String previous=powerObject==null ? null : LAST_POWER_STATES.put(powerObject,state);
            if(previous==null || !previous.equals(state)) changed.add(item);
            OBSERVED_TIMING_CLASSES.add(item.get("runtime_class").getAsString());
        }
        if(changed.size()==0) return;
        JsonObject observation=object();
        observation.addProperty("protocol","player_power_timing_observation");
        observation.addProperty("version",1);
        observation.addProperty("status",readOnlyMatch?"READ_ONLY_MATCH":"READ_ONLY_MISMATCH");
        observation.addProperty("capture_time_epoch_ms",now);
        observation.addProperty("evidence","REAL_GAME_INSTANCE_REQUIRED");
        observation.add("observed_instances",changed);
        observation.add("power_read_before",powersBefore);
        observation.add("power_read_after",powersAfter);
        observation.add("battle_boundary_before",boundaryBefore);
        observation.add("battle_boundary_after",boundaryAfter);
        observation.add("rng_before",rngBefore);
        observation.add("rng_after",rngAfter);
        observation.addProperty("power_reads_equal",powersStable);
        observation.addProperty("rng_unchanged",rngStable);
        observation.addProperty("battle_boundary_unchanged",boundaryStable);
        appendPowerTimingObservation(observation);
        if(!readOnlyMatch) powerTimingFailed=true;
    }
    private static boolean stable() throws Exception {
        Object d=AbstractDungeon.class; Object room=AbstractDungeon.getCurrRoom();
        if(room==null || !"COMBAT".equals(String.valueOf(get(room,"phase")))) return false;
        if(bool(room,"isBattleOver") || bool(room,"skipMonsterTurn")) return false;
        Object manager=stat(AbstractDungeon.class,"actionManager");
        if(manager==null || bool(manager,"turnHasEnded") || bool(AbstractDungeon.player,"endTurnQueued")
                || get(manager,"currentAction")!=null || bool(manager,"usingCard")) return false;
        String phase=String.valueOf(get(manager,"phase"));
        if(!"WAITING_ON_USER".equals(phase)) return false;
        for(String n:new String[]{"actions","cardQueue","monsterQueue"}) {
            Object q=get(manager,n); if(!(q instanceof List) || !((List<?>)q).isEmpty()) return false;
        }
        if(AbstractDungeon.isScreenUp || AbstractDungeon.player==null) return false;
        boundaryEvidence="room=COMBAT; phase="+phase+"; turnHasEnded=false; currentAction=null; usingCard=false; actions/cardQueue/monsterQueue empty; no screen open";
        return true;
    }
    private static JsonObject protocolPlayerBasic(Object player) throws Exception {
        JsonObject out=object();
        out.addProperty("hp",integer(player,"currentHealth"));
        out.addProperty("max_hp",integer(player,"maxHealth"));
        out.addProperty("block",integer(player,"currentBlock"));
        Object energy=required(get(player,"energy"),"AbstractPlayer.energy");
        out.addProperty("energy",((Number)Class.forName("com.megacrit.cardcrawl.ui.panels.EnergyPanel")
                .getMethod("getCurrentEnergy").invoke(null)).intValue());
        // Existing v1 calls this energy_per_turn; source is EnergyManager.energyMaster,
        // not a separate max-energy field.
        out.addProperty("energy_per_turn",integer(energy,"energyMaster"));
        out.addProperty("card_draw_per_turn",integer(player,"gameHandSize"));
        return out;
    }

    /**
     * Builds the deliberately narrow live wire state. This currently accepts
     * only the untouched first player turn with the vanilla Ironclad starter
     * relic and no player powers. Other roots fail closed because the v1 model
     * still lacks trusted mappings for their live counters/effects.
     */
    static JsonObject protocolFromSource(JsonObject source) {
        if (!"IRONCLAD".equals(requiredString(source, "character")))
            throw new IllegalStateException("unsupported character");
        if (!source.has("room_context") || !source.get("room_context").isJsonObject())
            throw new IllegalStateException("missing room provenance");
        JsonObject roomContext=source.getAsJsonObject("room_context");
        validateRoomContext(roomContext);
        if(!requiredString(source,"room_class").equals(requiredString(roomContext,"class")))
            throw new IllegalStateException("room class disagrees with room provenance");
        String cardFailures = cardMappingFailures(source);
        if (!cardFailures.isEmpty())
            throw new IllegalStateException("card mapping/effect support rejected; all observed card diagnostics: " + cardFailures);
        String identityFailures = cardIdentityFailures(source);
        if (!identityFailures.isEmpty())
            throw new IllegalStateException("card instance identity rejected; all observed conflicts: " + identityFailures);
        if (requiredCardInt(source, "turn") != 1)
            throw new IllegalStateException("live protocol currently requires untouched first player turn");
        JsonObject srcPlayer = source.getAsJsonObject("player");
        JsonArray powers = requiredArray(srcPlayer, "powers");
        if (powers.size() != 0)
            throw new IllegalStateException("unmapped live player powers prevent exact root restoration");
        if (requiredCardInt(srcPlayer, "cardsPlayedThisTurn") != 0
                || requiredCardInt(srcPlayer, "damagedThisCombat") != 0)
            throw new IllegalStateException("live player counters are outside untouched first-turn boundary");
        JsonObject counterBasis = source.getAsJsonObject("counter_basis");
        if (requiredCardInt(counterBasis, "total_discarded_this_turn") != 0
                || requiredCardInt(counterBasis, "damage_received_this_combat") != 0)
            throw new IllegalStateException("live action-manager counters are outside untouched first-turn boundary");
        JsonArray sourceRelics = requiredArray(source, "relics");
        if (sourceRelics.size() != 1 || !"Burning Blood".equals(requiredString(sourceRelics.get(0).getAsJsonObject(), "id")))
            throw new IllegalStateException("only the vanilla Ironclad Burning Blood starter relic is currently mapped");

        JsonObject protocol = object();
        protocol.addProperty("protocol", "live_battle_state");
        protocol.addProperty("version", 1);
        protocol.addProperty("capture_id", UUID.randomUUID().toString());
        protocol.addProperty("character", "IRONCLAD");
        protocol.addProperty("act", requiredCardInt(source, "act"));
        protocol.addProperty("stage", "PLAYER_NORMAL");
        JsonObject boundary = source.getAsJsonObject("protocol_boundary_fields");
        if (!boundary.get("queues_empty").getAsBoolean() || boundary.get("action_queue_size").getAsInt() != 0
                || boundary.get("card_queue_size").getAsInt() != 0 || boundary.get("battle_over").getAsBoolean()
                || boundary.get("end_turn_queued").getAsBoolean() || boundary.get("turn_has_ended").getAsBoolean()
                || boundary.get("skip_monster_turn").getAsBoolean())
            throw new IllegalStateException("live boundary is not idle PLAYER_NORMAL");
        protocol.addProperty("queues_empty", true);
        protocol.addProperty("action_queue_size", 0);
        protocol.addProperty("card_queue_size", 0);
        protocol.addProperty("seed", Long.parseLong(requiredString(source, "run_seed")));
        protocol.addProperty("floor", requiredCardInt(source, "floor"));
        protocol.addProperty("ascension", requiredCardInt(source, "ascension"));
        protocol.addProperty("turn", 1);
        protocol.addProperty("encounter", "THREE_SENTRIES");
        protocol.add("room_context", source.get("room_context"));

        JsonObject player = object();
        JsonObject basic = source.getAsJsonObject("player").getAsJsonObject("protocol_basic_fields");
        for (String key : new String[]{"hp", "max_hp", "block", "energy", "energy_per_turn", "card_draw_per_turn"})
            player.add(key, basic.get(key));
        player.addProperty("gold", requiredCardInt(srcPlayer, "gold"));
        player.addProperty("orb_slots", requiredCardInt(srcPlayer, "maxOrbs"));
        // last_targeted_monster is omitted: STS1 has no such player field, and
        // the accepted empty-power Sentry root cannot use C++ SURROUNDED logic.
        player.addProperty("strength", 0); player.addProperty("dexterity", 0);
        player.addProperty("focus", 0); player.addProperty("artifact", 0);
        String stanceClass = requiredString(srcPlayer, "stance_class");
        if ("com.megacrit.cardcrawl.stances.NeutralStance".equals(stanceClass)) player.addProperty("stance", "NEUTRAL");
        else if ("com.megacrit.cardcrawl.stances.WrathStance".equals(stanceClass)) player.addProperty("stance", "WRATH");
        else if ("com.megacrit.cardcrawl.stances.CalmStance".equals(stanceClass)) player.addProperty("stance", "CALM");
        else if ("com.megacrit.cardcrawl.stances.DivinityStance".equals(stanceClass)) player.addProperty("stance", "DIVINITY");
        else throw new IllegalStateException("unsupported player stance: " + stanceClass);
        player.add("statuses", array());
        JsonObject counters = object();
        counters.addProperty("necronomicon_used", false); counters.addProperty("combust_hp_loss", 0);
        counters.addProperty("deva_energy", 0); counters.addProperty("echo_doubled", 0);
        counters.addProperty("panache", 0); counters.addProperty("cards_played", 0);
        counters.addProperty("attacks_played", 0); counters.addProperty("skills_played", 0);
        JsonArray pellets = array(); pellets.add(false); pellets.add(false); pellets.add(false);
        counters.add("orange_pellets", pellets); counters.addProperty("cards_discarded", 0);
        counters.addProperty("last_attack_unblocked", 0); counters.addProperty("times_damaged", 0);
        counters.addProperty("bomb1", 0); counters.addProperty("bomb2", 0); counters.addProperty("bomb3", 0);
        player.add("counters", counters); protocol.add("player", player);

        JsonObject sourcePiles = source.getAsJsonObject("piles"), piles = object();
        if (requiredArray(sourcePiles, "limbo").size() != 0)
            throw new IllegalStateException("nonempty live limbo is unsupported at the stable player boundary");
        for (String pile : new String[]{"hand", "draw", "discard", "exhaust"}) {
            JsonArray output = array();
            for (com.google.gson.JsonElement e : requiredArray(sourcePiles, pile)) {
                JsonObject c = e.getAsJsonObject();
                if (!c.has("protocol_card") || !c.get("protocol_card").isJsonObject())
                    throw new IllegalStateException("missing canonical protocol card mapping: " + pile);
                output.add(GSON.fromJson(c.getAsJsonObject("protocol_card"), JsonObject.class));
            }
            piles.add(pile, output);
        }
        piles.add("limbo", requiredArray(sourcePiles, "limbo"));
        // No Sentry in the allowlisted encounter can create a Stasis card.
        piles.add("stasis", array());
        protocol.add("piles", piles);
        protocol.add("card_counters", deriveCardCounters(piles));

        JsonArray relics = array(); JsonObject starterRelic = object(); starterRelic.addProperty("id", "BURNING_BLOOD"); relics.add(starterRelic);
        protocol.add("relics", relics); protocol.add("relic_counters", array());
        JsonArray sourcePotions = requiredArray(source, "potions"), potions = array();
        if (requiredCardInt(srcPlayer, "potionSlots") != sourcePotions.size())
            throw new IllegalStateException("potion slot count does not match AbstractPlayer.potionSlots");
        int potionSlot = 0;
        for (com.google.gson.JsonElement e : sourcePotions) {
            if (e.isJsonNull()) { JsonObject empty = object(); empty.addProperty("id", "Potion Slot"); potions.add(empty); }
            else {
                JsonObject potion = e.getAsJsonObject(); JsonObject clean = object();
                if (requiredCardInt(potion, "slot") != potionSlot) throw new IllegalStateException("potion slot/index mismatch");
                String potionId = requiredString(potion, "id");
                if (!supportedPotionId(potionId)) throw new IllegalStateException("unsupported native potion ID: " + potionId);
                clean.addProperty("id", potionId); potions.add(clean);
            }
            potionSlot++;
        }
        protocol.add("potions", potions);
        protocol.add("rng", source.get("rng"));
        protocol.add("monsters", source.get("protocol_monsters"));
        JsonObject group = object(); JsonArray falseFlags = array(); falseFlags.add(false); falseFlags.add(false); falseFlags.add(false);
        group.add("extra_roll", falseFlags); group.add("skip_turn", falseFlags); protocol.add("monster_group", group);
        protocol.addProperty("battle_over", false); protocol.addProperty("end_turn_queued", false);
        protocol.addProperty("turn_has_ended", false); protocol.addProperty("skip_monster_turn", false);
        return protocol;
    }

    private static JsonObject deriveCardCounters(JsonObject piles) {
        JsonObject counts=object(); int strikes=0;
        for(String pile:new String[]{"hand","draw","discard","exhaust"}) for(com.google.gson.JsonElement e:requiredArray(piles,pile))
            if("STRIKE_RED".equals(requiredString(e.getAsJsonObject(),"id")) && !"exhaust".equals(pile)) strikes++;
        counts.addProperty("hand_normality",0); counts.addProperty("hand_pain",0); counts.addProperty("strikes_in_combat",strikes);
        counts.addProperty("hand_blood",0); counts.addProperty("draw_blood",0); counts.addProperty("discard_blood",0);
        return counts;
    }

    private static boolean supportedPotionId(String id) {
        return "Potion Slot".equals(id) || "Ambrosia".equals(id) || "Ancient Potion".equals(id)
                || "AttackPotion".equals(id) || "BlessingOfTheForge".equals(id) || "Block Potion".equals(id)
                || "BloodPotion".equals(id) || "BottledMiracle".equals(id) || "ColorlessPotion".equals(id)
                || "CultistPotion".equals(id) || "CunningPotion".equals(id) || "Dexterity Potion".equals(id)
                || "DistilledChaos".equals(id) || "DuplicationPotion".equals(id) || "ElixirPotion".equals(id)
                || "Energy Potion".equals(id) || "EntropicBrew".equals(id) || "EssenceOfDarkness".equals(id)
                || "EssenceOfSteel".equals(id) || "Explosive Potion".equals(id) || "FairyPotion".equals(id)
                || "Fear Potion".equals(id) || "Fire Potion".equals(id) || "SteroidPotion".equals(id)
                || "FocusPotion".equals(id) || "Fruit Juice".equals(id) || "GamblersBrew".equals(id)
                || "GhostInAJar".equals(id) || "HeartOfIron".equals(id) || "LiquidBronze".equals(id)
                || "LiquidMemories".equals(id) || "Poison Potion".equals(id) || "PotionOfCapacity".equals(id)
                || "PowerPotion".equals(id) || "Regen Potion".equals(id) || "SkillPotion".equals(id)
                || "SmokeBomb".equals(id) || "SneckoOil".equals(id) || "SpeedPotion".equals(id)
                || "StancePotion".equals(id) || "Strength Potion".equals(id) || "SwiftPotion".equals(id)
                || "Weak Potion".equals(id);
    }
    private static JsonObject protocolBoundary(Object room, Object player, Object manager) throws Exception {
        JsonObject out=object();
        Object actions=required(get(manager,"actions"),"GameActionManager.actions");
        Object cardQueue=required(get(manager,"cardQueue"),"GameActionManager.cardQueue");
        Object monsterQueue=required(get(manager,"monsterQueue"),"GameActionManager.monsterQueue");
        if(!(actions instanceof List) || !(cardQueue instanceof List) || !(monsterQueue instanceof List))
            throw new IllegalStateException("required action/card/monster queue is not a List");
        out.addProperty("stage","PLAYER_NORMAL");
        out.addProperty("queues_empty",((List<?>)actions).isEmpty() && ((List<?>)cardQueue).isEmpty() && ((List<?>)monsterQueue).isEmpty());
        out.addProperty("action_queue_size",((List<?>)actions).size());
        out.addProperty("card_queue_size",((List<?>)cardQueue).size());
        out.addProperty("battle_over",bool(room,"isBattleOver"));
        out.addProperty("end_turn_queued",bool(player,"endTurnQueued"));
        out.addProperty("turn_has_ended",bool(manager,"turnHasEnded"));
        out.addProperty("skip_monster_turn",bool(room,"skipMonsterTurn"));
        return out;
    }
    private static JsonObject captureSource() throws Exception {
        JsonObject root=object(); Class<?> d=AbstractDungeon.class; Object p=AbstractDungeon.player;
        Object room=required(AbstractDungeon.getCurrRoom(),"AbstractDungeon.getCurrRoom()"); Object monsters=required(get(room,"monsters"),"AbstractRoom.monsters");
        root.addProperty("run_seed",String.valueOf(required(stat(Class.forName("com.megacrit.cardcrawl.core.Settings"),"seed"),"Settings.seed")));
        root.addProperty("character",String.valueOf(required(get(p,"chosenClass"),"AbstractPlayer.chosenClass")));
        root.addProperty("act",((Number)stat(d,"actNum")).intValue()); root.addProperty("floor",((Number)stat(d,"floorNum")).intValue());
        root.addProperty("ascension",((Number)stat(d,"ascensionLevel")).intValue());
        root.addProperty("turn",((Number)stat(Class.forName("com.megacrit.cardcrawl.actions.GameActionManager"),"turn")).intValue());
        root.addProperty("room_class",room.getClass().getName()); root.addProperty("room_identity",Integer.toHexString(System.identityHashCode(room)));
        root.add("room_context",roomContext(room));
        root.addProperty("encounter_status","THREE_SENTRIES derived only after exact supported room provenance and exact vanilla roster validation");
        JsonObject player=object();
        for(String n:new String[]{"currentHealth","maxHealth","currentBlock","gold","gameHandSize"}) player.addProperty(n,integer(p,n));
        Object energy=required(get(p,"energy"),"AbstractPlayer.energy"); player.addProperty("energy_current",((Number)Class.forName("com.megacrit.cardcrawl.ui.panels.EnergyPanel").getMethod("getCurrentEnergy").invoke(null)).intValue());
        player.addProperty("energy_master",integer(energy,"energyMaster"));
        player.add("protocol_basic_fields",protocolPlayerBasic(p));
        Object stance=required(get(p,"stance"),"AbstractPlayer.stance"); player.addProperty("stance",String.valueOf(stance));
        player.addProperty("stance_class",stance.getClass().getName());
        player.addProperty("cardsPlayedThisTurn",integer(p,"cardsPlayedThisTurn"));
        player.addProperty("damagedThisCombat",integer(p,"damagedThisCombat"));
        player.addProperty("maxOrbs",integer(p,"maxOrbs"));
        player.addProperty("potionSlots",integer(p,"potionSlots"));
        JsonArray powers=array(); Object powerList=required(get(p,"powers"),"AbstractCreature.powers");
        for(Object power:(Iterable<?>)powerList) {
            JsonObject x=object(); x.addProperty("id",String.valueOf(required(get(power,"ID"),"AbstractPower.ID")));
            x.addProperty("amount",integer(power,"amount"));
            String timingId=playerTimingStatusId(power.getClass().getName());
            if(timingId!=null) x.add("protocol_timing_status",serializePlayerPowerTiming(power));
            powers.add(x);
        }
        player.add("powers",powers); root.add("player",player);
        Object actionManager=required(stat(AbstractDungeon.class,"actionManager"),"AbstractDungeon.actionManager");
        root.add("protocol_boundary_fields",protocolBoundary(room,p,actionManager));
        JsonObject piles=object(); piles.add("hand",cards(get(p,"hand"),"hand")); piles.add("draw",cards(get(p,"drawPile"),"draw"));
        piles.add("discard",cards(get(p,"discardPile"),"discard")); piles.add("exhaust",cards(get(p,"exhaustPile"),"exhaust"));
        piles.add("limbo",cards(get(p,"limbo"),"limbo"));
        root.add("piles",piles); root.add("card_identity_map",cardIdentityMap(piles));
        JsonObject counterBasis=object();
        counterBasis.addProperty("total_discarded_this_turn",((Number)stat(Class.forName("com.megacrit.cardcrawl.actions.GameActionManager"),"totalDiscardedThisTurn")).intValue());
        counterBasis.addProperty("damage_received_this_combat",((Number)stat(Class.forName("com.megacrit.cardcrawl.actions.GameActionManager"),"damageReceivedThisCombat")).intValue());
        root.add("counter_basis",counterBasis);
        JsonArray relics=array(); for(Object r:(Iterable<?>)required(get(p,"relics"),"AbstractPlayer.relics")) { JsonObject x=object(); x.addProperty("id",String.valueOf(required(get(r,"relicId"),"AbstractRelic.relicId"))); x.addProperty("counter",integer(r,"counter")); relics.add(x); } root.add("relics",relics);
        JsonArray potions=array(); for(Object potion:(Iterable<?>)required(get(p,"potions"),"AbstractPlayer.potions")) { if(potion==null) { potions.add((String)null); continue; } JsonObject x=object(); x.addProperty("id",String.valueOf(required(get(potion,"ID"),"AbstractPotion.ID"))); x.addProperty("slot",integer(potion,"slot")); potions.add(x); } root.add("potions",potions);
        JsonArray mons=array(); JsonArray protocolMons=array(); Object roster=required(get(monsters,"monsters"),"MonsterGroup.monsters"); int slot=0;
        for(Object m:(Iterable<?>)roster) { if(m==null){ mons.add((String)null); slot++; continue; }
            JsonObject x=value(m,"id","currentHealth","maxHealth","currentBlock","isDead","isEscaping","halfDead","escapeNext","nextMove","intent");
            x.addProperty("slot",slot); JsonArray history=array(); for(Object move:(Iterable<?>)required(get(m,"moveHistory"),"AbstractMonster.moveHistory")) history.add(String.valueOf(move)); x.add("move_history",history);
            JsonArray mp=array(); for(Object power:(Iterable<?>)required(get(m,"powers"),"AbstractCreature.powers")){
                JsonObject raw=object(); raw.addProperty("class_name",power.getClass().getName());
                raw.addProperty("id",String.valueOf(required(get(power,"ID"),"AbstractPower.ID"))); raw.addProperty("amount",integer(power,"amount"));
                String powerClass=power.getClass().getName();
                if("com.megacrit.cardcrawl.powers.WeakPower".equals(powerClass)||"com.megacrit.cardcrawl.powers.VulnerablePower".equals(powerClass))
                    raw.addProperty("just_applied",bool(power,"justApplied"));
                mp.add(raw);
            } x.add("powers",mp);
            if("com.megacrit.cardcrawl.monsters.exordium.Sentry".equals(m.getClass().getName())) {
                x.addProperty("firstMove",bool(m,"firstMove")); x.addProperty("beamDmg",integer(m,"beamDmg")); x.addProperty("dazedAmt",integer(m,"dazedAmt"));
                protocolMons.add(serializeSourceMonster(x,slot));
            }
            mons.add(x); slot++;
        } root.add("monsters",mons); root.add("rng",rngs());
        root.add("protocol_monsters",protocolMons);
        JsonObject sources=object();
        sources.addProperty("player", "AbstractDungeon.player: currentHealth/maxHealth/currentBlock/gold inherited from AbstractCreature; gameHandSize/energy/stance; powers inherited from AbstractCreature; energy via EnergyPanel.getCurrentEnergy");
        sources.addProperty("piles", "AbstractPlayer hand/drawPile/discardPile/exhaustPile group order; AbstractCard UUID.toString/cardID/timesUpgraded/cost/costForTurn/misc/isEthereal/retain and freeToPlayOnce");
        sources.addProperty("relics_potions", "AbstractPlayer relics/potions; AbstractRelic relicId/counter; AbstractPotion ID/slot");
        sources.addProperty("monsters", "current room MonsterGroup original roster order; AbstractMonster HP/block/flags/nextMove/intent/moveHistory; Sentry firstMove/beamDmg/dazedAmt");
        sources.addProperty("rng", "AbstractDungeon six RNG object references; RandomXS128.getState(0/1) and wrapper counter, sampled before/after without draws");
        root.add("field_sources",sources);
        JsonObject mapping=object();
        mapping.addProperty("$.capture_id","TRANSPORT_METADATA: independently generated capture transaction UUID; not a native battle identity and not a simulator state field");
        mapping.addProperty("$.player.counters.*","UNAVAILABLE: protocol-specific exact counter translations are not implemented");
        mapping.addProperty("$.player.statuses[].just_applied","VERIFIED for WEAK/FRAIL/VULNERABLE/DOUBLE_DAMAGE/DRAW_REDUCTION via each concrete Power private instance field; omitted for statuses whose C++ transition does not read this flag; timing serialization rejects unrecognized source classes and C++ rejects unknown status IDs");
        mapping.addProperty("$.card_counters.*","DERIVABLE_FROM_ORDERED_PILES: Counter category rules are verified against CardManager add/remove hooks; no separate game field is sampled");
        mapping.addProperty("$.relic_counters[]","UNVERIFIED: raw relic counters are readable, but protocol normalization to supported named counters is not implemented");
        mapping.addProperty("$.potions[]","UNVERIFIED: null slots and slot-index normalization are not yet emitted in protocol schema");
        mapping.addProperty("$.monsters[].statuses[].just_applied","UNAVAILABLE: no verified native power timing field mapping");
        mapping.addProperty("$.monsters[].unique_power0/unique_power1/misc_info","UNAVAILABLE: Sentry power-to-engine slot translation is not implemented");
        mapping.addProperty("$.monster_group.*","UNAVAILABLE: group flags and C++ monster-turn cursor are not captured at the stable boundary");
        mapping.addProperty("$.stage/queues_empty/action_queue_size/card_queue_size/battle_over/end_turn_queued/turn_has_ended/skip_monster_turn","SOURCE_MAPPED: protocol_boundary_fields; AbstractRoom and AbstractPlayer endTurnQueued plus GameActionManager phase/turn flags and exact queue lengths; full protocol remains separately gated");
        root.add("protocol_mapping_status",mapping);
        return root;
    }
    private static void writeReport(String status, JsonObject data, String error) throws Exception {
        File f=new File(OUTPUT); File parent=f.getParentFile(); if(!parent.isDirectory()&&!parent.mkdirs()) throw new IllegalStateException("cannot create output directory");
        JsonObject report=object(); report.addProperty("protocol","live_battle_capture_report"); report.addProperty("version",1); report.addProperty("status",status);
        report.addProperty("capture_time_epoch_ms",System.currentTimeMillis()); report.addProperty("stable_boundary",stableBoundaryObserved);
        report.addProperty("boundary_evidence",boundaryEvidence);
        report.add("source_snapshot",data); report.addProperty("error",error);
        FileWriter w=new FileWriter(f,false); try{GSON.toJson(report,w);}finally{w.close();}
    }

    /**
     * Fail-closed wire writer. It accepts only a fully assembled canonical v1
     * object; it never fills absent values. Keeping this gate separate from the
     * diagnostic report prevents a partial capture from becoming engine input.
     */
    static void writeProtocol(JsonObject protocol, File f) throws Exception {
        requireString(protocol, "protocol", "live_battle_state");
        requireInt(protocol, "version", 1);
        if (!protocol.has("capture_id") || protocol.get("capture_id").isJsonNull())
            protocol.addProperty("capture_id", UUID.randomUUID().toString());
        for (String key : new String[]{"character", "stage", "encounter", "seed"}) requireValue(protocol, key);
        if (!"THREE_SENTRIES".equals(requiredString(protocol, "encounter")))
            throw new IllegalStateException("only the exact THREE_SENTRIES encounter is supported");
        String captureId = requiredString(protocol, "capture_id");
        if (!captureId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("capture_id must be a UUID transaction identifier");
        if (!"IRONCLAD".equals(protocol.get("character").getAsString())) throw new IllegalStateException("unsupported character");
        if (!"PLAYER_NORMAL".equals(protocol.get("stage").getAsString())) throw new IllegalStateException("unsupported battle stage");
        if (!protocol.has("room_context") || !protocol.get("room_context").isJsonObject()) throw new IllegalStateException("missing room_context");
        validateRoomContext(protocol.getAsJsonObject("room_context"));
        if (!protocol.has("player") || !protocol.get("player").isJsonObject()) throw new IllegalStateException("missing player state");
        if (!protocol.has("piles") || !protocol.get("piles").isJsonObject()) throw new IllegalStateException("missing card piles");
        if (!protocol.has("rng") || !protocol.get("rng").isJsonObject()) throw new IllegalStateException("missing RNG states");
        if (!protocol.has("monsters") || !protocol.get("monsters").isJsonArray()) throw new IllegalStateException("missing monsters");
        // Reject temporary diagnostic payloads and any engine-only search fields.
        if (protocol.has("engine_only_fields") || protocol.has("source_snapshot") || protocol.has("protocol" ) == false)
            throw new IllegalStateException("diagnostic report is not a protocol snapshot");
        requireKeys(protocol, "act", "queues_empty", "action_queue_size", "card_queue_size", "seed", "floor", "ascension", "turn", "encounter",
                "card_counters", "relics", "relic_counters", "potions", "monster_group", "battle_over",
                "end_turn_queued", "turn_has_ended", "skip_monster_turn");
        JsonObject room = protocol.getAsJsonObject("room_context");
        JsonObject rng = protocol.getAsJsonObject("rng");
        for (String name : new String[]{"ai", "card", "misc", "monster_hp", "potion", "shuffle"}) {
            JsonObject stream = rng.has(name) && rng.get(name).isJsonObject() ? rng.getAsJsonObject(name) : null;
            if (stream == null) throw new IllegalStateException("missing rng." + name);
            for (String key : new String[]{"stream_id", "seed0", "seed1", "counter"}) requireValue(stream, key);
            if (stream.get("counter").getAsInt() < 0) throw new IllegalStateException("negative rng counter: " + name);
        }
        JsonObject player = protocol.getAsJsonObject("player");
        requireKeys(player, "hp", "max_hp", "block", "energy", "energy_per_turn", "card_draw_per_turn", "gold", "orb_slots",
                "strength", "dexterity", "focus", "artifact", "stance", "statuses", "counters");
        if (player.has("last_targeted_monster") && !player.get("last_targeted_monster").isJsonNull()
                && (!player.get("last_targeted_monster").isJsonPrimitive() || !player.getAsJsonPrimitive("last_targeted_monster").isNumber()))
            throw new IllegalStateException("last_targeted_monster must be an integer when supplied");
        if (!player.get("statuses").isJsonArray()) throw new IllegalStateException("player.statuses must be an array");
        for (com.google.gson.JsonElement element : player.getAsJsonArray("statuses")) {
            JsonObject status = element.getAsJsonObject();
            requireKeys(status, "id", "amount");
            String id = requiredString(status, "id");
            boolean timingRequired = playerStatusUsesJustApplied(id);
            if (timingRequired != status.has("just_applied"))
                throw new IllegalStateException(timingRequired
                        ? "missing required player status just_applied: " + id
                        : "player status just_applied is not applicable: " + id);
            if (timingRequired && !status.get("just_applied").isJsonPrimitive())
                throw new IllegalStateException("player status just_applied must be boolean: " + id);
            if (timingRequired && !status.getAsJsonPrimitive("just_applied").isBoolean())
                throw new IllegalStateException("player status just_applied must be boolean: " + id);
        }
        boolean surrounded = false;
        for (com.google.gson.JsonElement element : player.getAsJsonArray("statuses"))
            if ("SURROUNDED".equals(requiredString(element.getAsJsonObject(), "id"))) surrounded = true;
        if (surrounded && (!player.has("last_targeted_monster") || player.get("last_targeted_monster").isJsonNull()))
            throw new IllegalStateException("SURROUNDED requires a trusted last_targeted_monster value");
        requireKeys(player.getAsJsonObject("counters"), "necronomicon_used", "combust_hp_loss", "deva_energy", "echo_doubled", "panache",
                "cards_played", "attacks_played", "skills_played", "orange_pellets", "cards_discarded", "last_attack_unblocked",
                "times_damaged", "bomb1", "bomb2", "bomb3");
        JsonObject piles = protocol.getAsJsonObject("piles");
        requireKeys(piles, "hand", "draw", "discard", "exhaust", "limbo", "stasis");
        Set<String> uuids = new HashSet<String>();
        for (String pile : new String[]{"hand", "draw", "discard", "exhaust"}) {
            if (!piles.get(pile).isJsonArray()) throw new IllegalStateException("expected array: piles." + pile);
            for (com.google.gson.JsonElement element : piles.getAsJsonArray(pile)) {
                JsonObject card = element.getAsJsonObject();
                requireKeys(card, "uuid", "id", "special_data", "cost", "cost_for_turn", "upgraded", "upgrade_count", "free_to_play_once", "retain");
                String uuid = requiredString(card, "uuid");
                if (!uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                        || !uuids.add(uuid.toLowerCase(Locale.ROOT)))
                    throw new IllegalStateException("invalid or duplicate full card UUID: " + uuid);
                int upgrades = card.get("upgrade_count").getAsInt();
                if (upgrades < 0 || upgrades > 1 || card.get("upgraded").getAsBoolean() != (upgrades == 1))
                    throw new IllegalStateException("unsupported card upgrade count/state: " + uuid);
                String id=requiredString(card,"id");
                if(!CardIdWireMap.supportsEngineId(id)) throw new IllegalStateException("unsupported engine card id: "+id);
                if(!"CXX_IMPLEMENTATION_PRESENT_NOT_L4_VERIFIED".equals(CardIdWireMap.effectModelStatus(id)))
                    throw new IllegalStateException("card effect is not admitted for search: " + id);
                int cost=requiredCardInt(card,"cost"), costForTurn=requiredCardInt(card,"cost_for_turn");
                if(cost<Byte.MIN_VALUE||cost>Byte.MAX_VALUE||costForTurn<Byte.MIN_VALUE||costForTurn>Byte.MAX_VALUE)
                    throw new IllegalStateException("card cost outside C++ int8 range: "+uuid);
                int special=requiredCardInt(card,"special_data");
                boolean hasSpecial="SEARING_BLOW".equals(id)||"RITUAL_DAGGER".equals(id);
                if((!hasSpecial&&special!=0)||(hasSpecial&&special<0)||("SEARING_BLOW".equals(id)&&special!=upgrades))
                    throw new IllegalStateException("unsupported card special_data: "+id+"="+special);
            }
        }
        if (protocol.getAsJsonArray("monsters").size() != 3) throw new IllegalStateException("unsupported monster roster size");
        for (com.google.gson.JsonElement element : protocol.getAsJsonArray("monsters"))
            requireKeys(element.getAsJsonObject(), "slot", "id", "hp", "max_hp", "block", "escaping", "half_dead", "escape_next",
                    "move_history", "statuses", "unique_power0", "unique_power1", "misc_info", "beam_damage", "dazed_amount", "first_move");
        // The canonical protocol validator in C++ remains authoritative for all
        // engine enums and nested fields. This Java gate guarantees no partial
        // or diagnostic object is published as a candidate input.
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create protocol output directory");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8);
        try { GSON.toJson(protocol, w); } finally { w.close(); }
        writeCaptureManifest(protocol, f);
    }
    private static void writeCaptureManifest(JsonObject protocol, File snapshot) throws Exception {
        byte[] bytes = Files.readAllBytes(snapshot.toPath());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        JsonObject manifest = object();
        manifest.addProperty("protocol", "live_battle_capture_manifest");
        manifest.addProperty("version", 1);
        manifest.addProperty("capture_id", requiredString(protocol, "capture_id"));
        manifest.addProperty("snapshot_sha256", hex.toString());
        File sidecar = new File(snapshot.getPath() + ".capture.json");
        OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(sidecar, false), StandardCharsets.UTF_8);
        try { GSON.toJson(manifest, out); } finally { out.close(); }
    }
    private static void requireValue(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) throw new IllegalStateException("missing required protocol field: " + key);
    }
    private static void requireKeys(JsonObject o, String... keys) {
        for (String key : keys) requireValue(o, key);
    }
    private static String requiredString(JsonObject o, String key) {
        requireValue(o, key);
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString()) throw new IllegalStateException("expected string: " + key);
        return o.get(key).getAsString();
    }
    private static void requireString(JsonObject o, String key, String expected) {
        if (!expected.equals(requiredString(o, key))) throw new IllegalStateException("unexpected " + key);
    }
    private static void requireInt(JsonObject o, String key, int expected) {
        requireValue(o, key);
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isNumber() || o.get(key).getAsInt() != expected)
            throw new IllegalStateException("unexpected " + key);
    }
    private static void attempt() throws Exception {
        long now=System.currentTimeMillis(); if(now-lastAttempt<250L)return; lastAttempt=now;
        if(!stable())return;
        stableBoundaryObserved=true;
        Object room=AbstractDungeon.getCurrRoom(); Object monsters=get(room,"monsters"); Object roster=get(monsters,"monsters");
        String roomClass=room.getClass().getName();
        JsonObject context=roomContext(room);
        try { validateRoomContext(context); }
        catch (Throwable unsupportedRoom) {
            JsonObject evidence=object(); evidence.add("room_context",context);
            finished=true; writeReport("REJECTED",evidence,String.valueOf(unsupportedRoom.getMessage())); return;
        }
        if(!"IRONCLAD".equals(String.valueOf(get(AbstractDungeon.player,"chosenClass")))) {
            finished=true; writeReport("REJECTED",object(),"only vanilla Ironclad is supported"); return;
        }
        if(!(roster instanceof List) || ((List<?>)roster).size()!=3) {finished=true;writeReport("REJECTED",object(),"expected exactly three original monster slots");return;}
        for(Object m:(List<?>)roster) if(m==null || !"com.megacrit.cardcrawl.monsters.exordium.Sentry".equals(m.getClass().getName())
                || !"Sentry".equals(String.valueOf(get(m,"id")))) {finished=true;writeReport("REJECTED",object(),"encounter is not three exact vanilla Sentry instances");return;}
        JsonObject before=rngs(); JsonObject source=captureSource(); JsonObject after=rngs(); JsonObject sourceAfter=captureSource();
        if(!before.equals(after)){finished=true;writeReport("REJECTED",source,"RNG changed during read-only capture");return;}
        if(!source.equals(sourceAfter)){finished=true;writeReport("REJECTED",source,"source state changed while snapshot was being read");return;}
        Object manager=stat(AbstractDungeon.class,"actionManager");
        if(!stable() || room!=AbstractDungeon.getCurrRoom() || manager!=stat(AbstractDungeon.class,"actionManager")) {finished=true;writeReport("REJECTED",source,"battle identity or stable boundary changed during capture");return;}
        source.add("rng_before",before); source.add("rng_after",after);
        try {
            JsonObject protocol=protocolFromSource(source);
            File protocolFile=new File(PROTOCOL_OUTPUT);
            writeProtocol(protocol,protocolFile);
            JsonObject result=object();
            result.add("protocol_snapshot",protocol);
            result.addProperty("protocol_file",protocolFile.getAbsolutePath());
            result.addProperty("protocol_sha256",sha256(protocolFile));
            result.addProperty("source_state_stable",true);
            result.addProperty("rng_unchanged",true);
            finished=true; writeReport("PROTOCOL_CAPTURED",result,"complete v1 snapshot passed Java validation; C++ recovery remains a separate required check");
        } catch (Throwable t) {
            finished=true; writeReport("PROTOCOL_REJECTED",source,t.getClass().getName()+": "+String.valueOf(t.getMessage()));
        }
    }
    private static String sha256(File file) throws Exception {
        byte[] bytes=Files.readAllBytes(file.toPath());
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex=new StringBuilder(64);
        for(byte b:digest) hex.append(String.format(Locale.ROOT,"%02x",b&0xff));
        return hex.toString();
    }
    @SpirePostfixPatch public static void Postfix(){
        if(!finished) try { attempt(); } catch(Throwable t) { finished=true; try { writeReport("REJECTED",object(),t.getClass().getName()+": "+String.valueOf(t.getMessage())); } catch(Throwable ignored) { } }
        try{observePlayerPowerTiming();}catch(Throwable t){powerTimingFailed=true;try{JsonObject error=object();error.addProperty("protocol","player_power_timing_observation");error.addProperty("status","READ_ERROR");error.addProperty("error",t.getClass().getName()+": "+String.valueOf(t.getMessage()));appendPowerTimingObservation(error);}catch(Throwable ignored){}}
    }
}

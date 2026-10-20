package rngprobe;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/** Test-only serializer driver. Its input must be a fixture explicitly labelled offline. */
public final class LiveBattleStateV1OfflineProducer {
    private LiveBattleStateV1OfflineProducer() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--diagnose-cards".equals(args[0])) {
            JsonObject source = readObject(args[1]);
            if (!source.has("test_only") || !"OFFLINE_CARD_SOURCE_FIXTURE_NOT_REAL_GAME_STATE".equals(source.get("test_only").getAsString()))
                throw new IllegalArgumentException("card diagnostics input must be explicitly marked as offline");
            JsonObject diagnostic = new JsonObject();
            diagnostic.addProperty("protocol", "live_card_mapping_diagnostic");
            diagnostic.addProperty("evidence", "OFFLINE_FIXTURE_NOT_REAL_GAME_STATE");
            JsonObject piles = new JsonObject();
            for (String pile : new String[]{"hand", "draw", "discard", "exhaust"}) {
                JsonArray input = source.has(pile) ? source.getAsJsonArray(pile) : new JsonArray();
                JsonArray output = new JsonArray();
                for (int i = 0; i < input.size(); i++) {
                    JsonObject item = LiveBattleStateProbe.diagnoseSourceCard(input.get(i).getAsJsonObject());
                    item.addProperty("pile", pile); item.addProperty("pile_index", i); output.add(item);
                }
                piles.add(pile, output);
            }
            diagnostic.add("piles", piles);
            diagnostic.add("card_identity_map", LiveBattleStateProbe.cardIdentityMap(piles));
            FileWriter writer = new FileWriter(args[2]);
            try { new Gson().toJson(diagnostic, writer); } finally { writer.close(); }
            System.out.println("LIVE_CARD_MAPPING_DIAGNOSTIC evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE cards=" + countCards(piles));
            return;
        }
        if (args.length == 3 && "--mock-live-source".equals(args[0])) {
            FileReader fixtureReader = new FileReader(args[1]);
            JsonObject protocolFixture;
            try { protocolFixture = new JsonParser().parse(fixtureReader).getAsJsonObject(); }
            finally { fixtureReader.close(); }
            if (!protocolFixture.has("test_only") || !"OFFLINE_FIXTURE_NOT_REAL_GAME_STATE".equals(protocolFixture.get("test_only").getAsString()))
                throw new IllegalArgumentException("mock source must derive from an explicitly offline fixture");
            protocolFixture.remove("test_only");
            JsonObject source = mockLiveSource(protocolFixture);
            JsonObject rebuilt = LiveBattleStateProbe.protocolFromSource(source);
            LiveBattleStateProbe.writeProtocol(rebuilt, new File(args[2]));
            System.out.println("LIVE_STATE_V1_SOURCE_ASSEMBLER evidence=OFFLINE_MOCK_SOURCE_NOT_REAL_GAME_STATE");
            return;
        }
        if (args.length != 2 && args.length != 3 && args.length != 4) throw new IllegalArgumentException("usage: <offline fixture> <output> [card-source fixture [monster-source fixture]]");
        verifyPowerTimingClassMap();
        JsonObject input = readObject(args[0]);
        if (!input.has("test_only") || !"OFFLINE_FIXTURE_NOT_REAL_GAME_STATE".equals(input.get("test_only").getAsString()))
            throw new IllegalArgumentException("input is not explicitly marked as an offline fixture");
        input.remove("test_only");
        if (args.length >= 3) {
            JsonObject sourceCards;
            FileReader cardsReader = new FileReader(args[2]);
            try { sourceCards = new JsonParser().parse(cardsReader).getAsJsonObject(); }
            finally { cardsReader.close(); }
            if (!sourceCards.has("test_only") || !"OFFLINE_CARD_SOURCE_FIXTURE_NOT_REAL_GAME_STATE".equals(sourceCards.get("test_only").getAsString()))
                throw new IllegalArgumentException("card source input is not explicitly marked as an offline fixture");
            sourceCards.remove("test_only");
            JsonObject piles = input.getAsJsonObject("piles");
            for (String pile : new String[]{"hand", "draw", "discard", "exhaust"}) {
                JsonArray records = sourceCards.getAsJsonArray(pile);
                JsonArray converted = new JsonArray();
                for (int i = 0; i < records.size(); i++)
                    converted.add(LiveBattleStateProbe.serializeSourceCard(records.get(i).getAsJsonObject()));
                piles.add(pile, converted);
            }
            if (sourceCards.has("card_counters")) input.add("card_counters", sourceCards.getAsJsonObject("card_counters"));
        }
        if (args.length == 4) {
            JsonObject sourceMonsters;
            FileReader monstersReader = new FileReader(args[3]);
            try { sourceMonsters = new JsonParser().parse(monstersReader).getAsJsonObject(); }
            finally { monstersReader.close(); }
            if (!sourceMonsters.has("test_only") || !"OFFLINE_SENTRY_SOURCE_FIXTURE_NOT_REAL_GAME_STATE".equals(sourceMonsters.get("test_only").getAsString()))
                throw new IllegalArgumentException("monster source input is not explicitly marked as an offline fixture");
            sourceMonsters.remove("test_only");
            JsonArray source = sourceMonsters.getAsJsonArray("monsters");
            if (source == null || source.size() != 3) throw new IllegalArgumentException("offline Sentry source fixture must contain three original slots");
            JsonArray converted = new JsonArray();
            for (int i = 0; i < source.size(); i++)
                converted.add(LiveBattleStateProbe.serializeSourceMonster(source.get(i).getAsJsonObject(), i));
            input.add("monsters", converted);
        }
        LiveBattleStateProbe.writeProtocol(input, new File(args[1]));
        System.out.println("LIVE_STATE_V1_JAVA_SERIALIZED evidence=OFFLINE_FIXTURE_NOT_REAL_GAME_STATE");
    }

    private static JsonObject readObject(String path) throws Exception {
        FileReader reader = new FileReader(path);
        try { return new JsonParser().parse(reader).getAsJsonObject(); }
        finally { reader.close(); }
    }

    private static int countCards(JsonObject piles) {
        int count = 0;
        for (String pile : new String[]{"hand", "draw", "discard", "exhaust"}) count += piles.getAsJsonArray(pile).size();
        return count;
    }

    private static JsonObject mockLiveSource(JsonObject protocol) {
        JsonObject source = new JsonObject();
        source.addProperty("character", "IRONCLAD"); source.addProperty("run_seed", protocol.get("seed").getAsString());
        source.addProperty("act", protocol.get("act").getAsInt()); source.addProperty("floor", protocol.get("floor").getAsInt());
        source.addProperty("ascension", protocol.get("ascension").getAsInt()); source.addProperty("turn", 1);
        source.addProperty("room_class", protocol.getAsJsonObject("room_context").get("class").getAsString());
        source.add("room_context", protocol.get("room_context"));
        JsonObject player = new JsonObject(); JsonObject p = protocol.getAsJsonObject("player");
        player.addProperty("gold", p.get("gold").getAsInt()); player.addProperty("cardsPlayedThisTurn", 0);
        player.addProperty("damagedThisCombat", 0); player.addProperty("maxOrbs", 0);
        player.addProperty("potionSlots", protocol.getAsJsonArray("potions").size());
        player.addProperty("stance_class", "com.megacrit.cardcrawl.stances.NeutralStance");
        player.add("protocol_basic_fields", new JsonObject());
        JsonObject basic = player.getAsJsonObject("protocol_basic_fields");
        for (String key : new String[]{"hp", "max_hp", "block", "energy", "energy_per_turn", "card_draw_per_turn"}) basic.add(key, p.get(key));
        player.add("powers", new JsonArray()); source.add("player", player);
        JsonObject counters = new JsonObject(); counters.addProperty("total_discarded_this_turn", 0);
        counters.addProperty("damage_received_this_combat", 0); source.add("counter_basis", counters);
        JsonArray relics = new JsonArray(); JsonObject relic = new JsonObject(); relic.addProperty("id", "Burning Blood"); relic.addProperty("counter", -1); relics.add(relic); source.add("relics", relics);
        JsonArray rawPotions = new JsonArray();
        for (int i = 0; i < protocol.getAsJsonArray("potions").size(); i++) {
            com.google.gson.JsonElement element = protocol.getAsJsonArray("potions").get(i);
            JsonObject potion = new JsonObject();
            if (element.isJsonNull()) potion.addProperty("id", "Potion Slot");
            else potion.add("id", element.getAsJsonObject().get("id"));
            potion.addProperty("slot", i); rawPotions.add(potion);
        }
        source.add("potions", rawPotions); source.add("rng", protocol.get("rng"));
        source.add("protocol_boundary_fields", protocol);
        JsonObject piles = protocol.getAsJsonObject("piles"); JsonObject sourcePiles = new JsonObject();
        java.util.Map<String, String> wireToNative = new java.util.HashMap<String, String>();
        wireToNative.put("STRIKE_RED", "Strike_R"); wireToNative.put("DEFEND_RED", "Defend_R");
        wireToNative.put("BASH", "Bash"); wireToNative.put("TRUE_GRIT", "True Grit");
        wireToNative.put("HAVOC", "Havoc");
        wireToNative.put("SEARING_BLOW", "Searing Blow"); wireToNative.put("RITUAL_DAGGER", "RitualDagger");
        for (String pile : new String[]{"hand", "draw", "discard", "exhaust", "limbo"}) {
            JsonArray items = new JsonArray();
            if (piles.has(pile)) for (com.google.gson.JsonElement element : piles.getAsJsonArray(pile)) {
                JsonObject card = element.getAsJsonObject(), item = new JsonObject();
                item.add("uuid", card.get("uuid")); item.addProperty("card_id", wireToNative.get(card.get("id").getAsString()));
                item.addProperty("upgrades", card.get("upgrade_count").getAsInt()); item.addProperty("cost", card.get("cost").getAsInt());
                item.addProperty("cost_for_turn", card.get("cost_for_turn").getAsInt()); item.addProperty("misc", "RITUAL_DAGGER".equals(card.get("id").getAsString()) ? card.get("special_data").getAsInt() : 0);
                item.add("free_to_play_once", card.get("free_to_play_once")); item.add("retain", card.get("retain")); item.addProperty("ethereal", false);
                items.add(LiveBattleStateProbe.diagnoseSourceCard(item));
            }
            sourcePiles.add(pile, items);
        }
        source.add("piles", sourcePiles); source.add("card_identity_map", LiveBattleStateProbe.cardIdentityMap(sourcePiles));
        JsonArray rawMonsters = new JsonArray();
        for (com.google.gson.JsonElement element : protocol.getAsJsonArray("monsters")) {
            JsonObject m = element.getAsJsonObject(), raw = new JsonObject();
            raw.addProperty("id", "Sentry"); raw.add("slot", m.get("slot")); raw.add("currentHealth", m.get("hp"));
            raw.add("maxHealth", m.get("max_hp")); raw.add("currentBlock", m.get("block"));
            raw.add("isEscaping", m.get("escaping")); raw.add("halfDead", m.get("half_dead")); raw.add("escapeNext", m.get("escape_next"));
            raw.add("beamDmg", m.get("beam_damage")); raw.add("dazedAmt", m.get("dazed_amount")); raw.add("firstMove", m.get("first_move"));
            JsonArray moves = new JsonArray(); JsonArray history = m.getAsJsonArray("move_history");
            for (int i = history.size() - 1; i >= 0; i--) {
                String move = history.get(i).getAsString();
                if ("SENTRY_BEAM".equals(move)) moves.add(4); else if ("SENTRY_BOLT".equals(move)) moves.add(3);
            }
            raw.add("move_history", moves); JsonArray rawPowers = new JsonArray();
            for (com.google.gson.JsonElement powerElement : m.getAsJsonArray("statuses")) {
                JsonObject status = powerElement.getAsJsonObject(), power = new JsonObject(); String id=status.get("id").getAsString();
                if ("ARTIFACT".equals(id)) { power.addProperty("class_name", "com.megacrit.cardcrawl.powers.ArtifactPower"); power.addProperty("id", "Artifact"); }
                else if ("WEAK".equals(id)) { power.addProperty("class_name", "com.megacrit.cardcrawl.powers.WeakPower"); power.addProperty("id", "Weakened"); power.add("just_applied", status.get("just_applied")); }
                else { power.addProperty("class_name", "com.megacrit.cardcrawl.powers.VulnerablePower"); power.addProperty("id", "Vulnerable"); power.add("just_applied", status.get("just_applied")); }
                power.add("amount", status.get("amount")); rawPowers.add(power);
            }
            raw.add("powers", rawPowers); rawMonsters.add(raw);
        }
        JsonArray convertedMonsters = new JsonArray();
        for (int i = 0; i < rawMonsters.size(); i++)
            convertedMonsters.add(LiveBattleStateProbe.serializeSourceMonster(rawMonsters.get(i).getAsJsonObject(), i));
        source.add("protocol_monsters", convertedMonsters);
        return source;
    }

    private static void verifyPowerTimingClassMap() {
        String[][] mappings = {
                {"WeakPower", "WEAK"}, {"FrailPower", "FRAIL"}, {"VulnerablePower", "VULNERABLE"},
                {"DoubleDamagePower", "DOUBLE_DAMAGE"}, {"DrawReductionPower", "DRAW_REDUCTION"}
        };
        for (String[] mapping : mappings) {
            String className = "com.megacrit.cardcrawl.powers." + mapping[0];
            if (!mapping[1].equals(LiveBattleStateProbe.playerTimingStatusId(className)))
                throw new IllegalStateException("player power timing class map mismatch: " + className);
        }
        if (LiveBattleStateProbe.playerTimingStatusId("com.megacrit.cardcrawl.powers.IntangiblePlayerPower") != null)
            throw new IllegalStateException("IntangiblePlayerPower must not be assigned a just-applied timing field");
        System.out.println("LIVE_STATE_PLAYER_POWER_CLASS_MAP_OK mappings=5 intangible=not_applicable evidence=OFFLINE_MAPPING_CHECK");
    }
}

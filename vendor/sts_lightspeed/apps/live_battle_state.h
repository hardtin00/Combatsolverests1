#pragma once

#include <fstream>
#include <limits>
#include <stdexcept>
#include <set>
#include <string>
#include <vector>
#include <cctype>
#include <nlohmann/json.hpp>

#include "combat/BattleContext.h"
#include "sim/SimHelpers.h"
#include "constants/Relics.h"
#include "constants/MonsterEncounters.h"

namespace live_state_v1 {
using json = nlohmann::json;

struct RestoredState {
    sts::BattleContext context;
    std::vector<std::string> cardUuidByDenseId;
};

inline const json &required(const json &value, const char *key) {
    auto it = value.find(key);
    if (it == value.end() || it->is_null()) throw std::runtime_error(std::string("missing field: ") + key);
    return *it;
}

template <typename T> inline T number(const json &value, const char *key) {
    const auto &v = required(value, key);
    if (!v.is_number_integer()) throw std::runtime_error(std::string("expected integer: ") + key);
    const auto n = v.get<std::int64_t>();
    if (n < static_cast<std::int64_t>(std::numeric_limits<T>::min())
            || n > static_cast<std::int64_t>(std::numeric_limits<T>::max()))
        throw std::runtime_error(std::string("integer out of range: ") + key);
    return static_cast<T>(n);
}

inline bool playerStatusUsesJustApplied(int id) {
    return id == static_cast<int>(PS::DOUBLE_DAMAGE)
        || id == static_cast<int>(PS::DRAW_REDUCTION)
        || id == static_cast<int>(PS::FRAIL)
        || id == static_cast<int>(PS::VULNERABLE)
        || id == static_cast<int>(PS::WEAK);
}

template <int I = 0> inline bool setPlayerStatus(sts::Player &p, int id, int amount, bool hasJustApplied, bool justApplied) {
    constexpr int count = static_cast<int>(PS::THE_BOMB) + 1;
    if constexpr (I < count) {
        if (id == I) {
            const auto status = static_cast<PS>(I);
            p.setStatusValueNoChecks<static_cast<PS>(I)>(amount);
            p.setHasStatus<static_cast<PS>(I)>(amount != 0);
            if constexpr (I == static_cast<int>(PS::DOUBLE_DAMAGE) || I == static_cast<int>(PS::DRAW_REDUCTION)
                    || I == static_cast<int>(PS::FRAIL) || I == static_cast<int>(PS::VULNERABLE)
                    || I == static_cast<int>(PS::WEAK)) {
                if (!hasJustApplied) return false;
                p.setJustApplied<static_cast<PS>(I)>(justApplied);
            } else if (hasJustApplied) {
                return false;
            }
            return true;
        }
        return setPlayerStatus<I + 1>(p, id, amount, hasJustApplied, justApplied);
    }
    return false;
}

template <int I = 0> inline bool setMonsterStatus(sts::Monster &m, int id, int amount, bool justApplied) {
    constexpr int count = static_cast<int>(sts::MS::INVALID);
    if constexpr (I < count) {
        if (id == I) {
            m.setStatus<static_cast<sts::MS>(I)>(amount);
            m.setHasStatus<static_cast<sts::MS>(I)>(amount != 0);
            if constexpr (I == static_cast<int>(sts::MS::VULNERABLE)
                    || I == static_cast<int>(sts::MS::WEAK)
                    || I == static_cast<int>(sts::MS::RITUAL)) {
                m.setJustApplied<static_cast<sts::MS>(I)>(justApplied);
            } else if (justApplied) {
                return false;
            }
            return true;
        }
        return setMonsterStatus<I + 1>(m, id, amount, justApplied);
    }
    return false;
}

template <int I = 0> inline bool setRelic(sts::Player &p, int id) {
    constexpr int count = static_cast<int>(sts::RelicId::INVALID) < 128
            ? static_cast<int>(sts::RelicId::INVALID) : 128;
    if constexpr (I < count) {
        if (id == I) { p.setHasRelic<static_cast<sts::RelicId>(I)>(true); return true; }
        return setRelic<I + 1>(p, id);
    }
    return false;
}

inline Stance stance(const std::string &s) {
    if (s == "NEUTRAL") return Stance::NEUTRAL;
    if (s == "CALM") return Stance::CALM;
    if (s == "WRATH") return Stance::WRATH;
    if (s == "DIVINITY") return Stance::DIVINITY;
    throw std::runtime_error("unknown stance: " + s);
}

inline void loadRandom(sts::Random &rng, const json &j) {
    rng.seed0 = std::stoull(required(j, "seed0").get<std::string>(), nullptr, 16);
    rng.seed1 = std::stoull(required(j, "seed1").get<std::string>(), nullptr, 16);
    rng.counter = number<std::int32_t>(j, "counter");
    if (rng.counter < 0) throw std::runtime_error("negative RNG counter");
}

inline std::string streamId(const json &j) { return required(j, "stream_id").get<std::string>(); }

inline bool validUuid(const std::string &s) {
    if (s.size() != 36) return false;
    for (std::size_t i = 0; i < s.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (s[i] != '-') return false; }
        else if (!std::isxdigit(static_cast<unsigned char>(s[i]))) return false;
    }
    return true;
}

inline sts::CardInstance card(const json &j, int denseId) {
    const std::string id = required(j, "id").get<std::string>();
    const auto cardId = sts::SimHelpers::getCardIdForString(id);
    if (cardId == sts::CardId::INVALID) throw std::runtime_error("unknown card id: " + id);
    const bool mappedCard = cardId == sts::CardId::STRIKE_RED || cardId == sts::CardId::DEFEND_RED
            || cardId == sts::CardId::BASH || cardId == sts::CardId::TRUE_GRIT
            || cardId == sts::CardId::SEARING_BLOW || cardId == sts::CardId::RITUAL_DAGGER
            || cardId == sts::CardId::HAVOC || cardId == sts::CardId::SHRUG_IT_OFF
            || cardId == sts::CardId::CLEAVE || cardId == sts::CardId::BLUDGEON;
    if (!mappedCard) throw std::runtime_error("unsupported card outside verified mapping slice: " + id);
    const auto color = sts::getCardColor(cardId);
    if (color != sts::CardColor::RED && color != sts::CardColor::COLORLESS && color != sts::CardColor::CURSE)
        throw std::runtime_error("unsupported non-Ironclad card color: " + id);
    sts::CardInstance c(cardId);
    c.setUniqueId(denseId);
    const int specialData = number<int>(j, "special_data");
    if (specialData < std::numeric_limits<std::int16_t>::min() || specialData > std::numeric_limits<std::int16_t>::max())
        throw std::runtime_error("card special_data outside int16 range: " + id);
    const bool usesSpecialData = cardId == sts::CardId::SEARING_BLOW || cardId == sts::CardId::RITUAL_DAGGER;
    if ((!usesSpecialData && specialData != 0) || (usesSpecialData && specialData < 0))
        throw std::runtime_error("unsupported card special_data: " + id);
    c.specialData = static_cast<std::int16_t>(specialData);
    const int cost = number<int>(j, "cost");
    const int costForTurn = number<int>(j, "cost_for_turn");
    if (cost < std::numeric_limits<std::int8_t>::min() || cost > std::numeric_limits<std::int8_t>::max()
            || costForTurn < std::numeric_limits<std::int8_t>::min() || costForTurn > std::numeric_limits<std::int8_t>::max())
        throw std::runtime_error("card cost outside int8 range: " + id);
    c.cost = static_cast<std::int8_t>(cost);
    c.costForTurn = static_cast<std::int8_t>(costForTurn);
    c.upgraded = required(j, "upgraded").get<bool>();
    const int upgradeCount = number<int>(j, "upgrade_count");
    if (upgradeCount < 0 || upgradeCount > 1 || c.upgraded != (upgradeCount == 1))
        throw std::runtime_error("unsupported card upgrade count/state");
    if (cardId == sts::CardId::SEARING_BLOW && specialData != upgradeCount)
        throw std::runtime_error("Searing Blow misc/upgrade count mismatch");
    c.freeToPlayOnce = required(j, "free_to_play_once").get<bool>();
    c.retain = required(j, "retain").get<bool>();
    return c;
}

template <typename Pile> inline void loadPile(Pile &out, const json &items, int &denseId) {
    if (!items.is_array()) throw std::runtime_error("pile must be an array");
    for (const auto &item : items) out.push_back(card(item, denseId++));
}

inline RestoredState restore(const json &j) {
    if (required(j, "protocol").get<std::string>() != "live_battle_state") throw std::runtime_error("wrong protocol");
    if (number<int>(j, "version") != 1) throw std::runtime_error("unsupported live_battle_state version");
    if (required(j, "character").get<std::string>() != "IRONCLAD") throw std::runtime_error("only Ironclad is supported");
    const std::string captureId = required(j, "capture_id").get<std::string>();
    if (captureId.size() != 36 || captureId[8] != '-' || captureId[13] != '-'
            || captureId[18] != '-' || captureId[23] != '-')
        throw std::runtime_error("capture_id must be a UUID transaction identifier");
    for (std::size_t i = 0; i < captureId.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) continue;
        if (!std::isxdigit(static_cast<unsigned char>(captureId[i])))
            throw std::runtime_error("capture_id must be a UUID transaction identifier");
    }
    if (required(j, "stage").get<std::string>() != "PLAYER_NORMAL") throw std::runtime_error("unstable or unsupported battle stage");
    if (required(j, "queues_empty").get<bool>() != true) throw std::runtime_error("action/card/monster queues are not empty");

    // Preserve the exact room source. Both accepted origins use the same
    // THREE_SENTRIES combat model, but EventRoom is never relabeled as a map
    // elite based on eliteTrigger.
    const auto &room = required(j, "room_context");
    const std::string roomKind = required(room, "kind").get<std::string>();
    const std::string roomClass = required(room, "class").get<std::string>();
    const bool eliteTrigger = required(room, "elite_trigger").get<bool>();
    const auto eventClassIt = room.find("event_class");
    const auto eventEncounterIt = room.find("event_encounter");
    const auto eventEnemyIt = room.find("event_enemy_index");
    if (eventClassIt == room.end() || eventEncounterIt == room.end() || eventEnemyIt == room.end())
        throw std::runtime_error("missing room provenance field");
    if (roomKind == "MAP_MONSTER") {
        if (roomClass != "com.megacrit.cardcrawl.rooms.MonsterRoomElite"
                || !eliteTrigger || !eventClassIt->is_null()
                || !eventEncounterIt->is_null() || !eventEnemyIt->is_null())
            throw std::runtime_error("unsupported map room context for three-Sentry state");
    } else if (roomKind == "EVENT") {
        if (roomClass != "com.megacrit.cardcrawl.rooms.EventRoom"
                || !eliteTrigger
                || eventClassIt->get<std::string>() != "com.megacrit.cardcrawl.events.exordium.DeadAdventurer"
                || eventEncounterIt->get<std::string>() != "3 Sentries"
                || !eventEnemyIt->is_number_integer() || eventEnemyIt->get<int>() != 0)
            throw std::runtime_error("unsupported event provenance; only Dead Adventurer enemy 0 (3 Sentries) is accepted");
    } else {
        throw std::runtime_error("unsupported room provenance kind");
    }
    if (j.contains("battle_counters"))
        throw std::runtime_error("battle_counters is obsolete: search counters are initialized internally and misc state is scenario-derived");

    sts::BattleContext bc;
    bc.liveBattleOrigin = roomKind == "EVENT"
            ? sts::LiveBattleOrigin::DEAD_ADVENTURER_EVENT
            : sts::LiveBattleOrigin::MAP_MONSTER_ELITE;
    bc.liveEventEnemyIndex = roomKind == "EVENT" ? eventEnemyIt->get<int>() : -1;
    bc.liveEliteTrigger = eliteTrigger;
    bc.inputState = sts::InputState::PLAYER_NORMAL;
    bc.seed = required(j, "seed").get<std::uint64_t>();
    bc.act = number<int>(j, "act");
    bc.floorNum = number<int>(j, "floor");
    bc.ascension = number<int>(j, "ascension");
    bc.turn = number<int>(j, "turn");
    const std::string encounterName = required(j, "encounter").get<std::string>();
    bool encounterFound = false;
    for (int i = 1; i < static_cast<int>(sizeof(sts::monsterEncounterEnumNames) / sizeof(sts::monsterEncounterEnumNames[0])); ++i) {
        if (encounterName == sts::monsterEncounterEnumNames[i]) {
            bc.encounter = static_cast<sts::MonsterEncounter>(i);
            encounterFound = true;
            break;
        }
    }
    if (!encounterFound) throw std::runtime_error("unknown encounter: " + encounterName);
    if (bc.encounter != sts::MonsterEncounter::THREE_SENTRIES)
        throw std::runtime_error("unsupported encounter context; non-three-Sentry state including stolen-gold bookkeeping is unavailable");
    bc.player.cc = sts::CharacterClass::IRONCLAD;

    const auto &p = required(j, "player");
    bc.player.curHp = number<int>(p, "hp");
    bc.player.maxHp = number<int>(p, "max_hp");
    bc.player.block = number<int>(p, "block");
    bc.player.energy = number<int>(p, "energy");
    bc.player.energyPerTurn = number<std::int8_t>(p, "energy_per_turn");
    if (bc.player.maxHp <= 0 || bc.player.curHp < 0 || bc.player.curHp > bc.player.maxHp)
        throw std::runtime_error("player HP is outside valid range");
    if (bc.player.block < 0 || bc.player.energy < 0 || bc.player.energyPerTurn < 0)
        throw std::runtime_error("player block/energy is negative");
    bc.player.cardDrawPerTurn = number<std::int8_t>(p, "card_draw_per_turn");
    bc.player.gold = number<std::int16_t>(p, "gold");
    bc.player.orbSlots = number<std::int8_t>(p, "orb_slots");
    const bool hasLastTargetedMonster = p.contains("last_targeted_monster") && !p.at("last_targeted_monster").is_null();
    if (hasLastTargetedMonster)
        bc.player.lastTargetedMonster = number<std::int8_t>(p, "last_targeted_monster");
    bc.player.strength = number<int>(p, "strength");
    bc.player.dexterity = number<int>(p, "dexterity");
    bc.player.focus = number<int>(p, "focus");
    bc.player.artifact = number<int>(p, "artifact");
    bc.player.stance = stance(required(p, "stance").get<std::string>());
    const auto &statuses = required(p, "statuses");
    if (!statuses.is_array()) throw std::runtime_error("player.statuses must be an array");
    for (const auto &status : statuses) {
        const std::string name = required(status, "id").get<std::string>();
        const int id = static_cast<int>(sts::SimHelpers::getPlayerStatusForString(name));
        if (id <= 0 || id >= static_cast<int>(PS::THE_BOMB))
            throw std::runtime_error("unknown/unsupported player status id: " + name);
        int amount = number<int>(status, "amount");
        const bool hasJustApplied = status.contains("just_applied");
        const bool timingRequired = playerStatusUsesJustApplied(id);
        if (timingRequired != hasJustApplied)
            throw std::runtime_error(timingRequired ? "missing required player status just_applied: " + name
                                                   : "player status just_applied is not applicable: " + name);
        bool justApplied = false;
        if (hasJustApplied) {
            const auto &timing = status.at("just_applied");
            if (!timing.is_boolean()) throw std::runtime_error("player status just_applied must be boolean: " + name);
            justApplied = timing.get<bool>();
        }
        if (!setPlayerStatus(bc.player, id, amount, hasJustApplied, justApplied))
            throw std::runtime_error("unsupported player status timing mapping: " + name);
    }
    if (!hasLastTargetedMonster && bc.player.hasStatus<PS::SURROUNDED>())
        throw std::runtime_error("SURROUNDED requires last_targeted_monster from a trusted source");
    const auto &pc = required(p, "counters");
    bc.player.haveUsedNecronomiconThisTurn = required(pc, "necronomicon_used").get<bool>();
    bc.player.combustHpLoss = number<std::int8_t>(pc, "combust_hp_loss");
    bc.player.devaFormEnergyPerTurn = number<std::int16_t>(pc, "deva_energy");
    bc.player.echoFormCardsDoubled = number<std::int8_t>(pc, "echo_doubled");
    bc.player.panacheCounter = number<std::int8_t>(pc, "panache");
    bc.player.cardsPlayedThisTurn = number<std::int16_t>(pc, "cards_played");
    bc.player.attacksPlayedThisTurn = number<std::int16_t>(pc, "attacks_played");
    bc.player.skillsPlayedThisTurn = number<std::int16_t>(pc, "skills_played");
    bc.player.cardsDiscardedThisTurn = number<std::int16_t>(pc, "cards_discarded");
    bc.player.lastAttackUnblockedDamage = number<std::int16_t>(pc, "last_attack_unblocked");
    bc.player.timesDamagedThisCombat = number<std::int16_t>(pc, "times_damaged");
    bc.player.bomb1 = number<std::int8_t>(pc, "bomb1");
    bc.player.bomb2 = number<std::int8_t>(pc, "bomb2");
    bc.player.bomb3 = number<std::int8_t>(pc, "bomb3");
    const auto &pellets = required(pc, "orange_pellets");
    if (!pellets.is_array() || pellets.size() != 3) throw std::runtime_error("orange_pellets must have three values");
    for (int i = 0; i < 3; ++i) bc.player.orangePelletsCardTypesPlayed.set(i, pellets[i].get<bool>());

    const auto &rng = required(j, "rng");
    const json *streamStates[] = {&required(rng, "ai"), &required(rng, "card"), &required(rng, "misc"),
                                  &required(rng, "monster_hp"), &required(rng, "potion"), &required(rng, "shuffle")};
    std::set<std::string> streamIds;
    for (const auto *state : streamStates) {
        const auto id = streamId(*state);
        if (id.empty() || !streamIds.insert(id).second) throw std::runtime_error("RNG stream identities are missing or aliased");
    }
    loadRandom(bc.aiRng, *streamStates[0]);
    loadRandom(bc.cardRandomRng, *streamStates[1]);
    loadRandom(bc.miscRng, *streamStates[2]);
    loadRandom(bc.monsterHpRng, *streamStates[3]);
    loadRandom(bc.potionRng, *streamStates[4]);
    loadRandom(bc.shuffleRng, *streamStates[5]);

    int denseId = 0;
    std::set<std::string> cardUuids;
    const auto &piles = required(j, "piles");
    const auto &hand = required(piles, "hand");
    if (!hand.is_array() || hand.size() > sts::CardManager::MAX_HAND_SIZE) throw std::runtime_error("hand exceeds engine capacity");
    bc.cards.cardsInHand = static_cast<int>(hand.size());
    for (int i = 0; i < bc.cards.cardsInHand; ++i) bc.cards.hand[i] = card(hand[i], denseId++);
    if (required(piles, "limbo").size() != 0 || required(piles, "stasis").size() != 0)
        throw std::runtime_error("limbo/stasis cards are unsupported at this stable boundary");
    loadPile(bc.cards.drawPile, required(piles, "draw"), denseId);
    loadPile(bc.cards.discardPile, required(piles, "discard"), denseId);
    loadPile(bc.cards.exhaustPile, required(piles, "exhaust"), denseId);
    if (denseId > sts::CardManager::MAX_GROUP_SIZE) throw std::runtime_error("combat card count exceeds engine capacity");
    auto validateUuids = [&cardUuids](const json &items) {
        for (const auto &item : items) {
            const std::string uuid = required(item, "uuid").get<std::string>();
            if (!validUuid(uuid) || !cardUuids.insert(uuid).second) throw std::runtime_error("invalid or duplicate card UUID");
        }
    };
    validateUuids(required(piles, "hand"));
    validateUuids(required(piles, "draw"));
    validateUuids(required(piles, "discard"));
    validateUuids(required(piles, "exhaust"));
    RestoredState restored;
    auto appendUuids = [&restored](const json &items) {
        for (const auto &item : items) restored.cardUuidByDenseId.push_back(required(item, "uuid").get<std::string>());
    };
    appendUuids(hand);
    appendUuids(required(piles, "draw"));
    appendUuids(required(piles, "discard"));
    appendUuids(required(piles, "exhaust"));
    if (restored.cardUuidByDenseId.size() != static_cast<std::size_t>(denseId))
        throw std::runtime_error("card UUID/dense ID mapping size mismatch");
    bc.cards.nextUniqueCardId = denseId;
    const auto &cardCounters = required(j, "card_counters");
    int handNormality = 0, handPain = 0, strikesInCombat = 0;
    int handBlood = 0, drawBlood = 0, discardBlood = 0;
    for (int i = 0; i < bc.cards.cardsInHand; ++i) {
        const auto &c = bc.cards.hand[i];
        handNormality += c.id == sts::CardId::NORMALITY;
        handPain += c.id == sts::CardId::PAIN;
        handBlood += c.isBloodCard();
        strikesInCombat += c.isStrikeCard();
    }
    for (const auto &c : bc.cards.drawPile) {
        drawBlood += c.isBloodCard();
        strikesInCombat += c.isStrikeCard();
    }
    for (const auto &c : bc.cards.discardPile) {
        discardBlood += c.isBloodCard();
        strikesInCombat += c.isStrikeCard();
    }
    // CardManager's six counters are maintained by add/remove-to-pile hooks;
    // their exact values are a pure function of the stable player-root piles.
    // Exhausted cards have left combat and are deliberately excluded from
    // strikesInCombat; limbo/stasis were rejected above.
    if (number<int>(cardCounters, "hand_normality") != handNormality
            || number<int>(cardCounters, "hand_pain") != handPain
            || number<int>(cardCounters, "strikes_in_combat") != strikesInCombat
            || number<int>(cardCounters, "hand_blood") != handBlood
            || number<int>(cardCounters, "draw_blood") != drawBlood
            || number<int>(cardCounters, "discard_blood") != discardBlood)
        throw std::runtime_error("card_counters disagree with exact pile-derived values");
    bc.cards.handNormalityCount = handNormality;
    bc.cards.handPainCount = handPain;
    bc.cards.strikeCount = strikesInCombat;
    bc.cards.handBloodCardCount = handBlood;
    bc.cards.drawPileBloodCardCount = drawBlood;
    bc.cards.discardPileBloodCardCount = discardBlood;

    const auto &relics = required(j, "relics");
    if (!relics.is_array()) throw std::runtime_error("relics must be an array");
    std::set<std::string> relicIds;
    for (const auto &relic : relics) {
        const std::string id = required(relic, "id").get<std::string>();
        if (!relicIds.insert(id).second) throw std::runtime_error("duplicate relic id: " + id);
        int relicIndex = -1;
        for (int i = 0; i < 128 && i < static_cast<int>(sts::RelicId::INVALID); ++i) {
            if (id == sts::relicEnumNames[i]) { relicIndex = i; break; }
        }
        if (relicIndex < 0 || !setRelic(bc.player, relicIndex)) throw std::runtime_error("unknown relic id: " + id);
    }
    const auto &counters = required(j, "relic_counters");
    if (!counters.is_array()) throw std::runtime_error("relic_counters must be an array");
    for (const auto &entry : counters) {
        const std::string id = required(entry, "id").get<std::string>();
        const auto value = number<int>(entry, "value");
        if (id == "HAPPY_FLOWER") bc.player.happyFlowerCounter = static_cast<std::int8_t>(value);
        else if (id == "INCENSE_BURNER") bc.player.incenseBurnerCounter = static_cast<std::int8_t>(value);
        else if (id == "INK_BOTTLE") bc.player.inkBottleCounter = static_cast<std::int8_t>(value);
        else if (id == "INSERTER") bc.player.inserterCounter = static_cast<std::int8_t>(value);
        else if (id == "NUNCHAKU") bc.player.nunchakuCounter = static_cast<std::int8_t>(value);
        else if (id == "PEN_NIB") bc.player.penNibCounter = static_cast<std::int8_t>(value);
        else if (id == "SUNDIAL") bc.player.sundialCounter = static_cast<std::int8_t>(value);
        else throw std::runtime_error("unsupported relic counter: " + id);
    }

    const auto &potions = required(j, "potions");
    if (!potions.is_array() || potions.size() > bc.potions.size()) throw std::runtime_error("invalid potion slots");
    bc.potionCount = 0;
    bc.potionCapacity = static_cast<int>(potions.size());
    for (int i = 0; i < static_cast<int>(potions.size()); ++i) {
        const std::string id = required(potions[i], "id").get<std::string>();
        int potionIndex = -1;
        for (int k = 0; k < static_cast<int>(sizeof(sts::potionNames) / sizeof(sts::potionNames[0])); ++k) {
            if (id == sts::potionNames[k]
                    || id == sts::potionToJsonString(static_cast<sts::Potion>(k))) { potionIndex = k; break; }
        }
        if (potionIndex < 0) throw std::runtime_error("unknown potion id: " + id);
        bc.potions[i] = static_cast<sts::Potion>(potionIndex);
        if (bc.potions[i] != sts::Potion::EMPTY_POTION_SLOT) ++bc.potionCount;
    }

    const auto &monsters = required(j, "monsters");
    if (!monsters.is_array() || monsters.empty() || monsters.size() > bc.monsters.arr.size()) throw std::runtime_error("invalid monster count");
    bc.monsters.monsterCount = static_cast<int>(monsters.size());
    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        const auto &src = monsters[i];
        auto &m = bc.monsters.arr[i];
        m.idx = number<int>(src, "slot");
        if (m.idx != i) throw std::runtime_error("monster slots must preserve original group order");
        const std::string id = required(src, "id").get<std::string>();
        m.id = sts::SimHelpers::getMonsterIdForString(id);
        if (m.id == sts::MonsterId::INVALID) throw std::runtime_error("unknown monster id: " + id);
        m.curHp = number<int>(src, "hp");
        m.maxHp = number<int>(src, "max_hp");
        m.block = number<int>(src, "block");
        m.isEscapingB = required(src, "escaping").get<bool>();
        m.halfDead = required(src, "half_dead").get<bool>();
        m.escapeNext = required(src, "escape_next").get<bool>();
        const auto &history = required(src, "move_history");
        if (!history.is_array() || history.size() != 2) throw std::runtime_error("move_history must contain exactly two slots");
        for (int h = 0; h < 2; ++h) {
            const auto moveName = history[h].get<std::string>();
            const auto move = sts::SimHelpers::getMonsterMoveForString(moveName);
            if (move == sts::MonsterMoveId::INVALID && moveName != "INVALID") throw std::runtime_error("unknown monster move");
            m.moveHistory[h] = move;
        }
        const auto &ms = required(src, "statuses");
        if (!ms.is_array()) throw std::runtime_error("monster.statuses must be an array");
        int unique0Count = 0;
        int unique1Count = 0;
        std::set<int> seenMonsterStatuses;
        for (const auto &status : ms) {
            const std::string name = required(status, "id").get<std::string>();
            const int sid = static_cast<int>(sts::SimHelpers::getMonsterStatusForString(name));
            const int amount = number<int>(status, "amount");
            const bool justApplied = required(status, "just_applied").get<bool>();
            if (sid < 0 || !seenMonsterStatuses.insert(sid).second)
                throw std::runtime_error("unknown or duplicate monster status id");
            if (m.id == sts::MonsterId::SENTRY
                    && sid != static_cast<int>(sts::MS::ARTIFACT)
                    && sid != static_cast<int>(sts::MS::WEAK)
                    && sid != static_cast<int>(sts::MS::VULNERABLE))
                throw std::runtime_error("unsupported Sentry status id");
            if (amount < 0 || (sid == static_cast<int>(sts::MS::ARTIFACT) && amount > 127))
                throw std::runtime_error("Sentry status amount outside restored storage range");
            if (!setMonsterStatus(m, sid, amount, justApplied)) throw std::runtime_error("unknown/unsupported monster status id");
            if (sid >= static_cast<int>(sts::MS::ANGRY) && sid <= static_cast<int>(sts::MS::TIME_WARP)) ++unique0Count;
            if (sid >= static_cast<int>(sts::MS::INVINCIBLE) && sid <= static_cast<int>(sts::MS::SHARP_HIDE)) ++unique1Count;
        }
        m.uniquePower0 = number<int>(src, "unique_power0");
        m.uniquePower1 = number<std::int16_t>(src, "unique_power1");
        if (unique0Count > 1 || unique1Count > 1) throw std::runtime_error("conflicting shared unique-power slots");
        m.miscInfo = number<int>(src, "misc_info");
        if (m.id != sts::MonsterId::SENTRY || number<int>(src, "beam_damage") != (bc.ascension >= 3 ? 10 : 9)
                || number<int>(src, "dazed_amount") != (bc.ascension >= 18 ? 3 : 2)
                || required(src, "first_move").get<bool>())
            throw std::runtime_error("only verified initialized Sentry state is supported");
        if (m.uniquePower0 != 0 || m.uniquePower1 != 0 || m.miscInfo != 0)
            throw std::runtime_error("unsupported nonzero Sentry-only internal counter");
        if (m.moveHistory[0] != sts::MMID::SENTRY_BEAM && m.moveHistory[0] != sts::MMID::SENTRY_BOLT)
            throw std::runtime_error("unsupported Sentry current move");
        if (m.curHp > 0 && !m.isEscapingB && !m.halfDead) ++bc.monsters.monstersAlive;
    }
    if (bc.encounter != sts::MonsterEncounter::THREE_SENTRIES || bc.monsters.monsterCount != 3)
        throw std::runtime_error("v1 currently accepts only the verified three-Sentry encounter");
    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        if (bc.monsters.arr[i].id != sts::MonsterId::SENTRY || bc.monsters.arr[i].idx != i)
            throw std::runtime_error("three-Sentry room provenance requires three vanilla Sentry slots in original order");
    }
    // Bit 0 records stolen gold. Its only setter is Monster::stealGoldFromPlayer,
    // used by thief/looter/mugger AI; the exact map THREE_SENTRIES allowlist has
    // no such monster or action. Establish the scenario invariant only here.
    bc.miscBits.reset();
    const auto &groupBits = required(j, "monster_group");
    if (!required(groupBits, "extra_roll").is_array() || required(groupBits, "extra_roll").size() != static_cast<std::size_t>(bc.monsters.monsterCount)
            || !required(groupBits, "skip_turn").is_array() || required(groupBits, "skip_turn").size() != static_cast<std::size_t>(bc.monsters.monsterCount))
        throw std::runtime_error("monster group flags size mismatch");
    for (int i = 0; i < bc.monsters.monsterCount; ++i) {
        bc.monsters.extraRollMoveOnTurn.set(i, required(groupBits, "extra_roll")[i].get<bool>());
        bc.monsters.skipTurn.set(i, required(groupBits, "skip_turn")[i].get<bool>());
    }
    // A mcts_state root is reconstructed at the stable player-action boundary.
    // The traversal cursor is engine bookkeeping, not a game input: every
    // accepted end-turn action resets it to 0 before the monster loop reads it.
    // Start from BattleContext's pre-turn sentinel and never accept a wire value.
    bc.monsterTurnIdx = 6;
    bc.isBattleOver = required(j, "battle_over").get<bool>();
    bc.endTurnQueued = required(j, "end_turn_queued").get<bool>();
    bc.turnHasEnded = required(j, "turn_has_ended").get<bool>();
    bc.skipMonsterTurn = required(j, "skip_monster_turn").get<bool>();
    if (number<int>(j, "action_queue_size") != 0 || number<int>(j, "card_queue_size") != 0)
        throw std::runtime_error("action/card queue is not empty");
    if (bc.isBattleOver || bc.endTurnQueued || bc.turnHasEnded || bc.skipMonsterTurn)
        throw std::runtime_error("battle is not at an idle player action boundary");
    restored.context = bc;
    return restored;
}

inline json readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("cannot open live state file");
    json j;
    in >> j;
    return j;
}
}

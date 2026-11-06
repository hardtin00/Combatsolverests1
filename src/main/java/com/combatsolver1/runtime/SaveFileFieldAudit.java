package com.combatsolver1.runtime;

import com.megacrit.cardcrawl.cards.CardSave;
import com.megacrit.cardcrawl.saveAndContinue.SaveFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Strict, value-redacted audit of the SaveFile fields used to bind a native load to a battle. */
final class SaveFileFieldAudit {
    final String room;
    final String encounters;
    final String deck;
    final String relics;
    final String potions;
    final String summary;
    final boolean valid;

    private SaveFileFieldAudit(String room, String encounters, String deck, String relics,
                               String potions, String summary, boolean valid) {
        this.room = room;
        this.encounters = encounters;
        this.deck = deck;
        this.relics = relics;
        this.potions = potions;
        this.summary = summary;
        this.valid = valid;
    }

    static SaveFileFieldAudit inspect(SaveFile save) {
        String roomValue = save == null ? null : save.current_room;
        String roomStatus = roomValue == null ? "UNAVAILABLE(null; expected non-empty String)"
                : roomValue.isEmpty() ? "INVALID(empty; expected non-empty String)"
                : "VALID(String)";
        String normal = stringListStatus("monster_list", save == null ? null : save.monster_list);
        String elite = stringListStatus("elite_monster_list", save == null ? null : save.elite_monster_list);
        String boss = stringListStatus("boss_list", save == null ? null : save.boss_list);
        String encounterStatus = "normal=" + normal + ",elite=" + elite + ",boss=" + boss;
        String encounterSignature = save == null ? null : encounterSignature(save);

        CardSignature cardSignature = save == null ? CardSignature.invalid("UNAVAILABLE(null SaveFile)")
                : cardSignature(save.cards);
        RelicSignature relicSignature = save == null ? RelicSignature.invalid("UNAVAILABLE(null SaveFile)")
                : relicSignature(save.relics, save.relic_counters);
        String potionStatus = stringListStatus("potions", save == null ? null : save.potions);
        String potionSignature = save == null ? null : stringSignature(save.potions, true);

        List<String> statuses = new ArrayList<String>();
        statuses.add("current_room=" + roomStatus);
        statuses.add("encounters={" + encounterStatus + "}");
        statuses.add("cards=" + cardSignature.status);
        statuses.add("relics=" + relicSignature.status);
        statuses.add("potions=" + potionStatus);
        if (save != null) {
            // These are primitive fields in the installed SaveFile bytecode, so they cannot be null.
            statuses.add("seed=VALID(long)");
            statuses.add("floor_num=VALID(int)");
            statuses.add("current_health=VALID(int)");
            statuses.add("max_health=VALID(int)");
        } else {
            statuses.add("seed=UNAVAILABLE(null SaveFile)");
            statuses.add("floor_num=UNAVAILABLE(null SaveFile)");
            statuses.add("current_health=UNAVAILABLE(null SaveFile)");
            statuses.add("max_health=UNAVAILABLE(null SaveFile)");
        }

        boolean valid = save != null && roomValue != null && !roomValue.isEmpty()
                && encounterSignature != null && cardSignature.signature != null
                && relicSignature.signature != null && potionSignature != null;
        return new SaveFileFieldAudit(roomValue, encounterSignature, cardSignature.signature,
                relicSignature.signature, potionSignature, join(statuses), valid);
    }

    private static String encounterSignature(SaveFile save) {
        String normal = stringSignature(save.monster_list, false);
        String elite = stringSignature(save.elite_monster_list, false);
        String boss = stringSignature(save.boss_list, false);
        if (normal == null || elite == null || boss == null) return null;
        return "normal=" + normal + ";elite=" + elite + ";boss=" + boss;
    }

    private static String stringListStatus(String name, Object value) {
        if (value == null) return "UNAVAILABLE(null; expected ArrayList<String>)";
        if (!(value instanceof List)) return "INVALID(type=" + value.getClass().getName() + "; expected List<String>)";
        List<?> entries = (List<?>) value;
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof String)) {
                Object entry = entries.get(i);
                return "INVALID(index=" + i + "; type=" + (entry == null ? "null" : entry.getClass().getName())
                        + "; expected String)";
            }
        }
        return entries.isEmpty() ? "VALID_EMPTY(ArrayList<String>)" : "VALID(ArrayList<String>; size=" + entries.size() + ")";
    }

    private static String stringSignature(Object value, boolean potionList) {
        if (!(value instanceof List)) return null;
        List<?> entries = (List<?>) value;
        List<String> values = new ArrayList<String>();
        for (Object entry : entries) {
            if (!(entry instanceof String)) return null;
            String text = (String) entry;
            if (!text.isEmpty() && !"INVALID".equals(text)
                    && !(potionList && "Potion Slot".equals(text))) values.add(text);
        }
        return sorted(values);
    }

    private static CardSignature cardSignature(Object value) {
        if (value == null) return CardSignature.invalid("UNAVAILABLE(null; expected ArrayList<CardSave>)");
        if (!(value instanceof List)) return CardSignature.invalid("INVALID(type=" + value.getClass().getName()
                + "; expected List<CardSave>)");
        List<?> entries = (List<?>) value;
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (!(entry instanceof CardSave)) return CardSignature.invalid("INVALID(index=" + i + "; type="
                    + (entry == null ? "null" : entry.getClass().getName()) + "; expected CardSave)");
            CardSave card = (CardSave) entry;
            if (card.id == null || card.id.isEmpty()) return CardSignature.invalid("INVALID(index=" + i
                    + "; CardSave.id is null or empty)");
            values.add(card.id + ":" + card.upgrades + ":" + card.misc);
        }
        String status = entries.isEmpty() ? "VALID_EMPTY(ArrayList<CardSave>)"
                : "VALID(ArrayList<CardSave>; size=" + entries.size() + ")";
        return new CardSignature(sorted(values), status);
    }

    private static RelicSignature relicSignature(Object idsValue, Object countersValue) {
        if (idsValue == null) return RelicSignature.invalid("UNAVAILABLE(relics is null; expected ArrayList<String>)");
        if (!(idsValue instanceof List)) return RelicSignature.invalid("INVALID(relics type=" + idsValue.getClass().getName() + ")");
        if (countersValue == null) return RelicSignature.invalid("UNAVAILABLE(relic_counters is null; expected ArrayList<Integer>)");
        if (!(countersValue instanceof List)) return RelicSignature.invalid("INVALID(relic_counters type=" + countersValue.getClass().getName() + ")");
        List<?> ids = (List<?>) idsValue;
        List<?> counters = (List<?>) countersValue;
        if (ids.size() != counters.size()) return RelicSignature.invalid("INVALID(size mismatch; relics=" + ids.size()
                + "; relic_counters=" + counters.size() + ")");
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < ids.size(); i++) {
            if (!(ids.get(i) instanceof String) || ((String) ids.get(i)).isEmpty()) {
                Object item = ids.get(i);
                return RelicSignature.invalid("INVALID(relics index=" + i + "; type="
                        + (item == null ? "null" : item.getClass().getName()) + "; expected non-empty String)");
            }
            if (!(counters.get(i) instanceof Integer)) {
                Object item = counters.get(i);
                return RelicSignature.invalid("INVALID(relic_counters index=" + i + "; type="
                        + (item == null ? "null" : item.getClass().getName()) + "; expected Integer)");
            }
            values.add(ids.get(i) + ":" + counters.get(i));
        }
        String status = ids.isEmpty() ? "VALID_EMPTY(relics/relic_counters)"
                : "VALID(relics/relic_counters; size=" + ids.size() + ")";
        return new RelicSignature(sorted(values), status);
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

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append("; ");
            out.append(value);
        }
        return out.toString();
    }

    private static final class CardSignature {
        final String signature;
        final String status;
        CardSignature(String signature, String status) { this.signature = signature; this.status = status; }
        static CardSignature invalid(String status) { return new CardSignature(null, status); }
    }

    private static final class RelicSignature {
        final String signature;
        final String status;
        RelicSignature(String signature, String status) { this.signature = signature; this.status = status; }
        static RelicSignature invalid(String status) { return new RelicSignature(null, status); }
    }
}

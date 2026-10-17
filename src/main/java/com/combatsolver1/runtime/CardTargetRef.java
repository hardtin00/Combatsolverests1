package com.combatsolver1.runtime;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.ArrayList;
import java.util.List;

/** One authoritative interpretation of the target passed to AbstractPlayer.useCard. */
public final class CardTargetRef {
    public final String mode;
    public final boolean trusted;
    /** Original AbstractRoom monster-list slot; dead slots are retained. */
    public final int engineIndex;
    /** Position in LiveCapture/sts_lightspeed's compact active monster array. */
    public final int liveIndex;
    /** Stable within the room roster, including duplicate monster ids. */
    public final String identity;
    public final String error;

    private CardTargetRef(String mode, boolean trusted, int engineIndex, int liveIndex,
                          String identity, String error) {
        this.mode = mode;
        this.trusted = trusted;
        this.engineIndex = engineIndex;
        this.liveIndex = liveIndex;
        this.identity = identity == null ? "" : identity;
        this.error = error == null ? "" : error;
    }

    public static CardTargetRef capture(AbstractCard card, AbstractMonster target) {
        if (card == null || card.target == null) return untrusted("missing-card-target-mode");
        String cardTarget = card.target.name();
        if ("ALL_ENEMY".equals(cardTarget)) return noTarget("all_enemies");
        if ("NONE".equals(cardTarget)) return noTarget("none");
        if ("SELF".equals(cardTarget)) return noTarget("self");
        if (!"ENEMY".equals(cardTarget) && !"SELF_AND_ENEMY".equals(cardTarget)) {
            return untrusted("unsupported-card-target-mode-" + safeToken(cardTarget));
        }
        if (target == null) return untrusted("missing-selected-monster");

        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.monsters == null || room.monsters.monsters == null) {
            return untrusted("monster-roster-unavailable");
        }
        List<AbstractMonster> fullRoster = room.monsters.monsters;
        List<AbstractMonster> liveRoster = new ArrayList<AbstractMonster>();
        for (AbstractMonster monster : fullRoster) {
            if (monster != null && !monster.isDeadOrEscaped()) liveRoster.add(monster);
        }
        return resolveSingle(target, fullRoster, liveRoster, target.id);
    }

    private static CardTargetRef resolveSingle(Object target, List<?> fullRoster,
                                                List<?> liveRoster, String monsterId) {
        if (target == null || fullRoster == null || liveRoster == null) {
            return untrusted("target-roster-unavailable");
        }
        int engineIndex = identityIndex(fullRoster, target);
        int liveIndex = identityIndex(liveRoster, target);
        if (engineIndex < 0) return untrusted("target-not-in-game-monster-roster");
        if (liveIndex < 0) return untrusted("target-not-in-live-capture-roster");

        String id = safeToken(monsterId);
        if (id.isEmpty()) return untrusted("target-monster-id-unavailable");
        return new CardTargetRef("single", true, engineIndex, liveIndex,
                id + "#" + (engineIndex + 1), "");
    }

    /** Test seam: production and both input paths use the same identity-based mapping. */
    static CardTargetRef resolveForTest(String mode, Object target, List<?> fullRoster,
                                        List<?> liveRoster, String monsterId) {
        if ("none".equals(mode) || "self".equals(mode) || "all_enemies".equals(mode)) {
            return noTarget(mode);
        }
        if (!"single".equals(mode)) return untrusted("unsupported-target-mode");
        return resolveSingle(target, fullRoster, liveRoster, monsterId);
    }

    private static int identityIndex(List<?> values, Object target) {
        for (int i = 0; i < values.size(); i++) if (values.get(i) == target) return i;
        return -1;
    }

    private static CardTargetRef noTarget(String mode) {
        return new CardTargetRef(mode, true, -1, -1, "", "");
    }

    private static CardTargetRef untrusted(String error) {
        return new CardTargetRef("untrusted", false, -1, -1, "", error);
    }

    static CardTargetRef missing() {
        return untrusted("missing-target-reference");
    }

    /** Engine action bits require a bounded target field even when the card has no target. */
    public int journalTarget() {
        return trusted && "single".equals(mode) ? liveIndex : 0;
    }

    public String jsonFields() {
        int jsonTarget = trusted && "single".equals(mode) ? liveIndex : -1;
        StringBuilder out = new StringBuilder();
        out.append("\"target\":").append(jsonTarget)
                .append(",\"targetMode\":\"").append(jsonEscape(mode)).append('"')
                .append(",\"targetTrusted\":").append(trusted)
                .append(",\"targetEngineIndex\":").append(engineIndex)
                .append(",\"targetLiveIndex\":").append(liveIndex)
                .append(",\"targetIdentity\":\"").append(jsonEscape(identity)).append('"');
        if (!error.isEmpty()) out.append(",\"targetError\":\"").append(jsonEscape(error)).append('"');
        return out.toString();
    }

    public String journalMetadata(int actionNumber, int handIndex, String cardId) {
        return "# actionMeta index=" + actionNumber
                + " type=card hand=" + handIndex
                + " card=" + safeToken(cardId)
                + " targetMode=" + mode
                + " targetTrusted=" + trusted
                + " targetEngineIndex=" + engineIndex
                + " targetLiveIndex=" + liveIndex
                + " targetIdentity=" + safeToken(identity)
                + (error.isEmpty() ? "" : " targetError=" + safeToken(error));
    }

    private static String safeToken(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9_.#:-]", "_");
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}

package rngprobe;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Observes the exact random hand-exhaust call used by an unupgraded True Grit. */
public final class TrueGritSelectionProbe {
    private static final String OUTPUT = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\outputs\\combatsolver1\\build\\rng-capture-validation\\event-capture.tsv";
    private static final String[] RNG_FIELDS = {
            "aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"
    };
    private static String trueGritUuid;
    private static long trueGritMarkedAt;
    private static Pending pending;
    private static boolean eventCaptured;

    private TrueGritSelectionProbe() { }

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object value(Object object, String name) throws Exception {
        return field(object.getClass(), name).get(object);
    }

    private static Object staticValue(Class<?> type, String name) throws Exception {
        return field(type, name).get(null);
    }

    private static String hex(long value) {
        return String.format("%016x", Long.valueOf(value));
    }

    private static long[] state(Object rng) throws Exception {
        Object xs = value(rng, "random");
        Method getState = xs.getClass().getMethod("getState", Integer.TYPE);
        return new long[]{
                ((Long) getState.invoke(xs, Integer.valueOf(0))).longValue(),
                ((Long) getState.invoke(xs, Integer.valueOf(1))).longValue(),
                ((Integer) value(rng, "counter")).intValue()
        };
    }

    private static String stateText(Object rng) throws Exception {
        long[] s = state(rng);
        return hex(s[0]) + "," + hex(s[1]) + "," + s[2];
    }

    private static String allStates() throws Exception {
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < RNG_FIELDS.length; i++) {
            if (i > 0) out.append(';');
            Object rng = staticValue(dungeon, RNG_FIELDS[i]);
            out.append(RNG_FIELDS[i]).append('=').append(stateText(rng));
        }
        return out.toString();
    }

    private static String candidates(Object group) throws Exception {
        Object cards = value(group, "group");
        if (!(cards instanceof ArrayList)) throw new IllegalStateException("hand group is not ArrayList");
        StringBuilder out = new StringBuilder();
        ArrayList<?> list = (ArrayList<?>) cards;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(';');
            Object card = list.get(i);
            out.append(i).append(':').append(value(card, "cardID"))
                    .append('#').append(value(card, "uuid"))
                    .append('#').append(Integer.toHexString(System.identityHashCode(card)));
        }
        return out.toString();
    }

    private static String cardIdentity(Object card) throws Exception {
        return String.valueOf(value(card, "cardID")) + "#" + value(card, "uuid")
                + "#" + Integer.toHexString(System.identityHashCode(card));
    }

    private static void append(String line) throws Exception {
        File output = new File(OUTPUT);
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create output folder");
        FileWriter writer = new FileWriter(output, true);
        try { writer.write(line + "\n"); } finally { writer.close(); }
    }

    /** Called by the separate TrueGrit.use patch; no card or game state is changed. */
    static void markTrueGrit(Object card) throws Exception {
        trueGritUuid = Boolean.TRUE.equals(value(card, "upgraded")) ? null : String.valueOf(value(card, "uuid"));
        trueGritMarkedAt = System.currentTimeMillis();
    }

    private static boolean isTrueGritRandomExhaust(Object hand, Object rng) throws Exception {
        if (trueGritUuid == null || System.currentTimeMillis() - trueGritMarkedAt > 30000L) return false;
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        Object player = staticValue(dungeon, "player");
        if (player == null || value(player, "hand") != hand) return false;
        if (staticValue(dungeon, "cardRandomRng") != rng) return false;
        Object manager = staticValue(dungeon, "actionManager");
        Object action = value(manager, "currentAction");
        if (action == null || !"com.megacrit.cardcrawl.actions.common.ExhaustAction".equals(action.getClass().getName())) return false;
        return Boolean.TRUE.equals(value(action, "isRandom"))
                && ((Integer) value(action, "amount")).intValue() == 1;
    }

    @SpirePatch(cls = "com.megacrit.cardcrawl.cards.red.TrueGrit", method = "use",
            paramtypes = {"com.megacrit.cardcrawl.characters.AbstractPlayer", "com.megacrit.cardcrawl.monsters.AbstractMonster"})
    public static final class TrueGritUse {
        @SpirePrefixPatch
        public static void Prefix(Object __instance) {
            try { markTrueGrit(__instance); } catch (Throwable ignored) { trueGritUuid = null; }
        }
    }

    @SpirePatch(cls = "com.megacrit.cardcrawl.cards.CardGroup", method = "getRandomCard",
            paramtypes = {"com.megacrit.cardcrawl.random.Random"})
    public static final class RandomHandSelection {
        @SpirePrefixPatch
        public static void Prefix(Object __instance, Object __0) {
            if (eventCaptured || pending != null) return;
            try {
                if (isTrueGritRandomExhaust(__instance, __0)) {
                    Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
                    Object cardRandom = staticValue(dungeon, "cardRandomRng");
                    String orderedCandidates = candidates(__instance);
                    if (orderedCandidates.length() == 0) throw new IllegalStateException("random exhaust has no candidates");
                    pending = new Pending(orderedCandidates, allStates());
                }
            } catch (Throwable failure) {
                try { append("eventCaptureFailure=" + failure.getClass().getName()); } catch (Throwable ignored) { }
                eventCaptured = true;
            }
        }

        @SpirePostfixPatch
        public static void Postfix(Object __result, Object __instance, Object __0) {
            Pending event = pending;
            if (event == null) return;
            try {
                append("event=TRUE_GRIT_RANDOM_EXHAUST"
                        + "\ttrueGritUuid=" + trueGritUuid
                        + "\tbound=" + (event.candidateCount - 1)
                        + "\tcandidates=" + event.candidates
                        + "\tselected=" + cardIdentity(__result)
                        + "\tpre=" + event.pre
                        + "\tpost=" + allStates());
            } catch (Throwable failure) {
                try { append("eventPostCaptureFailure=" + failure.getClass().getName()); } catch (Throwable ignored) { }
            } finally {
                pending = null;
                trueGritUuid = null;
                eventCaptured = true;
            }
        }
    }

    private static final class Pending {
        final String candidates;
        final String pre;
        final int candidateCount;
        Pending(String candidates, String pre) {
            this.candidates = candidates;
            this.pre = pre;
            this.candidateCount = candidates.length() == 0 ? 0 : candidates.split(";").length;
        }
    }
}

package rngprobe;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;

/** One-shot, read-only runtime probe. It never invokes an RNG draw method. */
@SpirePatch(clz = AbstractDungeon.class, method = "update")
public final class LiveRngProbe {
    private static final String OUTPUT = "C:\\Users\\3476\\Documents\\Codex\\2026-10-15\\jie\\outputs\\combatsolver1\\build\\rng-capture-validation\\live-capture.tsv";
    private static final String[] RNG_FIELDS = {
            "aiRng", "cardRandomRng", "miscRng", "monsterHpRng", "potionRng", "shuffleRng"
    };
    private static boolean captured;
    private static long lastAttemptMillis;

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        return field(type, name).get(null);
    }

    private static Object value(Object object, String name) throws Exception {
        return field(object.getClass(), name).get(object);
    }

    private static String queuesAreStable() throws Exception {
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        Object room = dungeon.getMethod("getCurrRoom").invoke(null);
        if (room == null || !"COMBAT".equals(String.valueOf(value(room, "phase")))) return null;
        Object manager = staticField(dungeon, "actionManager");
        if (manager == null || Boolean.TRUE.equals(value(manager, "turnHasEnded"))) return null;
        if (value(manager, "currentAction") != null) return null;
        StringBuilder queues = new StringBuilder();
        for (String queue : new String[]{"actions", "cardQueue", "monsterQueue"}) {
            Object q = value(manager, queue);
            if (!(q instanceof ArrayList) || !((ArrayList<?>) q).isEmpty()) return null;
            if (queues.length() > 0) queues.append(',');
            queues.append(queue).append("=0");
        }
        return "room=COMBAT,turnHasEnded=false,currentAction=null," + queues;
    }

    private static String unsignedHex(long value) {
        return String.format("%016x", Long.valueOf(value));
    }

    private static long[] sample(Object rng) throws Exception {
        Object xs = value(rng, "random");
        Method getState = xs.getClass().getMethod("getState", Integer.TYPE);
        Field counter = field(rng.getClass(), "counter");
        return new long[]{
                ((Long) getState.invoke(xs, Integer.valueOf(0))).longValue(),
                ((Long) getState.invoke(xs, Integer.valueOf(1))).longValue(),
                counter.getInt(rng)
        };
    }

    private static String monsters() throws Exception {
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        Object group = dungeon.getMethod("getMonsters").invoke(null);
        if (group == null) return "monsters=unavailable";
        Object members = value(group, "monsters");
        if (!(members instanceof ArrayList)) return "monsters=unavailable";
        StringBuilder out = new StringBuilder("monsters=");
        int groupIndex = 0;
        for (Object monster : (ArrayList<?>) members) {
            if (monster == null) {
                groupIndex++;
                continue;
            }
            String id = String.valueOf(value(monster, "id"));
            if (out.charAt(out.length() - 1) != '=') out.append(';');
            out.append(id).append("{groupIndex=").append(groupIndex)
                    .append(",hp=").append(value(monster, "currentHealth"))
                    .append(",block=").append(value(monster, "currentBlock"))
                    .append(",nextMove=").append(value(monster, "nextMove"))
                    .append(",intent=").append(value(monster, "intent"));
            Object history = value(monster, "moveHistory");
            out.append(",moveHistory=[");
            if (history instanceof Iterable) {
                Iterator<?> iterator = ((Iterable<?>) history).iterator();
                boolean first = true;
                while (iterator.hasNext()) {
                    if (!first) out.append(',');
                    out.append(iterator.next());
                    first = false;
                }
            } else if (history != null && history.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(history); i++) {
                    if (i > 0) out.append(',');
                    out.append(Array.get(history, i));
                }
            }
            out.append(']');
            if (id.toLowerCase().contains("sentry")) {
                for (String privateField : new String[]{"firstMove", "beamDmg", "dazedAmt"}) {
                    try {
                        out.append(',').append(privateField).append('=').append(value(monster, privateField));
                    } catch (NoSuchFieldException absent) {
                        out.append(',').append(privateField).append("=absent");
                    }
                }
            }
            out.append('}');
            groupIndex++;
        }
        return out.toString();
    }

    private static boolean sentryMovesReady() throws Exception {
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        Object group = dungeon.getMethod("getMonsters").invoke(null);
        if (group == null) return false;
        Object members = value(group, "monsters");
        if (!(members instanceof Iterable)) return false;
        boolean foundSentry = false;
        for (Object monster : (Iterable<?>) members) {
            if (monster == null) continue;
            String id = String.valueOf(value(monster, "id"));
            if (!id.toLowerCase().contains("sentry")) continue;
            foundSentry = true;
            Object nextMove = value(monster, "nextMove");
            Object intent = value(monster, "intent");
            if (!(nextMove instanceof Number) || ((Number) nextMove).intValue() == -1
                    || "DEBUG".equals(String.valueOf(intent))) return false;
        }
        return foundSentry;
    }

    private static void capture() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastAttemptMillis < 250L) return;
        lastAttemptMillis = now;
        Class<?> dungeon = Class.forName("com.megacrit.cardcrawl.dungeons.AbstractDungeon");
        if (staticField(dungeon, "player") == null) return;
        String stable = queuesAreStable();
        if (stable == null) return;
        if (!sentryMovesReady()) return;
        Object[] rngs = new Object[RNG_FIELDS.length];
        long[][] before = new long[RNG_FIELDS.length][];
        for (int i = 0; i < RNG_FIELDS.length; i++) {
            rngs[i] = staticField(dungeon, RNG_FIELDS[i]);
            if (rngs[i] == null) return;
            before[i] = sample(rngs[i]);
        }
        String monsters = monsters();
        long[][] after = new long[RNG_FIELDS.length][];
        boolean unchanged = true;
        for (int i = 0; i < RNG_FIELDS.length; i++) {
            after[i] = sample(rngs[i]);
            unchanged &= before[i][0] == after[i][0]
                    && before[i][1] == after[i][1] && before[i][2] == after[i][2];
        }

        File output = new File(OUTPUT);
        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cannot create probe output folder");
        FileWriter writer = new FileWriter(output, true);
        try {
            writer.write("sample=" + now + "\tstable=" + stable
                    + "\treadOnlyUnchanged=" + unchanged + "\t" + monsters + "\n");
            for (int i = 0; i < RNG_FIELDS.length; i++) {
                writer.write("rng=" + RNG_FIELDS[i] + "\tidentity="
                        + Integer.toHexString(System.identityHashCode(rngs[i]))
                        + "\tbefore=" + unsignedHex(before[i][0]) + "," + unsignedHex(before[i][1]) + "," + before[i][2]
                        + "\tafter=" + unsignedHex(after[i][0]) + "," + unsignedHex(after[i][1]) + "," + after[i][2] + "\n");
            }
        } finally {
            writer.close();
        }
        captured = true;
    }

    @SpirePostfixPatch
    public static void Postfix() {
        if (captured) return;
        try {
            capture();
        } catch (Throwable failure) {
            captured = true;
            try {
                FileWriter writer = new FileWriter(OUTPUT, true);
                writer.write("probeFailure=" + failure.getClass().getName() + ":" + failure.getMessage() + "\n");
                writer.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

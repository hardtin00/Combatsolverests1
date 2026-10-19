import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;

/** Replays the exact Random.random(inclusiveRange) call used by unupgraded True Grit. */
public final class TrueGritRandomTrace {
    private static long parseHex(String value) {
        return new BigInteger(value.replaceFirst("^0[xX]", ""), 16).longValue();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("usage: TrueGritRandomTrace <seed0> <seed1> <counter> <inclusiveRange>");
        Class<?> rngClass = Class.forName("com.megacrit.cardcrawl.random.Random");
        Object rng = rngClass.getConstructor(Long.class).newInstance(Long.valueOf(0L));
        Object xs = rngClass.getField("random").get(rng);
        xs.getClass().getMethod("setState", Long.TYPE, Long.TYPE)
                .invoke(xs, Long.valueOf(parseHex(args[0])), Long.valueOf(parseHex(args[1])));
        Field counter = rngClass.getField("counter");
        counter.setInt(rng, Integer.parseInt(args[2]));
        Method random = rngClass.getMethod("random", Integer.TYPE);
        int selectedIndex = ((Integer) random.invoke(rng, Integer.valueOf(Integer.parseInt(args[3])))).intValue();
        Method getState = xs.getClass().getMethod("getState", Integer.TYPE);
        System.out.println(selectedIndex + "\t"
                + String.format("%016x", ((Long) getState.invoke(xs, Integer.valueOf(0))).longValue()) + "\t"
                + String.format("%016x", ((Long) getState.invoke(xs, Integer.valueOf(1))).longValue()) + "\t"
                + counter.getInt(rng));
    }
}

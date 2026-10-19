import java.math.BigInteger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Isolated trace runner. Input must be a state captured from a real STS1 RNG. */
public final class JavaRngTrace {
    private static long parseHex(String value) {
        return new BigInteger(value.replaceFirst("^0[xX]", ""), 16).longValue();
    }

    private static String hex(long value) {
        return String.format("%016x", value);
    }

    private static Object call(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private static void row(String name, String value, Object rng, Object randomObject,
                            Method getState, Field counter) throws Exception {
        System.out.println(name + "\t" + value + "\t"
                + hex(((Long) getState.invoke(randomObject, Integer.valueOf(0))).longValue())
                + "\t" + hex(((Long) getState.invoke(randomObject, Integer.valueOf(1))).longValue())
                + "\t" + counter.getInt(rng));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: JavaRngTrace <seed0-hex> <seed1-hex> <counter>");
        }
        long seed0 = parseHex(args[0]);
        long seed1 = parseHex(args[1]);
        Class<?> randomClass = Class.forName("com.megacrit.cardcrawl.random.Random");
        Constructor<?> constructor = randomClass.getConstructor(Long.class);
        Object rng = constructor.newInstance(Long.valueOf(0L));
        Field randomField = randomClass.getField("random");
        Object randomObject = randomField.get(rng);
        Class<?> xs128Class = randomObject.getClass();
        xs128Class.getMethod("setState", Long.TYPE, Long.TYPE)
                .invoke(randomObject, Long.valueOf(seed0), Long.valueOf(seed1));
        Field counter = randomClass.getField("counter");
        counter.setInt(rng, Integer.parseInt(args[2]));
        Method getState = xs128Class.getMethod("getState", Integer.TYPE);

        row("nextLong", hex(((Long) call(randomObject, "nextLong", new Class<?>[0])).longValue()), rng, randomObject, getState, counter);
        row("nextInt-rejection-bound-1073741825", call(randomObject, "nextInt", new Class<?>[]{Integer.TYPE}, Integer.valueOf(1073741825)).toString(), rng, randomObject, getState, counter);
        row("nextFloat-bits", Integer.toHexString(Float.floatToIntBits(((Float) call(randomObject, "nextFloat", new Class<?>[0])).floatValue())), rng, randomObject, getState, counter);
        row("nextDouble-bits", hex(Double.doubleToLongBits(((Double) call(randomObject, "nextDouble", new Class<?>[0])).doubleValue())), rng, randomObject, getState, counter);
        row("random-int-inclusive-0", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(0)).toString(), rng, randomObject, getState, counter);
        row("random-int-inclusive-1", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(1)).toString(), rng, randomObject, getState, counter);
        row("random-int-inclusive-2", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(2)).toString(), rng, randomObject, getState, counter);
        row("random-int-inclusive-10", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(10)).toString(), rng, randomObject, getState, counter);
        row("random-int-inclusive-large", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(1073741824)).toString(), rng, randomObject, getState, counter);
        row("random-int-inclusive-max-safe", call(rng, "random", new Class<?>[]{Integer.TYPE}, Integer.valueOf(Integer.MAX_VALUE - 1)).toString(), rng, randomObject, getState, counter);
        row("random-int-signed-range", call(rng, "random", new Class<?>[]{Integer.TYPE, Integer.TYPE}, Integer.valueOf(-100), Integer.valueOf(100)).toString(), rng, randomObject, getState, counter);
        row("random-float-bits", Integer.toHexString(Float.floatToIntBits(((Float) call(rng, "random", new Class<?>[0])).floatValue())), rng, randomObject, getState, counter);
        row("random-float-scaled-bits", Integer.toHexString(Float.floatToIntBits(((Float) call(rng, "random", new Class<?>[]{Float.TYPE}, Float.valueOf(17.25f))).floatValue())), rng, randomObject, getState, counter);
        row("random-float-range-bits", Integer.toHexString(Float.floatToIntBits(((Float) call(rng, "random", new Class<?>[]{Float.TYPE, Float.TYPE}, Float.valueOf(-1.25f), Float.valueOf(5.75f))).floatValue())), rng, randomObject, getState, counter);
        row("random-long", hex(((Long) call(rng, "randomLong", new Class<?>[0])).longValue()), rng, randomObject, getState, counter);
        row("random-long-scaled", hex(((Long) call(rng, "random", new Class<?>[]{Long.TYPE}, Long.valueOf(4294967296L))).longValue()), rng, randomObject, getState, counter);
        row("random-boolean", ((Boolean) call(rng, "randomBoolean", new Class<?>[0])).booleanValue() ? "1" : "0", rng, randomObject, getState, counter);
        row("random-boolean-half", ((Boolean) call(rng, "randomBoolean", new Class<?>[]{Float.TYPE}, Float.valueOf(0.5f))).booleanValue() ? "1" : "0", rng, randomObject, getState, counter);
    }
}

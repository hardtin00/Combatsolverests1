import com.evacipated.cardcrawl.modthespire.Patcher;
import javassist.ClassPool;
import javassist.LoaderClassPath;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/** Invokes the installed ModTheSpire patch discovery and injection APIs on a mod jar. */
public final class PatchInjectionCheck {
    private PatchInjectionCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("usage: PatchInjectionCheck <game.jar> <mts.jar> <basemod.jar> <stslib.jar> <mod.jar> <expectedMtsVersion>");
        }
        File game = required(args[0]);
        File mts = required(args[1]);
        File base = required(args[2]);
        File stslib = required(args[3]);
        File mod = required(args[4]);

        Class<?> mtsLoader = Class.forName("com.evacipated.cardcrawl.modthespire.Loader");
        Object mtsVersion = mtsLoader.getField("MTS_VERSION").get(null);
        if (mtsVersion == null) {
            java.lang.reflect.Method loadVersion = mtsLoader.getDeclaredMethod("loadMTSVersion");
            loadVersion.setAccessible(true);
            loadVersion.invoke(null);
            mtsVersion = mtsLoader.getField("MTS_VERSION").get(null);
        }
        System.out.println("MTS_VERSION=" + mtsVersion);
        if (mtsVersion == null || !args[5].equals(mtsVersion.toString())) {
            throw new IllegalStateException("Expected ModTheSpire " + args[5] + " but loaded " + mtsVersion);
        }

        URL[] patchUrls = new URL[] { mod.toURI().toURL() };
        URL[] classUrls = new URL[] { game.toURI().toURL(), mts.toURI().toURL(),
                base.toURI().toURL(), stslib.toURI().toURL(), mod.toURI().toURL() };
        URLClassLoader modLoader = new URLClassLoader(classUrls, PatchInjectionCheck.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(modLoader);
        ClassPool pool = new ClassPool(true);
        pool.insertClassPath(new LoaderClassPath(modLoader));
        for (File file : new File[] { game, mts, base, stslib, mod }) {
            pool.insertClassPath(file.getAbsolutePath());
        }

        List patches = Patcher.findPatches(patchUrls);
        int discovered = 0;
        for (Object group : patches) {
            for (Object patchClass : (Iterable) group) {
                discovered++;
                System.out.println("MTS_PATCH_DISCOVERED " + patchClass);
            }
        }
        System.out.println("MTS_PATCH_DISCOVERY_DONE groups=" + patches.size() + " classes=" + discovered);
        if (patches.isEmpty()) throw new IllegalStateException("No SpirePatch entries discovered in " + mod);

        System.out.println("Injecting patches...");
        Patcher.injectPatches(modLoader, pool, (List<Iterable<String>>) (List<?>) patches);
        Patcher.finalizePatches(modLoader);
        System.out.println("Injecting patches...Done");
    }

    private static File required(String path) {
        File f = new File(path);
        if (!f.isFile()) throw new IllegalArgumentException("Required jar missing: " + f.getAbsolutePath());
        return f;
    }
}

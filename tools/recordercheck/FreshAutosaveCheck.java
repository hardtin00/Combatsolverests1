package com.combatsolver1.runtime;

import java.io.File;
import java.nio.file.Files;

/** Integration regression for EngineBridge's stale-autosave rejection. */
public final class FreshAutosaveCheck {
    public static void main(String[] args) throws Exception {
        File gameDir = Files.createTempDirectory("fresh-autosave-check").toFile();
        File saves = new File(gameDir, "saves");
        saves.mkdirs();
        try {
            System.setProperty("combatsolver1.gameDir", gameDir.getAbsolutePath());
            File save = new File(saves, "IRONCLAD.autosave");
            Files.write(save.toPath(), new byte[]{1, 2, 3});
            save.setLastModified(System.currentTimeMillis() - 120000L);
            java.lang.reflect.Method fresh = Class.forName("com.combatsolver1.runtime.EngineBridge")
                    .getMethod("freshAutosave", long.class);
            File stale = (File) fresh.invoke(null, System.currentTimeMillis() - 3000L);
            if (stale != null) throw new AssertionError("stale autosave was returned: " + stale);

            save.setLastModified(System.currentTimeMillis());
            File current = (File) fresh.invoke(null, System.currentTimeMillis() - 3000L);
            if (current == null || !current.equals(save)) throw new AssertionError("fresh autosave was not selected");
            System.out.println("FreshAutosave regression passed: stale rejected, current save selected.");
        } finally {
            deleteTree(gameDir);
        }
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}

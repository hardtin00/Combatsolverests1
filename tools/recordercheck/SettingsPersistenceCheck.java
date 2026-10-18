package com.combatsolver1.settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Properties;

/** Regression for the UI saving settings without losing engine configuration. */
public final class SettingsPersistenceCheck {
    public static void main(String[] args) throws Exception {
        String previousHome = System.getProperty("user.home");
        File home = Files.createTempDirectory("combatsolver1-settings-check-").toFile();
        try {
            System.setProperty("user.home", home.getAbsolutePath());
            File dir = new File(home, ".combatsolver1");
            if (!dir.mkdir()) throw new AssertionError("test settings directory not created");
            File file = new File(dir, "settings.properties");
            Properties before = new Properties();
            before.setProperty("preset", "1");
            before.setProperty("engine.path", "C:/test/combatsolver1-engine.exe");
            before.setProperty("engine.mingw", "C:/test/mingw/bin");
            before.setProperty("other.owner", "preserve-me");
            try (FileOutputStream out = new FileOutputStream(file)) { before.store(out, "fixture"); }

            Class<?> settingsClass = Class.forName("com.combatsolver1.settings.SolverSettings");
            Object settings = settingsClass.getMethod("load").invoke(null);
            settingsClass.getField("preset").setInt(settings, 2);
            settingsClass.getMethod("save").invoke(settings);
            Properties after = new Properties();
            try (FileInputStream in = new FileInputStream(file)) { after.load(in); }
            check("C:/test/combatsolver1-engine.exe".equals(after.getProperty("engine.path")),
                    "UI settings save removed engine.path");
            check("C:/test/mingw/bin".equals(after.getProperty("engine.mingw")),
                    "UI settings save removed engine.mingw");
            check("preserve-me".equals(after.getProperty("other.owner")),
                    "UI settings save removed another owner's property");
            check("2".equals(after.getProperty("preset")), "UI settings update was lost");
            System.out.println("SettingsPersistenceCheck passed=4 failed=0");
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            Files.deleteIfExists(new File(new File(home, ".combatsolver1"), "settings.properties").toPath());
            Files.deleteIfExists(new File(home, ".combatsolver1").toPath());
            Files.deleteIfExists(home.toPath());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

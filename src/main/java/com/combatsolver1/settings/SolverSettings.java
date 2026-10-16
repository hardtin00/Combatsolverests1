package com.combatsolver1.settings;

import com.combatsolver1.search.CombatSearch;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 求解器设置：搜索预算预设、面板透明度、主题，并持久化到用户目录。
 * 对应 CombatSolver 的“可调搜索预算 / 界面与通知”。
 */
public class SolverSettings {
    public static final String[] PRESET_NAMES = {"低", "中", "高", "极高", "自定义"};
    public static final float[] OPACITY_STEPS = {0.55f, 0.75f, 1.0f};

    public int preset = 1;          // 默认“中”
    public int opacityStep = 2;     // 默认 100%
    public boolean lightTheme = false;

    public int customBeamWidth = 6;
    public int customWithinTurnWidth = 8;
    public int customMaxTurns = 8;
    public int customMaxNodes = 4000;

    /** 面板位置与尺寸（-1 表示未设置，用默认右上角）。 */
    public float panelX = -1f;
    public float panelY = -1f;
    public float panelW = -1f;
    public float panelH = -1f;

    public float opacity() {
        return OPACITY_STEPS[Math.max(0, Math.min(OPACITY_STEPS.length - 1, opacityStep))];
    }

    public String presetName() {
        return PRESET_NAMES[Math.max(0, Math.min(PRESET_NAMES.length - 1, preset))];
    }

    public void cyclePreset() {
        preset = (preset + 1) % PRESET_NAMES.length;
    }

    public void cycleOpacity() {
        opacityStep = (opacityStep + 1) % OPACITY_STEPS.length;
    }

    public void cycleTheme() {
        lightTheme = !lightTheme;
    }

    /** 把预设映射到搜索参数。 */
    public void applyTo(CombatSearch.Config cfg) {
        // 墙钟时间预算：预设越深给得越宽；"自定义"不限时，只受节点/回合预算约束
        long[] timeBudgets = {3000L, 8000L, 20000L, 45000L, 0L};
        cfg.timeBudgetMs = timeBudgets[Math.max(0, Math.min(timeBudgets.length - 1, preset))];
        switch (preset) {
            case 0:
                cfg.beamWidth = 3;
                cfg.withinTurnWidth = 4;
                cfg.maxTurns = 4;
                cfg.maxNodes = 1200;
                break;
            case 2:
                cfg.beamWidth = 14;
                cfg.withinTurnWidth = 16;
                cfg.maxTurns = 12;
                cfg.maxNodes = 24000;
                break;
            case 3:
                cfg.beamWidth = 20;
                cfg.withinTurnWidth = 20;
                cfg.maxTurns = 14;
                cfg.maxNodes = 60000;
                break;
            case 4:
                cfg.beamWidth = customBeamWidth;
                cfg.withinTurnWidth = customWithinTurnWidth;
                cfg.maxTurns = customMaxTurns;
                cfg.maxNodes = customMaxNodes;
                break;
            default:
                cfg.beamWidth = 8;
                cfg.withinTurnWidth = 12;
                cfg.maxTurns = 10;
                cfg.maxNodes = 8000;
                break;
        }
    }

    /** 引擎（sts_lightspeed）的模拟次数：跑得非常快，所以给得比内置搜索大得多。 */
    public int engineSimulations() {
        switch (preset) {
            case 0:
                return 4000;
            case 2:
                return 60000;
            case 3:
                return 200000;
            case 4:
                return Math.max(2000, Math.min(400000, customMaxNodes * 10));
            default:
                return 20000;
        }
    }

    private static File settingsFile() {
        File dir = new File(System.getProperty("user.home", "."), ".combatsolver1");
        return new File(dir, "settings.properties");
    }

    public static SolverSettings load() {
        SolverSettings s = new SolverSettings();
        File f = settingsFile();
        if (!f.isFile()) {
            return s;
        }
        InputStream in = null;
        try {
            Properties p = new Properties();
            in = new FileInputStream(f);
            p.load(in);
            s.preset = parseInt(p.getProperty("preset"), s.preset);
            s.opacityStep = parseInt(p.getProperty("opacityStep"), s.opacityStep);
            s.lightTheme = Boolean.parseBoolean(p.getProperty("lightTheme", "false"));
            s.customBeamWidth = parseInt(p.getProperty("customBeamWidth"), s.customBeamWidth);
            s.customWithinTurnWidth = parseInt(p.getProperty("customWithinTurnWidth"), s.customWithinTurnWidth);
            s.customMaxTurns = parseInt(p.getProperty("customMaxTurns"), s.customMaxTurns);
            s.customMaxNodes = parseInt(p.getProperty("customMaxNodes"), s.customMaxNodes);
            s.panelX = parseFloat(p.getProperty("panelX"), s.panelX);
            s.panelY = parseFloat(p.getProperty("panelY"), s.panelY);
            s.panelW = parseFloat(p.getProperty("panelW"), s.panelW);
            s.panelH = parseFloat(p.getProperty("panelH"), s.panelH);
        } catch (Throwable ignored) {
            // 读不到就用默认值
        } finally {
            close(in);
        }
        return s;
    }

    public void save() {
        File f = settingsFile();
        File dir = f.getParentFile();
        OutputStream out = null;
        try {
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Properties p = new Properties();
            p.setProperty("preset", String.valueOf(preset));
            p.setProperty("opacityStep", String.valueOf(opacityStep));
            p.setProperty("lightTheme", String.valueOf(lightTheme));
            p.setProperty("customBeamWidth", String.valueOf(customBeamWidth));
            p.setProperty("customWithinTurnWidth", String.valueOf(customWithinTurnWidth));
            p.setProperty("customMaxTurns", String.valueOf(customMaxTurns));
            p.setProperty("customMaxNodes", String.valueOf(customMaxNodes));
            if (panelX > 0) {
                p.setProperty("panelX", String.valueOf(panelX));
                p.setProperty("panelY", String.valueOf(panelY));
            }
            if (panelW > 0) {
                p.setProperty("panelW", String.valueOf(panelW));
                p.setProperty("panelH", String.valueOf(panelH));
            }
            out = new FileOutputStream(f);
            p.store(out, "CombatSolver1 settings");
        } catch (Throwable ignored) {
            // 保存失败不影响游戏
        } finally {
            close(out);
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float parseFloat(String v, float fallback) {
        try {
            return Float.parseFloat(v);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

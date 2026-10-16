package com.combatsolver1.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 常见战斗药水的效果规格：
 * {单体伤害, 是否群体, 格挡, 力量, 治疗, 虚弱, 易伤}
 */
public final class PotionLibrary {
    private PotionLibrary() {
    }

    private static final Map<String, int[]> TABLE = new HashMap<>();

    static {
        put("Fire Potion", 20, 0, 0, 0, 0, 0, 0);
        put("Explosive Potion", 10, 1, 0, 0, 0, 0, 0);
        put("Block Potion", 0, 0, 12, 0, 0, 0, 0);
        put("Strength Potion", 0, 0, 0, 2, 0, 0, 0);
        put("Weak Potion", 0, 0, 0, 0, 0, 3, 0);
        put("Fear Potion", 0, 0, 0, 0, 0, 0, 3);
        put("Blood Potion", 0, 0, 0, 0, 20, 0, 0);
        put("Regen Potion", 0, 0, 0, 0, 6, 0, 0);
        put("Liquid Bronze", 0, 0, 0, 0, 0, 0, 0);
    }

    private static void put(String id, int dmg, int aoe, int block, int str, int heal, int weak, int vuln) {
        TABLE.put(id, new int[]{dmg, aoe, block, str, heal, weak, vuln, 0, 0, 0});
    }

    /** 扩展：能量 / 抽牌 / 反伤类药水。 */
    private static void putExtra(String id, int energy, int draw, int thorns) {
        TABLE.put(id, new int[]{0, 0, 0, 0, 0, 0, 0, energy, draw, thorns});
    }

    static {
        putExtra("Energy Potion", 2, 0, 0);
        putExtra("Swift Potion", 0, 3, 0);
        putExtra("Liquid Bronze", 0, 0, 3);
    }

    /** 返回 {单体伤害, 是否群体, 格挡, 力量, 治疗, 虚弱, 易伤}；未登记返回 null。 */
    public static int[] spec(String id) {
        return TABLE.get(id);
    }

    public static boolean isKnown(String id) {
        return TABLE.containsKey(id);
    }
}

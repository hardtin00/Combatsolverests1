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
        TABLE.put(id, values(dmg, aoe, block, str, heal, weak, vuln));
    }

    /** 扩展：能量 / 抽牌 / 反伤类药水。 */
    private static void putExtra(String id, int energy, int draw, int thorns) {
        TABLE.put(id, values(0, 0, 0, 0, 0, 0, 0, energy, draw, thorns));
    }

    static {
        putExtra("Energy Potion", 2, 0, 0);
        putExtra("Swift Potion", 0, 3, 0);
        putExtra("Liquid Bronze", 0, 0, 3);
    }

    /** 槽位总数：0 伤害 / 1 群体 / 2 格挡 / 3 力量 / 4 治疗 / 5 虚弱 / 6 易伤 /
     *  7 能量 / 8 抽牌 / 9 尖刺 / 10 敏捷 / 11 金属化 / 12 无形 / 13 神器 /
     *  14 最大生命 / 15 临时力量 / 16 双发。 */
    private static final int SLOTS = 17;

    private static int[] values(int... v) {
        int[] a = new int[SLOTS];
        System.arraycopy(v, 0, a, 0, v.length);
        return a;
    }

    /** 按「槽位, 数值, 槽位, 数值…」登记。 */
    private static void putSlots(String id, int... slotValue) {
        int[] a = values();
        for (int i = 0; i + 1 < slotValue.length; i += 2) {
            a[slotValue[i]] = slotValue[i + 1];
        }
        TABLE.put(id, a);
    }

    static {
        // 数值取自 Wiki 的 Module:Potions/data（括号内是圣树皮的加成，这里只取基础值）
        putSlots("Dexterity Potion", 10, 2);      // +2 敏捷
        putSlots("Heart of Iron", 11, 6);         // +6 金属化
        putSlots("Ghost in a Jar", 12, 1);        // +1 无形
        putSlots("Ancient Potion", 13, 1);        // +1 神器
        putSlots("Fruit Juice", 14, 5);           // +5 最大生命
        putSlots("Flex Potion", 15, 5);           // +5 力量（本回合）
        putSlots("Speed Potion", 10, 5);          // +5 敏捷（按整场近似）
        putSlots("Duplication Potion", 16, 1);    // 下一张牌打两次
        putExtra("Snecko Oil", 0, 5, 0);          // 抽 5（费用随机化未建模）
    }

    /** 返回上面注释里的 17 个槽位；未登记返回 null。 */
    public static int[] spec(String id) {
        return TABLE.get(id);
    }

    public static boolean isKnown(String id) {
        return TABLE.containsKey(id);
    }
}

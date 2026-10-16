package com.combatsolver1.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 怪物行动循环表：用一串 A/N 表示它每个回合“是否出手攻击”。
 * A = 这一回合是攻击（伤害取自它当前招式的伤害），N = 这一回合不攻击（塞牌 / 强化 / 防御等）。
 * 末尾字符会一直重复，用来表示“起手特殊、之后固定”的怪（例如邪教徒先强化再一直打）。
 *
 * 说明：原项目是逐个怪物还原完整 AI，这里先用行动节奏近似——它决定了“哪几回合会挨打”，
 * 是承伤预测里影响最大的一项。未登记的怪物退回经验平均估计。
 */
public final class MonsterPatterns {
    private MonsterPatterns() {
    }

    private static final Map<String, String> TABLE = new HashMap<>();

    private static void put(String id, String pattern) {
        TABLE.put(id, pattern);
    }

    static {
        // 第一幕
        put("Cultist", "NAAAAAAAAAA");     // 先强化，之后每回合攻击
        put("Jaw Worm", "AAAA");           // 噬咬 / 猛击 / 嚼碎基本都是攻击
        put("Louse", "AAN");               // 咬 / 咬 / 削弱
        put("Fuzzy Louse (Normal)", "AAN");
        put("Fungi Beast", "AAN");
        put("Acid Slime (S)", "AAN");
        put("Acid Slime (M)", "AAN");
        put("Acid Slime (L)", "AAN");
        put("Spike Slime (S)", "AAN");
        put("Spike Slime (M)", "AAN");
        put("Spike Slime (L)", "AAN");
        put("Gremlin Nob", "NAAAAAAAAAA"); // 先咆哮，之后几乎每回合攻击
        put("Lagavulin", "NNNAAAAAAAA");   // 睡三回合，醒来连打
        put("Sentry", "AN");               // 攻击 / 塞牌 交替
        put("Mad Gremlin", "AAA");
        put("Gremlin Fat", "AAN");
        put("Gremlin Thief", "AAN");
        put("Gremlin Tsundere", "AAN");
        put("Gremlin Warrior", "AAN");
        put("Gremlin Wizard", "NNAAAAAAAA");
        put("Shield Gremlin", "AAN");
        put("The Guardian", "AAN");
        put("Hexaghost", "AAAN");
        put("Slime Boss", "AAN");
        put("Book of Stabbing", "AAAA");
        put("Gremlin Leader", "AAN");

        // 第二幕
        put("Chosen", "AAN");
        put("Byrd", "AAAN");
        put("Shelled Parasite", "AAN");
        put("Spheric Guardian", "AAN");
        put("Centurion", "AAN");
        put("Mystic", "NNAAAAAAAA");
        put("Snecko", "AAN");
        put("Chosen One", "AAN");
        put("Cultist (Act 2)", "NAAAAAAAAAA");
        put("Bronze Automaton", "AAAN");
        put("Automaton", "AAAN");
        put("The Champ", "AAAN");
        put("Gremlin Leader (Act 2)", "AAN");
        put("Slavers", "AAN");
        put("Taskmaster", "AAN");

        // 第三幕
        put("Darkling", "AAN");
        put("Orb Walker", "AAN");
        put("Transient", "AAAA");
        put("Exploder", "NNAAAAAAAA");
        put("Repulsor", "AAN");
        put("Spiker", "AAN");
        put("Writhing Mass", "AAN");
        put("Nemesis", "AAAN");
        put("The Collector", "AAAN");
        put("Time Eater", "AAAN");
        put("Awakened One", "AAAN");
        put("Donu and Deca", "AAAN");
        put("Deca", "AAN");
        put("Donu", "AAAA");
        put("Spire Growth", "AAN");
        put("Maw", "AAN");
        put("Corrupt Heart", "AAAN");
        put("Shield and Spear", "AAN");
    }

    /** 未知怪物返回空串，调用方退回经验平均。 */
    public static String patternFor(String monsterId) {
        String p = TABLE.get(monsterId);
        return p == null ? "" : p;
    }
}

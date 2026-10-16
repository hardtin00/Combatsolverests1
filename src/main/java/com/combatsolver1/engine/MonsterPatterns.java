package com.combatsolver1.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 未建模怪物的行动节奏表：用一串 A/N 表示它每个回合“是否出手攻击”。
 * A = 这一回合是攻击（伤害取自它当前招式的伤害），N = 这一回合不攻击（塞牌 / 强化 / 防御等）。
 * 末尾字符会一直重复，用来表示“起手特殊、之后固定”的怪。
 *
 * 键必须使用实机 {@code AbstractMonster.id}（例如 JawWorm / AcidSlime_M / FuzzyLouseNormal），
 * 而不是界面上的显示名 —— 旧版用显示名做键，导致整张表从未命中过。
 * ID 取自 desktop-1.0.jar（tools/inspect/ExtractMonsterFacts.java）。
 *
 * 第一幕的怪物已经由 MonsterAi 逐招式建模，不再需要这张表；这里覆盖的是第二幕之后的怪物，
 * 属于“节奏近似”，会在实战中用 EnemyModel 观察到的真实周期自我校正。
 */
public final class MonsterPatterns {
    private MonsterPatterns() {
    }

    private static final Map<String, String> TABLE = new HashMap<String, String>();

    private static void put(String id, String pattern) {
        TABLE.put(id, pattern);
    }

    static {
        // 第一幕里尚未逐招式建模的
        put("Looter", "AANAA");            // 抢夺 / 烟雾弹 / 突刺 / 逃跑
        put("Apology Slime", "AAN");

        // 第二幕
        put("Byrd", "AAAN");               // 啄击 / 俯冲 / 头撞 / 鸣叫
        put("Centurion", "AAAN");          // 斩击 / 保护 / 狂怒
        put("Chosen", "AAAN");             // 电击 / 削弱 / 戳刺 / 咒术
        put("Shelled Parasite", "AAAN");   // 重击 / 双重打击 / 吸血 / 硬壳
        put("SnakePlant", "AAAN");         // 三连咬 / 孢子
        put("Snecko", "AAAN");             // 咬击 / 甩尾 / 凝视
        put("SphericGuardian", "AAN");     // 猛击 / 硬化 / 削弱
        put("TorchHead", "AAAA");
        put("Mugger", "AANAA");
        put("BanditBear", "AAAN");
        put("BanditLeader", "AAAN");
        put("BanditChild", "AAA");
        put("BookOfStabbing", "AAAA");
        put("GremlinLeader", "NNAAA");     // 先召唤 / 鼓舞，之后突刺
        put("Healer", "NNAA");             // 治疗 / 强化 / 攻击
        put("SlaverBoss", "AAA");
        put("TheCollector", "AAAN");

        // 第二幕首领
        put("BronzeAutomaton", "AAAN");
        put("BronzeOrb", "AAN");
        put("Champ", "AAAN");

        // 第三幕
        put("Darkling", "AAN");
        put("Orb Walker", "AAAN");
        put("Transient", "AAAA");
        put("Exploder", "NNAAAAAAAA");
        put("Repulsor", "AAN");
        put("Spiker", "AAN");
        put("WrithingMass", "AAAN");
        put("Nemesis", "AAAN");
        put("Maw", "AAAA");
        put("GiantHead", "NNAAA");
        put("Reptomancer", "AAN");
        put("Dagger", "AAA");
        put("Serpent", "AAAN");            // 尖塔生长

        // 第三幕首领与终局
        put("TimeEater", "AAAN");
        put("AwakenedOne", "AAAN");
        put("Deca", "AAN");
        put("Donu", "AAA");
        put("CorruptHeart", "AAAN");
        put("SpireShield", "AAAN");
        put("SpireSpear", "AAAN");
    }

    /** 未知怪物返回空串，调用方退回经验平均。 */
    public static String patternFor(String monsterId) {
        String p = TABLE.get(monsterId);
        return p == null ? "" : p;
    }
}

/**
 * 由 tools/inspect/GenMonsters.java 从 Bestiary（casey-c/Bestiary，MIT，Casey C）的
 * monsters.json 自动生成，请勿手改。署名与许可见 THIRD_PARTY_NOTICES.md。
 *
 * 招式数值同样经游戏本体 desktop-1.0.jar 的类文件核对（tools/inspect/ExtractMonsterFacts）。
 */
package com.combatsolver1.engine;

final class GeneratedMonsterData {
    private GeneratedMonsterData() {
    }

    static void register() {
        MonsterAi.register("AcidSlime_L", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("CORROSIVE_SPIT", "Corrosive Spit").dmg(11, 1).approx().build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(16, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(2).build(), 30)));
        MonsterAi.register("AcidSlime_L", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(18, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(2).build(), 30)));
        MonsterAi.register("AcidSlime_L", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(18, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(2).build(), 30)));
        MonsterAi.register("AcidSlime_L", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 40), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(18, 1).build(), 30), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(2).build(), 30).maxStreak(1)));
        MonsterAi.register("AcidSlime_M", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("CORROSIVE_SPIT", "Corrosive Spit").dmg(7, 1).approx().build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(10, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(1).build(), 30)));
        MonsterAi.register("AcidSlime_M", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("CORROSIVE_SPIT", "Corrosive Spit").dmg(8, 1).approx().build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(12, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(1).build(), 30)));
        MonsterAi.register("AcidSlime_M", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("CORROSIVE_SPIT", "Corrosive Spit").dmg(8, 1).approx().build(), 30), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(12, 1).build(), 40).maxStreak(1), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(1).build(), 30)));
        MonsterAi.register("AcidSlime_M", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("CORROSIVE_SPIT", "Corrosive Spit").dmg(8, 1).approx().build(), 40), MonsterAi.w(MonsterAi.m("TACKLE", "Tackle").dmg(12, 1).build(), 40), MonsterAi.w(MonsterAi.m("LICK", "Lick").weak(1).build(), 20).maxStreak(1)));
        MonsterAi.register("AcidSlime_S", 0, MonsterAi.firstThenScript(MonsterAi.random(MonsterAi.m("TACKLE", "Tackle").dmg(3, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build()), MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(3, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build())));
        MonsterAi.register("AcidSlime_S", 2, MonsterAi.firstThenScript(MonsterAi.random(MonsterAi.m("TACKLE", "Tackle").dmg(4, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build()), MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(4, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build())));
        MonsterAi.register("AcidSlime_S", 7, MonsterAi.firstThenScript(MonsterAi.random(MonsterAi.m("TACKLE", "Tackle").dmg(4, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build()), MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(4, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build())));
        MonsterAi.register("AcidSlime_S", 17, MonsterAi.firstThen(MonsterAi.m("LICK", "Lick").weak(1).build(), MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(4, 1).build(), MonsterAi.m("LICK", "Lick").weak(1).build())));
        MonsterAi.register("BanditBear", 0, MonsterAi.firstThen(MonsterAi.m("BEAR_HUG", "Bear Hug").approx().build(), MonsterAi.cycle(MonsterAi.m("LUNGE", "Lunge").dmg(9, 1).blk(9).build(), MonsterAi.m("MAUL", "Maul").dmg(18, 1).build())));
        MonsterAi.register("BanditBear", 2, MonsterAi.firstThen(MonsterAi.m("BEAR_HUG", "Bear Hug").approx().build(), MonsterAi.cycle(MonsterAi.m("LUNGE", "Lunge").dmg(10, 1).blk(9).build(), MonsterAi.m("MAUL", "Maul").dmg(20, 1).build())));
        MonsterAi.register("BanditBear", 7, MonsterAi.firstThen(MonsterAi.m("BEAR_HUG", "Bear Hug").approx().build(), MonsterAi.cycle(MonsterAi.m("LUNGE", "Lunge").dmg(10, 1).blk(9).build(), MonsterAi.m("MAUL", "Maul").dmg(20, 1).build())));
        MonsterAi.register("BanditBear", 17, MonsterAi.firstThen(MonsterAi.m("BEAR_HUG", "Bear Hug").approx().build(), MonsterAi.cycle(MonsterAi.m("LUNGE", "Lunge").dmg(10, 1).blk(9).build(), MonsterAi.m("MAUL", "Maul").dmg(20, 1).build())));
        MonsterAi.register("BanditChild", 0, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").dmg(5, 2).build()));
        MonsterAi.register("BanditChild", 2, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").dmg(6, 2).build()));
        MonsterAi.register("BanditChild", 7, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").dmg(6, 2).build()));
        MonsterAi.register("BanditLeader", 0, MonsterAi.firstThen(MonsterAi.m("MOCK", "Mock").build(), MonsterAi.cycle(MonsterAi.m("AGONIZING_SLASH", "Agonizing Slash").dmg(10, 1).weak(2).build(), MonsterAi.m("CROSS_SLASH", "Cross Slash").dmg(15, 1).build())));
        MonsterAi.register("BanditLeader", 2, MonsterAi.firstThen(MonsterAi.m("MOCK", "Mock").build(), MonsterAi.cycle(MonsterAi.m("AGONIZING_SLASH", "Agonizing Slash").dmg(12, 1).weak(2).build(), MonsterAi.m("CROSS_SLASH", "Cross Slash").dmg(17, 1).build())));
        MonsterAi.register("BanditLeader", 7, MonsterAi.firstThen(MonsterAi.m("MOCK", "Mock").build(), MonsterAi.cycle(MonsterAi.m("AGONIZING_SLASH", "Agonizing Slash").dmg(12, 1).weak(2).build(), MonsterAi.m("CROSS_SLASH", "Cross Slash").dmg(17, 1).build())));
        MonsterAi.register("BookOfStabbing", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("SINGLE_ATTACK", "Single Attack").dmg(21, 1).build(), 15), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").approx().build(), 85)));
        MonsterAi.register("BookOfStabbing", 3, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("SINGLE_ATTACK", "Single Attack").dmg(24, 1).build(), 15), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").approx().build(), 85)));
        MonsterAi.register("BookOfStabbing", 8, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("SINGLE_ATTACK", "Single Attack").dmg(24, 1).build(), 15), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").approx().build(), 85)));
        MonsterAi.register("BookOfStabbing", 18, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("SINGLE_ATTACK", "Single Attack").dmg(24, 1).build(), 15), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").approx().build(), 85)));
        MonsterAi.register("Deca", 0, MonsterAi.firstThen(MonsterAi.m("BEAM", "Beam").dmg(10, 2).approx().build(), MonsterAi.cycle(MonsterAi.m("SQUARE_OF_PROTECTION", "Square of Protection").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(10, 2).approx().build())));
        MonsterAi.register("Deca", 4, MonsterAi.firstThen(MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build(), MonsterAi.cycle(MonsterAi.m("SQUARE_OF_PROTECTION", "Square of Protection").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build())));
        MonsterAi.register("Deca", 9, MonsterAi.firstThen(MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build(), MonsterAi.cycle(MonsterAi.m("SQUARE_OF_PROTECTION", "Square of Protection").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build())));
        MonsterAi.register("Deca", 19, MonsterAi.firstThen(MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build(), MonsterAi.cycle(MonsterAi.m("SQUARE_OF_PROTECTION", "Square of Protection").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).approx().build())));
        MonsterAi.register("Donu", 0, MonsterAi.firstThen(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.cycle(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(10, 2).build())));
        MonsterAi.register("Donu", 4, MonsterAi.firstThen(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.cycle(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).build())));
        MonsterAi.register("Donu", 9, MonsterAi.firstThen(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.cycle(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).build())));
        MonsterAi.register("Donu", 19, MonsterAi.firstThen(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.cycle(MonsterAi.m("CIRCLE_OF_POWER", "Circle of Power").approx().build(), MonsterAi.m("BEAM", "Beam").dmg(12, 2).build())));
        MonsterAi.register("FungiBeast", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(6, 1).build(), 60), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(3).build(), 40)));
        MonsterAi.register("FungiBeast", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(6, 1).build(), 60), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(4).build(), 40)));
        MonsterAi.register("FungiBeast", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(6, 1).build(), 60), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(4).build(), 40)));
        MonsterAi.register("FungiBeast", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(6, 1).build(), 60), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(5).build(), 40)));
        MonsterAi.register("FuzzyLouseDefensive", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("SPIT_WEB", "Spit Web").weak(2).build(), 25)));
        MonsterAi.register("FuzzyLouseDefensive", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("SPIT_WEB", "Spit Web").weak(2).build(), 25)));
        MonsterAi.register("FuzzyLouseDefensive", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("SPIT_WEB", "Spit Web").weak(2).build(), 25)));
        MonsterAi.register("FuzzyLouseDefensive", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("SPIT_WEB", "Spit Web").weak(2).build(), 25)));
        MonsterAi.register("FuzzyLouseNormal", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(3).build(), 25)));
        MonsterAi.register("FuzzyLouseNormal", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(3).build(), 25)));
        MonsterAi.register("FuzzyLouseNormal", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(3).build(), 25)));
        MonsterAi.register("FuzzyLouseNormal", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BITE", "Bite").approx().build(), 75), MonsterAi.w(MonsterAi.m("GROW", "Grow").str(4).build(), 25)));
        MonsterAi.register("GiantHead", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("COUNT", "Count").dmg(13, 1).build(), 50), MonsterAi.w(MonsterAi.m("GLARE", "Glare").weak(1).build(), 50)));
        MonsterAi.register("GiantHead", 3, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("COUNT", "Count").dmg(13, 1).build(), 50), MonsterAi.w(MonsterAi.m("GLARE", "Glare").weak(1).build(), 50)));
        MonsterAi.register("GiantHead", 8, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("COUNT", "Count").dmg(13, 1).build(), 50), MonsterAi.w(MonsterAi.m("GLARE", "Glare").weak(1).build(), 50)));
        MonsterAi.register("GiantHead", 18, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("COUNT", "Count").dmg(13, 1).build(), 50), MonsterAi.w(MonsterAi.m("GLARE", "Glare").weak(1).build(), 50)));
        MonsterAi.register("GremlinFat", 0, MonsterAi.cycle(MonsterAi.m("SMASH", "Smash").dmg(4, 1).weak(1).build()));
        MonsterAi.register("GremlinFat", 2, MonsterAi.cycle(MonsterAi.m("SMASH", "Smash").dmg(5, 1).weak(1).build()));
        MonsterAi.register("GremlinFat", 7, MonsterAi.cycle(MonsterAi.m("SMASH", "Smash").dmg(5, 1).weak(1).build()));
        MonsterAi.register("GremlinFat", 17, MonsterAi.cycle(MonsterAi.m("SMASH", "Smash").dmg(5, 1).weak(1).frail(1).build()));
        MonsterAi.register("GremlinThief", 0, MonsterAi.cycle(MonsterAi.m("PUNCTURE", "Puncture").dmg(9, 1).build()));
        MonsterAi.register("GremlinThief", 2, MonsterAi.cycle(MonsterAi.m("PUNCTURE", "Puncture").dmg(10, 1).build()));
        MonsterAi.register("GremlinThief", 7, MonsterAi.cycle(MonsterAi.m("PUNCTURE", "Puncture").dmg(10, 1).build()));
        MonsterAi.register("GremlinWarrior", 0, MonsterAi.cycle(MonsterAi.m("SCRATCH", "Scratch").dmg(4, 1).build()));
        MonsterAi.register("GremlinWarrior", 2, MonsterAi.cycle(MonsterAi.m("SCRATCH", "Scratch").dmg(5, 1).build()));
        MonsterAi.register("GremlinWarrior", 7, MonsterAi.cycle(MonsterAi.m("SCRATCH", "Scratch").dmg(5, 1).build()));
        MonsterAi.register("GremlinWarrior", 7, MonsterAi.cycle(MonsterAi.m("SCRATCH", "Scratch").dmg(5, 1).build()));
        MonsterAi.register("Nemesis", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BURN", "Burn").approx().build(), 35).maxStreak(1), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").dmg(6, 3).build(), 35).maxStreak(2), MonsterAi.w(MonsterAi.m("SCYTHE", "Scythe").dmg(45, 1).build(), 30)));
        MonsterAi.register("Nemesis", 3, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BURN", "Burn").approx().build(), 35).maxStreak(1), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").dmg(7, 3).build(), 35).maxStreak(2), MonsterAi.w(MonsterAi.m("SCYTHE", "Scythe").dmg(45, 1).build(), 30)));
        MonsterAi.register("Nemesis", 8, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BURN", "Burn").approx().build(), 35).maxStreak(1), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").dmg(7, 3).build(), 35).maxStreak(2), MonsterAi.w(MonsterAi.m("SCYTHE", "Scythe").dmg(45, 1).build(), 30)));
        MonsterAi.register("Nemesis", 18, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("BURN", "Burn").approx().build(), 35).maxStreak(1), MonsterAi.w(MonsterAi.m("MULTI_ATTACK", "Multi-Attack").dmg(7, 3).build(), 35).maxStreak(2), MonsterAi.w(MonsterAi.m("SCYTHE", "Scythe").dmg(45, 1).build(), 30)));
        MonsterAi.register("Orb Walker", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("LASER", "Laser").dmg(10, 1).approx().build(), 60), MonsterAi.w(MonsterAi.m("CLAW", "Claw").dmg(15, 1).build(), 40)));
        MonsterAi.register("Orb Walker", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("LASER", "Laser").dmg(11, 1).approx().build(), 60), MonsterAi.w(MonsterAi.m("CLAW", "Claw").dmg(16, 1).build(), 40)));
        MonsterAi.register("Orb Walker", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("LASER", "Laser").dmg(11, 1).approx().build(), 60), MonsterAi.w(MonsterAi.m("CLAW", "Claw").dmg(16, 1).build(), 40)));
        MonsterAi.register("Orb Walker", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("LASER", "Laser").dmg(11, 1).approx().build(), 60), MonsterAi.w(MonsterAi.m("CLAW", "Claw").dmg(16, 1).build(), 40)));
        MonsterAi.register("Repulsor", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DEBUFF", "Debuff").approx().build(), 80), MonsterAi.w(MonsterAi.m("ATTACK", "Attack").dmg(11, 1).build(), 20)));
        MonsterAi.register("Repulsor", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DEBUFF", "Debuff").approx().build(), 80), MonsterAi.w(MonsterAi.m("ATTACK", "Attack").dmg(13, 1).build(), 20)));
        MonsterAi.register("Repulsor", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DEBUFF", "Debuff").approx().build(), 80), MonsterAi.w(MonsterAi.m("ATTACK", "Attack").dmg(13, 1).build(), 20)));
        MonsterAi.register("Shelled Parasite", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DOUBLE_STRIKE", "Double Strike").dmg(6, 2).build(), 40), MonsterAi.w(MonsterAi.m("SUCK", "Suck").approx().build(), 40).maxStreak(2), MonsterAi.w(MonsterAi.m("FELL", "Fell").dmg(18, 1).frail(2).build(), 20).maxStreak(1)));
        MonsterAi.register("Shelled Parasite", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DOUBLE_STRIKE", "Double Strike").dmg(7, 2).build(), 40), MonsterAi.w(MonsterAi.m("SUCK", "Suck").approx().build(), 40).maxStreak(2), MonsterAi.w(MonsterAi.m("FELL", "Fell").dmg(21, 1).frail(2).build(), 20).maxStreak(1)));
        MonsterAi.register("Shelled Parasite", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DOUBLE_STRIKE", "Double Strike").dmg(7, 2).build(), 40), MonsterAi.w(MonsterAi.m("SUCK", "Suck").approx().build(), 40).maxStreak(2), MonsterAi.w(MonsterAi.m("FELL", "Fell").dmg(21, 1).frail(2).build(), 20).maxStreak(1)));
        MonsterAi.register("Shelled Parasite", 17, MonsterAi.firstThen(MonsterAi.m("FELL", "Fell").dmg(21, 1).frail(2).build(), MonsterAi.weighted(MonsterAi.w(MonsterAi.m("DOUBLE_STRIKE", "Double Strike").dmg(7, 2).build(), 40), MonsterAi.w(MonsterAi.m("SUCK", "Suck").approx().build(), 40).maxStreak(2), MonsterAi.w(MonsterAi.m("FELL", "Fell").dmg(21, 1).frail(2).build(), 20).maxStreak(1))));
        MonsterAi.register("SlaverBlue", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 60), MonsterAi.w(MonsterAi.m("RAKE", "Rake").dmg(7, 1).weak(1).build(), 40)));
        MonsterAi.register("SlaverBlue", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 60), MonsterAi.w(MonsterAi.m("RAKE", "Rake").dmg(8, 1).weak(1).build(), 40)));
        MonsterAi.register("SlaverBlue", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 60), MonsterAi.w(MonsterAi.m("RAKE", "Rake").dmg(8, 1).weak(1).build(), 40)));
        MonsterAi.register("SlaverBlue", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 60), MonsterAi.w(MonsterAi.m("RAKE", "Rake").dmg(8, 1).weak(2).build(), 40)));
        MonsterAi.register("SlaverBoss", 0, MonsterAi.cycle(MonsterAi.m("SCOURING_WHIP", "Scouring Whip").dmg(7, 1).approx().build()));
        MonsterAi.register("SlaverBoss", 3, MonsterAi.cycle(MonsterAi.m("SCOURING_WHIP", "Scouring Whip").dmg(7, 1).approx().build()));
        MonsterAi.register("SlaverBoss", 8, MonsterAi.cycle(MonsterAi.m("SCOURING_WHIP", "Scouring Whip").dmg(7, 1).approx().build()));
        MonsterAi.register("SlaverBoss", 18, MonsterAi.cycle(MonsterAi.m("SCOURING_WHIP", "Scouring Whip").dmg(7, 1).str(1).approx().build()));
        MonsterAi.register("Snecko", 0, MonsterAi.firstThen(MonsterAi.m("PERPLEXING_GLARE", "Perplexing Glare").approx().build(), MonsterAi.weighted(MonsterAi.w(MonsterAi.m("TAIL_WHIP", "Tail Whip").dmg(8, 1).vuln(2).build(), 40), MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(15, 1).build(), 60))));
        MonsterAi.register("Snecko", 2, MonsterAi.firstThen(MonsterAi.m("PERPLEXING_GLARE", "Perplexing Glare").approx().build(), MonsterAi.weighted(MonsterAi.w(MonsterAi.m("TAIL_WHIP", "Tail Whip").dmg(10, 1).vuln(2).build(), 40), MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(18, 1).build(), 60))));
        MonsterAi.register("Snecko", 7, MonsterAi.firstThen(MonsterAi.m("PERPLEXING_GLARE", "Perplexing Glare").approx().build(), MonsterAi.weighted(MonsterAi.w(MonsterAi.m("TAIL_WHIP", "Tail Whip").dmg(10, 1).vuln(2).build(), 40), MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(18, 1).build(), 60))));
        MonsterAi.register("Snecko", 17, MonsterAi.firstThen(MonsterAi.m("PERPLEXING_GLARE", "Perplexing Glare").approx().build(), MonsterAi.weighted(MonsterAi.w(MonsterAi.m("TAIL_WHIP", "Tail Whip").dmg(10, 1).weak(2).vuln(2).build(), 40), MonsterAi.w(MonsterAi.m("BITE", "Bite").dmg(18, 1).build(), 60))));
        MonsterAi.register("SpikeSlime_L", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_L", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_L", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_L", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_M", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_M", 2, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_M", 7, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_M", 17, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("null", "null").build(), 70), MonsterAi.w(MonsterAi.m("null", "null").build(), 30)));
        MonsterAi.register("SpikeSlime_S", 0, MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(5, 1).build()));
        MonsterAi.register("SpikeSlime_S", 2, MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(6, 1).build()));
        MonsterAi.register("SpikeSlime_S", 7, MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(6, 1).build()));
        MonsterAi.register("TimeEater", 0, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("RIPPLE", "Ripple").blk(20).vuln(1).weak(1).build(), 20), MonsterAi.w(MonsterAi.m("REVERBERATE", "Reverberate").dmg(7, 3).build(), 45).maxStreak(2), MonsterAi.w(MonsterAi.m("HEAD_SLAM", "Head Slam").dmg(26, 1).approx().build(), 35).maxStreak(1)));
        MonsterAi.register("TimeEater", 4, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("RIPPLE", "Ripple").blk(20).vuln(1).weak(1).build(), 20), MonsterAi.w(MonsterAi.m("REVERBERATE", "Reverberate").dmg(8, 3).build(), 45).maxStreak(2), MonsterAi.w(MonsterAi.m("HEAD_SLAM", "Head Slam").dmg(32, 1).approx().build(), 35).maxStreak(1)));
        MonsterAi.register("TimeEater", 9, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("RIPPLE", "Ripple").blk(20).vuln(1).weak(1).build(), 20), MonsterAi.w(MonsterAi.m("REVERBERATE", "Reverberate").dmg(8, 3).build(), 45).maxStreak(2), MonsterAi.w(MonsterAi.m("HEAD_SLAM", "Head Slam").dmg(32, 1).approx().build(), 35).maxStreak(1)));
        MonsterAi.register("TimeEater", 19, MonsterAi.weighted(MonsterAi.w(MonsterAi.m("RIPPLE", "Ripple").blk(20).vuln(1).weak(1).frail(1).build(), 20), MonsterAi.w(MonsterAi.m("REVERBERATE", "Reverberate").dmg(8, 3).build(), 45).maxStreak(2), MonsterAi.w(MonsterAi.m("HEAD_SLAM", "Head Slam").dmg(32, 1).approx().build(), 35).maxStreak(1)));
        MonsterAi.register("TorchHead", 0, MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(7, 1).build()));
        MonsterAi.register("TorchHead", 9, MonsterAi.cycle(MonsterAi.m("TACKLE", "Tackle").dmg(7, 1).build()));
        MonsterAi.register("Transient", 0, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").approx().build()));
        MonsterAi.register("Transient", 2, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").approx().build()));
        MonsterAi.register("Transient", 17, MonsterAi.cycle(MonsterAi.m("ATTACK", "Attack").approx().build()));
    }
}

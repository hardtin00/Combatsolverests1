package com.combatsolver1.engine;

/**
 * 手写的怪物脚本：多阶段、召唤、逃跑、条件分支这些生成器无法可靠解析的场合。
 * 招式数值与 AI 描述来自 Bestiary（casey-c/Bestiary，MIT，Casey C）的 monsters.json，
 * 并经游戏本体 desktop-1.0.jar 的类文件核对；无法完整建模的部分（召唤、逃跑、
 * 半血复活、阶段切换、状态牌曲）在对应招式上标 approx 并在本文档说明。
 *
 * 本文件只注册「进阶 0」的基础档；高进阶主要差在数值（+1 ~ +2 伤害），暂未分档。
 */
final class MonsterManual {

    private MonsterManual() {
    }

    private static MonsterAi.M m(String id, String name) {
        return MonsterAi.m(id, name);
    }

    private static MonsterAi.Choice w(MonsterAi.Move move, int weight) {
        return MonsterAi.w(move, weight);
    }

    static void register() {
        // ── 第二幕 · 地精小队与掠夺者 ─────────────────────────────
        MonsterAi.Move tsundereProtect = m("PROTECT", "掩护").allyBlock(7).build();
        MonsterAi.Move tsundereBash = m("BASH", "盾击").dmg(6, 1).build();
        // 「先保护队友直到没有可保护的，之后盾击」——按“一段时间内偏向掩护”近似
        MonsterAi.register("GremlinTsundere", 0, MonsterAi.weighted(
                w(tsundereProtect, 60).maxStreak(2),
                w(tsundereBash, 40)));

        MonsterAi.Move looterMug = m("MUG", "抢夺").dmg(10, 1).build();
        MonsterAi.Move looterLunge = m("LUNGE", "突刺").dmg(12, 1).build();
        MonsterAi.Move looterSmoke = m("SMOKE_BOMB", "烟雾弹").blk(6).build();
        // 「抢两次 → 50% 突刺 → 烟雾弹 → 逃跑」：逃跑未建模，按循环近似
        MonsterAi.register("Looter", 0, MonsterAi.cycle(looterMug, looterMug, looterLunge, looterSmoke, m("ESCAPE", "逃跑").escape().build()));

        MonsterAi.Move muggerMug = m("MUG", "抢夺").dmg(10, 1).build();
        MonsterAi.Move muggerLunge = m("LUNGE", "突刺").dmg(16, 1).build();
        MonsterAi.Move muggerSmoke = m("SMOKE_BOMB", "烟雾弹").blk(11).build();
        MonsterAi.register("Mugger", 0, MonsterAi.cycle(muggerMug, muggerMug, muggerLunge, muggerSmoke, m("ESCAPE", "逃跑").escape().build()));

        MonsterAi.Move slaverStab = m("STAB", "刺击").dmg(13, 1).build();
        MonsterAi.Move slaverScrape = m("SCRAPE", "刮擦").dmg(8, 1).vuln(1).build();
        MonsterAi.Move slaverEntangle = m("ENTANGLE", "缠身").entangle().build();
        // 「先刺击；未用过缠身时按 刮擦→刮擦→刺击 循环，否则 45-55」
        MonsterAi.register("SlaverRed", 0, MonsterAi.firstThen(slaverStab, MonsterAi.weighted(
                w(slaverScrape, 50).maxStreak(2),
                w(slaverStab, 40).maxStreak(2),
                w(slaverEntangle, 10))));

        // ── 第二幕 · 尖刺兽 / 蛇怪三人组 ─────────────────────────
        MonsterAi.Move spikerAttack = m("ATTACK", "攻击").dmg(7, 1).build();
        MonsterAi.Move spikerBuff = m("BUFF", "强化").selfThorns(2).build();
        MonsterAi.register("Spiker", 0, MonsterAi.weighted(
                w(spikerAttack, 50).maxStreak(1),
                w(spikerBuff, 50)));

        MonsterAi.Move serpentQuick = m("QUICK_TACKLE", "快速擒抱").dmg(16, 1).build();
        MonsterAi.Move serpentSmash = m("SMASH", "重砸").dmg(22, 1).build();
        MonsterAi.Move serpentConstrict = m("CONSTRICT", "缠绕").constrict(10).build();
        // 缠绕带来的持续伤害未建模，按权重近似
        MonsterAi.register("Serpent", 0, MonsterAi.weighted(
                w(serpentQuick, 40).maxStreak(2),
                w(serpentSmash, 35).maxStreak(2),
                w(serpentConstrict, 25).maxStreak(1)));

        // ── 第三幕 · 巨口 / 暗影 / 蠕动之躯 ───────────────────────
        MonsterAi.Move mawRoar = m("ROAR", "咆哮").weak(3).frail(3).build();
        MonsterAi.Move mawDrool = m("DROOL", "垂涎").str(3).build();
        MonsterAi.Move mawSlam = m("SLAM", "重砸").dmg(25, 1).build();
        MonsterAi.Move mawNom = m("NOM", "啃咬").dmg(5, 5).approx().build(); // 段数随回合增长
        MonsterAi.register("Maw", 0, new MonsterAi.Script() {
            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if (mob.aiCursor == 0) {
                    return mawRoar;
                }
                if (mawNom.id.equals(mob.lastMoveId)) {
                    return mawDrool;
                }
                if (mawSlam.id.equals(mob.lastMoveId)) {
                    return s.nextInt(2) == 0 ? mawNom : mawDrool;
                }
                return s.nextInt(2) == 0 ? mawSlam : mawNom;
            }
        });

        MonsterAi.Move darkNip = m("NIP", "轻咬").dmgRange(7, 11, 1).build();
        MonsterAi.Move darkChomp = m("CHOMP", "啃咬").dmg(8, 2).build();
        MonsterAi.Move darkHarden = m("HARDEN", "硬化").blk(12).build();
        // 半血复活与「中间的暗影不同」未建模，按基础分布近似
        MonsterAi.register("Darkling", 0, MonsterAi.firstThenScript(
                MonsterAi.random(darkNip, darkHarden), MonsterAi.weighted(
                        w(darkChomp, 40).maxStreak(1),
                        w(darkNip, 30).maxStreak(2),
                        w(darkHarden, 30).maxStreak(1))));

        MonsterAi.Move massParasite = m("PARASITE", "寄生").approx().build();
        MonsterAi.Move massDefend = m("ATTACK_DEFEND", "攻守").dmg(15, 1).blk(16).build();
        MonsterAi.Move massDebuff = m("ATTACK_DEBUFF", "攻击削弱").dmg(10, 1).weak(2).vuln(2).build();
        MonsterAi.Move massMulti = m("MULTI_ATTACK", "多重攻击").dmg(7, 3).build();
        MonsterAi.Move massSingle = m("SINGLE_ATTACK", "单次重击").dmg(32, 1).build();
        MonsterAi.register("WrithingMass", 0, MonsterAi.firstThenScript(
                MonsterAi.random(massMulti, massSingle, massDebuff), MonsterAi.weighted(
                        w(massParasite, 10).maxStreak(1),
                        w(massDefend, 30).maxStreak(1),
                        w(massDebuff, 20).maxStreak(1),
                        w(massMulti, 30).maxStreak(1),
                        w(massSingle, 10).maxStreak(1))));

        MonsterAi.Move daggerStab = m("STAB", "刺击").dmg(9, 1).add("Wound", 1).build();
        MonsterAi.Move daggerExplode = m("EXPLODE", "自爆").dmg(25, 1).approx().build();
        MonsterAi.register("Dagger", 0, MonsterAi.cycle(daggerStab, daggerExplode));

        MonsterAi.Move exploderAttack = m("ATTACK", "攻击").dmg(9, 1).build();
        MonsterAi.Move exploderExplode = m("EXPLODE", "自爆").dmg(30, 1).approx().build();
        MonsterAi.register("Exploder", 0,
                MonsterAi.cycle(exploderAttack, exploderAttack, exploderExplode));

        // ── 首领 ────────────────────────────────────────────────
        MonsterAi.Move orbSupport = m("SUPPORT_BEAM", "支援光束").allyBlock(12).build();
        MonsterAi.Move orbBeam = m("BEAM", "光束").dmg(8, 1).build();
        MonsterAi.Move orbStasis = m("STASIS", "停滞").approx().build(); // 偷牌未建模
        MonsterAi.register("BronzeOrb", 0, MonsterAi.firstThen(orbStasis, MonsterAi.weighted(
                w(orbSupport, 70).maxStreak(2),
                w(orbBeam, 30).maxStreak(2))));

        MonsterAi.Move autoSpawn = m("SPAWN_ORBS", "召唤能量球").summon("BronzeOrb", 2).build();
        MonsterAi.Move autoFlail = m("FLAIL", "连枷").dmg(7, 2).build();
        MonsterAi.Move autoBoost = m("BOOST", "强化").str(3).blk(9).build();
        MonsterAi.Move autoBeam = m("HYPER_BEAM", "超能光束").dmg(45, 1).build();
        MonsterAi.Move autoStunned = m("STUNNED", "眩晕").build();
        // 确定性循环（召唤的两只能量球未建模）
        MonsterAi.register("BronzeAutomaton", 0, MonsterAi.cycle(
                autoSpawn, autoFlail, autoBoost, autoFlail, autoBoost, autoBeam, autoStunned));

        MonsterAi.Move champHeavy = m("HEAVY_SLASH", "重斩").dmg(16, 1).build();
        MonsterAi.Move champSlap = m("FACE_SLAP", "掌掴").dmg(12, 1).frail(2).vuln(2).build();
        MonsterAi.Move champStance = m("DEFENSIVE_STANCE", "防御姿态").blk(15).approx().build();
        MonsterAi.Move champGloat = m("GLOAT", "自满").str(2).build();
        MonsterAi.Move champTaunt = m("TAUNT", "嘲讽").weak(2).vuln(2).build();
        // 每 4 回合嘲讽；其余按 45-25-15-15 且不连续同招（半血狂怒未建模）
        MonsterAi.register("Champ", 0, new MonsterAi.Script() {
            private final MonsterAi.Script normal = MonsterAi.weighted(
                    w(champHeavy, 45).maxStreak(1),
                    w(champSlap, 25).maxStreak(1),
                    w(champStance, 15).maxStreak(1),
                    w(champGloat, 15).maxStreak(1));

            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if (mob.aiCursor % 4 == 3) {
                    return champTaunt;
                }
                return normal.pick(s, mob);
            }
        });

        MonsterAi.Move colSpawn = m("SPAWN", "召唤火炬头").summon("TorchHead", 2).build();
        MonsterAi.Move colRevive = m("REVIVE", "复活火炬头").summon("TorchHead", 2).build();
        MonsterAi.Move colFireball = m("FIREBALL", "火球").dmg(18, 1).build();
        MonsterAi.Move colBuff = m("BUFF", "强化").blk(15).partyStr(3).build();
        MonsterAi.Move colMega = m("MEGA_DEBUFF", "大削弱").weak(3).vuln(3).frail(3).build();
        MonsterAi.register("TheCollector", 0, new MonsterAi.Script() {
            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if (mob.aiCursor == 0) {
                    return colSpawn;
                }
                if (mob.aiCursor % 4 == 3) {
                    return colMega; // 第 4 回合起每 4 回合
                }
                boolean torchHeadDown = false;
                for (SimState.Mob ally : s.mobs) {
                    if ("TorchHead".equals(ally.id) && !ally.alive) {
                        torchHeadDown = true;
                    }
                }
                if (torchHeadDown) {
                    return MonsterAi.weighted(
                            w(colRevive, 25).maxStreak(1),
                            w(colFireball, 45).maxStreak(2),
                            w(colBuff, 30).maxStreak(1)).pick(s, mob);
                }
                return MonsterAi.weighted(
                        w(colFireball, 70).maxStreak(2),
                        w(colBuff, 30)).pick(s, mob);
            }
        });

        MonsterAi.Move torchTackle = m("TACKLE", "擒抱").dmg(7, 1).build();
        MonsterAi.register("TorchHead", 0, MonsterAi.cycle(torchTackle));

        MonsterAi.Move wokeSlash = m("SLASH", "斩击").dmg(20, 1).build();
        MonsterAi.Move wokeSoul = m("SOUL_STRIKE", "灵魂打击").dmg(6, 4).build();
        // 只建模第一阶段（半血复活的第二阶段未建模）
        MonsterAi.register("AwakenedOne", 0, MonsterAi.firstThen(wokeSlash, MonsterAi.weighted(
                w(wokeSoul, 25).maxStreak(1),
                w(wokeSlash, 75).maxStreak(2))));

        MonsterAi.Move heartDebilitate = m("DEBILITATE", "衰弱").vuln(2).weak(2).frail(2).build();
        MonsterAi.Move heartBlood = m("BLOOD_SHOTS", "血弹").dmg(2, 12).build();
        MonsterAi.Move heartEcho = m("ECHO", "回声").dmg(40, 1).build();
        MonsterAi.Move heartBuff = m("BUFF", "强化").str(2).approx().build();
        // 每 3 回合（第 4 回合起）强化；其余在「血弹→回声」「回声→血弹」间等概率
        MonsterAi.register("CorruptHeart", 0, new MonsterAi.Script() {
            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if (mob.aiCursor == 0) {
                    return heartDebilitate;
                }
                if (mob.aiCursor % 3 == 0) {
                    return heartBuff;
                }
                return MonsterAi.weighted(
                        w(heartBlood, 50).maxStreak(2),
                        w(heartEcho, 50).maxStreak(2)).pick(s, mob);
            }
        });

        MonsterAi.Move shieldBash = m("BASH", "猛击").dmg(12, 1).strDown(1).approx().build();
        MonsterAi.Move shieldFortify = m("FORTIFY", "加固").allyBlock(30).build();
        MonsterAi.Move shieldSmash = m("SMASH", "重砸").dmg(34, 1).blk(34).build();
        MonsterAi.register("SpireShield", 0, new MonsterAi.Script() {
            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if ((mob.aiCursor + 1) % 3 == 0) {
                    return shieldSmash; // 第 3、6、9… 回合
                }
                return s.nextInt(2) == 0 ? shieldBash : shieldFortify;
            }
        });

        MonsterAi.Move spearBurn = m("BURN_STRIKE", "燃烧打击").dmg(5, 2).add("Burn", 2).build();
        MonsterAi.Move spearPiercer = m("PIERCER", "穿刺").partyStr(2).build();
        MonsterAi.Move spearSkewer = m("SKEWER", "串刺").dmg(10, 3).build();
        MonsterAi.register("SpireSpear", 0, new MonsterAi.Script() {
            public MonsterAi.Move pick(SimState s, SimState.Mob mob) {
                if (mob.aiCursor == 0) {
                    return spearBurn; // 起手固定
                }
                if ((mob.aiCursor + 1) % 3 == 0) {
                    return spearSkewer;
                }
                return s.nextInt(2) == 0 ? spearBurn : spearPiercer;
            }
        });
    }
}

package com.combatsolver1.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 怪物行动模型：把每个怪物的招式、选择规则与状态机写成数据表，
 * 让整场搜索里的「怪物这一回合做什么」从节奏近似升级为逐个招式还原。
 *
 * 数值来源：《杀戮尖塔》desktop-1.0.jar 里各怪物类的静态数值字段
 * （用 tools/inspect/ExtractMonsterFacts.java 读出），ID 也是实机运行时
 * AbstractMonster.id 的真实取值。选择规则与特殊机制（仪式、睡眠、狂怒、
 * 蓄力、模式切换、死亡触发）按原版行为实现；仍不能完全确认的部分标 approx。
 *
 * 覆盖范围：第一幕全部遭遇怪物 + 奴隶主（红/蓝）。未登记的怪物回退到
 * MonsterPatterns 的节奏表与 EnemyModel 的实战平均，不会比旧实现更差。
 */
public final class MonsterAi {

    private MonsterAi() {
    }

    /** 一个具体招式的完整效果。 */
    public static final class Move {
        public final String id;
        public final String name;
        public final int damage;
        public final int damageMax;
        public final int hits;
        public final int block;
        public final int strGain;
        public final int ritual;
        public final int playerWeak;
        public final int playerVuln;
        public final int playerFrail;
        public final int playerStrDown;
        public final int playerDexDown;
        public final String addCard;
        public final int addCount;
        public final boolean addToDraw;
        public final boolean sleep;
        public final boolean entangle;
        public final int allyHeal;
        public final int allyBlock;
        public final int partyStr;
        public final boolean approx;

        private Move(M b) {
            this.id = b.id;
            this.name = b.name;
            this.damage = b.damage;
            this.damageMax = Math.max(b.damage, b.damageMax);
            this.hits = Math.max(1, b.hits);
            this.block = b.block;
            this.strGain = b.strGain;
            this.ritual = b.ritual;
            this.playerWeak = b.playerWeak;
            this.playerVuln = b.playerVuln;
            this.playerFrail = b.playerFrail;
            this.playerStrDown = b.playerStrDown;
            this.playerDexDown = b.playerDexDown;
            this.addCard = b.addCard;
            this.addCount = b.addCount;
            this.addToDraw = b.addToDraw;
            this.sleep = b.sleep;
            this.entangle = b.entangle;
            this.allyHeal = b.allyHeal;
            this.allyBlock = b.allyBlock;
            this.partyStr = b.partyStr;
            this.approx = b.approx;
        }

        public boolean isAttack() {
            return damage > 0;
        }

        /** 预告伤害（多段随机取中值，用于面板与承伤计算）。 */
        public int previewDamage() {
            if (damage <= 0) {
                return 0;
            }
            return (damage + damageMax) / 2;
        }

        public String label() {
            return approx ? name + "~" : name;
        }
    }

    private static final class M {
        final String id;
        final String name;
        int damage;
        int damageMax;
        int hits = 1;
        int block;
        int strGain;
        int ritual;
        int playerWeak;
        int playerVuln;
        int playerFrail;
        int playerStrDown;
        int playerDexDown;
        String addCard;
        int addCount;
        boolean addToDraw;
        boolean sleep;
        boolean entangle;
        int allyHeal;
        int allyBlock;
        int partyStr;
        boolean approx;

        M(String id, String name) {
            this.id = id;
            this.name = name;
        }

        M dmg(int value, int hitCount) {
            this.damage = value;
            this.damageMax = value;
            this.hits = hitCount;
            return this;
        }

        M dmgRange(int min, int max, int hitCount) {
            this.damage = min;
            this.damageMax = max;
            this.hits = hitCount;
            return this;
        }

        M blk(int value) {
            this.block = value;
            return this;
        }

        M str(int value) {
            this.strGain = value;
            return this;
        }

        M ritual(int value) {
            this.ritual = value;
            return this;
        }

        M weak(int value) {
            this.playerWeak = value;
            return this;
        }

        M vuln(int value) {
            this.playerVuln = value;
            return this;
        }

        M frail(int value) {
            this.playerFrail = value;
            return this;
        }

        M strDown(int value) {
            this.playerStrDown = value;
            return this;
        }

        M dexDown(int value) {
            this.playerDexDown = value;
            return this;
        }

        M add(String card, int count) {
            this.addCard = card;
            this.addCount = count;
            return this;
        }

        M toDraw() {
            this.addToDraw = true;
            return this;
        }

        M sleep() {
            this.sleep = true;
            return this;
        }

        M entangle() {
            this.entangle = true;
            return this;
        }

        /** 治疗队伍里伤得最重的一员（秘术师）。 */
        M allyHeal(int value) {
            this.allyHeal = value;
            return this;
        }

        /** 给队伍里伤得最重的一员加格挡（百夫长的掩护）。 */
        M allyBlock(int value) {
            this.allyBlock = value;
            return this;
        }

        /** 全队获得力量（秘术师的鼓舞）。 */
        M partyStr(int value) {
            this.partyStr = value;
            return this;
        }

        M approx() {
            this.approx = true;
            return this;
        }

        Move build() {
            if (damage > 0) {
                this.hits = Math.max(1, hits);
            }
            return new Move(this);
        }
    }

    private static M m(String id, String name) {
        return new M(id, name);
    }

    /** 招式选择脚本。aiCursor = 该怪物已经执行过的招式数（即“下一个招式”的下标）。 */
    private interface Script {
        Move pick(SimState s, SimState.Mob mob);
    }

    /** 固定循环：cursor 对长度取模。 */
    private static Script cycle(final Move... moves) {
        return new Script() {
            public Move pick(SimState s, SimState.Mob mob) {
                return moves[Math.floorMod(mob.aiCursor, moves.length)];
            }
        };
    }

    /** 起手固定，之后进入另一个脚本（用于“先强化再打”的怪）。 */
    private static Script firstThen(final Move first, final Script then) {
        return new Script() {
            public Move pick(SimState s, SimState.Mob mob) {
                return mob.aiCursor == 0 ? first : then.pick(s, mob);
            }
        };
    }

    /** 均匀随机，但不能连续三次使用同一招式（原版对多数随机怪物的约束）。 */
    private static Script random(final Move... moves) {
        return new Script() {
            public Move pick(SimState s, SimState.Mob mob) {
                return pickWeighted(s, mob, moves, false);
            }
        };
    }

    /** 均匀随机，但不能连续两次使用同一招式（地精大块头）。 */
    private static Script randomNoRepeat(final Move... moves) {
        return new Script() {
            public Move pick(SimState s, SimState.Mob mob) {
                return pickWeighted(s, mob, moves, true);
            }
        };
    }

    private static Move pickWeighted(SimState s, SimState.Mob mob, Move[] moves, boolean noRepeat) {
        if (moves.length == 1) {
            return moves[0];
        }
        int total = 0;
        int[] weights = new int[moves.length];
        for (int i = 0; i < moves.length; i++) {
            boolean blocked = noRepeat
                    ? moves[i].id.equals(mob.lastMoveId)
                    : (mob.lastMoveStreak >= 2 && moves[i].id.equals(mob.lastMoveId));
            weights[i] = blocked ? 0 : 1;
            total += weights[i];
        }
        if (total == 0) {
            return moves[0];
        }
        int roll = s.nextInt(total);
        for (int i = 0; i < moves.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return moves[i];
            }
        }
        return moves[moves.length - 1];
    }

    /** 加权随机的候选项：权重 + 连用上限 + 是否禁止起手（照 Bestiary 的 AI 描述）。 */
    private static final class Choice {
        final Move move;
        final int weight;
        int maxStreak;
        boolean notFirst;

        Choice(Move move, int weight) {
            this.move = move;
            this.weight = weight;
        }

        /** 最多允许连续使用几次（3 连禁 = 2）。 */
        Choice maxStreak(int value) {
            this.maxStreak = value;
            return this;
        }

        Choice notFirst() {
            this.notFirst = true;
            return this;
        }
    }

    private static Choice w(Move move, int weight) {
        return new Choice(move, weight);
    }

    /** 按权重随机；被「连用上限 / 禁止起手」挡住的候选人权重记 0。 */
    private static Script weighted(final Choice... choices) {
        return new Script() {
            public Move pick(SimState s, SimState.Mob mob) {
                int total = 0;
                int[] weights = new int[choices.length];
                for (int i = 0; i < choices.length; i++) {
                    weights[i] = allowed(choices[i], mob) ? choices[i].weight : 0;
                    total += weights[i];
                }
                if (total <= 0) {
                    // 全被限制挡住时退回权重最高的一个，保证状态机不会卡死
                    Choice best = choices[0];
                    for (Choice c : choices) {
                        if (c.weight > best.weight) {
                            best = c;
                        }
                    }
                    return best.move;
                }
                int roll = s.nextInt(total);
                for (int i = 0; i < choices.length; i++) {
                    roll -= weights[i];
                    if (roll < 0) {
                        return choices[i].move;
                    }
                }
                return choices[choices.length - 1].move;
            }
        };
    }

    private static boolean allowed(Choice choice, SimState.Mob mob) {
        if (choice.notFirst && mob.aiCursor == 0 && mob.lastMoveId.isEmpty()) {
            return false;
        }
        return choice.maxStreak <= 0 || !choice.move.id.equals(mob.lastMoveId)
                || mob.lastMoveStreak < choice.maxStreak;
    }

    /** 场上是否还有某个 id 的存活怪物（百夫长判断秘术师是否还活着）。 */
    private static boolean allyAlive(SimState s, SimState.Mob self, String id) {
        for (SimState.Mob m : s.mobs) {
            if (m != self && m.alive && id.equals(m.id)) {
                return true;
            }
        }
        return false;
    }

    // ── 招式数据（数值取自游戏 jar 的静态字段；名字为简中显示名）──────────

    // 邪教徒：吟唱（获得 3 仪式）→ 黑暗打击 6 循环；仪式每回合末 +3 力量。
    private static final Move CULTIST_INCANTATION = m("INCANTATION", "吟唱").ritual(3).build();
    private static final Move CULTIST_DARK_STRIKE = m("DARK_STRIKE", "黑暗打击").dmg(6, 1).build();

    // 大颚虫：啃咬 11 / 猛击 7+5 格挡 / 咆哮 +3 力量 +6 格挡，随机但不能连续三次。
    private static final Move JAW_CHOMP = m("CHOMP", "啃咬").dmg(11, 1).build();
    private static final Move JAW_THRASH = m("THRASH", "猛击").dmg(7, 1).blk(5).build();
    private static final Move JAW_BELLOW = m("BELLOW", "咆哮").str(3).blk(6).build();

    // 虱虫：啃咬 5-7；绿虱虫强化 +3 力量，红虱虫吐丝施加 2 虚弱。
    private static final Move LOUSE_BITE = m("BITE", "啃咬").dmgRange(5, 7, 1).build();
    private static final Move LOUSE_GROW = m("GROW", "强化").str(3).build();
    private static final Move LOUSE_SPIT = m("WEAKEN", "吐丝").weak(2).build();

    // 真菌兽：啃咬 6 / 强化 +3 力量；死亡时孢子云给玩家 2 易伤。
    private static final Move FUNGUS_BITE = m("BITE", "啃咬").dmg(6, 1).build();
    private static final Move FUNGUS_GROW = m("GROW", "强化").str(3).build();

    // 酸液史莱姆（小/中/大）
    private static final Move ACID_S_TACKLE = m("TACKLE", "冲撞").dmg(3, 1).build();
    private static final Move ACID_S_LICK = m("DEBUFF", "舔舐").weak(1).build();
    private static final Move ACID_M_SPIT = m("WOUND_TACKLE", "腐蚀喷吐").dmg(7, 1).add("Slimed", 1).build();
    private static final Move ACID_M_TACKLE = m("NORMAL_TACKLE", "冲撞").dmg(10, 1).build();
    private static final Move ACID_M_LICK = m("WEAK_LICK", "舔舐").weak(1).build();
    private static final Move ACID_L_SPIT = m("SLIME_TACKLE", "腐蚀喷吐").dmg(11, 1).add("Slimed", 2).build();
    private static final Move ACID_L_TACKLE = m("NORMAL_TACKLE", "冲撞").dmg(16, 1).build();
    private static final Move ACID_L_LICK = m("WEAK_LICK", "舔舐").weak(2).build();

    // 尖刺史莱姆（小/中/大）
    private static final Move SPIKE_S_TACKLE = m("TACKLE", "冲撞").dmg(5, 1).build();
    private static final Move SPIKE_M_TACKLE = m("FLAME_TACKLE", "火焰冲撞").dmg(8, 1).add("Slimed", 1).build();
    private static final Move SPIKE_M_LICK = m("FRAIL_LICK", "舔舐").frail(1).build();
    private static final Move SPIKE_L_TACKLE = m("FLAME_TACKLE", "火焰冲撞").dmg(16, 1).add("Slimed", 2).build();
    private static final Move SPIKE_L_LICK = m("FRAIL_LICK", "舔舐").frail(2).build();

    // 地精大块头：先咆哮，之后冲锋 14 / 碎颅 6+2 易伤 / 咆哮 随机（不连续重复）；自带狂怒 2。
    private static final Move NOB_BELLOW = m("BELLOW", "咆哮").str(2).build();
    private static final Move NOB_RUSH = m("BULL_RUSH", "冲锋").dmg(14, 1).build();
    private static final Move NOB_SKULL_BASH = m("SKULL_BASH", "碎颅").dmg(6, 1).vuln(2).build();

    // 拉格威林：睡三回合（每回合 +8 格挡），被打醒或睡满后攻击 18 / 吸魂（-1 力量 -1 敏捷）交替。
    private static final Move LAG_SLEEP = m("IDLE", "沉睡").sleep().blk(8).build();
    private static final Move LAG_ATTACK = m("STRONG_ATK", "重击").dmg(18, 1).build();
    private static final Move LAG_SIPHON = m("DEBUFF", "吸魂").strDown(1).dexDown(1).build();
    private static final Script LAGAVULIN = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            if (mob.aiCursor < 3) {
                return LAG_SLEEP;
            }
            return ((mob.aiCursor - 3) % 2 == 0) ? LAG_ATTACK : LAG_SIPHON;
        }
    };

    // 哨卫：光束 9 / 射线 9+2 眩晕（交替）。
    private static final Move SENTRY_BOLT = m("BOLT", "光束").dmg(9, 1).build();
    private static final Move SENTRY_BEAM = m("BEAM", "射线").dmg(9, 1).add("Dazed", 2).build();

    // 地精小队
    private static final Move GREMLIN_SCRATCH = m("SCRATCH", "抓挠").dmg(4, 1).build();
    private static final Move GREMLIN_BLUNT = m("BLUNT", "钝击").dmg(4, 1).weak(1).build();
    private static final Move GREMLIN_PUNCTURE = m("PUNCTURE", "刺击").dmg(9, 1).build();
    private static final Move GREMLIN_TSUNDERE_BLOCK = m("PROTECT", "保护").blk(7).approx().build();
    private static final Move GREMLIN_TSUNDERE_BASH = m("BASH", "重击").dmg(6, 1).build();
    private static final Move GREMLIN_WIZARD_CHARGE = m("CHARGE", "蓄能").build();
    private static final Move GREMLIN_WIZARD_BLAST = m("DOPE_MAGIC", "终极爆裂").dmg(25, 1).build();
    private static final Script GREMLIN_WIZARD = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            return (mob.aiCursor % 4 == 3) ? GREMLIN_WIZARD_BLAST : GREMLIN_WIZARD_CHARGE;
        }
    };

    // 奴隶主
    private static final Move SLAVER_BLUE_STAB = m("STAB", "刺击").dmg(12, 1).build();
    private static final Move SLAVER_BLUE_RAKE = m("RAKE", "耙击").dmg(7, 1).weak(1).build();
    private static final Move SLAVER_RED_STAB = m("STAB", "刺击").dmg(13, 1).build();
    private static final Move SLAVER_RED_SCRAPE = m("SCRAPE", "刮擦").dmg(8, 1).vuln(1).build();
    private static final Move SLAVER_RED_ENTANGLE = m("ENTANGLE", "缠身").entangle().build();

    // 守护者（近似）：进攻模式循环；累计被击 30 点后进入防御模式（+20 格挡、3 尖刺）
    private static final Move GUARDIAN_CHARGE_UP = m("CHARGE_UP", "蓄力").blk(9).approx().build();
    private static final Move GUARDIAN_WHIRLWIND = m("WHIRLWIND", "旋风").dmg(5, 3).build();
    private static final Move GUARDIAN_FIERCE_BASH = m("FIERCE_BASH", "猛烈打击").dmg(32, 1).build();
    private static final Move GUARDIAN_VENT_STEAM = m("VENT_STEAM", "喷汽").weak(2).vuln(2).build();
    private static final Move GUARDIAN_ROLL = m("ROLL_ATTACK", "翻滚攻击").dmg(9, 1).blk(9).approx().build();
    private static final Move GUARDIAN_TWIN_SLAM = m("TWIN_SLAM", "双段猛击").dmg(5, 2).approx().build();
    private static final Move[] GUARDIAN_OFFENSIVE = {
            GUARDIAN_WHIRLWIND, GUARDIAN_FIERCE_BASH, GUARDIAN_VENT_STEAM,
    };
    private static final Script GUARDIAN = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            if (mob.aiState == 1) {
                if (mob.aiCounter >= 3) {
                    mob.aiState = 0;
                    mob.aiCounter = 0;
                } else {
                    mob.aiCounter++;
                    return (mob.aiCounter % 2 == 1) ? GUARDIAN_ROLL : GUARDIAN_TWIN_SLAM;
                }
            }
            if (mob.aiCursor == 0) {
                return GUARDIAN_CHARGE_UP;
            }
            return GUARDIAN_OFFENSIVE[(mob.aiCursor - 1) % GUARDIAN_OFFENSIVE.length];
        }
    };

    // 六火亡魂（近似）：启动 → 分割 6×6 → 灼烧/重击/炽焰/炼狱 循环
    private static final Move HEXA_ACTIVATE = m("ACTIVATE", "启动").build();
    private static final Move HEXA_DIVIDER = m("DIVIDER", "分割").dmg(6, 6).build();
    private static final Move HEXA_SEAR = m("SEAR", "灼烧").dmg(6, 1).add("Burn", 1).build();
    private static final Move HEXA_TACKLE = m("TACKLE", "重击").dmg(5, 2).approx().build();
    private static final Move HEXA_INFLAME = m("INFLAME", "燃烧").str(2).blk(12).build();
    private static final Move HEXA_INFERNO = m("INFERNO", "炼狱").dmg(2, 6).add("Burn", 1).build();
    private static final Move[] HEXA_CYCLE = {HEXA_SEAR, HEXA_TACKLE, HEXA_SEAR, HEXA_INFERNO};
    private static final Script HEXAGHOST = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            if (mob.aiCursor == 0) {
                return HEXA_ACTIVATE;
            }
            if (mob.aiCursor == 1) {
                return HEXA_DIVIDER;
            }
            return HEXA_CYCLE[(mob.aiCursor - 2) % HEXA_CYCLE.length];
        }
    };

    // 史莱姆首领（近似；分裂尚未建模）：黏液喷吐 → 准备 → 猛砸 35，之后 冲撞/喷吐/猛砸 循环
    private static final Move SLIME_BOSS_GOOP = m("STICKY", "黏液喷吐").add("Slimed", 3).build();
    private static final Move SLIME_BOSS_PREPARE = m("PREP_SLAM", "准备重砸").build();
    private static final Move SLIME_BOSS_SLAM = m("SLAM", "重砸").dmg(35, 1).build();
    private static final Move SLIME_BOSS_TACKLE = m("TACKLE", "冲撞").dmg(9, 1).approx().build();
    private static final Move[] SLIME_BOSS_CYCLE = {SLIME_BOSS_TACKLE, SLIME_BOSS_GOOP, SLIME_BOSS_SLAM};
    private static final Script SLIME_BOSS = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            if (mob.aiCursor == 0) {
                return SLIME_BOSS_GOOP;
            }
            if (mob.aiCursor == 1) {
                return SLIME_BOSS_PREPARE;
            }
            if (mob.aiCursor == 2) {
                return SLIME_BOSS_SLAM;
            }
            return SLIME_BOSS_CYCLE[(mob.aiCursor - 3) % SLIME_BOSS_CYCLE.length];
        }
    };

    // ── 第二幕 ────────────────────────────────────────────────

    // 鸟：飞行时 50-20-30（啄击/俯冲/鸣叫）；被击落则失衡 → 头撞 → 重新起飞。
    private static final Move BYRD_PECK = m("PECK", "啄击").dmg(1, 5).build();
    private static final Move BYRD_SWOOP = m("SWOOP", "俯冲").dmg(12, 1).build();
    private static final Move BYRD_CAW = m("CAW", "鸣叫").str(1).build();
    private static final Move BYRD_STUNNED = m("STUNNED", "失衡").build();
    private static final Move BYRD_HEADBUTT = m("HEADBUTT", "头撞").dmg(3, 1).build();
    private static final Move BYRD_FLY = m("GO_AIRBORNE", "重新起飞").build();
    private static final Script BYRD_FLYING = weighted(
            w(BYRD_PECK, 50).maxStreak(2),
            w(BYRD_SWOOP, 20).maxStreak(1).notFirst(),
            w(BYRD_CAW, 30).maxStreak(1));
    private static final Script BYRD = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            if (mob.flight > 0) {
                return BYRD_FLYING.pick(s, mob);
            }
            int since = mob.aiCursor - mob.aiCounter;
            if (since <= 0) {
                return BYRD_STUNNED;   // 落地当回合失衡
            }
            if (since == 1) {
                return BYRD_HEADBUTT;
            }
            mob.flight = 3;            // 重新起飞，恢复 3 层飞行
            return BYRD_FLY;
        }
    };

    // 被选者：先戳刺、再放咒术，之后「衰弱/汲取 50-50」与「电击/戳刺 40-60」交替
    private static final Move CHOSEN_POKE = m("POKE", "戳刺").dmg(5, 2).build();
    private static final Move CHOSEN_HEX = m("HEX", "咒术").approx().build();
    private static final Move CHOSEN_DEBILITATE = m("DEBILITATE", "衰弱").dmg(10, 1).vuln(2).build();
    private static final Move CHOSEN_DRAIN = m("DRAIN", "汲取").weak(3).str(3).build();
    private static final Move CHOSEN_ZAP = m("ZAP", "电击").dmg(18, 1).build();
    private static final Script CHOSEN = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            int cursor = mob.aiCursor;
            if (cursor == 0) {
                return CHOSEN_POKE;
            }
            if (cursor == 1) {
                return CHOSEN_HEX;
            }
            int index = cursor - 2;
            if (index % 2 == 0) {
                return s.nextInt(2) == 0 ? CHOSEN_DEBILITATE : CHOSEN_DRAIN;
            }
            return s.nextInt(100) < 40 ? CHOSEN_ZAP : CHOSEN_POKE;
        }
    };

    // 蛇草：65% 三连咬 / 35% 孢子（咬不能连续三次，孢子不能连续两次）
    private static final Move SNAKE_CHOMP = m("CHOMPY_CHOMPS", "三连咬").dmg(7, 3).build();
    private static final Move SNAKE_SPORES = m("SPORES", "虚弱孢子").frail(2).weak(2).build();

    // 球状守护者：启动 → 攻击+残废 → 猛击 / 硬化 交替
    private static final Move SG_ACTIVATE = m("INITIAL_BLOCK_GAIN", "启动").blk(25).build();
    private static final Move SG_ATTACK_DEBUFF = m("FRAIL_ATTACK", "攻击+残废").dmg(10, 1).frail(5).build();
    private static final Move SG_SLAM = m("BIG_ATTACK", "猛击").dmg(10, 2).build();
    private static final Move SG_HARDEN = m("BLOCK_ATTACK", "硬化").dmg(10, 1).blk(15).build();
    private static final Script SPHERIC_GUARDIAN = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            int cursor = mob.aiCursor;
            if (cursor == 0) {
                return SG_ACTIVATE;
            }
            if (cursor == 1) {
                return SG_ATTACK_DEBUFF;
            }
            return ((cursor - 2) % 2 == 0) ? SG_SLAM : SG_HARDEN;
        }
    };

    // 百夫长：秘术师活着时 65% 掩护 / 35% 斩击；秘术师死后掩护换成狂怒
    private static final Move CENT_SLASH = m("SLASH", "斩击").dmg(12, 1).build();
    private static final Move CENT_DEFEND = m("PROTECT", "掩护").allyBlock(15).build();
    private static final Move CENT_FURY = m("FURY", "狂怒").dmg(6, 3).build();
    private static final Script CENTURION = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            boolean mysticAlive = allyAlive(s, mob, "Healer");
            if (s.nextInt(100) < 65) {
                return mysticAlive ? CENT_DEFEND : CENT_FURY;
            }
            return CENT_SLASH;
        }
    };

    // 秘术师：队伍缺血 >= 16 就治疗（最多连续两次），否则 60% 攻击+残废 / 40% 鼓舞
    private static final Move MYSTIC_HEAL = m("HEAL", "治疗").allyHeal(16).build();
    private static final Move MYSTIC_BUFF = m("BUFF", "鼓舞").partyStr(2).build();
    private static final Move MYSTIC_ATTACK = m("ATTACK", "攻击+残废").dmg(8, 1).frail(2).build();
    private static final Script MYSTIC = new Script() {
        public Move pick(SimState s, SimState.Mob mob) {
            int missing = 0;
            for (SimState.Mob ally : s.mobs) {
                if (ally.alive) {
                    missing += Math.max(0, ally.maxHp - ally.hp);
                }
            }
            boolean healCapped = MYSTIC_HEAL.id.equals(mob.lastMoveId) && mob.lastMoveStreak >= 2;
            if (missing >= 16 && !healCapped) {
                return MYSTIC_HEAL;
            }
            return weighted(w(MYSTIC_ATTACK, 60).maxStreak(2), w(MYSTIC_BUFF, 40).maxStreak(2))
                    .pick(s, mob);
        }
    };

    private static final Map<String, Script> SCRIPTS = new HashMap<String, Script>();

    static {
        // ── 第一幕 · 普通怪 ───────────────────────────────────────
        SCRIPTS.put("Cultist", firstThen(CULTIST_INCANTATION, cycle(CULTIST_DARK_STRIKE)));
        // 大颚虫：先啃咬，之后 45-30-25（咆哮/猛击/啃咬）；猛击不能连续三次，
        // 咆哮与啃咬不能连续两次（照 Bestiary 的 AI 描述，数据来源见 THIRD_PARTY_NOTICES.md）
        SCRIPTS.put("JawWorm", firstThen(JAW_CHOMP, weighted(
                w(JAW_BELLOW, 45).maxStreak(1),
                w(JAW_THRASH, 30).maxStreak(2),
                w(JAW_CHOMP, 25).maxStreak(1))));
        SCRIPTS.put("FuzzyLouseNormal", random(LOUSE_BITE, LOUSE_GROW));
        SCRIPTS.put("FuzzyLouseDefensive", random(LOUSE_BITE, LOUSE_SPIT));
        SCRIPTS.put("FungiBeast", random(FUNGUS_BITE, FUNGUS_GROW));
        SCRIPTS.put("AcidSlime_S", random(ACID_S_TACKLE, ACID_S_LICK));
        SCRIPTS.put("AcidSlime_M", random(ACID_M_SPIT, ACID_M_TACKLE, ACID_M_LICK));
        SCRIPTS.put("AcidSlime_L", random(ACID_L_SPIT, ACID_L_TACKLE, ACID_L_LICK));
        SCRIPTS.put("SpikeSlime_S", cycle(SPIKE_S_TACKLE));
        SCRIPTS.put("SpikeSlime_M", random(SPIKE_M_TACKLE, SPIKE_M_LICK));
        SCRIPTS.put("SpikeSlime_L", random(SPIKE_L_TACKLE, SPIKE_L_LICK));

        // ── 第一幕 · 地精小队 ─────────────────────────────────────
        SCRIPTS.put("GremlinWarrior", cycle(GREMLIN_SCRATCH));
        SCRIPTS.put("GremlinFat", cycle(GREMLIN_BLUNT));
        SCRIPTS.put("GremlinThief", cycle(GREMLIN_PUNCTURE));
        SCRIPTS.put("GremlinTsundere", random(GREMLIN_TSUNDERE_BLOCK, GREMLIN_TSUNDERE_BASH));
        SCRIPTS.put("GremlinWizard", GREMLIN_WIZARD);

        // ── 第一幕 · 精英 ─────────────────────────────────────────
        SCRIPTS.put("GremlinNob", firstThen(NOB_BELLOW,
                randomNoRepeat(NOB_RUSH, NOB_SKULL_BASH, NOB_BELLOW)));
        SCRIPTS.put("Lagavulin", LAGAVULIN);
        SCRIPTS.put("Sentry", cycle(SENTRY_BOLT, SENTRY_BEAM));
        SCRIPTS.put("SlaverBlue", random(SLAVER_BLUE_STAB, SLAVER_BLUE_RAKE));
        SCRIPTS.put("SlaverRed", random(SLAVER_RED_STAB, SLAVER_RED_SCRAPE, SLAVER_RED_ENTANGLE));

        // ── 第一幕 · 首领（近似）──────────────────────────────────
        SCRIPTS.put("TheGuardian", GUARDIAN);
        SCRIPTS.put("Hexaghost", HEXAGHOST);
        SCRIPTS.put("SlimeBoss", SLIME_BOSS);

        // ── 第二幕 ────────────────────────────────────────────────
        SCRIPTS.put("Byrd", BYRD);
        SCRIPTS.put("Chosen", CHOSEN);
        SCRIPTS.put("SnakePlant", weighted(
                w(SNAKE_CHOMP, 65).maxStreak(2),
                w(SNAKE_SPORES, 35).maxStreak(1)));
        SCRIPTS.put("SphericGuardian", SPHERIC_GUARDIAN);
        SCRIPTS.put("Centurion", CENTURION);
        SCRIPTS.put("Healer", MYSTIC);
    }

    public static boolean isModeled(String monsterId) {
        return SCRIPTS.containsKey(monsterId);
    }

    /**
     * 捕捉实机状态时的永久强化：某些怪物的增益在起手就生效并持续整场
     * （邪教徒的仪式、地精大块头的狂怒），从当前进度推断它是否已经拥有。
     */
    public static int persistentRitual(String monsterId, int cursor) {
        if ("Cultist".equals(monsterId) && cursor >= 1) {
            return 3; // 已经吟唱过，仪式持续每回合 +3 力量
        }
        return 0;
    }

    public static int persistentEnrage(String monsterId) {
        return "GremlinNob".equals(monsterId) ? 2 : 0;
    }

    /** 捕捉实机状态时的初始飞行层数（鸟自带 3 层飞行）。 */
    public static int initialFlight(String monsterId) {
        return "Byrd".equals(monsterId) ? 3 : 0;
    }

    /** 捕捉实机状态时：该怪物此刻是否处于睡眠（拉格威林前三个回合）。 */
    public static boolean sleepingNow(String monsterId, int cursor) {
        return "Lagavulin".equals(monsterId) && cursor < 3;
    }

    /**
     * 推进并返回该怪物「下一回合」要执行的招式；未建模返回 null（调用方回退到均值/节奏）。
     * 返回值不会修改怪物状态，状态的推进由调用方在招式执行后完成。
     */
    public static Move next(SimState s, SimState.Mob mob) {
        Script script = SCRIPTS.get(mob.id);
        if (script == null) {
            return null;
        }
        return script.pick(s, mob);
    }

    /**
     * 取「当前这一格已经亮出、但还没结算」的招式（下标 = aiCursor - 1）。
     * 捕捉实机状态时用它补上当前招式的非伤害效果（咆哮加力量、吟唱给仪式等）。
     */
    public static Move current(SimState s, SimState.Mob mob) {
        Script script = SCRIPTS.get(mob.id);
        if (script == null) {
            return null;
        }
        int saved = mob.aiCursor;
        mob.aiCursor = Math.max(0, saved - 1);
        Move move = script.pick(s, mob);
        mob.aiCursor = saved;
        return move;
    }

    /**
     * 取「与实机捕捉到的意图伤害相符」的当前招式。随机出招的怪物无法从实机反推是哪一招，
     * 这里用伤害值核对：对不上就不套用效果，避免把不存在的格挡 / 力量算进模拟。
     */
    public static Move currentMatching(SimState s, SimState.Mob mob, int capturedDamage) {
        Move cur = current(s, mob);
        if (cur == null) {
            return null;
        }
        if (cur.damage <= 0) {
            return capturedDamage <= 0 ? cur : null;
        }
        return (capturedDamage >= cur.damage && capturedDamage <= cur.damageMax) ? cur : null;
    }

    /** 招式执行完毕后推进游标与“连续使用”计数。 */
    public static void advance(SimState.Mob mob) {
        mob.aiCursor++;
        if (mob.intent != null && mob.intent.id.equals(mob.lastMoveId)) {
            mob.lastMoveStreak++;
        } else {
            mob.lastMoveStreak = 1;
            mob.lastMoveId = mob.intent == null ? "" : mob.intent.id;
        }
    }

    /** 死亡触发（真菌兽孢子云）。 */
    public static void onDeath(SimState s, SimState.Mob mob) {
        if ("FungiBeast".equals(mob.id)) {
            s.playerVulnerable += 2;
        }
    }

    /** 受到伤害时的反应：拉格威林被打醒，守护者累计 30 点伤害进入防御模式。 */
    public static void onDamaged(SimState s, SimState.Mob mob, int amount) {
        if (amount <= 0) {
            return;
        }
        mob.damageTakenTotal += amount;
        if ("Lagavulin".equals(mob.id) && mob.sleeping) {
            mob.sleeping = false;
            if (mob.aiCursor < 3) {
                mob.aiCursor = 3;
            }
        }
        if ("Byrd".equals(mob.id) && mob.flight > 0) {
            // 飞行：每被打中一次掉一层，掉光就落地并进入“失衡 → 头撞 → 重新起飞”
            mob.flight--;
            if (mob.flight == 0) {
                mob.aiState = 1;
                mob.aiCounter = mob.aiCursor;
            }
        }
        if ("TheGuardian".equals(mob.id) && mob.aiState == 0 && mob.damageTakenTotal >= 30) {
            mob.aiState = 1;
            mob.aiCounter = 0;
            mob.block += 20;
            mob.thorns = 3;
        }
    }

    /** 招式表里登记过的怪物名称，供离线自检与文档使用。 */
    public static java.util.List<String> modeledIds() {
        java.util.List<String> ids = new java.util.ArrayList<String>(SCRIPTS.keySet());
        java.util.Collections.sort(ids);
        return ids;
    }
}

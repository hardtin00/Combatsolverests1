package com.combatsolver1.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * 怪物行为的经验模型：按怪物种类累计“每回合实际造成的伤害”，
 * 用它估计后续回合的输出。这样像哨卫这种隔回合才攻击的怪，会在两三个回合内
 * 自动收敛到真实平均值，而不是一直按“每回合都打”高估。
 *
 * 说明：原项目是逐个怪物还原 AI 行动循环；一代这里先用经验统计近似，
 * 属于可收敛的保守方法，后续再换成按怪物建表。
 */
public final class EnemyModel {
    private EnemyModel() {
    }

    private static final Map<String, Stat> STATS = new HashMap<>();

    private static class Stat {
        int turns;
        int damageSum;
        int lastObservedTurn = Integer.MIN_VALUE;
        final java.util.List<Boolean> attacks = new java.util.ArrayList<>();
    }

    public static void reset() {
        STATS.clear();
    }

    /** 每个回合记录一次观察：这一格意图是否为攻击，伤害多少。 */
    public static void observe(String monsterId, int turn, int damage) {
        Stat s = STATS.get(monsterId);
        if (s == null) {
            s = new Stat();
            STATS.put(monsterId, s);
        }
        if (s.lastObservedTurn == turn) {
            return;
        }
        s.lastObservedTurn = turn;
        s.turns++;
        s.damageSum += Math.max(0, damage);
        s.attacks.add(damage > 0);
    }

    /** 估计该种类怪物后续回合的平均输出；没有样本返回 0（由调用方退回当前意图）。 */
    public static int average(String monsterId) {
        Stat s = STATS.get(monsterId);
        if (s == null || s.turns == 0) {
            return 0;
        }
        return Math.max(0, s.damageSum / s.turns);
    }

    public static int samples(String monsterId) {
        Stat s = STATS.get(monsterId);
        return s == null ? 0 : s.turns;
    }

    /**
     * 从已观察到的“是否出手”序列里识别周期，返回形如 "AN" 的节奏串。
     * 识别不出（样本太少或不成周期）返回 null，由调用方退回出牌表或平均值。
     *
     * 这是对“出牌表是我猜的”这件事的自我校正：打上几回合后，模拟用的是这台机器上
     * 真实发生的节奏。
     */
    public static String detectCycle(String monsterId) {
        Stat s = STATS.get(monsterId);
        if (s == null || s.attacks.size() < 4) {
            return null;
        }
        java.util.List<Boolean> seq = s.attacks;
        for (int p = 1; p <= 4; p++) {
            if (seq.size() < p * 2) {
                continue;
            }
            boolean consistent = true;
            for (int i = p; i < seq.size(); i++) {
                if (!seq.get(i).equals(seq.get(i - p))) {
                    consistent = false;
                    break;
                }
            }
            if (consistent) {
                StringBuilder sb = new StringBuilder();
                for (int i = seq.size() - p; i < seq.size(); i++) {
                    sb.append(seq.get(i) ? 'A' : 'N');
                }
                return sb.toString();
            }
        }
        return null;
    }
}

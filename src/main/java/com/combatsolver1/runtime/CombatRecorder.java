package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.SimState;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

/**
 * 差分验证语料记录器。
 *
 * 每次实机出牌，记录“出牌前状态 + 牌 + 出牌后实际结果”一行；离线工具把同样的
 * 前状态喂给模拟器重放同一张牌，逐字段比对，即可定位到具体哪张牌/哪个效果算错。
 *
 * 记录格式（制表符分隔）：
 *   cardId  cost  preHp  preBlock  preEnergy  preHand(逗号)  preMobs(id:hp:block:vuln;)
 *   postHp  postBlock  postEnergy  postMobs(...)
 */
public final class CombatRecorder {
    private CombatRecorder() {
    }

    private static SimState pre;
    private static String pendingCardId;
    private static int pendingCost;
    private static int pendingDamage;
    private static int pendingBlock;
    private static boolean enabled = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 出牌瞬间：记下出牌前的稳定状态。 */
    public static void onCardUsed(String cardId, int cost, int damage, int block) {
        if (!enabled) {
            return;
        }
        SimState snap = LiveCapture.captureRoot();
        if (snap == null) {
            return;
        }
        pre = snap;
        pendingCardId = cardId;
        pendingCost = cost;
        pendingDamage = damage;
        pendingBlock = block;
    }

    /** 动作结算完毕、回到稳定状态时：写入一条记录。 */
    public static void onIdle() {
        if (!enabled || pre == null || pendingCardId == null) {
            return;
        }
        SimState post = LiveCapture.captureRoot();
        if (post == null) {
            pre = null;
            pendingCardId = null;
            return;
        }
        try {
            write(pre, pendingCardId, pendingCost, pendingDamage, pendingBlock, post);
        } catch (Throwable t) {
            CombatLog.error("record failed", t);
        }
        pre = null;
        pendingCardId = null;
    }

    private static void write(SimState pre, String cardId, int cost, int damage, int block,
                              SimState post) throws Exception {
        File dir = new File(System.getProperty("user.home", "."), ".combatsolver1");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        File f = new File(dir, "combat-records.tsv");
        Writer w = new FileWriter(f, true);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(cardId).append('\t').append(cost).append('\t')
              .append(damage).append('\t').append(block).append('\t');
            sb.append(pre.hp).append('\t').append(pre.block).append('\t').append(pre.energy).append('\t');
            for (SimState.Card c : pre.hand) {
                sb.append(c.id).append(',');
            }
            sb.append('\t');
            appendMobs(sb, pre);
            sb.append('\t');
            sb.append(post.hp).append('\t').append(post.block).append('\t').append(post.energy).append('\t');
            appendMobs(sb, post);
            sb.append('\n');
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    private static void appendMobs(StringBuilder sb, SimState s) {
        for (SimState.Mob m : s.mobs) {
            sb.append(m.id).append(':').append(m.hp).append(':')
              .append(m.block).append(':').append(m.vulnerable).append(';');
        }
    }
}

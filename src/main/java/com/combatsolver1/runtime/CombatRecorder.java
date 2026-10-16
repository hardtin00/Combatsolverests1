package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.SimState;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 差分验证语料记录器（实机一侧）。
 *
 * 每次实机出牌，记录「出牌前状态 + 这张牌 + 出牌后实际状态」，按 JSONL 追加到
 * {@code ~/.combatsolver1/recordings/combat-<时间戳>.jsonl}。离线工具
 * （tools/solvertest 的 diff 命令）会用同样的前状态喂给模拟器重放这张牌，
 * 与实机结果逐字段比对，从而把「哪里算错了」定位到具体字段。
 *
 * 记录格式（每行一个 JSON 对象）：
 *   {"type":"root",   "state":{完整根状态}}                     —— 每场战斗第一条
 *   {"type":"card",   "card":{id,cost,damage,block,hits},
 *                     "target":0,"pre":{状态},"post":{状态}}      —— 每次出牌
 *   {"type":"endTurn","pre":{状态},"post":{状态}}                —— 每次回合切换
 */
public final class CombatRecorder {
    private CombatRecorder() {
    }

    private static boolean enabled = true;
    private static Writer writer;
    private static File file;

    private static SimState pre;
    private static String pendingCardId;
    private static int pendingCost;
    private static int pendingDamage;
    private static int pendingBlock;
    private static int pendingTarget = -1;

    private static SimState lastState;
    private static int lastTurn = Integer.MIN_VALUE;
    private static String battleKey = "";
    private static boolean rootPending = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 当前记录文件路径（供面板 / 日志显示）。 */
    public static String currentPath() {
        return file == null ? "(未开始记录)" : file.getAbsolutePath();
    }

    /** 出牌瞬间：记下出牌前的稳定状态。 */
    public static void onCardUsed(String cardId, int cost, int damage, int block, int targetIndex) {
        if (!enabled) {
            return;
        }
        SimState snap = LiveCapture.captureRoot();
        if (snap == null) {
            return;
        }
        // 上一张牌还没等到稳定帧就又出了牌：说明中间的状态抓不稳，
        // 与其静默覆盖、让 pre/post 配错，不如把上一条标记为“未配对”如实写出来。
        if (pendingCardId != null && pre != null) {
            try {
                writeLine("{\"type\":\"card\",\"paired\":false,\"card\":{\"id\":\"" + esc(pendingCardId)
                        + "\",\"cost\":" + pendingCost + ",\"damage\":" + pendingDamage
                        + ",\"block\":" + pendingBlock + "},\"target\":" + pendingTarget
                        + ",\"pre\":" + stateJson(pre) + ",\"post\":null}");
            } catch (Throwable ignored) {
                // 写不进去也不能影响战斗
            }
        }
        pre = snap;
        pendingCardId = cardId;
        pendingCost = cost;
        pendingDamage = damage;
        pendingBlock = block;
        pendingTarget = targetIndex;
    }

    /** 动作结算完毕、回到稳定状态时：写出一条记录。 */
    public static void onIdle(boolean stable) {
        if (!enabled) {
            return;
        }
        if (!stable) {
            return; // 动作还在跑：这时抓到的不是出牌后的稳定状态
        }
        SimState now = LiveCapture.captureRoot();
        if (now == null) {
            return;
        }
        try {
            String key = battleKeyOf(now);
            if (!key.equals(battleKey)) {
                battleKey = key;
                lastTurn = Integer.MIN_VALUE;
                rootPending = true;
                pendingCardId = null;
            }
            if (rootPending) {
                writeLine("{\"type\":\"root\",\"engine\":\"" + esc(EngineBridge.describe())
                        + "\",\"state\":" + stateJson(now) + "}");
                rootPending = false;
                lastState = now;
                lastTurn = now.turn;
                return;
            }
            if (pendingCardId != null && pre != null) {
                writeLine("{\"type\":\"card\",\"paired\":true,\"card\":{\"id\":\"" + esc(pendingCardId)
                        + "\",\"cost\":" + pendingCost + ",\"damage\":" + pendingDamage
                        + ",\"block\":" + pendingBlock + "},\"target\":" + pendingTarget + ",\"pre\":"
                        + stateJson(pre) + ",\"post\":" + stateJson(now) + "}");
                pendingCardId = null;
            } else if (lastState != null && now.turn > lastTurn) {
                writeLine("{\"type\":\"endTurn\",\"pre\":" + stateJson(lastState)
                        + ",\"post\":" + stateJson(now) + "}");
            }
            lastState = now;
            lastTurn = now.turn;
        } catch (Throwable t) {
            CombatLog.error("record failed", t);
        }
    }

    /** 战斗结束：换文件，避免把不同战斗混在一起。 */
    public static void onBattleEnd() {
        battleKey = "";
        rootPending = true;
        pendingCardId = null;
        pre = null;
        lastState = null;
        closeWriter();
    }

    private static String battleKeyOf(SimState s) {
        StringBuilder sb = new StringBuilder();
        for (SimState.Mob m : s.mobs) {
            sb.append(m.id).append('+').append(m.maxHp).append(';');
        }
        return sb.toString();
    }

    private static void writeLine(String line) throws Exception {
        if (writer == null) {
            File dir = new File(System.getProperty("user.home", "."), ".combatsolver1/recordings");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            file = new File(dir, "combat-" + stamp + ".jsonl");
            writer = new FileWriter(file, true);
        }
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static void closeWriter() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Throwable ignored) {
            // 关闭失败不影响战斗
        }
        writer = null;
        file = null;
    }

    // ── 状态序列化 ──────────────────────────────────────────────

    /** 完整根状态（每场战斗的第一条 root 记录）。 */
    private static String stateJson(SimState s) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"turn\":").append(s.turn);
        sb.append(",\"hp\":").append(s.hp);
        sb.append(",\"maxHp\":").append(s.maxHp);
        sb.append(",\"block\":").append(s.block);
        sb.append(",\"energy\":").append(s.energy);
        sb.append(",\"energyPerTurn\":").append(s.energyPerTurn);
        sb.append(",\"handSize\":").append(s.handSize);
        sb.append(",\"playerVuln\":").append(s.playerVulnerable);
        sb.append(",\"playerWeak\":").append(s.playerWeak);
        sb.append(",\"playerFrail\":").append(s.playerFrail);
        sb.append(",\"playerEntangled\":").append(s.playerEntangled);
        sb.append(",\"playerIntangible\":").append(s.playerIntangible);
        sb.append(",\"playerArtifact\":").append(s.playerArtifact);
        sb.append(",\"playerPlatedArmor\":").append(s.playerPlatedArmor);
        sb.append(",\"constricted\":").append(s.constricted);
        sb.append(",\"blockLock\":").append(s.blockLock);
        sb.append(",\"powers\":{");
        sb.append("\"metallicize\":").append(s.pMetallicize)
          .append(",\"feelNoPain\":").append(s.pFeelNoPain)
          .append(",\"darkEmbrace\":").append(s.pDarkEmbrace)
          .append(",\"demonForm\":").append(s.pDemonForm)
          .append(",\"juggernaut\":").append(s.pJuggernaut)
          .append(",\"combust\":").append(s.pCombust)
          .append(",\"rupture\":").append(s.pRupture)
          .append(",\"thorns\":").append(s.pThorns)
          .append(",\"doubleTap\":").append(s.pDoubleTap)
          .append(",\"rage\":").append(s.pRage)
          .append(",\"berserk\":").append(s.pBerserk)
          .append(",\"brutality\":").append(s.pBrutality)
          .append(",\"barricade\":").append(s.pBarricade)
          .append(",\"corruption\":").append(s.pCorruption)
          .append(",\"panache\":").append(s.pPanache)
          .append(",\"sadistic\":").append(s.pSadistic)
          .append(",\"magnetism\":").append(s.pMagnetism)
          .append(",\"mayhem\":").append(s.pMayhem)
          .append(",\"omega\":").append(s.pOmega).append('}');
        sb.append(",\"hand\":[");
        for (int i = 0; i < s.hand.size(); i++) {
            SimState.Card c = s.hand.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(esc(c.id)).append("\",\"cost\":").append(c.cost)
              .append(",\"damage\":").append(c.damage)
              .append(",\"block\":").append(c.block)
              .append(",\"hits\":").append(c.hits).append('}');
        }
        sb.append("],\"drawPile\":[");
        appendIds(sb, s.drawPile);
        sb.append("],\"discardPile\":[");
        appendIds(sb, s.discardPile);
        sb.append("],\"exhaustPile\":[");
        appendIds(sb, s.exhaustPile);
        sb.append("],\"relics\":[");
        for (int i = 0; i < s.relics.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(s.relics.get(i))).append('"');
        }
        sb.append("],\"potions\":[");
        for (int i = 0; i < s.potionIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(s.potionIds.get(i))).append('"');
        }
        sb.append("],\"mobs\":[");
        for (int i = 0; i < s.mobs.size(); i++) {
            SimState.Mob m = s.mobs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(esc(m.id)).append("\",\"name\":\"").append(esc(m.name))
              .append("\",\"hp\":").append(m.hp)
              .append(",\"maxHp\":").append(m.maxHp)
              .append(",\"block\":").append(m.block)
              .append(",\"str\":").append(m.strength)
              .append(",\"vuln\":").append(m.vulnerable)
              .append(",\"weak\":").append(m.weak)
              .append(",\"dmg\":").append(m.atkDamage)
              .append(",\"hits\":").append(m.atkHits)
              .append(",\"cursor\":").append(m.aiCursor)
              .append(",\"artifact\":").append(m.artifact)
              .append(",\"malleable\":").append(m.malleable)
              .append(",\"platedArmor\":").append(m.platedArmor)
              .append(",\"intangible\":").append(m.intangible)
              .append(",\"invincible\":").append(m.invincible)
              .append(",\"beatOfDeath\":").append(m.beatOfDeath)
              .append(",\"timeWarp\":").append(m.timeWarp)
              .append(",\"alive\":").append(m.alive).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendIds(StringBuilder sb, java.util.List<SimState.Card> pile) {
        for (int i = 0; i < pile.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(pile.get(i).id)).append('"');
        }
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

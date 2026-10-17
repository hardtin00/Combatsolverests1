package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.SimState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

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
    private static long battleSequence;
    private static int transitionTurn = Integer.MIN_VALUE;
    private static int transitionStableFrames;
    private static final TurnTransitionTracker turnTracker = new TurnTransitionTracker();

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 生命周期钩子：战斗开始即建立新身份，root 等首个稳定状态再写出。 */
    public static void onBattleStart() {
        if (!enabled) return;
        closeWriter();
        battleSequence++;
        battleKey = "battle-" + battleSequence;
        rootPending = true;
        pendingCardId = null;
        pre = null;
        lastState = null;
        lastTurn = Integer.MIN_VALUE;
        transitionTurn = Integer.MIN_VALUE;
        transitionStableFrames = 0;
        turnTracker.reset();
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
        turnTracker.actionStarted(snap);
        // 上一张牌还没被稳定帧收尾就又出了牌。
        // 关键点：游戏只有在上一张**完全结算**之后才允许打出下一张，
        // 所以此刻抓到的状态就是上一张牌可靠的“出牌后”状态——用它收尾，配对成功。
        // （稳定帧判据太严时配对率只有三分之一，差分就没法当依据了。）
        if (pendingCardId != null && pre != null) {
            try {
                writeLine("{\"type\":\"card\",\"paired\":true,\"card\":{\"id\":\"" + esc(pendingCardId)
                        + "\",\"cost\":" + pendingCost + ",\"damage\":" + pendingDamage
                        + ",\"block\":" + pendingBlock + "},\"target\":" + pendingTarget
                        + ",\"pre\":" + stateJson(pre) + ",\"post\":" + stateJson(snap) + "}");
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
            if (rootPending) {
                writeLine("{\"type\":\"root\",\"battleKey\":\"" + esc(battleKey)
                        + "\",\"engine\":\"" + esc(EngineBridge.describe())
                        + "\",\"state\":" + stateJson(now) + "}");
                rootPending = false;
                lastState = now;
                lastTurn = now.turn;
                turnTracker.reset();
                turnTracker.observe(now);
                return;
            }
            if (pendingCardId != null && pre != null) {
                writeLine("{\"type\":\"card\",\"paired\":true,\"card\":{\"id\":\"" + esc(pendingCardId)
                        + "\",\"cost\":" + pendingCost + ",\"damage\":" + pendingDamage
                        + ",\"block\":" + pendingBlock + "},\"target\":" + pendingTarget + ",\"pre\":"
                        + stateJson(pre) + ",\"post\":" + stateJson(now) + "}");
                pendingCardId = null;
            } else {
                TurnTransitionTracker.EndTurn end = turnTracker.observe(now);
                if (end != null) {
                    writeLine("{\"type\":\"endTurn\",\"phase\":\"turn-transition\",\"pre\":" + stateJson(end.pre)
                            + ",\"post\":" + stateJson(end.post) + "}");
                }
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
        transitionTurn = Integer.MIN_VALUE;
        transitionStableFrames = 0;
        turnTracker.reset();
        closeWriter();
    }

    /** Small state machine kept separate so the multi-frame transition is regression-testable. */
    static final class TurnTransitionTracker {
        private SimState last;
        private int activeTurn = Integer.MIN_VALUE;
        private int stableFrames;
        private int writtenTurn = Integer.MIN_VALUE;

        void reset() { last = null; activeTurn = Integer.MIN_VALUE; stableFrames = 0; writtenTurn = Integer.MIN_VALUE; }
        void actionStarted(SimState now) {
            if (now == null) return;
            last = now;
            activeTurn = Integer.MIN_VALUE;
            stableFrames = 0;
        }
        EndTurn observe(SimState now) {
            if (now == null) return null;
            if (last == null) { last = now; return null; }
            if (activeTurn == Integer.MIN_VALUE) {
                if (now.turn > last.turn) { activeTurn = now.turn; stableFrames = 1; return null; }
                last = now;
                return null;
            }
            if (now.turn != activeTurn) {
                activeTurn = Integer.MIN_VALUE; stableFrames = 0; last = now; return null;
            }
            stableFrames++;
            EndTurn result = null;
            if (stableFrames >= 2 && writtenTurn != activeTurn) {
                result = new EndTurn(last, now);
                writtenTurn = activeTurn;
                activeTurn = Integer.MIN_VALUE;
                stableFrames = 0;
            }
            last = now;
            return result;
        }
        static final class EndTurn {
            final SimState pre, post;
            EndTurn(SimState pre, SimState post) { this.pre = pre; this.post = post; }
        }
    }

    private static void writeLine(String line) throws Exception {
        if (writer == null) {
            File dir = new File(System.getProperty("user.home", "."), ".combatsolver1/recordings");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            file = new File(dir, "combat-" + stamp + ".jsonl");
            writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
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
              .append(",\"hits\":").append(c.hits)
              // 同时记下“这张牌的数值是怎么来的”：live = 实机当前生效值，base = 基础值回退。
              // 差分工具遇到两者不一致时能直接说明原因，而不是猜。
              .append(",\"valueFrom\":\"").append(c.damage > 0 ? "live" : "base").append('"')
              .append('}');
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

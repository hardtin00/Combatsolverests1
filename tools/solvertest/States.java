import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 实机记录中的状态 JSON ↔ SimState 的互转，以及「可比较快照」的构造。
 * 比对口径：生命 / 格挡 / 能量 / 手牌（多集）/ 三个牌堆数量 / 每个怪物的血量格挡易伤存活。
 */
public final class States {

    private States() {
    }

    // ── JSON → SimState ────────────────────────────────────────

    public static SimState toSimState(Map<String, Object> state) {
        SimState s = new SimState();
        s.turn = Json.num(state, "turn", 1);
        s.hp = Json.num(state, "hp", 80);
        s.maxHp = Json.num(state, "maxHp", 80);
        s.block = Json.num(state, "block", 0);
        s.energy = Json.num(state, "energy", 3);
        s.energyPerTurn = Json.num(state, "energyPerTurn", 3);
        s.handSize = Json.num(state, "handSize", 5);
        s.playerVulnerable = Json.num(state, "playerVuln", 0);
        s.playerWeak = Json.num(state, "playerWeak", 0);
        s.playerFrail = Json.num(state, "playerFrail", 0);
        s.playerEntangled = Json.num(state, "playerEntangled", 0);
        s.playerIntangible = Json.num(state, "playerIntangible", 0);
        s.playerArtifact = Json.num(state, "playerArtifact", 0);
        s.playerPlatedArmor = Json.num(state, "playerPlatedArmor", 0);
        s.constricted = Json.num(state, "constricted", 0);
        s.blockLock = Json.num(state, "blockLock", 0);
        Map<String, Object> powers = Json.obj(state, "powers");
        s.pMetallicize = Json.num(powers, "metallicize", 0);
        s.pFeelNoPain = Json.num(powers, "feelNoPain", 0);
        s.pDarkEmbrace = Json.num(powers, "darkEmbrace", 0);
        s.pDemonForm = Json.num(powers, "demonForm", 0);
        s.pJuggernaut = Json.num(powers, "juggernaut", 0);
        s.pCombust = Json.num(powers, "combust", 0);
        s.pRupture = Json.num(powers, "rupture", 0);
        s.pThorns = Json.num(powers, "thorns", 0);
        s.pDoubleTap = Json.num(powers, "doubleTap", 0);
        s.pRage = Json.num(powers, "rage", 0);
        s.pBerserk = Json.num(powers, "berserk", 0);
        s.pBrutality = Json.num(powers, "brutality", 0);
        s.pBarricade = Json.bool(powers, "barricade", false);
        s.pCorruption = Json.bool(powers, "corruption", false);
        s.pPanache = Json.num(powers, "panache", 0);
        s.pSadistic = Json.num(powers, "sadistic", 0);
        s.pMagnetism = Json.num(powers, "magnetism", 0);
        s.pMayhem = Json.num(powers, "mayhem", 0);
        s.pOmega = Json.num(powers, "omega", 0);
        s.rngState = 0x2545F4914F6CDD1DL;
        for (Object o : Json.list(state, "hand")) {
            s.hand.add(card(Json.asMap(o)));
        }
        for (Object o : Json.list(state, "drawPile")) {
            s.drawPile.add(byId(String.valueOf(o)));
        }
        for (Object o : Json.list(state, "discardPile")) {
            s.discardPile.add(byId(String.valueOf(o)));
        }
        for (Object o : Json.list(state, "exhaustPile")) {
            s.exhaustPile.add(byId(String.valueOf(o)));
        }
        for (Object o : Json.list(state, "relics")) {
            s.relics.add(String.valueOf(o));
        }
        for (Object o : Json.list(state, "potions")) {
            s.potionIds.add(String.valueOf(o));
        }
        for (Object o : Json.list(state, "mobs")) {
            Map<String, Object> mob = Json.asMap(o);
            SimState.Mob m = new SimState.Mob(
                    Json.str(mob, "id", "?"),
                    Json.str(mob, "name", Json.str(mob, "id", "?")),
                    Json.num(mob, "hp", 1),
                    Json.num(mob, "maxHp", 1),
                    Json.num(mob, "block", 0),
                    Json.num(mob, "dmg", 0),
                    Json.num(mob, "hits", 1),
                    Json.num(mob, "str", 0),
                    Json.bool(mob, "alive", true));
            m.vulnerable = Json.num(mob, "vuln", 0);
            m.weak = Json.num(mob, "weak", 0);
            // 游标：实机记录里是 moveHistory 大小（当前招式已记入），缺省按第 1 回合处理
            m.aiCursor = Json.num(mob, "cursor", 1);
            m.pattern = Json.str(mob, "pattern", "");
            m.artifact = Json.num(mob, "artifact", 0);
            m.malleable = Json.num(mob, "malleable", 0);
            m.platedArmor = Json.num(mob, "platedArmor", 0);
            m.intangible = Json.num(mob, "intangible", 0);
            m.invincible = Json.num(mob, "invincible", 0);
            m.beatOfDeath = Json.num(mob, "beatOfDeath", 0);
            m.timeWarp = Json.num(mob, "timeWarp", 0);
            s.mobs.add(m);
        }
        // 初始化怪物 AI（下一回合招式 / 睡眠 / 永久强化）
        CombatEngine.initMonsters(s);
        return s;
    }

    private static SimState.Card card(Map<String, Object> c) {
        String id = Json.str(c, "id", "?");
        int cost = Json.num(c, "cost", 0);
        int damage = Json.num(c, "damage", 0);
        int block = Json.num(c, "block", 0);
        int hits = Json.num(c, "hits", 0);
        return CardLibrary.toSimCard(id, id, cost, damage, block, hits,
                damage > 0, false, true, !CardLibrary.isKnown(id));
    }

    private static SimState.Card byId(String id) {
        return CardLibrary.toSimCard(id, id, 0, 0, 0, 0, false, false, false,
                !CardLibrary.isKnown(id));
    }

    // ── 可比较快照 ─────────────────────────────────────────────

    public static final class Snapshot {
        public int turn;
        public int hp;
        public int block;
        public int energy;
        public String playerEffects;
        public final List<String> hand = new ArrayList<String>();
        public int draw;
        public int discard;
        public int exhaust;
        public final List<String> mobs = new ArrayList<String>();
        /** 两个牌堆的牌身份（排序后比较，顺序交由记录侧单独核对）。 */
        public final List<String> drawIds = new ArrayList<String>();
        public final List<String> discardIds = new ArrayList<String>();

        public List<String> handSorted() {
            List<String> copy = new ArrayList<String>(hand);
            Collections.sort(copy);
            return copy;
        }
    }

    public static Snapshot of(SimState s) {
        Snapshot snap = new Snapshot();
        snap.turn = s.turn;
        snap.hp = s.hp;
        snap.block = s.block;
        snap.energy = s.energy;
        snap.playerEffects = playerEffects(s);
        for (SimState.Card c : s.hand) {
            snap.hand.add(c.id);
        }
        for (SimState.Card c : s.drawPile) {
            snap.drawIds.add(c.id);
        }
        for (SimState.Card c : s.discardPile) {
            snap.discardIds.add(c.id);
        }
        Collections.sort(snap.drawIds);
        Collections.sort(snap.discardIds);
        snap.draw = s.drawPile.size();
        snap.discard = s.discardPile.size();
        snap.exhaust = s.exhaustPile.size();
        for (SimState.Mob m : s.mobs) {
            snap.mobs.add(m.id + ":" + m.hp + ":" + m.block + ":" + m.vulnerable + ":" + m.weak
                    + ":" + m.strength + ":" + m.artifact + ":" + m.malleable
                    + ":" + m.platedArmor + ":" + m.intangible + ":" + m.invincible
                    + ":" + m.beatOfDeath + ":" + m.timeWarp + (m.alive ? "" : ":dead"));
        }
        return snap;
    }

    private static String playerEffects(SimState s) {
        return s.playerVulnerable + ":" + s.playerWeak + ":" + s.playerFrail + ":"
                + s.playerEntangled + ":" + s.playerIntangible + ":" + s.playerArtifact + ":"
                + s.playerPlatedArmor + ":" + s.constricted + ":" + s.blockLock + ":"
                + s.pMetallicize + ":" + s.pFeelNoPain + ":" + s.pDarkEmbrace + ":"
                + s.pDemonForm + ":" + s.pJuggernaut + ":" + s.pCombust + ":" + s.pRupture + ":"
                + s.pThorns + ":" + s.pDoubleTap + ":" + s.pRage + ":" + s.pBerserk + ":"
                + s.pBrutality + ":" + s.pBarricade + ":" + s.pCorruption + ":" + s.pPanache + ":"
                + s.pSadistic + ":" + s.pMagnetism + ":" + s.pMayhem + ":" + s.pOmega;
    }

    private static String playerEffects(Map<String, Object> state) {
        Map<String, Object> powers = Json.obj(state, "powers");
        return Json.num(state, "playerVuln", 0) + ":" + Json.num(state, "playerWeak", 0) + ":"
                + Json.num(state, "playerFrail", 0) + ":" + Json.num(state, "playerEntangled", 0) + ":"
                + Json.num(state, "playerIntangible", 0) + ":" + Json.num(state, "playerArtifact", 0) + ":"
                + Json.num(state, "playerPlatedArmor", 0) + ":" + Json.num(state, "constricted", 0) + ":"
                + Json.num(state, "blockLock", 0) + ":" + Json.num(powers, "metallicize", 0) + ":"
                + Json.num(powers, "feelNoPain", 0) + ":" + Json.num(powers, "darkEmbrace", 0) + ":"
                + Json.num(powers, "demonForm", 0) + ":" + Json.num(powers, "juggernaut", 0) + ":"
                + Json.num(powers, "combust", 0) + ":" + Json.num(powers, "rupture", 0) + ":"
                + Json.num(powers, "thorns", 0) + ":" + Json.num(powers, "doubleTap", 0) + ":"
                + Json.num(powers, "rage", 0) + ":" + Json.num(powers, "berserk", 0) + ":"
                + Json.num(powers, "brutality", 0) + ":" + Json.bool(powers, "barricade", false) + ":"
                + Json.bool(powers, "corruption", false) + ":" + Json.num(powers, "panache", 0) + ":"
                + Json.num(powers, "sadistic", 0) + ":" + Json.num(powers, "magnetism", 0) + ":"
                + Json.num(powers, "mayhem", 0) + ":" + Json.num(powers, "omega", 0);
    }

    public static Snapshot ofJson(Map<String, Object> state) {
        Snapshot snap = new Snapshot();
        snap.turn = Json.num(state, "turn", 0);
        snap.hp = Json.num(state, "hp", 0);
        snap.block = Json.num(state, "block", 0);
        snap.energy = Json.num(state, "energy", 0);
        snap.playerEffects = playerEffects(state);
        for (Object o : Json.list(state, "hand")) {
            snap.hand.add(Json.str(Json.asMap(o), "id", "?"));
        }
        for (Object o : Json.list(state, "drawPile")) {
            snap.drawIds.add(String.valueOf(o));
        }
        for (Object o : Json.list(state, "discardPile")) {
            snap.discardIds.add(String.valueOf(o));
        }
        Collections.sort(snap.drawIds);
        Collections.sort(snap.discardIds);
        snap.draw = Json.list(state, "drawPile").size();
        snap.discard = Json.list(state, "discardPile").size();
        snap.exhaust = Json.list(state, "exhaustPile").size();
        for (Object o : Json.list(state, "mobs")) {
            Map<String, Object> m = Json.asMap(o);
            boolean alive = Json.bool(m, "alive", true);
            snap.mobs.add(Json.str(m, "id", "?") + ":" + Json.num(m, "hp", 0) + ":"
                    + Json.num(m, "block", 0) + ":" + Json.num(m, "vuln", 0) + ":"
                    + Json.num(m, "weak", 0) + ":" + Json.num(m, "str", 0)
                    + ":" + Json.num(m, "artifact", 0) + ":" + Json.num(m, "malleable", 0)
                    + ":" + Json.num(m, "platedArmor", 0) + ":" + Json.num(m, "intangible", 0)
                    + ":" + Json.num(m, "invincible", 0) + ":" + Json.num(m, "beatOfDeath", 0)
                    + ":" + Json.num(m, "timeWarp", 0) + (alive ? "" : ":dead"));
        }
        return snap;
    }
}

import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.Plan;
import com.combatsolver1.search.PlanReducer;

import java.util.ArrayList;
import java.util.List;

/**
 * W7「计划精简」离线自检（L2）。
 *
 * <p>构造一个手工计划验证 PlanReducer 的四条核心性质：
 * ① 能删掉冗余动作；② 不删致胜必需动作（失败回滚）；③ 有界（不超 MAX_ATTEMPTS/MAX_REPLAYS）；
 * ④ 不修改传入的原计划；⑤ 原计划自身不可重放时安全返回、不抛异常。
 * 纯离线，不接入交付路径，不改 W0 指标。
 */
public final class PlanReducerCheck {

    private PlanReducerCheck() { }

    private static int pass;
    private static int fail;

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    private static SimState.Card card(String id, int cost, int dmg, int block) {
        return CardLibrary.toSimCard(id, id, cost, dmg, block, 0,
                dmg > 0, false, dmg > 0, false);
    }

    private static SimState root(int mobHp) {
        SimState s = new SimState();
        s.hp = 70;
        s.maxHp = 80;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.handSize = 5;
        s.turn = 1;
        s.rngState = 0x2545F4914F6CDD1DL;
        s.hand.add(card("Strike_R", 1, 6, 0));   // 致胜牌
        s.hand.add(card("Defend_R", 1, 0, 5));   // 冗余牌
        SimState.Mob m = new SimState.Mob("JawWorm", "JawWorm", mobHp, mobHp, 0, 0, 1, 0, true);
        m.aiCursor = 1;
        s.mobs.add(m);
        CombatEngine.initMonsters(s);
        return s;
    }

    private static Plan.Turn turnWith(String... cardIds) {
        Plan.Turn t = new Plan.Turn();
        t.turnNumber = 1;
        for (int i = 0; i < cardIds.length; i++) {
            Plan.Action a = new Plan.Action(cardIds[i], cardIds[i], 0, 1);
            a.handIndex = i;
            t.actions.add(a);
        }
        return t;
    }

    private static int actions(List<Plan.Turn> turns) {
        int n = 0;
        for (Plan.Turn t : turns) n += t.actions.size();
        return n;
    }

    public static void main(String[] args) {
        // A. 冗余动作（Defend_R）应被删，致胜动作（Strike_R）保留
        List<Plan.Turn> plan = new ArrayList<Plan.Turn>();
        plan.add(turnWith("Strike_R", "Defend_R"));
        SimState root = root(6);
        PlanReducer.Result r = PlanReducer.reduce(root, plan);
        check(r.removed() >= 1, "能删掉冗余动作", "removed=" + r.removed());
        check(actions(r.turns) < actions(plan), "精简后动作数减少",
                actions(plan) + " -> " + actions(r.turns));
        boolean strikeKept = false;
        for (Plan.Turn t : r.turns) {
            for (Plan.Action a : t.actions) if ("Strike_R".equals(a.cardId)) strikeKept = true;
        }
        check(strikeKept, "致胜必需动作未被删（失败回滚生效）", "Strike_R kept");
        check(actions(plan) == 2, "原计划对象未被修改", "source still 2 actions");

        // B. 有界
        check(r.attempts <= PlanReducer.MAX_ATTEMPTS && r.replays <= PlanReducer.MAX_REPLAYS,
                "尝试/重放次数在上限内",
                "attempts=" + r.attempts + " replays=" + r.replays);

        // C. 精简后重放仍获胜（等价判据真的成立，不是靠跳过动作）
        SimState after = root.fork();
        for (Plan.Turn t : r.turns) {
            for (Plan.Action a : t.actions) {
                int idx = -1;
                for (int i = 0; i < after.hand.size(); i++) {
                    if (a.cardId.equals(after.hand.get(i).id)) { idx = i; break; }
                }
                if (idx >= 0) CombatEngine.playCard(after, after.hand.remove(idx), a.targetIndex);
            }
            CombatEngine.endTurn(after);
        }
        check(after.won(), "精简后路线原样重放仍获胜", "won=true");

        // D. 「必需动作不可删」：怪物血厚，两张牌都不足以赢 → 不应删任何动作
        List<Plan.Turn> hard = new ArrayList<Plan.Turn>();
        hard.add(turnWith("Strike_R", "Defend_R"));
        PlanReducer.Result r2 = PlanReducer.reduce(root(60), hard);
        check(r2.removed() == 0, "未获胜时不做任何精简", "removed=0");

        // E. 原计划自身不可重放（手牌里没有计划要的牌）→ 安全返回，不抛异常
        List<Plan.Turn> broken = new ArrayList<Plan.Turn>();
        broken.add(turnWith("NOT_IN_HAND"));
        PlanReducer.Result r3 = PlanReducer.reduce(root(6), broken);
        check(r3 != null && r3.removed() == 0, "不可重放的原计划安全返回", "removed=0");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；PlanReducer 未接入交付路径，W0 指标不变");
        System.exit(fail == 0 ? 0 : 1);
    }
}

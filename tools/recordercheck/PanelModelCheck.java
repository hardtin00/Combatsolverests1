package com.combatsolver1.ui;

import com.combatsolver1.search.Plan;
import java.util.List;

/** Read-only route presentation must preserve executable action order and targets. */
public final class PanelModelCheck {
    public static void main(String[] args) {
        PanelModel model = new PanelModel();
        require(model.routeLines().isEmpty(), "no plan");
        Plan plan = new Plan();
        model.plan = plan;
        Plan.Turn turn = new Plan.Turn();
        turn.turnNumber = 1;
        turn.liveTurnNumber = 3;
        Plan.Action first = new Plan.Action("Strike_R", "打击", 1, 1).target("后排哨卫");
        turn.actions.add(first);
        turn.actions.add(new Plan.Action("Strike_R", "打击", 0, 1).target("前排哨卫"));
        Plan.Action potion = new Plan.Action("POTION:Dexterity Potion", "敏捷药水", -1, 0);
        turn.actions.add(potion);
        Plan.Action select = new Plan.Action("SELECT", "发掘：岿然不动", -1, 0);
        select.kind = Plan.Action.KIND_SELECT;
        turn.actions.add(select);
        turn.actions.add(new Plan.Action("Defend_R", "防御", 0, 1));
        turn.hpAfter = 0;
        turn.endsTurn = true;
        plan.turns.add(turn);
        List<String> lines = model.routeLines();
        require(lines.size() == 6, "all five actions and result visible");
        require(lines.get(0).startsWith("T3 · 1") && lines.get(0).endsWith("后排哨卫")
                && lines.get(1).endsWith("前排哨卫"), "order, live turn and distinct targets");
        require(lines.get(2).contains("药水 · 敏捷药水") && lines.get(3).contains("选牌 · 发掘"),
                "potion and native choice distinguished");
        require(lines.get(4).endsWith("防御") && !lines.get(4).contains("→"),
                "untargeted card protocol placeholder is hidden");
        require(lines.get(5).contains("结束回合") && lines.get(5).contains("HP 0"), "zero HP retained");
        lines.clear();
        require(model.routeLines().size() == 6 && turn.actions.get(0) == first
                && first.targetIndex == 1 && turn.actions.size() == 5, "view cannot mutate plan");
        System.out.println("PanelModelCheck: 6 passed, 0 failed");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}

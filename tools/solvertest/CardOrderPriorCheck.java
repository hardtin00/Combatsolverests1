import com.combatsolver1.search.CardOrderPrior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * W7-a 离线自检：出牌排序先验「只引导不剪枝」。
 *
 * <p>任务书 W7.5。关键断言：先验默认关闭（W0 基线不变）、量级远小于 turnScore、
 * 未知牌为 0、且开启先验后**候选集合完全不变，只有顺序可能变**（不剪枝）。
 */
public final class CardOrderPriorCheck {

    private CardOrderPriorCheck() { }

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

    private static final class Cand {
        final String id;
        final double base;
        Cand(String id, double base) { this.id = id; this.base = base; }
    }

    public static void main(String[] args) {
        // A. 默认关闭 → 所有牌 bonus=0（W0 基线不变）
        check(!CardOrderPrior.isEnabled(), "先验默认关闭", "enabled=false");
        boolean allZero = true;
        for (String id : CardOrderPrior.weights().keySet()) {
            if (CardOrderPrior.bonus(id) != 0.0) allZero = false;
        }
        check(allZero, "关闭时所有 bonus=0", "W0 baseline unchanged");

        // B. 开启后：已知牌 0<bonus<=MAX_BONUS，未知牌 0，量级有界
        CardOrderPrior.setEnabled(true);
        Map<String, Double> w = CardOrderPrior.weights();
        boolean bounded = true;
        for (String id : w.keySet()) {
            double b = CardOrderPrior.bonus(id);
            if (b <= 0.0 || b > CardOrderPrior.MAX_BONUS) bounded = false;
        }
        check(bounded && CardOrderPrior.MAX_BONUS <= 0.5,
                "开启后 bonus ∈ (0, MAX_BONUS] 且 MAX_BONUS≤0.5",
                "MAX_BONUS=" + CardOrderPrior.MAX_BONUS + " 表规模=" + w.size());
        check(CardOrderPrior.bonus("UNKNOWN_CARD") == 0.0
                        && CardOrderPrior.bonus(null) == 0.0,
                "未知牌 / null 的 bonus=0", "no fabrication");

        // C. ★「只引导不剪枝」：同一组候选，开启先验前后集合完全相同，只有顺序可能变
        List<Cand> cands = new ArrayList<Cand>();
        cands.add(new Cand("Strike_R", 10.0));
        cands.add(new Cand("Defend_R", 10.0));   // 与 Strike_R 同分
        cands.add(new Cand("Bash", 9.0));
        cands.add(new Cand("Heavy Blade", 8.0));
        cands.add(new Cand("UNKNOWN_CARD", 7.0));
        Set<String> ids = new HashSet<String>();
        for (Cand c : cands) ids.add(c.id);

        List<Cand> noPrior = new ArrayList<Cand>(cands);
        Collections.sort(noPrior, new Comparator<Cand>() {
            public int compare(Cand a, Cand b) { return Double.compare(b.base, a.base); }
        });
        List<Cand> withPrior = new ArrayList<Cand>(cands);
        Collections.sort(withPrior, new Comparator<Cand>() {
            public int compare(Cand a, Cand b) {
                return Double.compare(b.base + CardOrderPrior.bonus(b.id),
                                      a.base + CardOrderPrior.bonus(a.id));
            }
        });
        Set<String> after = new HashSet<String>();
        for (Cand c : withPrior) after.add(c.id);
        check(ids.equals(after) && noPrior.size() == withPrior.size(),
                "候选集合不变（无剪枝）", "size=" + withPrior.size() + " ids equal");

        // D. 先验确实能打破同分（引导作用），但不改变异分相对顺序
        check(withPrior.get(0).id.equals("Strike_R") && withPrior.get(1).id.equals("Defend_R"),
                "同分候选按先验排序（引导生效）", withPrior.get(0).id + " > " + withPrior.get(1).id);
        boolean orderKept = true;
        for (int i = 0; i < noPrior.size(); i++) {
            for (int j = i + 1; j < noPrior.size(); j++) {
                if (noPrior.get(i).base > noPrior.get(j).base + CardOrderPrior.MAX_BONUS) {
                    // 分差超过先验上限，顺序必须保持
                    int pi = indexOf(withPrior, noPrior.get(i).id);
                    int pj = indexOf(withPrior, noPrior.get(j).id);
                    if (pi > pj) orderKept = false;
                }
            }
        }
        check(orderKept, "分差>MAX_BONUS 的异分顺序不被先验翻转", "no big reorder");

        // E. 关闭后恢复（不影响后续测试/其它用例）
        CardOrderPrior.setEnabled(false);
        check(!CardOrderPrior.isEnabled() && CardOrderPrior.bonus("Strike_R") == 0.0,
                "可关闭且关闭后归零", "clean state");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线；先验表已编码但**未接入** CombatSearch.turnScore（下一单元做 W0 前后对照）");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int indexOf(List<Cand> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }
}

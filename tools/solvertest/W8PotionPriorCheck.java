import com.combatsolver1.search.Plan;
import com.combatsolver1.search.W8PotionPrior;

import java.util.ArrayList;
import java.util.List;

/**
 * W8-d 自检：证明 W8PotionPrior 开关确实生效（否则「调参无效」的阴性结论不可信）。
 *
 * <p>验证：默认关闭时 penalty=15.0 / bonus=0（W0 基线逐位不变）；
 * 开启时 penalty=0.0 且对「喝药后低费连打」给出有界小加分；
 * 关闭后恢复。纯 L2 离线，不跑搜索。
 */
public final class W8PotionPriorCheck {

    private W8PotionPriorCheck() { }

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

    private static Plan.Action card(String id, int cost) {
        return new Plan.Action(id, id, -1, cost);
    }

    private static Plan.Action potion() {
        Plan.Action p = new Plan.Action("POTION:Snecko Oil", "药水", -1, 0);
        p.kind = Plan.Action.KIND_POTION;
        return p;
    }

    public static void main(String[] args) {
        List<Plan.Action> chain = new ArrayList<Plan.Action>();
        chain.add(potion());
        chain.add(card("Cleave", 1));
        chain.add(card("Strike_R", 1));
        chain.add(card("Anger", 0));
        chain.add(card("Defend_R", 1));

        // A. 默认关闭
        check(!W8PotionPrior.isEnabled(), "默认关闭", "enabled=false");
        check(W8PotionPrior.potionCostPenalty() == 15.0,
                "关闭时保持原硬编码惩罚 15.0", "penalty=15.0");
        check(W8PotionPrior.lowCostChainBonus(chain) == 0.0,
                "关闭时低费连打加分=0", "bonus=0.0");

        // B. 开启后确实生效
        W8PotionPrior.setEnabled(true);
        check(W8PotionPrior.potionCostPenalty() == 0.0,
                "开启时取消药水惩罚", "penalty=0.0");
        double b = W8PotionPrior.lowCostChainBonus(chain);
        check(b > 0.0 && b <= chain.size() * 2 * W8PotionPrior.MAX_BONUS,
                "开启时低费连打给出有界小加分", "bonus=" + b);
        // 4 张牌：Cleave(1)->+0.5, Strike_R(1)->+0.5, Anger(0)->+1.0, Defend_R(1)->+0.5 = 2.5
        check(Math.abs(b - 2.5) < 1e-9, "加分数值符合 max(0,2-cost)*0.5", "bonus=2.5");

        // C. 不含药水时不给加分（只针对含药路线）
        List<Plan.Action> noPotion = new ArrayList<Plan.Action>();
        noPotion.add(card("Cleave", 1));
        noPotion.add(card("Anger", 0));
        check(W8PotionPrior.lowCostChainBonus(noPotion) == 0.0,
                "无药水动作时不加", "bonus=0.0");
        check(W8PotionPrior.lowCostChainBonus(null) == 0.0,
                "null 动作列表不加", "bonus=0.0");

        // D. 关闭后恢复（同场可复位）
        W8PotionPrior.setEnabled(false);
        check(!W8PotionPrior.isEnabled()
                        && W8PotionPrior.potionCostPenalty() == 15.0
                        && W8PotionPrior.lowCostChainBonus(chain) == 0.0,
                "关闭后完全复位", "clean state");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（开关行为自检）；调参对 Q44 无效的结论见 docs/W8_TUNING_NEGATIVE_RESULT.md");
        System.exit(fail == 0 ? 0 : 1);
    }
}

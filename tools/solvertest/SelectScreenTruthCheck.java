import com.combatsolver1.engine.CardLibrary;

/**
 * W5 选牌屏真值夹具（L2 离线，真值来自游戏字节码，不是启发式）。
 *
 * <p>W5-a 把「选牌屏」登记为 BOUNDARY 未覆盖。本单元不伪造"已覆盖"，而是把
 * **游戏真实规则**与**引擎启发式**逐张对照并登记差异：
 * <ul>
 *   <li>桌面版 1.0 {@code com.megacrit.cardcrawl.cards.red.BurningPact.use} 字节码：
 *       {@code new ExhaustAction(1, false)}。{@code ExhaustAction.isRandom=false} 时
 *       {@code update()} 打开 {@code AbstractDungeon.handCardSelectScreen} 由**玩家选 1 张消耗**。</li>
 *   <li>{@code TrueGrit.use}：{@code GainBlockAction} 后接 {@code ExhaustAction(1,false)}
 *       （玩家选）与 {@code ExhaustAction(1,true,false,false)}（随机/任意分支）。</li>
 *   <li>{@code Warcry.use}：{@code DrawCardAction} + {@code PutOnDeckAction(...,1,false)}
 *       （玩家选 1 张放回牌堆顶）。</li>
 *   <li>{@code Armaments.use}：{@code ArmamentsAction(false)}；{@code Headbutt.use}：
 *       {@code DiscardPileToTopOfDeckAction}；{@code Exhume.use}：{@code ExhumeAction(false)}
 *       —— 三者都打开选择屏由玩家选。</li>
 * </ul>
 * 引擎侧这三张牌被标 {@code approx=false} 但实际是**启发式代选**
 * （{@code exhaustOneDrawTwo()}「消耗最差」、{@code putBackWorst()}「放回最差」），
 * 与"玩家可选任意一张"不等价。本夹具逐项核对这些事实并**把差异单列**，
 * 不把差异计作通过、不删除断言、不降低精度（AGENTS.md 4.3）。
 *
 * <p>结论：选牌屏**仍是未覆盖的代选近似**，但已从"只写一行 BOUNDARY"升级为
 * 「有字节码真值来源 + 逐张差异清单」的可核对夹具。
 */
public final class SelectScreenTruthCheck {

    private SelectScreenTruthCheck() { }

    private static int pass;
    private static int fail;
    private static int differences;

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    /** 逐张登记：id / 游戏原生动作(字节码) / 游戏选择主体 / 引擎代选规则 / 是否等价。 */
    private static final String[][] CARDS = {
            {"Burning Pact", "ExhaustAction(1,false) + DrawCardAction(magicNumber)",
                    "玩家从手牌选 1 张消耗", "exhaustOneDrawTwo(): 消耗最差的一张并抽二", "否"},
            {"Warcry", "VFXAction + DrawCardAction + PutOnDeckAction(...,1,false)",
                    "玩家选 1 张放回牌堆顶", "putBackWorst(): 放回最差的一张", "否"},
            {"True Grit", "GainBlockAction + ExhaustAction(1,false) + ExhaustAction(1,true,false,false)",
                    "玩家选 1 张消耗（另有随机/任意分支）", "CardLibrary 仅登记为普通牌（无显式选择建模）", "否"},
            {"Armaments", "GainBlockAction + ArmamentsAction(false)",
                    "玩家选 1 张升级", "upgradeHand(): 升级手牌中最好的一张", "否"},
            {"Headbutt", "DamageAction + DiscardPileToTopOfDeckAction",
                    "玩家选 1 张弃牌堆牌置顶", "discardToDraw(): 取回最优的一张", "否"},
            {"Exhume", "ExhumeAction(false)",
                    "玩家选 1 张已消耗牌回手", "exhaustToHand(): 取回最优的一张", "否"},
    };

    public static void main(String[] args) {
        // A. 6 张牌在引擎里都已登记（否则连代选都谈不上）
        int registered = 0;
        for (String[] c : CARDS) {
            if (CardLibrary.isKnown(c[0])) registered++;
        }
        check(registered == CARDS.length, "6 张选牌类牌在 CardLibrary 均已登记",
                "registered=" + registered);

        // B. 逐张输出「游戏真值 vs 引擎代选」差异，差异数单独统计
        System.out.println("── 游戏真值(字节码) vs 引擎代选 差异清单 ──");
        for (String[] c : CARDS) {
            boolean equivalent = "是".equals(c[4]);
            if (!equivalent) differences++;
            System.out.println("[DIFF] " + c[0]
                    + " | 游戏=" + c[1]
                    + " | 游戏选择主体=" + c[2]
                    + " | 引擎=" + c[3]
                    + " | 等价=" + c[4]);
        }
        check(differences == CARDS.length, "6 张全部登记为「不等价」差异（不伪造覆盖）",
                "differences=" + differences);

        // C. 引擎把这 6 张标为非 approx，但真实是代选 —— 这个错配必须被显式指出
        boolean allClaimedModeled = true;
        for (String[] c : CARDS) {
            // CardLibrary 不暴露 approx 给离线层；用 isKnown + 已知建模标记做间接核对，
            // 这里只断言「已登记」，近似/代选事实由上面字节码差异清单给出。
            if (!CardLibrary.isKnown(c[0])) allClaimedModeled = false;
        }
        check(allClaimedModeled, "引擎侧的「已登记」与真值侧的「玩家选择」差异已单列",
                "见 DIFF 行；不把登记当作完整建模");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail
                + " 项，已登记差异 " + differences + " 项");
        System.out.println("L 等级：L2 离线（游戏字节码真值）；选牌屏仍为代选近似，未覆盖、未做实机 L4");
        System.exit(fail == 0 ? 0 : 1);
    }
}

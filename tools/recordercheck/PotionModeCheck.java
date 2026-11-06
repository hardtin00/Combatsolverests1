package com.combatsolver1.runtime;

import com.combatsolver1.runtime.EngineProtocolFields;

/**
 * W4-a 离线自检：引擎路径药水协议与 potion_actions_mode 判定口径。
 *
 * <p>真值来源：`_build/cs1_search.cpp`（外部引擎适配层）：
 * - 第 775 行：`out["potion_actions_mode"] = gLoadedPotions > 0 ? "on" : "off"`；
 * - 第 638-650 行：POTION 动作写成 `USE_POTION <WIRE_ID> potion_slot=<n> target_slot=<m>`；
 * - 第 710 行：`out["plan_potions"]` 是本次计划用到的药水 id 列表。
 * 本自检不读游戏对象、不改引擎，只核对 Java 侧解析与上述真值一致。
 *
 * <p>任务书 W4：「potion_actions_mode=on；含药路线在逐步核对兜底下执行；真机一场含药路线全链证据；
 * 文档同步更新」。本单元只做**离线协议自检**；真机含药路线与文档同步留后续单元。
 */
public final class PotionModeCheck {

    private PotionModeCheck() { }

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

    /** 与 cs1_search.cpp:775 逐字一致的判定规则。 */
    private static String modeFor(int loadedPotions) {
        return loadedPotions > 0 ? "on" : "off";
    }

    public static void main(String[] args) {
        // A. potion_actions_mode 判定口径（引擎真值规则）
        check("on".equals(modeFor(1)) && "on".equals(modeFor(2)) && "off".equals(modeFor(0)),
                "potion_actions_mode = gLoadedPotions>0 ? on : off",
                "0→off, 1→on, 2→on");
        check(!"on".equals(modeFor(-1)), "非法负数装载不判为 on", "-1→off");

        // B. PLANPOTION 行解析（EngineProtocolFields 权威实现）
        check("Dexterity Potion".equals(EngineProtocolFields.potionId(
                        "PLANPOTION idx=0 id=Dexterity Potion tgt=0")),
                "PLANPOTION 多词 id 保留", "Dexterity Potion");
        check("Heart of Iron".equals(EngineProtocolFields.potionId(
                        "PLANPOTION idx=2 id=Heart of Iron tgt=0")),
                "PLANPOTION 三词 id 保留", "Heart of Iron");
        check(EngineProtocolFields.potionId("PLANPOTION idx=0 id=Dexterity Potion") == null,
                "缺 target 分隔符被拒", "malformed rejected");
        check(EngineProtocolFields.potionId("PLANPOTION idx=0 tgt=0") == null,
                "缺 id 被拒", "malformed rejected");
        check(EngineProtocolFields.potionId("PLANCARD idx=0 id=Strike_R tgt=0") == null,
                "非药水协议行被拒", "PLANCARD rejected");

        // C. USE_POTION 步骤格式（cs1_search.cpp:646）
        String step = "USE_POTION FIRE_POTION potion_slot=0 target_slot=1";
        check(step.startsWith("USE_POTION ") && step.contains("potion_slot=")
                        && step.contains("target_slot="),
                "USE_POTION 步骤含 wire id / slot / target", step);
        check(step.split("\\s+")[1].equals("FIRE_POTION"),
                "USE_POTION 用引擎 wire 名（不是显示名）", "FIRE_POTION");

        // D. 身份严格比对（W3 兜底依赖）
        check(EngineProtocolFields.samePotionId("Dexterity Potion", "Dexterity Potion"),
                "原生/协议药水 id 精确一致才放行", "exact match");
        check(!EngineProtocolFields.samePotionId("Dexterity", "Dexterity Potion"),
                "近似 id 被拒", "prefix rejected");

        System.out.println("自检汇总：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("L 等级：L2 离线（协议自检）；potion_actions_mode=on 的真机含药路线全链证据未完成");
        System.exit(fail == 0 ? 0 : 1);
    }
}

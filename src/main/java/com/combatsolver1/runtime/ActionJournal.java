package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 本场战斗的动作日志：按顺序记录“实际发生了什么”的引擎动作编码
 * （出牌 = 出牌时的手牌下标 + 目标下标、用药水、结束回合）。
 *
 * 有了它，就能让 sts_lightspeed 引擎从战斗起点回放到当前状态，
 * 再从当前状态重新搜索 —— 这是跨回合精确重规划的基础。
 *
 * 只要出现无法忠实记录的动作（选牌、读不出下标的出牌、未记录的药水变化），
 * 就标记为脏，之后改用内置近似求解器，避免把错误状态喂给引擎。
 */
public final class ActionJournal {
    public static final int TYPE_CARD = 0;
    public static final int TYPE_POTION = 1;
    public static final int TYPE_SINGLE_SELECT = 2;
    public static final int TYPE_MULTI_SELECT = 3;
    public static final int TYPE_END_TURN = 4;

    /** 引擎里 target>5 表示“丢弃药水”。 */
    public static final int DISCARD_POTION_TARGET = 6;

    private final List<String> actions = new ArrayList<>();
    private boolean dirty;
    private String dirtyReason = "";

    public void recordCard(int handIndex, int targetIndex) {
        record(bits(TYPE_CARD, handIndex, targetIndex));
    }

    public void recordPotion(int potionSlot, int targetIndex) {
        record(bits(TYPE_POTION, potionSlot, targetIndex));
    }

    public void recordEndTurn() {
        record(bits(TYPE_END_TURN, 0, 0));
    }

    public void recordSelection(com.combatsolver1.search.Plan.Action action) {
        if (!action.isSelect() || action.selectBits == null) throw new IllegalArgumentException("missing selection bits");
        record(action.selectBits);
    }

    private void record(String hex) {
        if (!dirty) {
            actions.add(hex);
            if (!BattleArtifacts.appendAction(hex)) {
                markDirty("动作日志写入本场归档失败");
            }
        }
    }

    /** 标记日志失真：本场战斗不再尝试引擎回放。 */
    public void markDirty(String reason) {
        if (!dirty) {
            dirty = true;
            dirtyReason = reason;
            BattleArtifacts.markJournalDirty(reason);
            CombatLog.info("journal dirty: " + reason);
        }
    }

    public boolean isUsable() {
        return !dirty;
    }

    public String dirtyReason() {
        return dirtyReason;
    }

    public int size() {
        return actions.size();
    }

    /** 日志最后一条是不是“结束回合”。 */
    public boolean endsWithEndTurn() {
        if (actions.isEmpty()) {
            return false;
        }
        return actions.get(actions.size() - 1).equals(bits(TYPE_END_TURN, 0, 0));
    }

    public List<String> snapshot() {
        return new ArrayList<>(actions);
    }

    public void clear() {
        actions.clear();
        dirty = false;
        dirtyReason = "";
    }

    /** 打包成引擎 action bits：type<<29 | target<<16 | source。 */
    public static String bits(int type, int sourceIdx, int targetIdx) {
        int value = (type << 29) | ((targetIdx & 0x1FFF) << 16) | (sourceIdx & 0xFFFF);
        return Integer.toHexString(value);
    }

    /** 日志里的动作条数（用于面板显示）。 */
    public String summary() {
        return actions.size() + " 动作" + (dirty ? "（已失真：" + dirtyReason + "）" : "");
    }
}

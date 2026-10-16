package com.combatsolver1.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 整场战斗的预测路线：逐回合的出牌动作，以及每一步之后的预计生命值。
 * 只读结果，供 UI 展示与执行使用。
 */
public class Plan {
    public final List<Turn> turns = new ArrayList<>();
    public int startHp;
    public int endHp;
    public int predictedHpLoss;
    public boolean predictedWin;
    public int turnsToWin = -1;
    public double score;
    public int nodesSearched;
    /** 随机分支复推的样本数与承伤区间：路线在多个洗牌/随机分支下是否仍成立。 */
    public int samples = 1;
    public int hpLossBest = -1;
    public int hpLossWorst = -1;
    public boolean robustWin = true;

    /** 面板与日志用的一句话可信度说明。 */
    public String confidenceNote() {
        if (samples <= 1) {
            return "未做随机分支复推";
        }
        return samples + " 个随机分支下：扣血 " + hpLossBest + "~" + hpLossWorst
                + (robustWin ? "，均能取胜" : "，存在失败分支");
    }
    /** 路线来源：engine = sts_lightspeed 精确引擎；local = 内置近似求解器。 */
    public String source = "local";
    /** 引擎给出的“战斗起点状态”与“本次搜索起点状态”原文，用于校验是否与实机一致。 */
    public String state0Line;
    public String stateLine;

    public static class Turn {
        public int turnNumber;
        /**
         * 实机回合号（引擎的回合计数从 0 开始，这里换算成实机的第几回合）。
         * 引擎路线的第 n 回合只能用在实机第 n 回合，早一回合晚一回合都不行。
         */
        public int liveTurnNumber = -1;
        public final List<Action> actions = new ArrayList<>();
        public int hpAfter;
        /** 本回合开始时的状态签名，用于下一回合核对并复用路线。 */
        public String stateSignature;
        /** 引擎给出的本回合结束时状态（与实机核对，发现偏差就丢弃引擎路线）。 */
        public String endState;
        public int endBlock = -1;
        public int endEnergy = -1;
        /** 这段路线是否以“结束回合”收尾（引擎路线看有没有回合结束记录）。 */
        public boolean endsTurn;
        /** 这一回合结束时是否击杀了所有敌人（引擎的回合结束状态里怪物全为 0）。 */
        public boolean killsAll;
    }

    public static class Action {
        public static final int KIND_CARD = 0;
        public static final int KIND_POTION = 1;
        public static final int KIND_SELECT = 2;

        public int kind = KIND_CARD;
        public String cardId;
        public String cardName;
        public int targetIndex = -1;
        public String targetName;
        public int cost;
        public boolean upgraded;
        public int potionIndex = -1;
        /** 选择类动作（燃烧契约/双持等）要选的手牌/弃牌堆下标。 */
        public int selectIndex = -1;
        public boolean discardPotion;
        /** 引擎给出的手牌下标（用于按位置映射实机手牌）。 */
        public int handIndex = -1;
        /**
         * 胶囊颜色分类：0 攻击、1 技能、2 能力、3 状态/诅咒、4 药水、5 选牌。
         */
        public int colorIndex = -1;

        public Action(String cardId, String cardName, int targetIndex, int cost) {
            this.cardId = cardId;
            this.cardName = cardName;
            this.targetIndex = targetIndex;
            this.cost = cost;
        }

        public Action target(String name) {
            this.targetName = name;
            return this;
        }

        public boolean isPotion() {
            return kind == KIND_POTION || (cardId != null && cardId.startsWith("POTION:"));
        }

        public boolean isSelect() {
            return kind == KIND_SELECT;
        }

        /** 药水在引擎里的 ID（例如 FearPotion）。 */
        public String potionId() {
            if (cardId != null && cardId.startsWith("POTION:")) {
                return cardId.substring("POTION:".length());
            }
            return cardName;
        }

        public String potionName() {
            return cardName == null ? "" : cardName;
        }

        /** 面板与日志里显示的名字。 */
        public String displayName() {
            if (isPotion()) {
                return potionName();
            }
            if (cardName != null && !cardName.isEmpty()) {
                return cardName;
            }
            return cardId == null ? "?" : cardId;
        }
    }
}

import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 差分重放器：读入实机记录（JSONL），对每条动作把记录里的「前状态」喂给模拟器，
 * 执行同一个动作，再与记录里的「后状态」逐字段比对，输出差异清单。
 *
 * 目标不是「模拟必须全对」，而是让每一处不一致都变成一条可定位、可复现、
 * 可以在修复后回放的条目 —— 这是后续提高模拟保真度的验证底座。
 */
public final class DiffRunner {

    private DiffRunner() {
    }

    public static final class Result {
        public int actions;
        public int matchedFields;
        public int totalFields;
        public int badActions;
        public final List<String> report = new ArrayList<String>();
    }

    public static Result run(String path) throws Exception {
        Result result = new Result();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8));
        try {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = Json.parse(line);
                } catch (RuntimeException e) {
                    result.report.add("第 " + lineNo + " 行解析失败：" + e.getMessage());
                    result.badActions++;
                    continue;
                }
                Map<String, Object> record = Json.asMap(parsed);
                String type = Json.str(record, "type", "?");
                if ("root".equals(type)) {
                    result.report.add("第 " + lineNo + " 行：战斗根记录（" + mobSummary(record) + "）");
                    continue;
                }
                if (!"card".equals(type) && !"endTurn".equals(type)) {
                    continue;
                }
                result.actions++;
                List<String> issues = new ArrayList<String>();
                int matched = compareRecord(record, issues);
                if (issues.isEmpty()) {
                    result.matchedFields += 9 + mobCount(record);
                    result.totalFields += 9 + mobCount(record);
                    result.report.add("第 " + lineNo + " 行 [" + type + "] "
                            + describe(record) + "：一致");
                } else {
                    result.badActions++;
                    result.matchedFields += matched;
                    result.totalFields += 9 + mobCount(record);
                    result.report.add("第 " + lineNo + " 行 [" + type + "] "
                            + describe(record) + "：不一致");
                    for (String issue : issues) {
                        result.report.add("    " + issue);
                    }
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    private static int compareRecord(Map<String, Object> record, List<String> issues) {
        String type = Json.str(record, "type", "");
        Map<String, Object> preJson = Json.obj(record, "pre");
        Map<String, Object> postJson = Json.obj(record, "post");
        SimState sim = States.toSimState(preJson);

        if ("card".equals(type)) {
            Map<String, Object> cardJson = Json.obj(record, "card");
            int target = replayCard(sim, cardJson, postJson);
            if (target == Integer.MIN_VALUE) {
                issues.add("记录里的牌在手牌里找不到：" + Json.str(cardJson, "id", "?"));
                return 0;
            }
        } else {
            CombatEngine.endTurn(sim);
        }

        States.Snapshot expected = States.ofJson(postJson);
        States.Snapshot actual = States.of(sim);
        return compare(expected, actual, issues);
    }

    /**
     * 重放一张牌。记录里的 target 可能是 -1（实机没记录目标）：
     * 这时对所有存活敌人各试一次，取与实机结果吻合度最高的那个目标。
     */
    private static int replayCard(SimState base, Map<String, Object> cardJson,
                                  Map<String, Object> postJson) {
        String id = Json.str(cardJson, "id", "?");
        int damage = Json.num(cardJson, "damage", 0);
        int block = Json.num(cardJson, "block", 0);
        int cost = Json.num(cardJson, "cost", 0);
        int hits = Json.num(cardJson, "hits", 0);

        int handIndex = findHandIndex(base, id, damage, block, cost);
        if (handIndex < 0) {
            return Integer.MIN_VALUE;
        }

        States.Snapshot expected = States.ofJson(postJson);
        int bestTarget = -1;
        int bestScore = -1;
        States.Snapshot bestSnap = null;
        for (int t = -1; t < base.mobs.size(); t++) {
            if (t >= 0 && !base.mobs.get(t).alive) {
                continue;
            }
            SimState trial = base.fork();
            SimState.Card card = trial.hand.remove(handIndex);
            CombatEngine.playCard(trial, card, t);
            States.Snapshot snap = States.of(trial);
            int score = score(expected, snap);
            if (score > bestScore) {
                bestScore = score;
                bestTarget = t;
                bestSnap = snap;
            }
            if (t < 0) {
                continue; // 无目标版本先试，再试各目标
            }
        }
        if (bestSnap == null) {
            SimState trial = base.fork();
            SimState.Card card = trial.hand.remove(handIndex);
            CombatEngine.playCard(trial, card, -1);
            bestTarget = -1;
        }
        // 真正执行一次（用选中的目标），让调用方拿到最终状态
        SimState chosen = base.fork();
        SimState.Card card = chosen.hand.remove(handIndex);
        CombatEngine.playCard(chosen, card, bestTarget);
        copyState(chosen, base);
        return bestTarget;
    }

    private static void copyState(SimState from, SimState to) {
        // 简易回写：把重放结果写回 base（保持对象引用不变）
        to.hp = from.hp;
        to.maxHp = from.maxHp;
        to.block = from.block;
        to.energy = from.energy;
        to.playerVulnerable = from.playerVulnerable;
        to.playerWeak = from.playerWeak;
        to.playerFrail = from.playerFrail;
        to.hand.clear();
        for (SimState.Card c : from.hand) {
            to.hand.add(c);
        }
        to.drawPile.clear();
        to.drawPile.addAll(from.drawPile);
        to.discardPile.clear();
        to.discardPile.addAll(from.discardPile);
        to.exhaustPile.clear();
        to.exhaustPile.addAll(from.exhaustPile);
        to.mobs.clear();
        for (SimState.Mob m : from.mobs) {
            to.mobs.add(m);
        }
    }

    private static int findHandIndex(SimState s, String id, int damage, int block, int cost) {
        int fallback = -1;
        for (int i = 0; i < s.hand.size(); i++) {
            SimState.Card c = s.hand.get(i);
            if (!id.equals(c.id)) {
                continue;
            }
            if (fallback < 0) {
                fallback = i;
            }
            boolean damageOk = damage <= 0 || c.damage == damage;
            boolean blockOk = block <= 0 || c.block == block;
            if (damageOk && blockOk && c.cost == cost) {
                return i;
            }
        }
        return fallback;
    }

    private static int compare(States.Snapshot expected, States.Snapshot actual, List<String> issues) {
        int matched = 0;
        if (expected.hp == actual.hp) {
            matched++;
        } else {
            issues.add("生命：实机 " + expected.hp + " / 模拟 " + actual.hp);
        }
        if (expected.block == actual.block) {
            matched++;
        } else {
            issues.add("格挡：实机 " + expected.block + " / 模拟 " + actual.block);
        }
        if (expected.energy == actual.energy) {
            matched++;
        } else {
            issues.add("能量：实机 " + expected.energy + " / 模拟 " + actual.energy);
        }
        if (expected.handSorted().equals(actual.handSorted())) {
            matched++;
        } else {
            issues.add("手牌：实机 " + expected.handSorted() + " / 模拟 " + actual.handSorted());
        }
        if (expected.draw == actual.draw) {
            matched++;
        } else {
            issues.add("抽牌堆数量：实机 " + expected.draw + " / 模拟 " + actual.draw);
        }
        if (expected.discard == actual.discard) {
            matched++;
        } else {
            issues.add("弃牌堆数量：实机 " + expected.discard + " / 模拟 " + actual.discard);
        }
        if (expected.exhaust == actual.exhaust) {
            matched++;
        } else {
            issues.add("消耗堆数量：实机 " + expected.exhaust + " / 模拟 " + actual.exhaust);
        }
        if (expected.drawIds.equals(actual.drawIds)) {
            matched++;
        } else {
            issues.add("抽牌堆内容：实机 " + expected.drawIds + " / 模拟 " + actual.drawIds);
        }
        if (expected.discardIds.equals(actual.discardIds)) {
            matched++;
        } else {
            issues.add("弃牌堆内容：实机 " + expected.discardIds + " / 模拟 " + actual.discardIds);
        }
        int mobs = Math.min(expected.mobs.size(), actual.mobs.size());
        for (int i = 0; i < mobs; i++) {
            if (expected.mobs.get(i).equals(actual.mobs.get(i))) {
                matched++;
            } else {
                issues.add("怪物 " + (i + 1) + "：实机 " + expected.mobs.get(i)
                        + " / 模拟 " + actual.mobs.get(i));
            }
        }
        return matched;
    }

    private static int score(States.Snapshot expected, States.Snapshot actual) {
        List<String> ignored = new ArrayList<String>();
        return compare(expected, actual, ignored);
    }

    private static int mobCount(Map<String, Object> record) {
        return Json.list(Json.obj(record, "post"), "mobs").size();
    }

    private static String describe(Map<String, Object> record) {
        if ("card".equals(Json.str(record, "type", ""))) {
            return "出牌 " + Json.str(Json.obj(record, "card"), "id", "?");
        }
        return "结束回合";
    }

    private static String mobSummary(Map<String, Object> record) {
        StringBuilder sb = new StringBuilder();
        for (Object o : Json.list(Json.obj(record, "state"), "mobs")) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(Json.str(Json.asMap(o), "name", Json.str(Json.asMap(o), "id", "?")));
        }
        return sb.toString();
    }
}

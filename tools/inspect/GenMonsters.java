import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发工具：把 Bestiary（casey-c/Bestiary，MIT）的 monsters.json 中「可直接解析」的
 * 怪物 AI 转换成 `engine/GeneratedMonsterData.java`（引擎脚本表达式）。
 *
 * 能处理：起手固定、加权随机（含百分比与 N-M-K 分配）、交替循环、每招连续使用上限、
 * 禁止起手。无法可靠解析的（多阶段、条件分支、按回合计数）会打印 MANUAL 清单，
 * 由人手写在 MonsterAi 里，不会被静默猜错。
 *
 * 用法：
 *   java -cp <out> GenMonsters <monsters.json> <输出文件> [跳过的怪物 ID...]
 */
public final class GenMonsters {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: GenMonsters <monsters.json> <out.java> [skipIds...]");
            System.exit(2);
        }
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Set<String> skip = new HashSet<String>();
        if (args.length > 2) {
            skip.addAll(Arrays.asList(args).subList(2, args.length));
        }
        Object root = Json.parse(text);
        List<Object> monsters = Json.list(root, "monsters");

        Collections.sort(monsters, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                return Json.str(a, "id", "").compareTo(Json.str(b, "id", ""));
            }
        });

        StringBuilder body = new StringBuilder();
        List<String> manual = new ArrayList<String>();
        int emitted = 0;
        int blocked = 0;
        for (Object monster : monsters) {
            String id = Json.str(monster, "id", "?");
            if (skip.contains(id)) {
                continue;
            }
            List<Object> sets = Json.list(monster, "ai");
            List<Object> sorted = new ArrayList<Object>(sets);
            Collections.sort(sorted, new java.util.Comparator<Object>() {
                public int compare(Object a, Object b) {
                    return Json.num(a, "min_asc", 0) - Json.num(b, "min_asc", 0);
                }
            });
            for (Object set : sorted) {
                int minAsc = Json.num(set, "min_asc", 0);
                List<Object> moves = Json.list(set, "moves");
                List<String> names = new ArrayList<String>();
                for (Object move : moves) {
                    names.add(Json.str(move, "name", "?"));
                }
                String desc = Json.str(set, "desc", "");
                String script = parse(desc, names, moves, id);
                if (script == null) {
                    manual.add(id + " (进阶>=" + minAsc + ")");
                    blocked++;
                    continue;
                }
                body.append("        MonsterAi.register(\"").append(js(id)).append("\", ")
                        .append(minAsc).append(", ").append(script).append(");\n");
                emitted++;
            }
        }

        String out = "/**\n"
                + " * 由 tools/inspect/GenMonsters.java 从 Bestiary（casey-c/Bestiary，MIT，Casey C）的\n"
                + " * monsters.json 自动生成，请勿手改。署名与许可见 THIRD_PARTY_NOTICES.md。\n"
                + " *\n"
                + " * 招式数值同样经游戏本体 desktop-1.0.jar 的类文件核对（tools/inspect/ExtractMonsterFacts）。\n"
                + " */\n"
                + "package com.combatsolver1.engine;\n\n"
                + "final class GeneratedMonsterData {\n"
                + "    private GeneratedMonsterData() {\n"
                + "    }\n\n"
                + "    static void register() {\n"
                + body
                + "    }\n"
                + "}\n";
        Files.write(Paths.get(args[1]), out.getBytes(StandardCharsets.UTF_8));

        System.err.println("生成 " + emitted + " 条脚本（" + (emitted == 0 ? 0 : emitted)
                + "），无法解析 " + blocked + " 条：");
        for (String m : manual) {
            System.err.println("  MANUAL " + m);
        }
    }

    // ══ 解析 ══════════════════════════════════════════════════

    private static String parse(String rawDesc, List<String> names, List<Object> moves, String monsterId) {
        String desc = normalize(rawDesc).replaceFirst("^(When not splitting, |When knocked down, )", "");
        Map<String, String> nameById = new HashMap<String, String>();
        Map<String, String> idByName = new HashMap<String, String>();
        for (String name : names) {
            nameById.put(id(name), name);
            idByName.put(key(name), id(name));
        }

        String first = null;
        Matcher firstMatcher = Pattern.compile(
                "(?:always )?(?:starts?|begins?)(?: the fight)? with ([A-Za-z0-9 '/\\-]+?)(?:,|\\.|$| then)",
                Pattern.CASE_INSENSITIVE).matcher(desc);
        if (firstMatcher.find()) {
            first = matchId(firstMatcher.group(1), idByName);
        }

        // 权重表：N-M-K 分配 / 百分比
        List<String> order = new ArrayList<String>();
        List<Integer> weights = new ArrayList<Integer>();
        Matcher split3 = Pattern.compile(
                "(\\d+)-(\\d+)-(\\d+)(?: probability)? split between ([^,]+), ([^,]+), and ([^.,]+)")
                .matcher(desc);
        Matcher split2 = Pattern.compile(
                "(\\d+)-(\\d+)(?: probability)? split (?:between|of) ([^,]+) and ([^.,]+)")
                .matcher(desc);
        Matcher pct2 = Pattern.compile(
                "(\\d+)% (?:chance )?(?:of|to|to use|to use the) ([A-Za-z0-9 '/\\-]+?) and (\\d+)% (?:chance )?(?:of|to|to use|to use the) ([A-Za-z0-9 '/\\-]+?)(?: except| but| although|,| when|\\.|$)")
                .matcher(desc);
        Matcher pct1 = Pattern.compile("(\\d+)% (?:chance )?(?:of|to) ([A-Za-z0-9 '/\\-]+?)(?: except| but| although|,| when|\\.|$)")
                .matcher(desc);
        if (split3.find()) {
            order.add(matchId(split3.group(4), idByName));
            order.add(matchId(split3.group(5), idByName));
            order.add(matchId(split3.group(6), idByName));
            weights.add(num(split3.group(1)));
            weights.add(num(split3.group(2)));
            weights.add(num(split3.group(3)));
        } else if (split2.find()) {
            order.add(matchId(split2.group(3), idByName));
            order.add(matchId(split2.group(4), idByName));
            weights.add(num(split2.group(1)));
            weights.add(num(split2.group(2)));
        } else if (pct2.find()) {
            order.add(matchId(pct2.group(2), idByName));
            order.add(matchId(pct2.group(4), idByName));
            weights.add(num(pct2.group(1)));
            weights.add(num(pct2.group(3)));
        } else if (pct1.find() && names.size() == 2) {
            int firstWeight = num(pct1.group(1));
            String firstMove = matchId(pct1.group(2), idByName);
            order.add(firstMove);
            for (String name : names) {
                if (!id(name).equals(firstMove)) {
                    order.add(id(name));
                }
            }
            weights.add(firstWeight);
            weights.add(100 - firstWeight);
        }

        String alternating = null;
        Matcher altMatcher = Pattern.compile("(?:always )?alternat(?:es|ing) between (?:the two moves|([A-Za-z0-9 '/\\-]+?) and ([A-Za-z0-9 '/\\-]+?))")
                .matcher(desc);
        if (altMatcher.find()) {
            alternating = altMatcher.group(1) == null ? "TWO"
                    : matchId(altMatcher.group(1), idByName) + "|" + matchId(altMatcher.group(2), idByName);
        }

        String repeat = null;
        Matcher repeatMatcher = Pattern.compile("(?:then )?repeats? ([A-Za-z0-9 '/\\-]+?)(?:\\.|$)")
                .matcher(desc);
        if (repeatMatcher.find()) {
            repeat = matchId(repeatMatcher.group(1), idByName);
        }

        String always = null;
        Matcher alwaysMatcher = Pattern.compile("always uses ([A-Za-z0-9 '/\\-]+?)(?:\\.|$| every)")
                .matcher(desc);
        if (alwaysMatcher.find()) {
            always = matchId(alwaysMatcher.group(1), idByName);
        }

        // 复杂条件 / 多阶段：交给人工
        if (hasCondition(desc)) {
            return null;
        }

        Map<String, Integer> maxStreak = new HashMap<String, Integer>();
        parseStreakLimits(desc, idByName, names, maxStreak);
        Set<String> notFirst = new HashSet<String>();
        Matcher notFirstMatcher = Pattern.compile("cannot use ([A-Za-z0-9 '/\\-]+?) on turn 1")
                .matcher(desc);
        while (notFirstMatcher.find()) {
            notFirst.add(matchId(notFirstMatcher.group(1), idByName));
        }

        // 起手随机：50% chance to start with either move
        if (first == null && desc.matches("(?s).*chance to start with either move.*")) {
            String a = id(names.get(0));
            String b = id(names.get(1));
            return "MonsterAi.firstThenScript(MonsterAi.random(" + moveRef(a, moves)
                    + ", " + moveRef(b, moves) + "), MonsterAi.cycle("
                    + moveRef(a, moves) + ", " + moveRef(b, moves) + "))";
        }
        if (always != null) {
            return "MonsterAi.cycle(" + moveRef(always, moves) + ")";
        }
        if (!weights.isEmpty()) {
            StringBuilder sb = new StringBuilder("MonsterAi.weighted(");
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("MonsterAi.w(").append(moveRef(order.get(i), moves)).append(", ")
                  .append(weights.get(i)).append(')');
                Integer limit = maxStreak.get(order.get(i));
                if (limit != null) {
                    sb.append(".maxStreak(").append(limit).append(')');
                }
                if (notFirst.contains(order.get(i))) {
                    sb.append(".notFirst()");
                }
            }
            sb.append(')');
            if (first != null && !first.equals(order.get(0))) {
                return "MonsterAi.firstThen(" + moveRef(first, moves) + ", " + sb + ")";
            }
            return sb.toString();
        }
        if (alternating != null) {
            String body;
            if ("TWO".equals(alternating)) {
                body = "MonsterAi.cycle(" + moveRef(id(names.get(0)), moves) + ", "
                        + moveRef(id(names.get(1)), moves) + ")";
            } else {
                String[] parts = alternating.split("\\|");
                body = "MonsterAi.cycle(" + moveRef(parts[0], moves) + ", "
                        + moveRef(parts[1], moves) + ")";
            }
            return first == null ? body : "MonsterAi.firstThen(" + moveRef(first, moves) + ", " + body + ")";
        }
        if (first != null && repeat != null) {
            return "MonsterAi.firstThen(" + moveRef(first, moves) + ", MonsterAi.cycle("
                    + moveRef(repeat, moves) + "))";
        }
        if (first != null && names.size() == 2) {
            String other = null;
            for (String name : names) {
                if (!id(name).equals(first)) {
                    other = id(name);
                }
            }
            return "MonsterAi.firstThen(" + moveRef(first, moves) + ", MonsterAi.cycle("
                    + moveRef(other, moves) + "))";
        }
        if (names.size() == 1) {
            return "MonsterAi.cycle(" + moveRef(id(names.get(0)), moves) + ")";
        }
        return null;
    }

    private static boolean hasCondition(String desc) {
        String[] patterns = {
                "\\bIf ", "\\bWhile ", "\\bunless\\b", "\\bonce ", "\\bwhen ", "half-dead",
                "\\bPhase\\b", "in the order of", "every \\d+ turns?", "starting from turn",
                "followed by", "no longer", "\\binstead\\b", "changes", "\\battempt",
        };
        for (String pattern : patterns) {
            if (Pattern.compile(pattern).matcher(desc).find()) {
                return true;
            }
        }
        return false;
    }

    private static void parseStreakLimits(String desc, Map<String, String> idByName,
                                          List<String> names, Map<String, Integer> out) {
        Matcher matcher = Pattern.compile(
                "([A-Za-z0-9 '/\\-]+?) cannot be (?:successfully )?used (twice|three times|two times|three times|3 times|2 times) in a row")
                .matcher(desc);
        while (matcher.find()) {
            String subject = matcher.group(1);
            String times = matcher.group(2);
            int limit = times.startsWith("twice") || times.startsWith("two") || times.startsWith("2")
                    ? 1 : 2;
            String[] subjects = subject.split(", and | and |\\+");
            for (String rawSubject : subjects) {
                String candidate = rawSubject.trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                if ("the other moves".equals(candidate) || "the other move".equals(candidate)) {
                    for (String name : names) {
                        if (!out.containsKey(id(name))) {
                            out.put(id(name), limit);
                        }
                    }
                    continue;
                }
                String matched = matchId(candidate, idByName);
                if (matched != null) {
                    out.put(matched, limit);
                }
            }
        }
    }

    private static String normalize(String desc) {
        String text = desc.replace("#w", "").replace("#u", "").replace("#b", "")
                .replace("#d", "").replace("#r", "").replace("#y", "").replace("#g", "")
                .replace("#p", "").replace(" NL ", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String matchId(String rawName, Map<String, String> idByName) {
        String candidate = key(rawName);
        if (candidate.isEmpty()) {
            return null;
        }
        String exact = idByName.get(candidate);
        if (exact != null) {
            return exact;
        }
        // 允许描述里少写/多写词，取前缀匹配里最长的一个
        String best = null;
        for (Map.Entry<String, String> entry : idByName.entrySet()) {
            if (entry.getKey().startsWith(candidate) || candidate.startsWith(entry.getKey())) {
                if (best == null || entry.getKey().length() > best.length()) {
                    best = entry.getKey();
                }
            }
        }
        return best == null ? null : idByName.get(best);
    }

    private static String moveRef(String moveId, List<Object> moves) {
        for (Object move : moves) {
            if (id(Json.str(move, "name", "?")).equals(moveId)) {
                return buildMove(move);
            }
        }
        return "MonsterAi.m(\"" + moveId + "\", \"" + moveId + "\").build()";
    }

    private static String buildMove(Object move) {
        String name = Json.str(move, "name", "?");
        StringBuilder sb = new StringBuilder("MonsterAi.m(\"").append(js(id(name)))
                .append("\", \"").append(js(name)).append("\")");
        boolean approx = false;
        for (Object effect : Json.list(move, "effects")) {
            String color = Json.str(effect, "color", "");
            String value = Json.str(effect, "name", "");
            if ("DAMAGE".equals(color)) {
                Matcher m = Pattern.compile("^(\\d+)(?:x(\\d+))?$").matcher(value.trim());
                if (m.find()) {
                    sb.append(".dmg(").append(m.group(1)).append(", ")
                      .append(m.group(2) == null ? "1" : m.group(2)).append(')');
                } else {
                    approx = true;
                }
            } else if ("BLOCK".equals(color)) {
                Matcher m = Pattern.compile("^(?:Ally: )?Block (\\d+)$").matcher(value.trim());
                Matcher heal = Pattern.compile("^Party Heal (\\d+)$").matcher(value.trim());
                if (value.startsWith("Ally:")) {
                    sb.append(".allyBlock(").append(digits(value)).append(')');
                } else if (heal.find()) {
                    sb.append(".allyHeal(").append(heal.group(1)).append(')');
                } else if (m.find()) {
                    sb.append(".blk(").append(m.group(1)).append(')');
                } else {
                    approx = true;
                }
            } else if ("BUFF".equals(color)) {
                if (value.startsWith("Str +")) {
                    sb.append(".str(").append(digits(value)).append(')');
                } else if (value.startsWith("Party Str +")) {
                    sb.append(".partyStr(").append(digits(value)).append(')');
                } else {
                    approx = true;   // 飞行层数等暂不建模
                }
            } else if ("DEBUFF".equals(color)) {
                if (value.startsWith("Weak ")) {
                    sb.append(".weak(").append(digits(value)).append(')');
                } else if (value.startsWith("Vuln ")) {
                    sb.append(".vuln(").append(digits(value)).append(')');
                } else if (value.startsWith("Frail ")) {
                    sb.append(".frail(").append(digits(value)).append(')');
                } else if (value.startsWith("Str -")) {
                    sb.append(".strDown(").append(digits(value)).append(')');
                } else {
                    approx = true;
                }
            } else if ("SKIP".equals(color)) {
                // 空过，无效果
            } else {
                approx = true;
            }
        }
        if (approx) {
            sb.append(".approx()");
        }
        return sb.append(".build()").toString();
    }

    private static int digits(String value) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(value);
        return matcher.find() ? num(matcher.group(1)) : 0;
    }

    private static int num(String value) {
        return Integer.parseInt(value);
    }

    /** 招式 id：大写、空格与符号换成下划线。 */
    private static String id(String name) {
        return name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
    }

    private static String key(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String js(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

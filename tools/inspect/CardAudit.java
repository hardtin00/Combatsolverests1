import com.combatsolver1.engine.CardLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发工具：用 Wiki 的 Module:Cards/data（Lua）核对 CardLibrary。
 *
 * 该模块每条形如：
 *   {Name = "Swift Strike", Color = "Colorless", Rarity = "Uncommon", Type = "Attack",
 *    Cost = 0, Text = "Deal [7|10] damage.", Traits = {},},
 * 其中 Text 里 [基础|升级] 给出数值，Traits 里带 SelfExhaust / AddWeak / RemoveStrength 等标签。
 *
 * 本工具做两件事：
 *   1) 用 Traits 里的 SelfExhaust 反查「打出即消耗」标记（对拼写错误最敏感）；
 *   2) 列出该颜色下 CardLibrary 里还没有的牌（铁甲可用的红牌 + 任何职业都能拿的无色牌）。
 *
 * 用法：java -cp <engine+工具> CardAudit <cards-lua.txt> [Red|Colorless]
 */
public final class CardAudit {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CardAudit <cards-lua.txt> [Color]");
            System.exit(2);
        }
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        List<Map<String, String>> cards = parse(lines);
        String colorFilter = args.length > 1 ? args[1] : "Red";

        Map<String, String> known = new HashMap<String, String>();
        for (String id : CardLibrary.ids()) {
            known.put(key(id), id);
            if (id.endsWith("_R")) {
                known.put(key(id.substring(0, id.length() - 2)), id); // Strike_R 与 Strike 视为同一张
            }
        }

        int total = 0;
        int missing = 0;
        int mismatch = 0;
        List<String> missingNames = new ArrayList<String>();
        for (Map<String, String> card : cards) {
            String color = card.get("Color");
            if (!colorFilter.isEmpty() && !colorFilter.equals(color)) {
                continue;
            }
            total++;
            String name = card.get("Name");
            String id = known.get(key(name));
            if (id == null) {
                missing++;
                missingNames.add(name + "/" + card.get("Type") + "/" + card.get("Cost"));
                continue;
            }
            boolean gameExhausts = endsWithExhaustKeyword(card.get("Text"));
            boolean tableExhausts = CardLibrary.get(id).exhaustSelf;
            if (gameExhausts != tableExhausts) {
                mismatch++;
                System.out.println("  [不一致] " + name + "（" + id + "）：表 exhaustSelf="
                        + tableExhausts + "，Wiki Traits=" + gameExhausts);
            }
        }
        System.out.println("颜色 " + colorFilter + "：共 " + total + " 张，已登记 "
                + (total - missing) + " 张，缺 " + missing + " 张，「打出即消耗」不一致 " + mismatch + " 张");
        if (!missingNames.isEmpty()) {
            System.out.println("缺的牌：");
            for (String n : missingNames) {
                System.out.println("  " + n);
            }
        }
    }

    private static List<Map<String, String>> parse(List<String> lines) {
        List<Map<String, String>> cards = new ArrayList<Map<String, String>>();
        Map<String, String> current = null;
        StringBuilder text = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{Name = ")) {
                current = new HashMap<String, String>();
                cards.add(current);
                current.put("Name", value(trimmed));
                text = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (text != null) {
                // Text 可能跨行（行尾以 \ 续行）
                text.append('\n').append(trimmed);
                if (!trimmed.endsWith("\\")) {
                    current.put("Text", text.toString());
                    text = null;
                }
                continue;
            }
            if (trimmed.startsWith("Color = ")) {
                current.put("Color", value(trimmed));
            } else if (trimmed.startsWith("Type = ")) {
                current.put("Type", value(trimmed));
            } else if (trimmed.startsWith("Cost = ")) {
                current.put("Cost", value(trimmed).replace("\"", ""));
            } else if (trimmed.startsWith("Traits = ")) {
                current.put("Traits", trimmed);
            } else if (trimmed.startsWith("Text = ")) {
                String value = trimmed.substring("Text = ".length());
                if (value.endsWith("\\")) {
                    text = new StringBuilder(value.substring(0, value.length() - 1));
                } else {
                    current.put("Text", value);
                }
            }
        }
        return cards;
    }

    /**
     * 「打出即消耗」的判定：Wiki 文本里关键词写作 #Exhaust，
     * 且只有自我消耗才会出现在文本末尾（如 "Gain [30|40] #Block.\n#Exhaust."）；
     * 「Exhaust a card」「Exhaust it」指的是别的牌，不会落在结尾。
     */
    private static boolean endsWithExhaustKeyword(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace("\"", "").replace("\\", "").replace("\n", " ").trim();
        while (normalized.endsWith(",")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized.endsWith("#Exhaust.");
    }

    /** 取出 = 后面引号里的字符串。 */
    private static String value(String line) {
        int first = line.indexOf('"');
        int last = line.lastIndexOf('"');
        if (first < 0 || last <= first) {
            return "";
        }
        return line.substring(first + 1, last);
    }

    /** 归一化名称：小写、去掉非字母数字（Strike 与 Strike_R 视为同一张）。 */
    private static String key(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

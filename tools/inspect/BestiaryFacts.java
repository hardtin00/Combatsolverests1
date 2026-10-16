import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 开发工具：把 Bestiary（casey-c/Bestiary，MIT）的 monsters.json 转成紧凑的「怪物事实单」，
 * 用于手写 / 核对 MonsterAi 的招式表。数据来源与许可见 THIRD_PARTY_NOTICES.md。
 *
 * 用法：
 *   java -cp <out> BestiaryFacts <monsters.json> [怪物 ID...]   // 不传 ID 则全部
 */
public final class BestiaryFacts {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: BestiaryFacts <monsters.json> [id...]");
            System.exit(2);
        }
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        Object root = Json.parse(text);
        List<Object> monsters = Json.list(root, "monsters");
        Set<String> want = args.length > 1
                ? new HashSet<String>(Arrays.asList(args).subList(1, args.length))
                : null;
        int printed = 0;
        for (Object monster : monsters) {
            String id = Json.str(monster, "id", "?");
            if (want != null && !want.contains(id)) {
                continue;
            }
            printed++;
            System.out.println("=== " + id + "  「" + Json.str(monster, "name", "?") + "」");
            for (Object ai : Json.list(monster, "ai")) {
                System.out.println("  [进阶 >= " + Json.num(ai, "min_asc", 0) + "] HP "
                        + Json.str(ai, "hp", "?"));
                System.out.println("    AI: " + Json.str(ai, "desc", ""));
                String notes = Json.str(ai, "notes", "");
                if (!notes.isEmpty()) {
                    System.out.println("    注: " + notes);
                }
                for (Object move : Json.list(ai, "moves")) {
                    StringBuilder sb = new StringBuilder("    - ");
                    sb.append(Json.str(move, "name", "?")).append("  {");
                    for (Object effect : Json.list(move, "effects")) {
                        sb.append(Json.str(effect, "color", "?")).append('=')
                          .append(Json.str(effect, "name", "?")).append(' ');
                    }
                    System.out.println(sb.append('}').toString());
                }
            }
            System.out.println();
        }
        System.err.println("printed " + printed + " monster(s) of " + monsters.size());
    }
}

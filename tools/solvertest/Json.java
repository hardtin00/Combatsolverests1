import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（只依赖 JDK），供离线自检工具读取实机记录。
 * 只支持对象 / 数组 / 字符串 / 数字 / true / false / null，足够覆盖记录格式。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWs();
        Object value = p.value();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("JSON 解析后仍有多余内容：位置 " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    /** 读取对象字段；不存在返回空 Map。 */
    public static Map<String, Object> obj(Object o, String key) {
        Object v = asMap(o).get(key);
        return v == null ? new LinkedHashMap<String, Object>() : asMap(v);
    }

    /** 读取数组字段；不存在返回空 List。 */
    public static List<Object> list(Object o, String key) {
        Object v = asMap(o).get(key);
        return v == null ? new ArrayList<Object>() : asList(v);
    }

    public static String str(Object o, String key, String def) {
        Object v = asMap(o).get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int num(Object o, String key, int def) {
        Object v = asMap(o).get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean bool(Object o, String key, boolean def) {
        Object v = asMap(o).get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    // ── 解析实现 ────────────────────────────────────────────────

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Object value() {
        skipWs();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, pos)) {
            throw new IllegalArgumentException("JSON 期望 " + word + "，位置 " + pos);
        }
        pos += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            if (src.charAt(pos) != ':') {
                throw new IllegalArgumentException("JSON 期望冒号，位置 " + pos);
            }
            pos++;
            map.put(key, value());
            skipWs();
            char c = src.charAt(pos);
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 期望逗号或右花括号，位置 " + (pos - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWs();
            char c = src.charAt(pos);
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 期望逗号或右方括号，位置 " + (pos - 1));
            }
        }
    }

    private String string() {
        if (src.charAt(pos) != '"') {
            throw new IllegalArgumentException("JSON 期望字符串，位置 " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = src.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("JSON 非法转义 \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            return Long.valueOf(Long.parseLong(text));
        }
        return Double.valueOf(Double.parseDouble(text));
    }
}

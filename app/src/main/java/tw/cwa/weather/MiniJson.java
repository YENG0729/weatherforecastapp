package tw.cwa.weather;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 極簡 JSON 剖析器（不依賴任何外部函式庫，維持單一 JAR 可直接執行）。
 *
 * parse() 回傳結構：
 *   物件 -> Map&lt;String,Object&gt;
 *   陣列 -> List&lt;Object&gt;
 *   字串 -> String
 *   數字 -> Double
 *   true/false -> Boolean
 *   null -> null
 *
 * 另外提供 get()/list()/str() 等「不分大小寫」的取值輔助方法，
 * 用來同時相容氣象署新舊兩種 JSON 欄位命名（例如 location / Location）。
 */
public final class MiniJson {

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    // ------------------------------------------------------------------
    // 對外入口
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        MiniJson p = new MiniJson(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        return value;
    }

    // ------------------------------------------------------------------
    // 取值輔助（key 不分大小寫，可一次列出多個候選名稱）
    // ------------------------------------------------------------------

    /** 從 Map 取值，key 不分大小寫；可傳入多個候選 key，取第一個找到的。 */
    @SuppressWarnings("unchecked")
    public static Object get(Object node, String... keys) {
        if (!(node instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) node;
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** 沿著路徑逐層取值，每層皆不分大小寫。 */
    public static Object dig(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            current = get(current, key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** 一律轉成 List；單一物件會被包成只有一個元素的 List，null 則回傳空 List。 */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node) {
        if (node instanceof List) {
            return (List<Object>) node;
        }
        List<Object> result = new ArrayList<Object>();
        if (node != null) {
            result.add(node);
        }
        return result;
    }

    /** 轉成字串；數字會去掉多餘的 .0。 */
    public static String str(Object node) {
        if (node == null) {
            return "";
        }
        if (node instanceof Double) {
            double d = (Double) node;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return node.toString();
    }

    // ------------------------------------------------------------------
    // 剖析實作
    // ------------------------------------------------------------------

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            return null;
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
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
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // '{'
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ':') {
                pos++;
            }
            map.put(key, readValue());
            skipWhitespace();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
            }
            break;
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<Object>();
        pos++; // '['
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return array;
        }
        while (pos < src.length()) {
            array.add(readValue());
            skipWhitespace();
            if (pos >= src.length()) {
                break;
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
            }
            break;
        }
        return array;
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        if (pos >= src.length() || src.charAt(pos) != '"') {
            return "";
        }
        pos++; // 開頭的引號
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                break;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (pos + 4 <= src.length()) {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    break;
                default:
                    sb.append(esc);
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        String token = src.substring(start, pos);
        try {
            return Double.valueOf(token);
        } catch (NumberFormatException e) {
            return token;
        }
    }

    private void expect(String word) {
        if (src.startsWith(word, pos)) {
            pos += word.length();
        } else {
            pos++;
        }
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }
}

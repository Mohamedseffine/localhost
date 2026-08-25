import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Simple JSON parser. */
public final class JsonParser {
    private final String src;
    private int pos;

    public JsonParser(String src) {
        if (src == null) throw new IllegalArgumentException("Null JSON");
        this.src = src;
    }

    public Object parse() {
        skipWs();
        Object value = value();
        skipWs();
        if (pos < src.length()) throw new IllegalArgumentException("Trailing data at " + pos);
        return value;
    }

    private Object value() {
        skipWs();
        if (pos >= src.length()) throw new IllegalArgumentException("Unexpected EOF");
        char ch = src.charAt(pos);
        return switch (ch) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        consume('{');
        skipWs();
        Map<String, Object> map = new LinkedHashMap<>();
        if (match('}')) return map;
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            consume(':');
            skipWs();
            if (map.containsKey(key)) throw new IllegalArgumentException("Duplicate key: " + key);
            map.put(key, value());
            skipWs();
            if (match('}')) return map;
            consume(',');
        }
    }

    private List<Object> array() {
        consume('[');
        skipWs();
        List<Object> list = new ArrayList<>();
        if (match(']')) return list;
        while (true) {
            list.add(value());
            skipWs();
            if (match(']')) return list;
            consume(',');
        }
    }

    private String string() {
        consume('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char ch = src.charAt(pos++);
            if (ch == '"') return sb.toString();
            if (ch != '\\') {
                if (ch < 0x20) throw new IllegalArgumentException("Control char in string");
                sb.append(ch);
                continue;
            }
            if (pos >= src.length()) throw new IllegalArgumentException("Bad escape");
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"', '\\', '/' -> sb.append(esc);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) throw new IllegalArgumentException("Bad unicode escape");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("Unknown escape: " + esc);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Long number() {
        int start = pos;
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        if (pos < src.length() && src.charAt(pos) == '0') {
            pos++;
            if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                throw new IllegalArgumentException("Leading zero not allowed");
            }
        } else {
            int digits = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos == digits) throw new IllegalArgumentException("Invalid number");
        }
        if (pos < src.length() && ".eE".indexOf(src.charAt(pos)) >= 0) {
            throw new IllegalArgumentException("Integer expected");
        }
        return Long.parseLong(src.substring(start, pos));
    }

    private Object literal(String expected, Object val) {
        if (!src.startsWith(expected, pos)) throw new IllegalArgumentException("Expected " + expected);
        pos += expected.length();
        return val;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean match(char ch) {
        if (pos < src.length() && src.charAt(pos) == ch) {
            pos++;
            return true;
        }
        return false;
    }

    private void consume(char ch) {
        if (!match(ch)) throw new IllegalArgumentException("Expected '" + ch + "' at " + pos);
    }
}
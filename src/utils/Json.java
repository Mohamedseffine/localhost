package utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean, lightweight, zero-dependency JSON parser and serializer.
 */
public final class Json {
    private final String src;
    private int cursor = 0;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String jsonText) {
        if (jsonText == null) return null;
        Json parser = new Json(jsonText);
        Object result = parser.parseValue();
        parser.skipWhitespace();
        if (parser.cursor < parser.src.length()) {
            throw new IllegalArgumentException("Trailing characters after JSON input at index " + parser.cursor);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String jsonText) {
        Object val = parse(jsonText);
        if (val instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected JSON object but got " + (val == null ? "null" : val.getClass().getSimpleName()));
    }

    private Object parseValue() {
        skipWhitespace();
        if (cursor >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        }
        char ch = src.charAt(cursor);
        return switch (ch) {
            case '{' -> parseObjectInternal();
            case '[' -> parseArrayInternal();
            case '"' -> parseStringInternal();
            case 't', 'f' -> parseBooleanInternal();
            case 'n' -> parseNullInternal();
            default -> {
                if (ch == '-' || (ch >= '0' && ch <= '9')) {
                    yield parseNumberInternal();
                }
                throw new IllegalArgumentException("Unexpected character '" + ch + "' at position " + cursor);
            }
        };
    }

    private Map<String, Object> parseObjectInternal() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            cursor++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("Expected string key in object at position " + cursor);
            }
            String key = parseStringInternal();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == '}') {
                cursor++;
                break;
            }
            expect(',');
        }
        return map;
    }

    private List<Object> parseArrayInternal() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            cursor++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') {
                cursor++;
                break;
            }
            expect(',');
        }
        return list;
    }

    private String parseStringInternal() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (cursor < src.length()) {
            char ch = src.charAt(cursor++);
            if (ch == '"') {
                return sb.toString();
            }
            if (ch == '\\') {
                if (cursor >= src.length()) {
                    throw new IllegalArgumentException("Unterminated escape sequence at " + cursor);
                }
                char esc = src.charAt(cursor++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > src.length()) throw new IllegalArgumentException("Invalid unicode escape");
                        String hex = src.substring(cursor, cursor + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        cursor += 4;
                    }
                    default -> throw new IllegalArgumentException("Unknown escape character '\\" + esc + "'");
                }
            } else {
                sb.append(ch);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Boolean parseBooleanInternal() {
        if (src.startsWith("true", cursor)) {
            cursor += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", cursor)) {
            cursor += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean literal at position " + cursor);
    }

    private Object parseNullInternal() {
        if (src.startsWith("null", cursor)) {
            cursor += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid null literal at position " + cursor);
    }

    private Number parseNumberInternal() {
        int start = cursor;
        if (src.charAt(cursor) == '-') cursor++;
        while (cursor < src.length() && Character.isDigit(src.charAt(cursor))) {
            cursor++;
        }
        boolean isFloat = false;
        if (cursor < src.length() && src.charAt(cursor) == '.') {
            isFloat = true;
            cursor++;
            while (cursor < src.length() && Character.isDigit(src.charAt(cursor))) {
                cursor++;
            }
        }
        if (cursor < src.length() && (src.charAt(cursor) == 'e' || src.charAt(cursor) == 'E')) {
            isFloat = true;
            cursor++;
            if (cursor < src.length() && (src.charAt(cursor) == '+' || src.charAt(cursor) == '-')) cursor++;
            while (cursor < src.length() && Character.isDigit(src.charAt(cursor))) {
                cursor++;
            }
        }
        String numStr = src.substring(start, cursor);
        if (isFloat) {
            return Double.parseDouble(numStr);
        }
        return Long.parseLong(numStr);
    }

    private void skipWhitespace() {
        while (cursor < src.length()) {
            char c = src.charAt(cursor);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                cursor++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (cursor >= src.length()) throw new IllegalArgumentException("Unexpected end of JSON input");
        return src.charAt(cursor);
    }

    private void expect(char c) {
        if (cursor >= src.length() || src.charAt(cursor) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' but found " + (cursor >= src.length() ? "EOF" : "'" + src.charAt(cursor) + "'"));
        }
        cursor++;
    }

    /**
     * Serializes any standard Java Object (Map, List, String, Number, Boolean, null) to a JSON string.
     */
    public static String stringify(Object obj) {
        StringBuilder sb = new StringBuilder();
        serialize(obj, sb);
        return sb.toString();
    }

    private static void serialize(Object obj, StringBuilder sb) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj);
        } else if (obj instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                serialize(e.getValue(), sb);
            }
            sb.append('}');
        } else if (obj instanceof Iterable<?> iter) {
            sb.append('[');
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(',');
                first = false;
                serialize(item, sb);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(obj.toString())).append('"');
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

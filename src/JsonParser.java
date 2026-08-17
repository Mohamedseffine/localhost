import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader used for the server configuration. */
public final class JsonParser {
    private final String input;
    private int position;

    public JsonParser(String input) {
        this.input = input;
    }

    public Object parse() {
        whitespace();
        Object value = value();
        whitespace();
        if (!finished()) fail("trailing data");
        return value;
    }

    private Object value() {
        if (finished()) return fail("expected value");
        return switch (input.charAt(position)) {
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
        expect('{');
        whitespace();
        Map<String, Object> result = new LinkedHashMap<>();
        if (take('}')) return result;
        while (true) {
            String key = string();
            whitespace();
            expect(':');
            whitespace();
            if (result.containsKey(key)) fail("duplicate key " + key);
            result.put(key, value());
            whitespace();
            if (take('}')) return result;
            expect(',');
            whitespace();
        }
    }

    private List<Object> array() {
        expect('[');
        whitespace();
        List<Object> result = new ArrayList<>();
        if (take(']')) return result;
        while (true) {
            result.add(value());
            whitespace();
            if (take(']')) return result;
            expect(',');
            whitespace();
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (!finished()) {
            char current = input.charAt(position++);
            if (current == '"') return result.toString();
            if (current != '\\') {
                if (current < 0x20) fail("control character");
                result.append(current);
                continue;
            }
            if (finished()) fail("bad escape");
            char escaped = input.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(unicode());
                default -> fail("bad escape");
            }
        }
        return fail("unterminated string");
    }

    private char unicode() {
        if (position + 4 > input.length()) return fail("bad unicode escape");
        try {
            char result = (char) Integer.parseInt(input.substring(position, position + 4), 16);
            position += 4;
            return result;
        } catch (NumberFormatException error) {
            return fail("bad unicode escape");
        }
    }

    private Long number() {
        int start = position;
        take('-');
        if (take('0')) {
            if (!finished() && Character.isDigit(input.charAt(position))) fail("leading zero");
        } else {
            int first = position;
            while (!finished() && Character.isDigit(input.charAt(position))) position++;
            if (first == position) return fail("invalid number");
        }
        if (!finished() && ".eE".indexOf(input.charAt(position)) >= 0) return fail("integer required");
        try {
            return Long.parseLong(input.substring(start, position));
        } catch (NumberFormatException error) {
            return fail("invalid integer");
        }
    }

    private Object literal(String word, Object value) {
        if (!input.startsWith(word, position)) return fail("unexpected token");
        position += word.length();
        return value;
    }

    private void whitespace() {
        while (!finished() && Character.isWhitespace(input.charAt(position))) position++;
    }

    private boolean take(char wanted) {
        if (!finished() && input.charAt(position) == wanted) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char wanted) {
        if (!take(wanted)) fail("expected " + wanted);
    }

    private boolean finished() {
        return position == input.length();
    }

    private <T> T fail(String message) {
        throw new IllegalArgumentException("Invalid JSON at " + position + ": " + message);
    }
}
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Binary-safe parser for the multipart fields needed by uploads. */
public final class MultipartParser {
    private MultipartParser() {}

    public record Part(String name, String filename, byte[] content) {}

    public static List<Part> read(byte[] body, String contentType) {
        String boundary = parameter(contentType, "boundary");
        if (boundary == null || boundary.isBlank() || boundary.length() > 200) {
            throw new IllegalArgumentException("Missing multipart boundary");
        }
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        if (find(body, 0, marker) != 0) throw new IllegalArgumentException("Bad multipart body");
        int position = marker.length;
        List<Part> parts = new ArrayList<>();
        while (position < body.length) {
            if (starts(body, position, new byte[] {'-', '-'})) return parts;
            if (!starts(body, position, new byte[] {'\r', '\n'})) throw new IllegalArgumentException("Bad boundary");
            position += 2;
            int headerEnd = find(body, position, new byte[] {'\r', '\n', '\r', '\n'});
            if (headerEnd < 0) throw new IllegalArgumentException("Bad multipart headers");
            String headers = new String(body, position, headerEnd - position, StandardCharsets.ISO_8859_1);
            String disposition = null;
            for (String line : headers.split("\\r\\n")) {
                if (line.toLowerCase().startsWith("content-disposition:")) disposition = line;
            }
            if (disposition == null) throw new IllegalArgumentException("Missing disposition");
            String name = parameter(disposition, "name");
            String filename = parameter(disposition, "filename");
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing field name");
            int contentStart = headerEnd + 4;
            int contentEnd = find(body, contentStart,
                    ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1));
            if (contentEnd < 0) throw new IllegalArgumentException("Unterminated multipart body");
            parts.add(new Part(name, filename, Arrays.copyOfRange(body, contentStart, contentEnd)));
            position = contentEnd + 2 + marker.length;
        }
        throw new IllegalArgumentException("Unterminated multipart body");
    }

    private static String parameter(String value, String name) {
        for (String item : value.split(";")) {
            int equals = item.indexOf('=');
            if (equals > 0 && item.substring(0, equals).trim().equalsIgnoreCase(name)) {
                String result = item.substring(equals + 1).trim();
                return result.startsWith("\"") && result.endsWith("\"")
                        ? result.substring(1, result.length() - 1) : result;
            }
        }
        return null;
    }

    private static boolean starts(byte[] input, int start, byte[] wanted) {
        if (start + wanted.length > input.length) return false;
        for (int i = 0; i < wanted.length; i++) if (input[start + i] != wanted[i]) return false;
        return true;
    }

    private static int find(byte[] input, int start, byte[] wanted) {
        outer:
        for (int i = start; i <= input.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) if (input[i + j] != wanted[j]) continue outer;
            return i;
        }
        return -1;
    }
}

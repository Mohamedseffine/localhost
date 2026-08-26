package webserver.delivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Multipart/form-data parser for file uploads. */
public final class MultipartParser {
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    private MultipartParser() {}

    public record Part(String name, String filename, byte[] content) {}

    public static List<Part> read(byte[] body, String contentType) {
        String boundary = param(contentType, "boundary");
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Missing boundary");
        }

        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        if (find(body, 0, marker) != 0) throw new IllegalArgumentException("Bad multipart body");

        int pos = marker.length;
        List<Part> parts = new ArrayList<>();

        while (pos < body.length) {
            if (starts(body, pos, new byte[] {'-', '-'})) return parts;
            if (!starts(body, pos, CRLF)) throw new IllegalArgumentException("Bad boundary line ending");
            pos += 2;

            int headerEnd = find(body, pos, CRLF_CRLF);
            if (headerEnd < 0) throw new IllegalArgumentException("Missing header delimiter");

            String headers = new String(body, pos, headerEnd - pos, StandardCharsets.ISO_8859_1);
            String disposition = null;
            for (String line : headers.split("\\r\\n")) {
                if (line.toLowerCase().startsWith("content-disposition:")) disposition = line;
            }
            if (disposition == null) throw new IllegalArgumentException("Missing disposition");

            String name = param(disposition, "name");
            String filename = param(disposition, "filename");
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing field name");

            int contentStart = headerEnd + 4;
            byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int contentEnd = find(body, contentStart, nextDelimiter);
            if (contentEnd < 0) throw new IllegalArgumentException("Unterminated multipart part");

            parts.add(new Part(name, filename, Arrays.copyOfRange(body, contentStart, contentEnd)));
            pos = contentEnd + 2 + marker.length;
        }
        throw new IllegalArgumentException("Unterminated multipart body");
    }

    private static String param(String header, String name) {
        if (header == null) return null;
        for (String segment : header.split(";")) {
            int eq = segment.indexOf('=');
            if (eq > 0 && segment.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String val = segment.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                    return val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return null;
    }

    private static boolean starts(byte[] src, int offset, byte[] prefix) {
        if (src == null || offset + prefix.length > src.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (src[offset + i] != prefix[i]) return false;
        }
        return true;
    }

    private static int find(byte[] src, int start, byte[] target) {
        if (src == null || target == null || start < 0) return -1;
        outer:
        for (int i = start; i <= src.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (src[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

package utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Clean multipart/form-data parser for file uploads.
 */
public final class Multipart {
    private Multipart() {}

    public record Part(String name, String filename, String contentType, byte[] data) {}

    public static List<Part> parse(byte[] body, String contentTypeHeader) {
        if (body == null || contentTypeHeader == null) {
            throw new IllegalArgumentException("Body or Content-Type is null");
        }
        String boundary = extractBoundary(contentTypeHeader);
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("No boundary found in Content-Type header");
        }

        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, boundaryBytes, 0);
        if (pos != 0) {
            throw new IllegalArgumentException("Body does not start with boundary");
        }

        List<Part> parts = new ArrayList<>();
        pos += boundaryBytes.length;

        while (pos < body.length) {
            // Check for closing delimiter "--"
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                return parts; // Normal completion
            }
            // Must have CRLF after boundary
            if (pos + 1 >= body.length || body[pos] != '\r' || body[pos + 1] != '\n') {
                throw new IllegalArgumentException("Expected CRLF after boundary");
            }
            pos += 2;

            // Find headers end \r\n\r\n
            byte[] headerEndDelim = new byte[]{'\r', '\n', '\r', '\n'};
            int headerEnd = indexOf(body, headerEndDelim, pos);
            if (headerEnd == -1) {
                throw new IllegalArgumentException("Malformed multipart headers");
            }

            String headersStr = new String(body, pos, headerEnd - pos, StandardCharsets.ISO_8859_1);
            String[] headerLines = headersStr.split("\r\n");

            String name = null;
            String filename = null;
            String partContentType = "text/plain";

            for (String line : headerLines) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String hName = line.substring(0, colon).trim().toLowerCase();
                    String hVal = line.substring(colon + 1).trim();
                    if ("content-disposition".equals(hName)) {
                        name = extractParam(hVal, "name");
                        filename = extractParam(hVal, "filename");
                    } else if ("content-type".equals(hName)) {
                        partContentType = hVal;
                    }
                }
            }

            if (name == null) {
                throw new IllegalArgumentException("Missing 'name' in Content-Disposition");
            }

            int contentStart = headerEnd + 4;
            byte[] nextBoundary = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int nextBoundaryPos = indexOf(body, nextBoundary, contentStart);
            if (nextBoundaryPos == -1) {
                throw new IllegalArgumentException("Unclosed multipart part");
            }

            byte[] partData = Arrays.copyOfRange(body, contentStart, nextBoundaryPos);
            parts.add(new Part(name, filename, partContentType, partData));

            pos = nextBoundaryPos + 2 + boundaryBytes.length; // skip \r\n--boundary
        }

        throw new IllegalArgumentException("Unterminated multipart body");
    }

    private static String extractBoundary(String contentType) {
        for (String segment : contentType.split(";")) {
            int eq = segment.indexOf('=');
            if (eq > 0 && segment.substring(0, eq).trim().equalsIgnoreCase("boundary")) {
                String val = segment.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                    return val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return null;
    }

    private static String extractParam(String headerValue, String paramName) {
        for (String seg : headerValue.split(";")) {
            int eq = seg.indexOf('=');
            if (eq > 0 && seg.substring(0, eq).trim().equalsIgnoreCase(paramName)) {
                String val = seg.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                    return val.substring(1, val.length() - 1);
                }
                return val;
            }
        }
        return null;
    }

    private static int indexOf(byte[] src, byte[] target, int fromIndex) {
        if (src == null || target == null || fromIndex < 0) return -1;
        outer:
        for (int i = fromIndex; i <= src.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (src[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

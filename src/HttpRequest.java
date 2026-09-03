import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Robust HTTP/1.1 Request model and stream parser.
 */
public final class HttpRequest {
    public static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};
    private static final byte[] CRLF = {'\r', '\n'};

    private final String method;
    private final String uri;
    private final String path;
    private final String queryString;
    private final String version;
    private final Map<String, String> headers;
    private final byte[] body;

    public HttpRequest(String method, String uri, String version, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.uri = uri;
        int qIdx = uri.indexOf('?');
        if (qIdx >= 0) {
            this.path = uri.substring(0, qIdx);
            this.queryString = uri.substring(qIdx + 1);
        } else {
            this.path = uri;
            this.queryString = "";
        }
        this.version = version;
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body == null ? new byte[0] : body;
    }

    public String method() { return method; }
    public String uri() { return uri; }
    public String path() { return path; }
    public String queryString() { return queryString; }
    public String version() { return version; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body; }

    public String header(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public enum ParseState { COMPLETE, INCOMPLETE, ERROR }

    public record ParseResult(ParseState state, HttpRequest request, int errorCode, int bytesConsumed) {
        public static ParseResult incomplete() {
            return new ParseResult(ParseState.INCOMPLETE, null, 0, 0);
        }
        public static ParseResult error(int code) {
            return new ParseResult(ParseState.ERROR, null, code, 0);
        }
        public static ParseResult complete(HttpRequest req, int bytesConsumed) {
            return new ParseResult(ParseState.COMPLETE, req, 0, bytesConsumed);
        }
    }

    /**
     * Parses a raw byte buffer into an HTTP request.
     */
    public static ParseResult parse(byte[] data, int length, long maxBodySize) {
        if (data == null || length == 0) return ParseResult.incomplete();

        int headerEnd = indexOf(data, 0, length, CRLF_CRLF);
        if (headerEnd < 0) {
            return length > MAX_HEADER_BYTES ? ParseResult.error(400) : ParseResult.incomplete();
        }
        if (headerEnd > MAX_HEADER_BYTES) {
            return ParseResult.error(400);
        }

        String headerBlock = new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = headerBlock.split("\r\n");
        if (lines.length == 0) return ParseResult.error(400);

        String[] reqLine = lines[0].trim().split("\\s+");
        if (reqLine.length != 3 || !reqLine[2].equals("HTTP/1.1") || !reqLine[1].startsWith("/")) {
            return ParseResult.error(400);
        }

        String method = reqLine[0];
        String uri = reqLine[1];
        String version = reqLine[2];

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) return ParseResult.error(400);
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty()) return ParseResult.error(400);
            headers.merge(name, value, (oldVal, newVal) -> oldVal + ", " + newVal);
        }

        if (!headers.containsKey("host")) {
            return ParseResult.error(400);
        }

        int bodyStart = headerEnd + 4;
        String te = headers.getOrDefault("transfer-encoding", "");
        boolean isChunked = te.equalsIgnoreCase("chunked");

        // GET requests must not carry a body
        if ("GET".equalsIgnoreCase(method)) {
            if (isChunked) return ParseResult.error(400);
            if (headers.containsKey("content-length")) {
                try {
                    long cl = Long.parseLong(headers.get("content-length"));
                    if (cl != 0) return ParseResult.error(400);
                } catch (NumberFormatException e) {
                    return ParseResult.error(400);
                }
            }
            return ParseResult.complete(new HttpRequest(method, uri, version, headers, new byte[0]), bodyStart);
        }

        if (isChunked) {
            if (headers.containsKey("content-length")) {
                return ParseResult.error(400);
            }
            ChunkResult chunkResult = parseChunks(data, bodyStart, length, maxBodySize);
            if (chunkResult.errorCode != 0) {
                return ParseResult.error(chunkResult.errorCode);
            }
            if (!chunkResult.isComplete) {
                return ParseResult.incomplete();
            }
            return ParseResult.complete(new HttpRequest(method, uri, version, headers, chunkResult.body), chunkResult.endPos);
        } else {
            long contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException e) {
                    return ParseResult.error(400);
                }
            }
            if (contentLength < 0) return ParseResult.error(400);
            if (contentLength > maxBodySize || contentLength > Integer.MAX_VALUE) {
                return ParseResult.error(413);
            }

            int needed = bodyStart + (int) contentLength;
            if (length < needed) {
                return ParseResult.incomplete();
            }

            byte[] body = Arrays.copyOfRange(data, bodyStart, needed);
            return ParseResult.complete(new HttpRequest(method, uri, version, headers, body), needed);
        }
    }

    private record ChunkResult(boolean isComplete, byte[] body, int errorCode, int endPos) {}

    private static ChunkResult parseChunks(byte[] data, int startPos, int length, long maxBodySize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = startPos;

        while (true) {
            int lineEnd = indexOf(data, pos, length, CRLF);
            if (lineEnd < 0) {
                return new ChunkResult(false, null, 0, 0);
            }

            String sizeHex = new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII).trim();
            int semi = sizeHex.indexOf(';');
            if (semi >= 0) sizeHex = sizeHex.substring(0, semi).trim();

            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizeHex, 16);
            } catch (NumberFormatException e) {
                return new ChunkResult(false, null, 400, 0);
            }

            if (chunkSize < 0 || chunkSize > Integer.MAX_VALUE || out.size() + chunkSize > maxBodySize) {
                return new ChunkResult(false, null, 413, 0);
            }

            pos = lineEnd + 2;
            if (chunkSize == 0) {
                // Final chunk: expect CRLF
                if (pos + 2 > length) {
                    return new ChunkResult(false, null, 0, 0);
                }
                if (data[pos] != '\r' || data[pos + 1] != '\n') {
                    return new ChunkResult(false, null, 400, 0);
                }
                return new ChunkResult(true, out.toByteArray(), 0, pos + 2);
            }

            long chunkEnd = (long) pos + chunkSize;
            if (chunkEnd + 2 > length) {
                return new ChunkResult(false, null, 0, 0);
            }
            if (data[(int) chunkEnd] != '\r' || data[(int) chunkEnd + 1] != '\n') {
                return new ChunkResult(false, null, 400, 0);
            }

            out.write(data, pos, (int) chunkSize);
            pos = (int) chunkEnd + 2;
        }
    }

    private static int indexOf(byte[] data, int start, int length, byte[] target) {
        if (data == null || target == null || start < 0) return -1;
        outer:
        for (int i = start; i <= length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}

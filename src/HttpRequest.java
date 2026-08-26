import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable HTTP/1.1 request representation and parser. */
public record HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
    public static final int MAX_HEADERS = 64 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    public String header(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }

    public static Result parse(byte[] data, long maxBodySize) {
        if (data == null || data.length == 0) return Result.incomplete();

        int headerEnd = find(data, 0, CRLF_CRLF);
        if (headerEnd < 0) return data.length > MAX_HEADERS ? Result.error(400) : Result.incomplete();
        if (headerEnd > MAX_HEADERS) return Result.error(400);

        String headerText = new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\\r\\n");
        if (lines.length == 0) return Result.error(400);

        String[] reqLine = lines[0].trim().split("\\s+");
        if (reqLine.length != 3 || !"HTTP/1.1".equals(reqLine[2]) || !reqLine[1].startsWith("/")) {
            return Result.error(400);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) return Result.error(400);
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (name.isEmpty()) return Result.error(400);
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        if (!headers.containsKey("host")) return Result.error(400);

        String method = reqLine[0];
        String target = reqLine[1];
        int bodyStart = headerEnd + 4;
        String te = headers.getOrDefault("transfer-encoding", "");
        boolean chunked = !te.isEmpty();

        if (HttpMethods.GET.equals(method)) {
            if (chunked) return Result.error(400);
            if (headers.containsKey("content-length")) {
                try {
                    if (Long.parseLong(headers.get("content-length")) != 0) return Result.error(400);
                } catch (NumberFormatException e) {
                    return Result.error(400);
                }
            }
            return Result.complete(new HttpRequest(method, target, Collections.unmodifiableMap(headers), new byte[0]));
        }

        if (!HttpMethods.POST.equals(method) && !HttpMethods.DELETE.equals(method)) {
            return Result.complete(new HttpRequest(method, target, Collections.unmodifiableMap(headers), new byte[0]));
        }

        byte[] body;
        if (chunked) {
            if (!"chunked".equalsIgnoreCase(te.trim()) || headers.containsKey("content-length")) {
                return Result.error(400);
            }
            ChunkResult cr = parseChunks(data, bodyStart, maxBodySize);
            if (cr.code != 0) return Result.error(cr.code);
            if (!cr.complete) return Result.incomplete();
            body = cr.body;
        } else {
            long length = 0;
            if (headers.containsKey("content-length")) {
                try {
                    length = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException e) {
                    return Result.error(400);
                }
            }
            if (length < 0) return Result.error(400);
            if (length > maxBodySize || length > Integer.MAX_VALUE) return Result.error(413);
            if ((long) bodyStart + length > data.length) return Result.incomplete();
            body = Arrays.copyOfRange(data, bodyStart, bodyStart + (int) length);
        }

        return Result.complete(new HttpRequest(method, target, Collections.unmodifiableMap(headers), body));
    }

    private static ChunkResult parseChunks(byte[] data, int pos, long maxSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int lineEnd = find(data, pos, CRLF);
            if (lineEnd < 0) return ChunkResult.incomplete();

            String sizeStr = new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int semi = sizeStr.indexOf(';');
            if (semi >= 0) sizeStr = sizeStr.substring(0, semi);

            final long size;
            try {
                size = Long.parseLong(sizeStr.trim(), 16);
            } catch (NumberFormatException e) {
                return ChunkResult.error(400);
            }

            if (size < 0 || size > Integer.MAX_VALUE || out.size() + size > maxSize) {
                return ChunkResult.error(413);
            }

            pos = lineEnd + 2;
            if (size == 0) {
                if (pos + 2 > data.length) return ChunkResult.incomplete();
                if (data[pos] != '\r' || data[pos + 1] != '\n') return ChunkResult.error(400);
                return ChunkResult.complete(out.toByteArray());
            }

            long end = (long) pos + size;
            if (end + 2 > data.length) return ChunkResult.incomplete();
            if (data[(int) end] != '\r' || data[(int) end + 1] != '\n') return ChunkResult.error(400);

            out.write(data, pos, (int) size);
            pos = (int) end + 2;
        }
    }

    private static int find(byte[] src, int start, byte[] pat) {
        if (src == null || pat == null || start < 0) return -1;
        outer:
        for (int i = start; i <= src.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public record Result(State state, HttpRequest request, int errorCode) {
        public enum State { INCOMPLETE, COMPLETE, ERROR }
        public static Result incomplete() { return new Result(State.INCOMPLETE, null, 0); }
        public static Result complete(HttpRequest r) { return new Result(State.COMPLETE, r, 0); }
        public static Result error(int c) { return new Result(State.ERROR, null, c); }
    }

    private record ChunkResult(boolean complete, byte[] body, int code) {
        static ChunkResult incomplete() { return new ChunkResult(false, null, 0); }
        static ChunkResult complete(byte[] b) { return new ChunkResult(true, b, 0); }
        static ChunkResult error(int c) { return new ChunkResult(false, null, c); }
    }
}

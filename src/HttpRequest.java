import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Incremental HTTP/1.1 request parser with fixed and chunked body support. */
public record HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
    private static final int MAX_HEADERS = 64 * 1024;

    public String header(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }

    public static Result parse(byte[] data, long maxBodySize) {
        int headerEnd = locate(data, 0, new byte[] {'\r', '\n', '\r', '\n'});
        if (headerEnd < 0) return data.length > MAX_HEADERS ? Result.error(400) : Result.incomplete();
        if (headerEnd > MAX_HEADERS) return Result.error(400);
        String[] lines = new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\\r\\n");
        if (lines.length == 0) return Result.error(400);
        String[] first = lines[0].trim().split("\\s+");
        if (first.length != 3 || !first[2].equals("HTTP/1.1") || !first[1].startsWith("/")) {
            return Result.error(400);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) return Result.error(400);
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (name.isEmpty()) return Result.error(400);
            headers.merge(name, value, (left, right) -> left + ", " + right);
        }
        if (!headers.containsKey("host")) return Result.error(400);

        int bodyStart = headerEnd + 4;
        byte[] body;
        String transfer = headers.getOrDefault("transfer-encoding", "");
        if (!first[0].equals("GET") && !first[0].equals("POST") && !first[0].equals("DELETE")) {
            return Result.complete(new HttpRequest(
                    first[0], first[1], Map.copyOf(headers), new byte[0]));
        }
        if (first[0].equals("GET")) {
            if (!transfer.isEmpty()) return Result.error(400);
            if (headers.containsKey("content-length")) {
                final long length;
                try {
                    length = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException error) {
                    return Result.error(400);
                }
                if (length != 0) return Result.error(400);
            }
            return Result.complete(new HttpRequest(
                    first[0], first[1], Map.copyOf(headers), new byte[0]));
        }
        if (!transfer.isEmpty()) {
            if (!transfer.equalsIgnoreCase("chunked") || headers.containsKey("content-length")) {
                return Result.error(400);
            }
            Chunk chunk = readChunks(data, bodyStart, maxBodySize);
            if (chunk.error != 0) return Result.error(chunk.error);
            if (!chunk.complete) return Result.incomplete();
            body = chunk.body;
        } else {
            long length = 0;
            if (headers.containsKey("content-length")) {
                try {
                    length = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException error) {
                    return Result.error(400);
                }
            }
            if (length < 0) return Result.error(400);
            if (length > maxBodySize || length > Integer.MAX_VALUE) return Result.error(413);
            if ((long) bodyStart + length > data.length) return Result.incomplete();
            body = Arrays.copyOfRange(data, bodyStart, bodyStart + (int) length);
        }
        return Result.complete(new HttpRequest(first[0], first[1], Map.copyOf(headers), body));
    }

    private static Chunk readChunks(byte[] data, int position, long maxBodySize) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int lineEnd = locate(data, position, new byte[] {'\r', '\n'});
            if (lineEnd < 0) return Chunk.incomplete();
            String line = new String(data, position, lineEnd - position, StandardCharsets.US_ASCII);
            int semicolon = line.indexOf(';');
            if (semicolon >= 0) line = line.substring(0, semicolon);
            final long size;
            try {
                size = Long.parseLong(line.trim(), 16);
            } catch (NumberFormatException error) {
                return Chunk.error(400);
            }
            if (size < 0 || size > Integer.MAX_VALUE || body.size() + size > maxBodySize) {
                return Chunk.error(413);
            }
            position = lineEnd + 2;
            if (size == 0) {
                if (position + 2 > data.length) return Chunk.incomplete();
                if (data[position] != '\r' || data[position + 1] != '\n') return Chunk.error(400);
                return Chunk.complete(body.toByteArray());
            }
            long end = (long) position + size;
            if (end + 2 > data.length) return Chunk.incomplete();
            if (data[(int) end] != '\r' || data[(int) end + 1] != '\n') return Chunk.error(400);
            body.write(data, position, (int) size);
            position = (int) end + 2;
        }
    }

    private static int locate(byte[] input, int start, byte[] wanted) {
        outer:
        for (int i = start; i <= input.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) {
                if (input[i + j] != wanted[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public record Result(State state, HttpRequest request, int errorCode) {
        public enum State { INCOMPLETE, COMPLETE, ERROR }
        static Result incomplete() { return new Result(State.INCOMPLETE, null, 0); }
        static Result complete(HttpRequest request) { return new Result(State.COMPLETE, request, 0); }
        static Result error(int code) { return new Result(State.ERROR, null, code); }
    }

    private record Chunk(boolean complete, byte[] body, int error) {
        static Chunk incomplete() { return new Chunk(false, null, 0); }
        static Chunk complete(byte[] body) { return new Chunk(true, body, 0); }
        static Chunk error(int code) { return new Chunk(false, null, code); }
    }
}

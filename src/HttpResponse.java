import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP/1.1 response builder and non-blocking byte serializer.
 */
public final class HttpResponse {
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body;
    private ByteBuffer outBuffer = null;

    public HttpResponse(int statusCode) {
        this(statusCode, reasonFor(statusCode), new byte[0]);
    }

    public HttpResponse(int statusCode, String reasonPhrase, byte[] body) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase != null ? reasonPhrase : reasonFor(statusCode);
        this.body = body == null ? new byte[0] : body;
        header("Server", "LocalServer/2.0 (Java NIO)");
    }

    public int status() { return statusCode; }
    public byte[] body() { return body; }

    public HttpResponse body(byte[] body) {
        this.body = body == null ? new byte[0] : body;
        return this;
    }

    public HttpResponse body(String text, String contentType) {
        this.body = text.getBytes(StandardCharsets.UTF_8);
        header("Content-Type", contentType);
        return this;
    }

    public HttpResponse header(String name, String value) {
        if (name == null || value == null) return this;
        // Prevent HTTP header injection (CRLF)
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 ||
            value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Header contains invalid CRLF characters");
        }
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public HttpResponse setHeader(String name, String value) {
        headers.remove(name);
        return header(name, value);
    }

    public static String reasonFor(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Payload Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }

    /**
     * Prepares the serialized byte buffer if not already done.
     */
    public void prepare() {
        if (outBuffer != null) return;

        // Ensure Content-Length or Content-Type
        if (!hasHeader("Content-Length")) {
            header("Content-Length", String.valueOf(body.length));
        }
        if (!hasHeader("Content-Type") && body.length > 0) {
            header("Content-Type", "text/plain; charset=utf-8");
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reasonPhrase).append("\r\n");

        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String val : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(val).append("\r\n");
            }
        }
        sb.append("\r\n");

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(headerBytes.length + body.length);
        buf.put(headerBytes);
        buf.put(body);
        buf.flip();
        this.outBuffer = buf;
    }

    private boolean hasHeader(String name) {
        for (String k : headers.keySet()) {
            if (k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Performs a single non-blocking write to the socket channel.
     * @return true if all bytes have been written, false if more bytes remain.
     */
    public boolean writeTo(SocketChannel channel) throws IOException {
        prepare();
        if (outBuffer.hasRemaining()) {
            int written = channel.write(outBuffer);
            if (written < 0) {
                throw new IOException("Socket channel closed during write");
            }
        }
        return !outBuffer.hasRemaining();
    }
}

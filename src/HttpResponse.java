import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** HTTP/1.1 response model and non-blocking writer. */
public final class HttpResponse {
    public static final int CHUNK_THRESHOLD = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final List<String> headers = new ArrayList<>();

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = (contentType == null || contentType.isBlank()) ? "text/plain; charset=utf-8" : contentType;
        this.body = (body == null) ? new byte[0] : body;
    }

    public HttpResponse header(String name, String value) {
        if (name.contains("\r") || name.contains("\n") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("CRLF in header");
        }
        headers.add(name + ": " + value);
        return this;
    }

    public Writer writer() {
        boolean chunked = body.length > CHUNK_THRESHOLD;
        StringBuilder sb = new StringBuilder(128)
                .append("HTTP/1.1 ").append(status).append(' ').append(HttpCodes.reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");

        if (chunked) sb.append("Transfer-Encoding: chunked\r\n");
        else sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");

        for (String h : headers) sb.append(h).append("\r\n");
        sb.append("\r\n");

        return new Writer(sb.toString().getBytes(StandardCharsets.ISO_8859_1), body, chunked);
    }

    public byte[] bytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Writer w = writer();
            WritableByteChannel ch = Channels.newChannel(out);
            while (!w.complete()) w.writeTo(ch);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Writer {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

        private final byte[] body;
        private final boolean chunked;
        private ByteBuffer current;
        private Stage stage = Stage.HEADERS;
        private int pos = 0;
        private int chunkSize = 0;

        private Writer(byte[] headers, byte[] body, boolean chunked) {
            this.body = body;
            this.chunked = chunked;
            this.current = ByteBuffer.wrap(headers);
        }

        public int writeTo(WritableByteChannel channel) throws IOException {
            if (current == null) return 0;
            int n = channel.write(current);
            if (n < 0) throw new IOException("Channel closed");
            if (!current.hasRemaining()) step();
            return n;
        }

        public boolean complete() {
            return stage == Stage.DONE;
        }

        private void step() {
            switch (stage) {
                case HEADERS -> {
                    if (chunked) nextChunk();
                    else if (body.length == 0) finish();
                    else {
                        current = ByteBuffer.wrap(body);
                        stage = Stage.BODY;
                    }
                }
                case BODY, LAST_CHUNK -> finish();
                case CHUNK_HEAD -> {
                    current = ByteBuffer.wrap(body, pos, chunkSize).slice();
                    stage = Stage.CHUNK_BODY;
                }
                case CHUNK_BODY -> {
                    current = ByteBuffer.wrap(CRLF);
                    stage = Stage.CHUNK_TAIL;
                }
                case CHUNK_TAIL -> {
                    pos += chunkSize;
                    nextChunk();
                }
                case DONE -> current = null;
            }
        }

        private void nextChunk() {
            if (pos == body.length) {
                current = ByteBuffer.wrap(LAST_CHUNK);
                stage = Stage.LAST_CHUNK;
                return;
            }
            chunkSize = Math.min(CHUNK_SIZE, body.length - pos);
            String head = Integer.toHexString(chunkSize) + "\r\n";
            current = ByteBuffer.wrap(head.getBytes(StandardCharsets.US_ASCII));
            stage = Stage.CHUNK_HEAD;
        }

        private void finish() {
            current = null;
            stage = Stage.DONE;
        }

        private enum Stage { HEADERS, BODY, CHUNK_HEAD, CHUNK_BODY, CHUNK_TAIL, LAST_CHUNK, DONE }
    }
}

package webserver.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Response value object with a resumable wire encoder. */
public final class HttpResponse {
    public static final int CHUNK_THRESHOLD = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final List<String> headers = new ArrayList<>();

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType == null || contentType.isBlank()
            ? "text/plain; charset=utf-8" : contentType;
        this.body = body == null ? new byte[0] : body.clone();
    }

    public HttpResponse header(String name, String value) {
        rejectLineBreaks(name, value);
        headers.add(name + ": " + value);
        return this;
    }

    private static void rejectLineBreaks(String name, String value) {
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("CRLF in header");
        }
    }

    public Writer writer() {
        boolean chunked = body.length > CHUNK_THRESHOLD;
        StringBuilder sb = new StringBuilder(128)
                .append("HTTP/1.1 ").append(status).append(' ').append(HttpCodes.reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");
        appendLength(sb, chunked);
        sb.append("Connection: close\r\n");

        for (String h : headers) sb.append(h).append("\r\n");
        sb.append("\r\n");

        return new Writer(sb.toString().getBytes(StandardCharsets.ISO_8859_1), body, chunked);
    }

    private void appendLength(StringBuilder headers, boolean chunked) {
        headers.append(chunked ? "Transfer-Encoding: chunked\r\n"
                : "Content-Length: " + body.length + "\r\n");
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
                    advanceBody();
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

        private void advanceBody() {
            if (chunked) {
                nextChunk();
            } else if (body.length == 0) {
                finish();
            } else {
                current = ByteBuffer.wrap(body);
                stage = Stage.BODY;
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

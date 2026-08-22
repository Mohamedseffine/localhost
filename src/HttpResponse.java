import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small HTTP response serializer. */
public final class HttpResponse {
    private static final int CHUNK_THRESHOLD = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final List<String> headers = new ArrayList<>();

    public HttpResponse(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public HttpResponse header(String name, String value) {
        if (name.contains("\r") || name.contains("\n") || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Invalid header");
        }
        headers.add(name + ": " + value);
        return this;
    }

    public Writer writer() {
        boolean chunked = body.length > CHUNK_THRESHOLD;
        StringBuilder text = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ').append(HttpCodes.reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");
        if (chunked) text.append("Transfer-Encoding: chunked\r\n");
        else text.append("Content-Length: ").append(body.length).append("\r\n");
        text
                .append("Connection: close\r\n");
        for (String header : headers) text.append(header).append("\r\n");
        text.append("\r\n");
        return new Writer(text.toString().getBytes(StandardCharsets.ISO_8859_1), body, chunked);
    }

    public byte[] bytes() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Writer responseWriter = writer();
            WritableByteChannel channel = Channels.newChannel(output);
            while (!responseWriter.complete()) responseWriter.writeTo(channel);
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Writes one response incrementally without assembling a second full payload in memory. */
    public static final class Writer {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

        private final byte[] body;
        private final boolean chunked;
        private ByteBuffer current;
        private Stage stage = Stage.HEADERS;
        private int bodyPosition;
        private int currentChunkSize;

        private Writer(byte[] headers, byte[] body, boolean chunked) {
            this.body = body;
            this.chunked = chunked;
            this.current = ByteBuffer.wrap(headers);
        }

        public int writeTo(WritableByteChannel channel) throws IOException {
            if (current == null) return 0;
            int written = channel.write(current);
            if (written < 0) throw new IOException("Channel closed while writing response");
            if (!current.hasRemaining()) advance();
            return written;
        }

        public boolean complete() {
            return stage == Stage.DONE;
        }

        private void advance() {
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
                case CHUNK_SIZE -> {
                    current = ByteBuffer.wrap(body, bodyPosition, currentChunkSize).slice();
                    stage = Stage.CHUNK_DATA;
                }
                case CHUNK_DATA -> {
                    current = ByteBuffer.wrap(CRLF);
                    stage = Stage.CHUNK_END;
                }
                case CHUNK_END -> {
                    bodyPosition += currentChunkSize;
                    nextChunk();
                }
                case DONE -> current = null;
            }
        }

        private void nextChunk() {
            if (bodyPosition == body.length) {
                current = ByteBuffer.wrap(LAST_CHUNK);
                stage = Stage.LAST_CHUNK;
                return;
            }
            currentChunkSize = Math.min(CHUNK_SIZE, body.length - bodyPosition);
            String size = Integer.toHexString(currentChunkSize) + "\r\n";
            current = ByteBuffer.wrap(size.getBytes(StandardCharsets.US_ASCII));
            stage = Stage.CHUNK_SIZE;
        }

        private void finish() {
            current = null;
            stage = Stage.DONE;
        }

        private enum Stage { HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, CHUNK_END, LAST_CHUNK, DONE }
    }

    public static String reason(int status) { return HttpCodes.reason(status); }
}

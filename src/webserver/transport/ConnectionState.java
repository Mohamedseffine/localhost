package webserver.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;

/** Mutable per-channel state attached to selector keys. */
public final class ConnectionState {
    private ConnectionState() {}

    public record Listener(List<ConfigLoader.VirtualServer> servers) {}

    public static final class Client {
        final List<ConfigLoader.VirtualServer> servers;
        private final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private HttpResponse.Writer writer;
        private long lastActivity = System.nanoTime();

        public Client(List<ConfigLoader.VirtualServer> servers) {
            this.servers = servers;
        }

        public int read(SocketChannel channel) throws IOException {
            buffer.clear();
            int n = channel.read(buffer);
            if (n > 0) {
                buffer.flip();
                byte[] temp = new byte[buffer.remaining()];
                buffer.get(temp);
                input.write(temp);
                lastActivity = System.nanoTime();
            }
            return n;
        }

        public byte[] requestBytes() { return input.toByteArray(); }
        public List<ConfigLoader.VirtualServer> servers() { return servers; }
        public HttpResponse.Writer writer() { return writer; }
        public long lastActivity() { return lastActivity; }

        public void attach(HttpResponse response, SelectionKey key) {
            this.writer = response.writer();
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }
}
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

/** Attachments held by selector keys for listeners and client connections. */
public final class ConnectionState {
    private ConnectionState() {}

    public record Listener(List<ConfigLoader.VirtualServer> servers) {}

    public static final class Client {
        final List<ConfigLoader.VirtualServer> servers;
        final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        HttpResponse.Writer output;
        long lastActivity = System.nanoTime();

        Client(List<ConfigLoader.VirtualServer> servers) {
            this.servers = servers;
        }

        void attach(HttpResponse response, SelectionKey key) {
            output = response.writer();
            key.interestOps(SelectionKey.OP_WRITE);
        }

        int read(SocketChannel channel) throws java.io.IOException {
            return channel.read(buffer);
        }
    }
}
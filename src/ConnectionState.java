import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

/** Attachments held by selector keys. */
public final class ConnectionState {
    private ConnectionState() {}

    public record Listener(List<ConfigLoader.VirtualServer> servers) {}

    public static final class Client {
        final List<ConfigLoader.VirtualServer> servers;
        final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        HttpResponse.Writer writer;
        long lastActivity = System.nanoTime();

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

        public void attach(HttpResponse response, SelectionKey key) {
            this.writer = response.writer();
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }
}
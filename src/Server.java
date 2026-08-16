import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** One-thread, non-blocking server lifecycle and selector loop. */
public final class Server implements AutoCloseable {
    private static final int LISTEN_BACKLOG = 1024;

    private final ConfigLoader.Config config;
    private final Router router;
    private final Selector selector = Selector.open();
    private final List<ServerSocketChannel> listeners = new ArrayList<>();
    private volatile boolean running = true;

    public Server(ConfigLoader.Config config) throws IOException {
        this.config = config;
        this.router = new Router(config);
        List<String> failures = new ArrayList<>();
        for (InetSocketAddress address : config.listenAddresses()) {
            ServerSocketChannel channel = null;
            try {
                channel = ServerSocketChannel.open();
                channel.configureBlocking(false);
                channel.bind(address, LISTEN_BACKLOG);
                List<ConfigLoader.VirtualServer> candidates =
                        config.serversFor(address.getHostString(), address.getPort());
                if (candidates.isEmpty()) throw new IllegalArgumentException("No virtual server candidates");
                channel.register(selector, SelectionKey.OP_ACCEPT,
                    new ConnectionState.Listener(candidates));
                listeners.add(channel);
                System.out.println("Listening on http://" + address.getHostString() + ":" + address.getPort());
            } catch (IOException | IllegalArgumentException error) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // The failed listener is already closed.
                    }
                }
                String failure = address.getHostString() + ":" + address.getPort()
                        + " (" + error.getMessage() + ")";
                failures.add(failure);
                System.err.println("Ignoring failed listener: " + failure);
            }
        }
        if (listeners.isEmpty()) {
            close();
            throw new IOException("No listener could be started: " + String.join(", ", failures));
        }
    }

    public void run() throws IOException {
        while (running) {
            selector.select(1000);
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) continue;
                try {
                    if (key.isAcceptable()) accept(key);
                    else if (key.isReadable()) read(key);
                    else if (key.isWritable()) write(key);
                } catch (Exception error) {
                    System.err.println("Connection error: " + error.getMessage());
                    close(key);
                }
            }
            expireClients();
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ConnectionState.Listener listener = (ConnectionState.Listener) key.attachment();
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel channel;
        while ((channel = server.accept()) != null) {
            channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_READ,
                    new ConnectionState.Client(listener.servers()));
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ConnectionState.Client client = (ConnectionState.Client) key.attachment();
        int read = client.read(channel);
        client.lastActivity = System.nanoTime();
        if (read < 0) {
            if (client.input.size() == 0) close(key);
            else prepare(key, FaultPages.response(config, 400));
            return;
        }
        if (read == 0) return;
        client.buffer.flip();
        byte[] bytes = new byte[client.buffer.remaining()];
        client.buffer.get(bytes);
        client.buffer.clear();
        client.input.write(bytes);
        HttpRequest.Result result = HttpRequest.parse(client.input.toByteArray(), config.maxBodySize());
        if (result.state() == HttpRequest.Result.State.ERROR) {
            prepare(key, FaultPages.response(config, result.errorCode()));
        } else if (result.state() == HttpRequest.Result.State.COMPLETE) {
            ConfigLoader.VirtualServer selected = config.selectServer(
                    result.request().header("host"), client.servers);
            prepare(key, router.handle(result.request(), selected));
        } else if (client.input.size() > config.maxBodySize() + 1024 * 1024L) {
            prepare(key, FaultPages.response(config, 413));
        }
    }

    private void prepare(SelectionKey key, HttpResponse response) {
        ConnectionState.Client client = (ConnectionState.Client) key.attachment();
        client.attach(response, key);
    }

    private void write(SelectionKey key) throws IOException {
        ConnectionState.Client client = (ConnectionState.Client) key.attachment();
        int written = client.output.writeTo((SocketChannel) key.channel());
        if (written > 0) client.lastActivity = System.nanoTime();
        if (client.output.complete()) close(key);
    }

    private void expireClients() {
        long cutoff = System.nanoTime() - config.requestTimeoutSeconds() * 1_000_000_000L;
        for (SelectionKey key : selector.keys()) {
                if (key.attachment() instanceof ConnectionState.Client client
                    && client.lastActivity < cutoff) {
                if (client.output == null) prepare(key, FaultPages.response(config, 408));
                else close(key);
            }
        }
    }

    private static void close(SelectionKey key) {
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        selector.wakeup();
        for (ServerSocketChannel listener : listeners) listener.close();
        if (selector.isOpen()) {
            for (SelectionKey key : new ArrayList<>(selector.keys())) close(key);
            selector.close();
        }
    }

}

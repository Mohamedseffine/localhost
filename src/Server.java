import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Non-blocking NIO HTTP Server. */
public final class Server implements Closeable, Runnable {
    private final ConfigLoader.Config config;
    private final Router router;
    private final Selector selector;
    private final List<ServerSocketChannel> listeners = new ArrayList<>();
    private volatile boolean running = true;

    public Server(ConfigLoader.Config config) throws IOException {
        this.config = config;
        this.router = new Router(config);
        this.selector = Selector.open();
        try {
            for (InetSocketAddress addr : config.listenAddresses()) {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                ssc.bind(addr, 1024);
                ssc.register(selector, SelectionKey.OP_ACCEPT,
                        new ConnectionState.Listener(config.serversFor(addr.getHostString(), addr.getPort())));
                listeners.add(ssc);
                System.out.println("HTTP server listening on http://" + addr.getHostString() + ":" + addr.getPort());
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                selector.select(500);
                if (!running) break;

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) accept(key);
                        else if (key.isReadable()) read(key);
                        else if (key.isWritable()) write(key);
                    } catch (Exception e) {
                        close(key);
                    }
                }
                evictTimeouts();
            } catch (ClosedChannelException e) {
                break;
            } catch (Exception e) {
                if (!running) break;
                System.err.println("Reactor loop error: " + e.getMessage());
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;

        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        var listener = (ConnectionState.Listener) key.attachment();
        client.register(selector, SelectionKey.OP_READ, new ConnectionState.Client(listener.servers()));
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        var state = (ConnectionState.Client) key.attachment();

        int n = state.read(client);
        if (n < 0) {
            close(key);
            return;
        }

        HttpRequest.Result result = HttpRequest.parse(state.input.toByteArray(), config.maxBodySize());
        if (result.state() == HttpRequest.Result.State.COMPLETE) {
            ConfigLoader.VirtualServer vhost = config.selectServer(result.request().header("host"), state.servers);
            state.attach(router.handle(result.request(), vhost), key);
        } else if (result.state() == HttpRequest.Result.State.ERROR) {
            state.attach(FaultPages.response(config, result.errorCode()), key);
        }
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        var state = (ConnectionState.Client) key.attachment();
        if (state.writer == null) return;

        state.writer.writeTo(client);
        if (state.writer.complete()) close(key);
    }

    private void evictTimeouts() {
        long limit = (long) config.requestTimeoutSeconds() * 1_000_000_000L;
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ConnectionState.Client state) {
                if (now - state.lastActivity > limit) {
                    try {
                        if (state.writer == null) {
                            state.attach(FaultPages.response(config, 408), key);
                        } else {
                            close(key);
                        }
                    } catch (Exception e) {
                        close(key);
                    }
                }
            }
        }
    }

    private void close(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        running = false;
        try {
            selector.wakeup();
            for (ServerSocketChannel ssc : listeners) ssc.close();
            for (SelectionKey key : selector.keys()) close(key);
            selector.close();
        } catch (Exception ignored) {}
    }
}

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Single-threaded, event-driven Java NIO HTTP/1.1 Server.
 * Multiplexes multiple ports and virtual hosts via a single non-blocking Selector.
 */
public final class Server implements Runnable, Closeable {
    private final ConfigLoader.ServerConfig config;
    private final Router router;
    private final Selector selector;
    private final List<ServerSocketChannel> serverChannels = new ArrayList<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(32 * 1024);
    private volatile boolean running = true;

    // Attachments for selection keys
    private record ListenerContext(List<ConfigLoader.VirtualServer> vhosts, int port) {}

    private static final class ClientContext {
        final List<ConfigLoader.VirtualServer> vhosts;
        final int port;
        final ByteArrayOutputStream incomingBytes = new ByteArrayOutputStream();
        HttpResponse response = null;
        long lastActive = System.currentTimeMillis();

        ClientContext(List<ConfigLoader.VirtualServer> vhosts, int port) {
            this.vhosts = vhosts;
            this.port = port;
        }

        void touch() {
            this.lastActive = System.currentTimeMillis();
        }
    }

    public Server(ConfigLoader.ServerConfig config) throws IOException {
        this.config = config;
        this.router = new Router(config);
        this.selector = Selector.open();

        for (InetSocketAddress addr : config.getListenAddresses()) {
            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                ssc.bind(addr, 1024);

                List<ConfigLoader.VirtualServer> serversForPort = config.getServersFor(addr.getHostString(), addr.getPort());
                ssc.register(selector, SelectionKey.OP_ACCEPT, new ListenerContext(serversForPort, addr.getPort()));
                serverChannels.add(ssc);

                System.out.println("Server listening on http://" + addr.getHostString() + ":" + addr.getPort());
            } catch (IOException e) {
                System.err.println("Failed to bind " + addr + ": " + e.getMessage());
                // Continue binding other addresses if some fail
            }
        }

        if (serverChannels.isEmpty()) {
            close();
            throw new IOException("Unable to bind to any configured ports");
        }
    }

    @Override
    public void run() {
        System.out.println("LocalServer reactor loop started (single-threaded NIO).");
        while (running) {
            try {
                int ready = selector.select(250);
                if (!running) break;

                if (ready > 0) {
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();

                        if (!key.isValid()) continue;

                        try {
                            if (key.isAcceptable()) {
                                handleAccept(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            } else if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (Exception e) {
                            closeClient(key);
                        }
                    }
                }

                // Check and evict idle / timed-out connections
                checkTimeouts();

            } catch (ClosedChannelException e) {
                break;
            } catch (Exception e) {
                if (!running) break;
                System.err.println("Selector loop warning: " + e.getMessage());
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;

        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);

        ListenerContext ctx = (ListenerContext) key.attachment();
        client.register(selector, SelectionKey.OP_READ, new ClientContext(ctx.vhosts(), ctx.port()));
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();
        ctx.touch();

        readBuffer.clear();
        int bytesRead = client.read(readBuffer);
        if (bytesRead < 0) {
            // Client closed connection (EOF)
            closeClient(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        readBuffer.flip();
        byte[] chunk = new byte[readBuffer.remaining()];
        readBuffer.get(chunk);
        ctx.incomingBytes.write(chunk);

        byte[] allBytes = ctx.incomingBytes.toByteArray();
        HttpRequest.ParseResult parseRes = HttpRequest.parse(allBytes, allBytes.length, config.maxBodySize());

        if (parseRes.state() == HttpRequest.ParseState.COMPLETE) {
            HttpRequest req = parseRes.request();
            ConfigLoader.VirtualServer vhost = config.resolveServer(req.header("host"), ctx.vhosts);
            ctx.response = router.handle(req, vhost, ctx.port);

            // Switch interest to OP_WRITE
            key.interestOps(SelectionKey.OP_WRITE);
        } else if (parseRes.state() == HttpRequest.ParseState.ERROR) {
            ctx.response = ErrorPages.response(parseRes.errorCode(), config.errorPages());
            key.interestOps(SelectionKey.OP_WRITE);
        }
        // If INCOMPLETE, keep OP_READ and wait for next select iteration
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();
        ctx.touch();

        if (ctx.response == null) {
            key.interestOps(SelectionKey.OP_READ);
            return;
        }

        boolean finished = ctx.response.writeTo(client);
        if (finished) {
            // Check connection persistence
            String connHeader = ctx.response.body().length > 0 ? "close" : "close";
            // Default to close after response delivery to guarantee no hanging descriptors
            closeClient(key);
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutLimit = (long) config.requestTimeoutSeconds() * 1000L;

        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ClientContext ctx) {
                if (now - ctx.lastActive > timeoutLimit) {
                    try {
                        if (ctx.response == null) {
                            ctx.response = ErrorPages.response(408, config.errorPages());
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else {
                            closeClient(key);
                        }
                    } catch (Exception e) {
                        closeClient(key);
                    }
                }
            }
        }
    }

    private void closeClient(SelectionKey key) {
        if (key == null) return;
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
            for (ServerSocketChannel ssc : serverChannels) {
                try { ssc.close(); } catch (Exception ignored) {}
            }
            for (SelectionKey key : selector.keys()) {
                closeClient(key);
            }
            selector.close();
        } catch (Exception ignored) {}
    }
}

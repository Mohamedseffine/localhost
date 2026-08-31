# Java HTTP Server — Complete Architectural & Codebase Guide

This document provides a comprehensive technical breakdown of the entire HTTP/1.1 Web Server codebase. It explains every concept, architecture layer, security mechanism, data structure, class, method, and line of code, along with practical use cases and edge cases.

---

## Table of Contents

1. [Architectural Overview & Core Concepts](#1-architectural-overview--core-concepts)
   - [Non-Blocking I/O & The Reactor Pattern](#non-blocking-io--the-reactor-pattern)
   - [HTTP/1.1 Protocol Mechanics & Incremental Parsing](#http11-protocol-mechanics--incremental-parsing)
   - [Resumable Stateful Response Streaming](#resumable-stateful-response-streaming)
   - [Security & Jail Sandboxing](#security--jail-sandboxing)
   - [Virtual Hosting & Dispatching](#virtual-hosting--dispatching)
   - [Multipart/Form-Data Handling (RFC 7578)](#multipartform-data-handling-rfc-7578)
   - [CGI Subprocess Isolation](#cgi-subprocess-isolation)
   - [Thread-Safe Cookie Sessions](#thread-safe-cookie-sessions)
2. [End-to-End Request Lifecycle](#2-end-to-end-request-lifecycle)
3. [Deep Dive: Package by Package & File by File](#3-deep-dive-package-by-package--file-by-file)
   - [3.1 Bootstrap Layer (`webserver.bootstrap`)](#31-bootstrap-layer-webserverbootstrap)
     - [`Main.java`](#mainjava)
   - [3.2 Transport Layer (`webserver.transport`)](#32-transport-layer-webservertransport)
     - [`Server.java`](#serverjava)
     - [`ConnectionState.java`](#connectionstatejava)
   - [3.3 Configuration Layer (`webserver.config`)](#33-configuration-layer-webserverconfig)
     - [`JsonParser.java`](#jsonparserjava)
     - [`ConfigLoader.java`](#configloaderjava)
   - [3.4 HTTP Protocol Layer (`webserver.http`)](#34-http-protocol-layer-webserverhttp)
     - [`HttpCodes.java`](#httpcodesjava)
     - [`HttpMethods.java`](#httpmethodsjava)
     - [`HttpRequest.java`](#httprequestjava)
     - [`HttpResponse.java`](#httpresponsejava)
     - [`RequestPolicy.java`](#requestpolicyjava)
   - [3.5 Routing Layer (`webserver.routing`)](#35-routing-layer-webserverrouting)
     - [`RouteMatcher.java`](#routematcherjava)
     - [`Router.java`](#routerjava)
   - [3.6 Resource Delivery & Execution (`webserver.delivery`)](#36-resource-delivery--execution-webserverdelivery)
     - [`ResourceService.java`](#resourceservicejava)
     - [`MultipartParser.java`](#multipartparserjava)
     - [`CGIHandler.java`](#cgihandlerjava)
   - [3.7 Response & Error Handling (`webserver.response`)](#37-response--error-handling-webserverresponse)
     - [`ResponseFactory.java`](#responsefactoryjava)
     - [`FaultPages.java`](#faultpagesjava)
   - [3.8 Session Management (`webserver.session`)](#38-session-management-webserversession)
     - [`SessionStore.java`](#sessionstorejava)
4. [Public Assets, CGI Scripts, Configuration & Tests](#4-public-assets-cgi-scripts-configuration--tests)
   - [`config.json`](#configjson)
   - [`public/cgi/Echo.java`](#publiccgiechojava)
   - [`tests/audit.sh`](#testsauditsh)
   - [`Makefile`](#makefile)

---

# 1. Architectural Overview & Core Concepts

```
                  +-------------------------------------------------------------+
                  |                     Client Browser / curl                   |
                  +-------------------------------------------------------------+
                                                 |
                                     (TCP Connect / Data)
                                                 v
+---------------------------------------------------------------------------------------------------+
| Server Reactor Loop (java.nio.channels.Selector)                                                  |
|                                                                                                   |
|  +---------------------------+     +--------------------------+     +--------------------------+  |
|  |   SelectionKey.OP_ACCEPT  |     |   SelectionKey.OP_READ   |     |  SelectionKey.OP_WRITE   |  |
|  | (Bind & accept client ch) |     |  (Read into byte buffer) |     | (Stream chunks via NIO)  |  |
|  +---------------------------+     +--------------------------+     +--------------------------+  |
+------------------------------------------------|--------------------------------------------------+
                                                 |
                                     (Complete Request Bytes)
                                                 v
                               +----------------------------------+
                               |     HttpRequest.parse()          |
                               | (Incremental, Chunked, Headers)  |
                               +----------------------------------+
                                                 |
                                                 v
                               +----------------------------------+
                               |   ConfigLoader.selectServer()    |
                               | (Virtual Host Header Matching)   |
                               +----------------------------------+
                                                 |
                                                 v
                               +----------------------------------+
                               |        Router.handle()           |
                               |  - Session cookie resolution     |
                               |  - Policy & Method verification  |
                               |  - RouteMatcher (Longest prefix) |
                               +----------------------------------+
                                                 |
            +------------------------------------+------------------------------------+
            |                                    |                                    |
            v                                    v                                    v
+-----------------------+            +-----------------------+            +-----------------------+
|    Static Resource    |            |   Multipart Upload    |            |     CGI Execution     |
| (ResourceService.java)|            | (MultipartParser.java)|            |   (CGIHandler.java)   |
| - Directory Jail      |            | - Boundary extraction |            | - Process isolation   |
| - Default Index file  |            | - UUID file generation|            | - Environment vars    |
| - Auto Directory List |            | - JSON response return|            | - Timeout management  |
+-----------------------+            +-----------------------+            +-----------------------+
            |                                    |                                    |
            +------------------------------------+------------------------------------+
                                                 |
                                                 v
                               +----------------------------------+
                               |      HttpResponse.writer()       |
                               | (Chunked / Identity NIO Stream)  |
                               +----------------------------------+
```

### Non-Blocking I/O & The Reactor Pattern
* **Traditional Thread-per-Client Model vs Reactor**: Classic web servers spawn one thread per client socket. When handling thousands of slow or idle connections, this wastes OS memory and incurs heavy thread context switching.
* **Java NIO `Selector` Multiplexing**: This server uses `java.nio.channels.Selector` to monitor multiple non-blocking sockets simultaneously from a single thread. The selector reports readiness events:
  * `OP_ACCEPT`: A new TCP handshake is ready on a listening port.
  * `OP_READ`: Data has arrived in the OS kernel buffer and can be read without blocking.
  * `OP_WRITE`: Sockets have space in their kernel send buffer to transmit response data.

### HTTP/1.1 Protocol Mechanics & Incremental Parsing
* **RFC 9112 Compliance**: Requests arrive as an arbitrary stream of TCP packets. The server must handle partial reads and buffer bytes until the full header delimiter (`\r\n\r\n`) arrives.
* **Transfer-Encoding: chunked**: Enables request bodies of unknown total length. Slices of data are prefixed by their hexadecimal length and terminated by `\r\n`. A `0\r\n\r\n` chunk indicates completion.
* **Content-Length vs Chunked**: The parser enforces RFC rules—simultaneous `Content-Length` and `Transfer-Encoding` headers result in an immediate `400 Bad Request` to prevent HTTP request smuggling attacks.

### Resumable Stateful Response Streaming
* **Zero-copy / Non-blocking chunking**: Large response bodies (> 64 KB) are broken into 16 KB chunks encoded on-the-fly (`HttpResponse.Writer`).
* **State Machine Stages**: The writer transitions across `HEADERS -> CHUNK_HEAD -> CHUNK_BODY -> CHUNK_TAIL -> LAST_CHUNK -> DONE`, writing whatever the socket can accept in each cycle without blocking the event loop.

### Security & Jail Sandboxing
* **Path Traversal Prevention (`../../`)**: Attackers frequently attempt `GET /../../etc/passwd`. The server normalizes all paths, validates relative prefixes against root directories using `Path.toRealPath()`, and throws `Forbidden (403)` if a path resolves outside the configured root jail.

### Virtual Hosting & Dispatching
* **Multiple Virtual Hosts on Shared Ports**: Sockets can listen on multiple IP/Port pairs. Requests dispatched on the same physical port match the `Host:` header against configured `server_names` (e.g., `named.local` vs `default.local`).

### Multipart/Form-Data Handling (RFC 7578)
* **File Uploads**: Form data delimited by custom boundaries is parsed byte-by-byte. Files are extracted and saved with randomized UUID filenames in the uploads folder to prevent overwriting or malicious execution.

### CGI Subprocess Isolation
* **CGI (Common Gateway Interface)**: Dynamically executes external scripts (like `public/cgi/Echo.java`) in a separate OS process, setting `PATH_INFO`, capturing stdout, and killing hung processes using a 10-second timeout.

### Thread-Safe Cookie Sessions
* **Cookie-backed Sessions**: Automatic generation of `session_id` tokens with `HttpOnly; SameSite=Lax; Path=/` cookies, tracking expiration TTLs and sliding on active reuse.

---

# 2. End-to-End Request Lifecycle

1. **Client Connects**: Client initiates TCP handshake on port 8080.
2. **Server Accepts**: `Server.run()` selector wakes up on `key.isAcceptable()`. `Server.accept()` creates a non-blocking `SocketChannel` and attaches a `ConnectionState.Client`.
3. **Data Arrives**: Socket becomes readable (`key.isReadable()`). `ConnectionState.Client.read()` appends bytes to its internal buffer.
4. **Incremental Parsing**: `HttpRequest.parse()` checks for `\r\n\r\n`. If incomplete, the reactor waits for more packets. If complete, an immutable `HttpRequest` record is created.
5. **Virtual Host Selection**: `ConfigLoader.selectServer()` checks the `Host:` header to pick the matching `VirtualServer`.
6. **Policy & Session Verification**: `RequestPolicy.rejectionCode()` validates basic protocol compliance. `SessionStore.find()` inspects `Cookie` headers and issues or refreshes the session.
7. **Routing & Dispatch**: `RouteMatcher.find()` picks the longest matching route.
8. **Handling Execution**:
   - `GET`: Reads the target static file, renders an auto-index HTML page, or triggers CGI.
   - `POST`: Parses JSON/text data, processes `multipart/form-data` uploads into `uploads/`, or executes CGI.
   - `DELETE`: Verifies file existence within jail and removes it.
9. **Response Attachment**: `HttpResponse` prepares headers and creates an `HttpResponse.Writer`. Sockets register for `SelectionKey.OP_WRITE`.
10. **Resumable Writing**: `Server.write()` streams bytes/chunks into the channel until `writer.complete()` is true.
11. **Connection Close**: The server sends `Connection: close`, closes the socket channel, and cancels the key.

---

# 3. Deep Dive: Package by Package & File by File

---

## 3.1 Bootstrap Layer (`webserver.bootstrap`)

### `Main.java`
**Location:** [`src/webserver/bootstrap/Main.java`](file:///home/kali/localhost/src/webserver/bootstrap/Main.java)

#### Purpose & Responsibility
The CLI entry point for the HTTP server application. It parses command-line arguments, boots the configuration loader, starts the network reactor, registers JVM shutdown hooks, and manages process exit codes.

#### Detailed Code Breakdown

```java
package webserver.bootstrap;

import java.nio.file.Path;
import webserver.config.ConfigLoader;
import webserver.transport.Server;

public final class Main {
    private Main() {}
```
* **Lines 1–9**: The class is declared `final` with a private constructor to prevent instantiation, as it is purely a static entry-point utility.

```java
    public static void main(String[] args) {
        try {
            Path configPath = configuration(args);

            ConfigLoader.Config config = ConfigLoader.load(configPath);
            Server server = new Server(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (Exception ignored) {}
            }));

            try {
                server.run();
            } finally {
                server.close();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            System.exit(1);
        }
    }
```
* **Lines 11–13**: Calls `configuration(args)` to resolve the configuration file path from CLI flags.
* **Line 15**: Loads and validates the configuration into an immutable `ConfigLoader.Config` record.
* **Line 16**: Instantiates the NIO `Server`. Sockets are bound to configured ports.
* **Lines 17–21**: Registers a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`). When SIGINT (Ctrl+C) or SIGTERM is received, `server.close()` gracefully unbinds listeners and frees selector resources.
* **Lines 23–27**: Starts the single-threaded reactor loop `server.run()`. The `finally` block guarantees resource cleanup upon unexpected loop termination.
* **Lines 28–34**: Catches exceptions:
  * `IllegalArgumentException` (e.g. malformed JSON, bad ports) logs the error and exits with code **2**.
  * Unexpected runtime/I/O exceptions log the error and exit with code **1**.

```java
    private static Path configuration(String[] args) {
        if (args.length == 0) return Path.of("config.json");
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: java -jar build/java-server.jar [--config config.json]");
            System.exit(0);
        }
        if (args.length == 1 && args[0].startsWith("--config=")) return Path.of(args[0].substring(9));
        if (args.length == 2 && (args[0].equals("--config") || args[0].equals("-c"))) return Path.of(args[1]);
        throw new IllegalArgumentException("Use --config <file>");
    }
}
```
* **Lines 37–46**: `configuration(String[] args)`:
  * No arguments $\rightarrow$ defaults to `"config.json"`.
  * `--help` or `-h` $\rightarrow$ prints usage and exits cleanly with code 0.
  * `--config=path` $\rightarrow$ substrings past the `=` sign to extract the path.
  * `--config path` or `-c path` $\rightarrow$ reads `args[1]`.
  * Any unexpected parameter pattern throws an `IllegalArgumentException`.

---

## 3.2 Transport Layer (`webserver.transport`)

### `Server.java`
**Location:** [`src/webserver/transport/Server.java`](file:///home/kali/localhost/src/webserver/transport/Server.java)

#### Purpose & Responsibility
The core NIO Reactor loop. It binds `ServerSocketChannel` instances across all unique IP/port endpoints, registers them with a single `Selector`, accepts incoming client connections, reads raw bytes, invokes the router, streams responses, and evicts timed-out sockets.

#### Detailed Code Breakdown

```java
package webserver.transport;

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
import webserver.config.ConfigLoader;
import webserver.http.HttpRequest;
import webserver.response.FaultPages;
import webserver.routing.Router;

public final class Server implements Closeable, Runnable {
    private final ConfigLoader.Config config;
    private final Router router;
    private final Selector selector;
    private final List<ServerSocketChannel> listeners = new ArrayList<>();
    private volatile boolean running = true;
```
* **Lines 21–26**: Fields:
  * `config`: Loaded configuration.
  * `router`: HTTP request router.
  * `selector`: The multiplexing `Selector`.
  * `listeners`: List of open server listener sockets.
  * `running`: Volatile flag to control loop execution across threads.

```java
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
```
* **Lines 28–47**: Constructor:
  * Opens the `Selector`.
  * For each distinct `InetSocketAddress` in `config.listenAddresses()`, creates a non-blocking `ServerSocketChannel`.
  * `SO_REUSEADDR` is enabled to allow immediate rebinding after restarts without `TIME_WAIT` socket errors.
  * Sockets are bound with a connection backlog of 1024.
  * Registers each listener with `OP_ACCEPT`, attaching a `ConnectionState.Listener` containing candidate virtual servers for that specific address/port.

```java
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
                        dispatch(key);
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
```
* **Lines 49–76**: `run()` (Event Loop):
  * `selector.select(500)`: Blocks for up to 500 milliseconds waiting for channel I/O events. The timeout ensures the thread periodically wakes up to execute `evictTimeouts()`.
  * Iterates over `selectedKeys()`. Must call `it.remove()` so processed events are not re-triggered.
  * Validates key state and calls `dispatch(key)`. Any channel exception triggers `close(key)`.

```java
    private void dispatch(SelectionKey key) throws IOException {
        if (key.isAcceptable()) {
            accept(key);
        } else if (key.isReadable()) {
            read(key);
        } else if (key.isWritable()) {
            write(key);
        }
    }
```
* **Lines 78–86**: Dispatches based on key readiness flags (`OP_ACCEPT`, `OP_READ`, `OP_WRITE`).

```java
    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;

        client.configureBlocking(false);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        var listener = (ConnectionState.Listener) key.attachment();
        client.register(selector, SelectionKey.OP_READ, new ConnectionState.Client(listener.servers()));
    }
```
* **Lines 88–97**: `accept(SelectionKey key)`:
  * Accepts the incoming connection. Configures it as non-blocking.
  * Sets `TCP_NODELAY = true` to disable Nagle's algorithm, ensuring small HTTP responses and chunks are dispatched immediately without latency buffering.
  * Registers the new channel with `OP_READ` and attaches a new `ConnectionState.Client`.

```java
    private void read(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        var state = (ConnectionState.Client) key.attachment();

        int n = state.read(client);
        if (n < 0) {
            close(key);
            return;
        }

        HttpRequest.Result result = HttpRequest.parse(state.requestBytes(), config.maxBodySize());
        if (result.state() == HttpRequest.Result.State.COMPLETE) {
            ConfigLoader.VirtualServer vhost = config.selectServer(result.request().header("host"), state.servers());
            state.attach(router.handle(result.request(), vhost), key);
        } else if (result.state() == HttpRequest.Result.State.ERROR) {
            state.attach(FaultPages.response(config, result.errorCode()), key);
        }
    }
```
* **Lines 99–116**: `read(SelectionKey key)`:
  * Reads newly arrived bytes into `state`.
  * If `n < 0` (EOF / client closed connection), closes the channel.
  * Parses buffered data via `HttpRequest.parse()`.
  * If `COMPLETE`: Resolves virtual host via `config.selectServer()`, calls `router.handle()`, and attaches the response writer to the key, switching interest to `OP_WRITE`.
  * If `ERROR`: Attaches error response (e.g. 400 Bad Request, 413 Payload Too Large) and sets interest to `OP_WRITE`.
  * If `INCOMPLETE`: Does nothing and waits for the next packet.

```java
    private void write(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        var state = (ConnectionState.Client) key.attachment();
        if (state.writer() == null) return;

        state.writer().writeTo(client);
        if (state.writer().complete()) close(key);
    }
```
* **Lines 118–125**: `write(SelectionKey key)`:
  * Calls `writer().writeTo(client)` to push encoded response bytes.
  * When `writer().complete()` is true (all headers and body/chunks sent), calls `close(key)`.

```java
    private void evictTimeouts() {
        long limit = (long) config.requestTimeoutSeconds() * 1_000_000_000L;
        long now = System.nanoTime();
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ConnectionState.Client state) {
                if (now - state.lastActivity() > limit) {
                    try {
                        if (state.writer() == null) {
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
```
* **Lines 127–145**: `evictTimeouts()`:
  * Iterates across all registered client keys.
  * If the idle time (`now - state.lastActivity()`) exceeds `requestTimeoutSeconds`, either attaches a `408 Request Timeout` page to send to the client or immediately closes hanging write sockets.

```java
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
```
* **Lines 147–164**: Cleans up individual keys or shuts down the entire server by closing listeners, connected client channels, and the selector.

---

### `ConnectionState.java`
**Location:** [`src/webserver/transport/ConnectionState.java`](file:///home/kali/localhost/src/webserver/transport/ConnectionState.java)

#### Purpose & Responsibility
Contains state objects attached to `SelectionKey` instances to maintain stateful connection buffers and activity timestamps across multiple non-blocking reactor cycles.

#### Detailed Code Breakdown

```java
package webserver.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;

public final class ConnectionState {
    private ConnectionState() {}

    public record Listener(List<ConfigLoader.VirtualServer> servers) {}
```
* **Lines 1–16**: `Listener` record attached to listener server channels storing candidate virtual servers bound to that port.

```java
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
```
* **Lines 18–51**: `Client` state:
  * `buffer`: A reusable 16 KB direct heap `ByteBuffer` for socket reads.
  * `input`: `ByteArrayOutputStream` accumulating all incoming chunks/packets until a full HTTP request is assembled.
  * `writer`: The active `HttpResponse.Writer` responsible for streaming the response.
  * `lastActivity`: High-precision timestamp (`System.nanoTime()`) refreshed on every socket read to enforce idle timeout eviction.
  * `attach(HttpResponse response, SelectionKey key)`: Sets the writer and switches the socket's interest operation to `OP_WRITE`.

---

## 3.3 Configuration Layer (`webserver.config`)

### `JsonParser.java`
**Location:** [`src/webserver/config/JsonParser.java`](file:///home/kali/localhost/src/webserver/config/JsonParser.java)

#### Purpose & Responsibility
A lightweight, zero-dependency, recursive-descent JSON parser designed specifically for server configuration. It performs strict validation (rejecting trailing commas, duplicate map keys, invalid escapes, unquoted keys, and floating point numbers where integers are expected).

#### Detailed Code Breakdown

```java
package webserver.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonParser {
    private final String src;
    private int pos;

    public JsonParser(String src) {
        if (src == null) throw new IllegalArgumentException("Null JSON");
        this.src = src;
    }

    public Object parse() {
        Object result = value();
        skipWs();
        requireEnd();
        return result;
    }
```
* **Lines 1–23**: Accepts raw JSON string. `parse()` parses a root value, skips any trailing whitespace, and verifies that the entire input was consumed (`requireEnd()`).

```java
    private void requireEnd() {
        if (pos != src.length()) throw new IllegalArgumentException("Trailing data at " + pos);
    }

    private Object value() {
        skipWs();
        if (pos >= src.length()) throw new IllegalArgumentException("Unexpected EOF");
        char ch = src.charAt(pos);
        return switch (ch) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }
```
* **Lines 25–42**: Recursive descent root `value()`: Dispatches parsing based on lookahead character (`{` for object, `[` for array, `"` for string, `t`/`f`/`n` for booleans/null, and digits/minus for numbers).

```java
    private Map<String, Object> object() {
        consume('{');
        skipWs();
        Map<String, Object> map = new LinkedHashMap<>();
        if (match('}')) return map;
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            consume(':');
            skipWs();
            rejectDuplicate(map, key);
            map.put(key, value());
            skipWs();
            if (match('}')) return map;
            consume(',');
        }
    }

    private static void rejectDuplicate(Map<String, Object> values, String key) {
        if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate key: " + key);
    }
```
* **Lines 44–65**: `object()`: Parses JSON objects into `LinkedHashMap` preserving insertion order. Rejects duplicate keys explicitly to prevent configuration ambiguity.

```java
    private List<Object> array() {
        consume('[');
        skipWs();
        List<Object> list = new ArrayList<>();
        if (match(']')) return list;
        while (true) {
            list.add(value());
            skipWs();
            if (match(']')) return list;
            consume(',');
        }
    }
```
* **Lines 67–78**: `array()`: Parses JSON lists into `ArrayList`.

```java
    private String string() {
        consume('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char ch = src.charAt(pos++);
            if (ch == '"') return sb.toString();
            if (ch != '\\') {
                if (ch < 0x20) throw new IllegalArgumentException("Control char in string");
                sb.append(ch);
                continue;
            }
            if (pos >= src.length()) throw new IllegalArgumentException("Bad escape");
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"', '\\', '/' -> sb.append(esc);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) throw new IllegalArgumentException("Bad unicode escape");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("Unknown escape: " + esc);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }
```
* **Lines 80–109**: `string()`: Parses quoted strings and decodes JSON escape sequences (`\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, and 4-digit hexadecimal unicode `\uXXXX`). Rejects unescaped ASCII control characters (< 0x20).

```java
    private Long number() {
        int start = pos;
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        if (pos < src.length() && src.charAt(pos) == '0') {
            pos++;
            if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                throw new IllegalArgumentException("Leading zero not allowed");
            }
        } else {
            int digits = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos == digits) throw new IllegalArgumentException("Invalid number");
        }
        if (pos < src.length() && ".eE".indexOf(src.charAt(pos)) >= 0) {
            throw new IllegalArgumentException("Integer expected");
        }
        String digits = src.substring(start, pos);
        return Long.valueOf(digits);
    }
```
* **Lines 111–129**: `number()`: Parses 64-bit integer values (`Long`). Rejects invalid JSON syntax like leading zeros (`05`) and floats/exponents (`.eE`).

```java
    private Object literal(String expected, Object val) {
        if (!src.startsWith(expected, pos)) throw new IllegalArgumentException("Expected " + expected);
        pos += expected.length();
        return val;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean match(char ch) {
        if (pos < src.length() && src.charAt(pos) == ch) {
            pos++;
            return true;
        }
        return false;
    }

    private void consume(char ch) {
        if (!match(ch)) throw new IllegalArgumentException("Expected '" + ch + "' at " + pos);
    }
}
```
* **Lines 131–151**: Token helper utilities for skipping whitespace and matching/consuming specific characters or literal keywords.

---

### `ConfigLoader.java`
**Location:** [`src/webserver/config/ConfigLoader.java`](file:///home/kali/localhost/src/webserver/config/ConfigLoader.java)

#### Purpose & Responsibility
Validates JSON configuration against business rules, converts relative path strings to canonical `Path` objects, ensures sandbox roots exist, sorts routes by length for longest-prefix matching, and configures virtual servers.

#### Detailed Code Breakdown

```java
package webserver.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import webserver.http.HttpMethods;

public final class ConfigLoader {
    private static final Set<Integer> ERROR_CODES = Set.of(400, 403, 404, 405, 413, 500);
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 307, 308);
```
* **Lines 1–22**: Defines supported error codes and valid redirect status codes.

```java
    public record Config(
            Path root, Path uploads, long maxBodySize, int requestTimeoutSeconds,
            Map<Integer, Path> errorPages, List<Route> routes,
            List<VirtualServer> servers, Cgi cgi
    ) {
        public Config {
            errorPages = Map.copyOf(errorPages);
            routes = List.copyOf(routes);
            servers = List.copyOf(servers);
        }

        public List<InetSocketAddress> listenAddresses() {
            return servers.stream()
                    .flatMap(s -> s.ports().stream().map(p -> new InetSocketAddress(s.address(), p)))
                    .distinct().toList();
        }

        public List<VirtualServer> serversFor(String address, int port) {
            return servers.stream()
                    .filter(s -> s.address().equals(address) && s.ports().contains(port))
                    .toList();
        }

        public VirtualServer selectServer(String hostHeader, List<VirtualServer> candidates) {
            if (hostHeader != null && !hostHeader.isBlank()) {
                String host = hostHeader.trim().toLowerCase(Locale.ROOT);
                int colon = host.indexOf(':');
                if (colon >= 0) host = host.substring(0, colon);
                for (VirtualServer server : candidates) {
                    for (String name : server.serverNames()) {
                        if (name.equalsIgnoreCase(host)) return server;
                    }
                }
            }
            return candidates.get(0);
        }
    }
```
* **Lines 25–61**: `Config` record:
  * Contains immutable collections for error pages, routes, and virtual servers.
  * `listenAddresses()`: Aggregates all distinct IP/port pairs across all virtual servers to bind.
  * `serversFor(address, port)`: Returns virtual servers configured on that specific address and port.
  * `selectServer(hostHeader, candidates)`: Strips port from `Host:` header and matches against `server_names`. Defaults to `candidates.get(0)` if unmatched or header is omitted.

```java
    public record Route(
            String path, List<String> methods, String root, String defaultFile,
            String redirect, int redirectStatus, boolean directoryListing, boolean cgi
    ) {
        public Route {
            methods = List.copyOf(methods);
        }
    }

    public record VirtualServer(String address, List<Integer> ports, List<String> serverNames) {
        public VirtualServer {
            ports = List.copyOf(ports);
            serverNames = List.copyOf(serverNames);
        }

        public String name() {
            return serverNames.isEmpty() ? address : serverNames.get(0);
        }
    }

    public record Cgi(String extension, String command) {}
```
* **Lines 63–83**: Data records for `Route`, `VirtualServer`, and `Cgi`.

```java
    public static Config load(Path configFile) throws IOException {
        Path file = configFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Config not found: " + file);

        Map<String, Object> json = object(new JsonParser(Files.readString(file, StandardCharsets.UTF_8)).parse(), "root");
        checkKeys(json, Set.of("root", "uploads", "max_body_size", "request_timeout_seconds",
                "error_pages", "cgi", "routes", "servers"), "root");

        Path base = file.getParent().toRealPath();
        Path root = checkDir(base, string(required(json, "root", "root"), "root"));
        Path uploads = root.resolve(string(required(json, "uploads", "root"), "uploads")).normalize();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("Uploads outside root");
        Files.createDirectories(uploads);
        uploads = uploads.toRealPath();
        if (!uploads.startsWith(root)) throw new IllegalArgumentException("Uploads realpath outside root");

        long maxBodySize = number(required(json, "max_body_size", "root"), "max_body_size");
        if (maxBodySize < 0) throw new IllegalArgumentException("Negative max_body_size");
        int timeout = Math.toIntExact(number(json.getOrDefault("request_timeout_seconds", 15L), "request_timeout_seconds"));
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("Timeout out of range: " + timeout);

        Map<Integer, Path> errorPages = loadErrorPages(json, base);
        Cgi cgi = loadCgi(json);
```
* **Lines 85–108**: Reads configuration file, verifies unknown top-level keys, validates the sandbox root directory, verifies that `uploads/` stays within `root/`, and parses size/timeout limits.

```java
        List<Route> routes = new ArrayList<>();
        for (Object routeObj : list(required(json, "routes", "root"), "routes")) {
            Map<String, Object> r = object(routeObj, "route");
            checkKeys(r, Set.of("path", "methods", "root", "default_file", "redirect",
                    "redirect_status", "directory_listing", "cgi"), "route");

            String path = string(required(r, "path", "route"), "route.path");
            if (!path.startsWith("/")) throw new IllegalArgumentException("Path must start with /");

            List<String> methods = new ArrayList<>();
            for (Object m : list(required(r, "methods", "route"), "route.methods")) {
                String method = string(m, "method").toUpperCase(Locale.ROOT);
                if (!HttpMethods.supported(method)) throw new IllegalArgumentException("Unsupported method: " + method);
                methods.add(method);
            }
            if (methods.isEmpty()) throw new IllegalArgumentException("Empty route methods");

            String routeRoot = r.containsKey("root") ? string(r.get("root"), "route.root") : ".";
            checkRelative(routeRoot, "route.root");
            String defaultFile = r.containsKey("default_file") ? string(r.get("default_file"), "route.default_file") : null;
            if (defaultFile != null) checkRelative(defaultFile, "route.default_file");

            String redirect = r.containsKey("redirect") ? string(r.get("redirect"), "route.redirect") : null;
            if (redirect != null && !redirect.startsWith("/") && !redirect.startsWith("http://") && !redirect.startsWith("https://")) {
                throw new IllegalArgumentException("Invalid redirect location");
            }
            int redirectStatus = Math.toIntExact(number(r.getOrDefault("redirect_status", 302L), "route.redirect_status"));
            if (!REDIRECT_CODES.contains(redirectStatus)) throw new IllegalArgumentException("Invalid redirect status");

            boolean listing = booleanValue(r.getOrDefault("directory_listing", Boolean.FALSE), "directory_listing");
            boolean routeCgi = booleanValue(r.getOrDefault("cgi", Boolean.FALSE), "route.cgi");
            routes.add(new Route(path, methods, routeRoot, defaultFile, redirect, redirectStatus, listing, routeCgi));
        }
        routes.sort(Comparator.comparingInt((Route route) -> route.path().length()).reversed());
```
* **Lines 109–143**: Routes parsing:
  * Ensures route paths begin with `/`.
  * Verifies method support (`GET`, `POST`, `DELETE`).
  * Enforces that route-relative roots and default files are relative paths.
  * Validates redirect status codes (301, 302, 307, 308).
  * **Critical Sort**: `routes.sort(Comparator.comparingInt((Route route) -> route.path().length()).reversed())` sorts routes in descending order of path length, ensuring the longest prefix is matched first (e.g. `/files/` matches before `/`).

```java
        List<VirtualServer> servers = new ArrayList<>();
        Set<String> allNames = new HashSet<>();
        for (Object sObj : list(required(json, "servers", "root"), "servers")) {
            try {
                Map<String, Object> s = object(sObj, "server");
                checkKeys(s, Set.of("address", "ports", "server_names"), "server");
                String address = string(required(s, "address", "server"), "server.address");
                if (address.isBlank()) throw new IllegalArgumentException("Empty address");

                List<Integer> ports = new ArrayList<>();
                Set<Integer> usedPorts = new HashSet<>();
                for (Object pObj : list(required(s, "ports", "server"), "server.ports")) {
                    int p = Math.toIntExact(number(pObj, "port"));
                    if (p < 1 || p > 65535 || !usedPorts.add(p)) throw new IllegalArgumentException("Bad port: " + p);
                    ports.add(p);
                }
                if (ports.isEmpty()) throw new IllegalArgumentException("Empty server ports");

                List<String> names = new ArrayList<>();
                if (s.containsKey("server_names")) {
                    for (Object nObj : list(s.get("server_names"), "server_names")) {
                        String name = string(nObj, "server_name");
                        String norm = name.toLowerCase(Locale.ROOT);
                        if (!name.matches("^[A-Za-z0-9.-]+$") || allNames.contains(norm)) {
                            throw new IllegalArgumentException("Duplicate/invalid name: " + name);
                        }
                        allNames.add(norm);
                        names.add(name);
                    }
                }
                servers.add(new VirtualServer(address, ports, names));
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping invalid server: " + e.getMessage());
            }
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("No valid virtual servers");

        return new Config(root, uploads, maxBodySize, timeout, errorPages, routes, servers, cgi);
    }
```
* **Lines 144–182**: Server block parsing: Validates IP addresses, port ranges (1–65535), uniqueness of server names, and returns the final `Config`.

```java
    private static Path checkDir(Path base, String dirStr) throws IOException { ... }
    private static Map<Integer, Path> loadErrorPages(Map<String, Object> json, Path base) throws IOException { ... }
    private static Cgi loadCgi(Map<String, Object> json) { ... }
    private static Path checkFile(Path base, String fileStr) throws IOException { ... }
    private static void checkRelative(String pathStr, String field) { ... }
    private static Object required(...) { ... }
    private static String string(...) { ... }
    private static long number(...) { ... }
    private static boolean booleanValue(...) { ... }
    private static Map<String, Object> object(...) { ... }
    private static List<Object> list(...) { ... }
    private static void checkKeys(...) { ... }
}
```
* **Lines 184–279**: Helper validation methods ensuring strict type checking and security assertions.

---

## 3.4 HTTP Protocol Layer (`webserver.http`)

### `HttpCodes.java`
**Location:** [`src/webserver/http/HttpCodes.java`](file:///home/kali/localhost/src/webserver/http/HttpCodes.java)

#### Purpose & Responsibility
Defines HTTP status code integer constants and returns standard RFC reason phrases.

#### Detailed Code Breakdown

```java
package webserver.http;

public final class HttpCodes {
    public static final int OK = 200, CREATED = 201;
    public static final int MOVED_PERMANENTLY = 301, FOUND = 302;
    public static final int TEMPORARY_REDIRECT = 307, PERMANENT_REDIRECT = 308;
    public static final int BAD_REQUEST = 400, FORBIDDEN = 403, NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405, REQUEST_TIMEOUT = 408;
    public static final int PAYLOAD_TOO_LARGE = 413, INTERNAL_SERVER_ERROR = 500;

    private HttpCodes() {}

    public static String reason(int status) {
        if (status == OK) return "OK";
        if (status == CREATED) return "Created";
        if (status == MOVED_PERMANENTLY) return "Moved Permanently";
        if (status == FOUND) return "Found";
        if (status == TEMPORARY_REDIRECT) return "Temporary Redirect";
        if (status == PERMANENT_REDIRECT) return "Permanent Redirect";
        if (status == BAD_REQUEST) return "Bad Request";
        if (status == FORBIDDEN) return "Forbidden";
        if (status == NOT_FOUND) return "Not Found";
        if (status == METHOD_NOT_ALLOWED) return "Method Not Allowed";
        if (status == REQUEST_TIMEOUT) return "Request Timeout";
        if (status == PAYLOAD_TOO_LARGE) return "Payload Too Large";
        return "Internal Server Error";
    }
}
```
* **Lines 1–29**: Provides status code mappings for standard HTTP response formatting.

---

### `HttpMethods.java`
**Location:** [`src/webserver/http/HttpMethods.java`](file:///home/kali/localhost/src/webserver/http/HttpMethods.java)

#### Purpose & Responsibility
Restricts request verbs to supported HTTP methods and formats the `Allow` header for `405 Method Not Allowed` responses.

#### Detailed Code Breakdown

```java
package webserver.http;

import java.util.Set;

public final class HttpMethods {
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String DELETE = "DELETE";

    private static final Set<String> SUPPORTED = Set.of(GET, POST, DELETE);
    private static final String ALLOW = "GET, POST, DELETE";

    private HttpMethods() {}

    public static boolean supported(String candidate) {
        return SUPPORTED.contains(candidate);
    }

    public static String allowValue() {
        return ALLOW;
    }
}
```
* **Lines 1–23**: Encapsulates `GET`, `POST`, `DELETE` validation logic and `allowValue()` return.

---

### `HttpRequest.java`
**Location:** [`src/webserver/http/HttpRequest.java`](file:///home/kali/localhost/src/webserver/http/HttpRequest.java)

#### Purpose & Responsibility
RFC 9112 compliant incremental HTTP request parser. Parses request lines, headers, normalized field lookups, content-length validation, and chunked transfer decoding.

#### Detailed Code Breakdown

```java
package webserver.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
    public static final int MAX_HEADERS = 64 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    public String header(String name) { return headers.getOrDefault(normalize(name), ""); }
```
* **Lines 1–17**: Record definition. Limits maximum headers size to 64 KB to guard against denial-of-service memory exhaustion. `header(name)` provides case-insensitive header lookup.

```java
    public static Result parse(byte[] data, long maxBodySize) {
        if (data == null || data.length == 0) return Result.incomplete();

        int headerEnd = find(data, 0, CRLF_CRLF);
        if (headerEnd < 0) return data.length > MAX_HEADERS ? Result.error(400) : Result.incomplete();
        if (headerEnd > MAX_HEADERS) return Result.error(400);

        String[] lines = new String(data, 0, headerEnd, StandardCharsets.ISO_8859_1).split("\\r\\n");
        String[] reqLine = lines.length == 0 ? new String[0] : lines[0].trim().split("\\s+");
        if (!validRequestLine(reqLine)) {
            return Result.error(400);
        }

        Map<String, String> headers = headers(lines);
        if (headers == null || !headers.containsKey("host")) return Result.error(400);

        String method = reqLine[0], target = reqLine[1];
        int bodyStart = headerEnd + 4;
        String te = headers.getOrDefault("transfer-encoding", "");
        boolean chunked = !te.isEmpty();
```
* **Lines 19–39**:
  * Scans raw bytes for `\r\n\r\n`. If not found and input $< 64\text{ KB}$, returns `Result.incomplete()` to await more TCP packets.
  * Decodes header lines using `ISO-8859-1` per RFC specifications.
  * Verifies request line syntax (`validRequestLine(reqLine)`: exactly 3 tokens, HTTP/1.1 protocol, target starting with `/`).
  * Parses headers and strictly enforces the mandatory `Host:` header (RFC 9112 §7.1).

```java
        if (HttpMethods.GET.equals(method)) {
            if (chunked) return Result.error(400);
            if (headers.containsKey("content-length")) {
                try {
                    if (Long.parseLong(headers.get("content-length")) != 0) return Result.error(400);
                } catch (NumberFormatException e) {
                    return Result.error(400);
                }
            }
            return complete(method, target, headers, new byte[0]);
        }

        if (!HttpMethods.POST.equals(method) && !HttpMethods.DELETE.equals(method)) {
            return complete(method, target, headers, new byte[0]);
        }
```
* **Lines 40–54**:
  * Rejects `GET` requests with chunked transfer or non-zero `Content-Length`.
  * Allows empty body for other non-payload methods.

```java
        byte[] body;
        if (chunked) {
            if (!"chunked".equalsIgnoreCase(te.trim()) || headers.containsKey("content-length")) {
                return Result.error(400);
            }
            ChunkResult cr = parseChunks(data, bodyStart, maxBodySize);
            if (cr.code != 0) return Result.error(cr.code);
            if (!cr.complete) return Result.incomplete();
            body = cr.body;
        } else {
            long length = 0;
            if (headers.containsKey("content-length")) {
                try {
                    length = Long.parseLong(headers.get("content-length"));
                } catch (NumberFormatException e) {
                    return Result.error(400);
                }
            }
            if (length < 0) return Result.error(400);
            if (length > maxBodySize || length > Integer.MAX_VALUE) return Result.error(413);
            if ((long) bodyStart + length > data.length) return Result.incomplete();
            body = Arrays.copyOfRange(data, bodyStart, bodyStart + (int) length);
        }

        return complete(method, target, headers, body);
    }
```
* **Lines 56–81**:
  * If chunked: Enforces that `Transfer-Encoding` is strictly `chunked` and rejects requests containing both `Content-Length` and `Transfer-Encoding`. Calls `parseChunks()`.
  * If identity: Reads `Content-Length`. If $>\text{maxBodySize}$, returns `413 Payload Too Large`. If total buffered bytes $< \text{bodyStart} + \text{length}$, returns `Result.incomplete()`.

```java
    private static ChunkResult parseChunks(byte[] data, int pos, long maxSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int lineEnd = find(data, pos, CRLF);
            if (lineEnd < 0) return ChunkResult.incomplete();

            String sizeStr = new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int semi = sizeStr.indexOf(';');
            if (semi >= 0) sizeStr = sizeStr.substring(0, semi);

            final long size;
            try {
                size = Long.parseLong(sizeStr.trim(), 16);
            } catch (NumberFormatException e) {
                return ChunkResult.error(400);
            }

            if (size < 0 || size > Integer.MAX_VALUE || out.size() + size > maxSize) {
                return ChunkResult.error(413);
            }

            pos = lineEnd + 2;
            if (size == 0) {
                if (pos + 2 > data.length) return ChunkResult.incomplete();
                if (data[pos] != '\r' || data[pos + 1] != '\n') return ChunkResult.error(400);
                return ChunkResult.complete(out.toByteArray());
            }

            long end = (long) pos + size;
            if (end + 2 > data.length) return ChunkResult.incomplete();
            if (data[(int) end] != '\r' || data[(int) end + 1] != '\n') return ChunkResult.error(400);

            out.write(data, pos, (int) size);
            pos = (int) end + 2;
        }
    }
```
* **Lines 106–141**: `parseChunks()`: Decodes hex chunk sizes, strips chunk extensions (`;...`), enforces maximum body constraints (returning 413 if exceeded), concatenates chunk bytes, and verifies trailing `0\r\n\r\n`.

```java
    private static int find(byte[] src, int start, byte[] pat) { ... }
    public record Result(...) { ... }
    private record ChunkResult(...) { ... }
}
```
* **Lines 143–167**: Fast byte-array search utility and parser status records (`Result`, `ChunkResult`).

---

### `HttpResponse.java`
**Location:** [`src/webserver/http/HttpResponse.java`](file:///home/kali/localhost/src/webserver/http/HttpResponse.java)

#### Purpose & Responsibility
Encapsulates HTTP responses and provides a resumable, non-blocking chunking channel encoder (`Writer`) that writes data to non-blocking `SocketChannel` instances without blocking the main reactor loop.

#### Detailed Code Breakdown

```java
package webserver.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HttpResponse {
    public static final int CHUNK_THRESHOLD = 64 * 1024;
    private static final int CHUNK_SIZE = 16 * 1024;

    private final int status;
    private final String contentType;
    private final byte[] body;
    private final List<String> headers = new ArrayList<>();
```
* **Lines 1–21**: If body $> 64\text{ KB}$, responses automatically stream using `Transfer-Encoding: chunked` with 16 KB chunk slices.

```java
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
```
* **Lines 22–40**: Prevents HTTP response splitting vulnerabilities by rejecting headers containing `\r` or `\n`.

```java
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
```
* **Lines 42–54**: Builds status lines, content-type, connection headers, and returns an active `Writer`.

```java
    public static final class Writer {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

        private final byte[] body;
        private final boolean chunked;
        private ByteBuffer current;
        private Stage stage = Stage.HEADERS;
        private int pos = 0;
        private int chunkSize = 0;
```
* **Lines 73–83**: Writer state machine: Tracks the current `ByteBuffer`, playback index `pos`, and active `Stage`.

```java
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
```
* **Lines 90–100**: Non-blocking write: Writes as many bytes as possible from `current` buffer into the socket channel. If the buffer is fully drained, transitions to the next stage via `step()`.

```java
        private void step() {
            switch (stage) {
                case HEADERS -> advanceBody();
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
```
* **Lines 102–122**: `step()`: State machine transition logic driving chunk header $\rightarrow$ chunk payload $\rightarrow$ chunk CRLF $\rightarrow$ terminal chunk $\rightarrow$ DONE.

---

### `RequestPolicy.java`
**Location:** [`src/webserver/http/RequestPolicy.java`](file:///home/kali/localhost/src/webserver/http/RequestPolicy.java)

#### Purpose & Responsibility
Validates basic HTTP request invariants prior to route matching.

#### Detailed Code Breakdown

```java
package webserver.http;

public final class RequestPolicy {
    private RequestPolicy() {}

    public static int rejectionCode(HttpRequest request) {
        if (request == null) return HttpCodes.BAD_REQUEST;
        if (request.body().length != 0 && HttpMethods.GET.equals(request.method())) {
            return HttpCodes.BAD_REQUEST;
        }
        return HttpMethods.supported(request.method()) ? 0 : HttpCodes.METHOD_NOT_ALLOWED;
    }
}
```
* **Lines 1–14**: Rejects null requests or `GET` with payloads with `400 Bad Request`. Rejects unsupported methods (like `PATCH`, `PUT`, `OPTIONS`) with `405 Method Not Allowed`.

---

## 3.5 Routing Layer (`webserver.routing`)

### `RouteMatcher.java`
**Location:** [`src/webserver/routing/RouteMatcher.java`](file:///home/kali/localhost/src/webserver/routing/RouteMatcher.java)

#### Purpose & Responsibility
Decodes URL targets, separates query strings from path components, checks against null bytes and backslashes, and selects the matching route using prefix matching.

#### Detailed Code Breakdown

```java
package webserver.routing;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import webserver.config.ConfigLoader;

public final class RouteMatcher {
    private final ConfigLoader.Config config;

    public RouteMatcher(ConfigLoader.Config config) {
        this.config = config;
    }

    public Match find(String rawTarget) {
        Target target = parseTarget(rawTarget);
        ConfigLoader.Route selected = config.routes().stream()
                .filter(route -> matches(route.path(), target.path()))
                .findFirst().orElse(null);
        return new Match(target, selected);
    }
```
* **Lines 1–21**: Decodes raw target and filters routes. Because `config.routes()` is sorted by path length in descending order, the first match found is guaranteed to be the longest prefix match.

```java
    private static boolean matches(String routePath, String path) {
        if (routePath.equals("/") || path.equals(routePath)) return true;
        if (routePath.endsWith("/")) {
            String prefix = routePath.substring(0, routePath.length() - 1);
            return path.equals(prefix) || path.startsWith(routePath);
        }
        return path.startsWith(routePath + "/");
    }

    private static Target parseTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()) {
            throw new IllegalArgumentException("Empty target");
        }
        String[] pieces = rawTarget.split("\\?", 2);
        String rawPath = pieces[0];
        String query = pieces.length == 2 ? pieces[1] : "";

        String path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid target: " + rawPath);
        }
        return new Target(path, query);
    }

    public record Match(Target target, ConfigLoader.Route route) {}
    public record Target(String path, String query) {}
}
```
* **Lines 23–49**:
  * `matches()`: Handles exact matches, root `/`, directory slash boundaries, and child resources.
  * `parseTarget()`: Splits query strings (`?key=val`), decodes URL percent-encoding safely, and rejects backslashes (`\`) or null bytes (`\0`) to thwart directory bypass attempts.

---

### `Router.java`
**Location:** [`src/webserver/routing/Router.java`](file:///home/kali/localhost/src/webserver/routing/Router.java)

#### Purpose & Responsibility
Orchestrates high-level HTTP request dispatching. Coordinates sessions, evaluates policies, checks allowed HTTP methods for matched routes, handles redirects, dispatches `GET`, `POST`, `DELETE` operations, and catches runtime exceptions to render error pages.

#### Detailed Code Breakdown

```java
package webserver.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import webserver.config.ConfigLoader;
import webserver.delivery.CGIHandler;
import webserver.delivery.ResourceService;
import webserver.delivery.ResourceService.BadRequest;
import webserver.delivery.ResourceService.Forbidden;
import webserver.http.HttpMethods;
import webserver.http.HttpRequest;
import webserver.http.HttpResponse;
import webserver.http.RequestPolicy;
import webserver.response.ResponseFactory;
import webserver.session.SessionStore;

public final class Router {
    private final ConfigLoader.Config config;
    private final SessionStore sessions = new SessionStore();
    private final RouteMatcher matcher;
    private final ResponseFactory responses;
    private final ResourceService resources;

    public Router(ConfigLoader.Config config) {
        this.config = config;
        this.matcher = new RouteMatcher(config);
        this.responses = new ResponseFactory(config);
        this.resources = new ResourceService(config, responses);
    }
```
* **Lines 1–34**: Router dependencies: Initializes `SessionStore`, `RouteMatcher`, `ResponseFactory`, and `ResourceService`.

```java
    public HttpResponse handle(HttpRequest request, ConfigLoader.VirtualServer server) {
        SessionStore.Result session = sessions.find(request.header("cookie"));
        HttpResponse response;
        try {
            int policy = RequestPolicy.rejectionCode(request);
            if (policy == 400) {
                response = responses.error(400);
            } else if (policy == 405) {
                response = responses.error(405).header("Allow", HttpMethods.allowValue());
            } else {
                RouteMatcher.Match match = matcher.find(request.target());
                RouteMatcher.Target target = match.target();
                ConfigLoader.Route route = match.route();

                if (route == null) {
                    response = responses.error(404);
                } else if (!route.methods().contains(request.method())) {
                    response = responses.error(405).header("Allow", String.join(", ", route.methods()));
                } else if (route.redirect() != null) {
                    response = responses.redirect(route.redirectStatus(), route.redirect());
                } else {
                    response = switch (request.method()) {
                        case HttpMethods.GET -> get(target, route);
                        case HttpMethods.POST -> post(request, target, route);
                        case HttpMethods.DELETE -> delete(target, route);
                        default -> responses.error(405);
                    };
                }
            }
        } catch (Forbidden e) {
            response = responses.error(403);
        } catch (BadRequest | IllegalArgumentException e) {
            response = responses.error(400);
        } catch (Exception e) {
            System.err.println("Router error: " + e.getMessage());
            response = responses.error(500);
        }

        if (session.setCookie() != null) response.header("Set-Cookie", session.setCookie());
        response.header("X-Server-Name", server.name());
        return response;
    }
```
* **Lines 35–76**: `handle()`:
  * Manages session identification and `Set-Cookie` generation.
  * Dispatches methods to `get()`, `post()`, or `delete()`.
  * Catches `Forbidden` (403), `BadRequest` (400), and generic exceptions (500).
  * Adds `X-Server-Name` tracking header to the response.

```java
    private HttpResponse get(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        ResourceService.Resource file = resources.resolve(target.path(), route, false);
        HttpResponse statusResponse = resourceStatus(file);
        if (statusResponse != null) return statusResponse;
        if (Files.isDirectory(file.path())) return resources.directoryListing(target.path(), file.path());
        if (route.cgi()) {
            resources.verifyCgi(file.path());
            byte[] output = CGIHandler.execute(config, file.path(), target.query(), target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        return new HttpResponse(200, ResourceService.contentType(file.path()), Files.readAllBytes(file.path()));
    }
```
* **Lines 78–89**: `get()`: Resolves file path $\rightarrow$ checks redirects/errors $\rightarrow$ generates directory listings for folders $\rightarrow$ executes CGI if enabled $\rightarrow$ or returns static file bytes with detected MIME type.

```java
    private HttpResponse post(HttpRequest req, RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        ResourceService.Resource file = resources.resolve(target.path(), route, true);
        HttpResponse statusResponse = resourceStatus(file);
        if (statusResponse != null) return statusResponse;

        String ct = req.header("content-type");
        boolean multipart = ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        String data = multipart ? resources.multipart(req.body(), ct) : new String(req.body(), StandardCharsets.UTF_8);

        if (route.cgi()) {
            if (Files.isDirectory(file.path())) throw new BadRequest();
            resources.verifyCgi(file.path());
            byte[] output = CGIHandler.execute(config, file.path(), data, target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        if (multipart) {
            return responses.bytes(201, "application/json; charset=utf-8", data.getBytes(StandardCharsets.UTF_8));
        }
        return responses.bytes(200, "text/plain; charset=utf-8", new byte[0]);
    }
```
* **Lines 91–110**: `post()`: Handles raw body and `multipart/form-data` uploads. Passes body to CGI or writes files to the uploads directory.

```java
    private HttpResponse delete(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        ResourceService.Resource file = resources.resolve(target.path(), route, false);
        if (file.redirect() != null) return responses.redirect(301, file.redirect());
        if (file.status() != 200 || !Files.isRegularFile(file.path())) return responses.error(404);
        Files.delete(file.path());
        return responses.text(200, "deleted\n");
    }
```
* **Lines 117–124**: `delete()`: Resolves the target file within the jail and deletes it, returning `200 OK` with `"deleted\n"`.

---

## 3.6 Resource Delivery & Execution (`webserver.delivery`)

### `ResourceService.java`
**Location:** [`src/webserver/delivery/ResourceService.java`](file:///home/kali/localhost/src/webserver/delivery/ResourceService.java)

#### Purpose & Responsibility
Filesystem security sandboxing, canonical path resolution, directory index resolution, automatic HTML directory listing generation, MIME content-type mapping, and multipart upload persistence.

#### Detailed Code Breakdown

```java
package webserver.delivery;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;
import webserver.response.ResponseFactory;

public final class ResourceService {
    private final ConfigLoader.Config config;
    private final ResponseFactory responses;

    public ResourceService(ConfigLoader.Config config, ResponseFactory responses) {
        this.config = config;
        this.responses = responses;
    }
```
* **Lines 1–26**: Handles filesystem interaction and response formatting.

```java
    public Resource resolve(String requestPath, ConfigLoader.Route route, boolean allowDirectory) throws IOException {
        String relative = relativePath(requestPath, route.path());
        Path routeRoot = config.root().resolve(route.root()).normalize();
        Path candidate = routeRoot.resolve(relative).normalize();
        if (!routeRoot.startsWith(config.root()) || !candidate.startsWith(routeRoot)) {
            throw new Forbidden();
        }
        if (!Files.exists(candidate)) return new Resource(candidate, 404, null);

        Path realRoot = routeRoot.toRealPath();
        Path realPath = candidate.toRealPath();
        if (!realRoot.startsWith(config.root()) || !realPath.startsWith(realRoot)) {
            throw new Forbidden();
        }

        if (!Files.isDirectory(realPath)) return new Resource(realPath, 200, null);
        if (!requestPath.endsWith("/")) return new Resource(realPath, 301, requestPath + "/");
        if (allowDirectory) return new Resource(realPath, 200, null);

        if (route.defaultFile() != null) {
            Path defaultPath = realPath.resolve(route.defaultFile()).normalize();
            if (defaultPath.startsWith(config.root()) && Files.isRegularFile(defaultPath)) {
                Path realDefault = defaultPath.toRealPath();
                if (!realDefault.startsWith(realPath)) throw new Forbidden();
                return new Resource(realDefault, 200, null);
            }
        }
        int status = route.directoryListing() ? 200 : 403;
        return new Resource(realPath, status, null);
    }
```
* **Lines 28–57**: `resolve()`:
  * **Directory Jail Check**: Uses `toRealPath()` on both the root and candidate paths, throwing `Forbidden` (403) if symlinks or `../` escape the sandbox.
  * **Trailing Slash Redirect**: If a request points to a folder without a trailing slash (e.g. `/files`), returns `301 Moved Permanently` redirecting to `/files/`.
  * **Default Index Resolution**: If `defaultFile` (e.g., `index.html`) exists within the directory, serves it directly.
  * **Listing vs Forbidden**: If no default file exists, permits directory listing (200) or blocks access (403) based on `directory_listing` config.

```java
    public HttpResponse directoryListing(String requestPath, Path directory) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><title>Files</title><h1>Files</h1><ul>");
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                html.append("<li><a href=\"").append(requestPath).append(encoded).append("\">")
                        .append(escapeHtml(name)).append("</a></li>");
            }
        }
        html.append("</ul>");
        return responses.bytes(200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
    }
```
* **Lines 59–71**: Generates sanitized HTML listing of folder contents with URL-encoded links and HTML-escaped labels.

```java
    public String multipart(byte[] body, String contentType) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        final List<MultipartParser.Part> parts;
        try {
            parts = MultipartParser.read(body, contentType);
        } catch (IllegalArgumentException e) {
            throw new BadRequest();
        }

        for (MultipartParser.Part part : parts) {
            if (part.filename() == null || part.filename().isEmpty()) {
                values.put(part.name(), new String(part.content(), StandardCharsets.UTF_8));
                continue;
            }
            String extension = fileExtension(part.filename());
            Path output = config.uploads().resolve(UUID.randomUUID() + extension).normalize();
            if (!output.startsWith(config.uploads())) throw new Forbidden();
            Files.write(output, part.content(), StandardOpenOption.CREATE_NEW);
            values.put(part.name(), config.root().relativize(output).toString().replace('\\', '/'));
        }
        return json(values);
    }
```
* **Lines 73–94**: `multipart()`: Parses multipart streams. Stores uploaded files under `uploads/<UUID>.<ext>`, preventing filename collision or path traversal attacks, and returns a JSON summary map.

```java
    public static String contentType(Path file) { ... }
    private static String relativePath(...) { ... }
    private static String fileExtension(...) { ... }
    private static String json(...) { ... }
    private static String escapeJson(...) { ... }
    private static String escapeHtml(...) { ... }
}
```
* **Lines 96–160**: Content-type detector and string escaping helpers.

---

### `MultipartParser.java`
**Location:** [`src/webserver/delivery/MultipartParser.java`](file:///home/kali/localhost/src/webserver/delivery/MultipartParser.java)

#### Purpose & Responsibility
Decodes RFC 7578 multipart/form-data bodies without third-party dependencies, extracting fields, filenames, and byte content delimited by boundaries.

#### Detailed Code Breakdown

```java
package webserver.delivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MultipartParser {
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLF_CRLF = {'\r', '\n', '\r', '\n'};

    private MultipartParser() {}

    public record Part(String name, String filename, byte[] content) {}
```
* **Lines 1–15**: Defines the `Part` record containing field name, optional filename, and binary byte array.

```java
    public static List<Part> read(byte[] body, String contentType) {
        String boundary = param(contentType, "boundary");
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Missing boundary");
        }

        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        if (find(body, 0, marker) != 0) throw new IllegalArgumentException("Bad multipart body");

        int pos = marker.length;
        List<Part> parts = new ArrayList<>();

        while (pos < body.length) {
            if (starts(body, pos, new byte[] {'-', '-'})) return parts;
            if (!starts(body, pos, CRLF)) throw new IllegalArgumentException("Bad boundary line ending");
            pos += 2;

            int headerEnd = find(body, pos, CRLF_CRLF);
            if (headerEnd < 0) throw new IllegalArgumentException("Missing header delimiter");

            String headers = text(body, pos, headerEnd);
            String disposition = Arrays.stream(headers.split("\\r\\n"))
                    .filter(line -> line.regionMatches(true, 0, "content-disposition:", 0, 20))
                    .findFirst().orElse(null);
            if (disposition == null) throw new IllegalArgumentException("Missing disposition");

            String name = param(disposition, "name");
            String filename = param(disposition, "filename");
            if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing field name");

            int contentStart = headerEnd + 4;
            byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int contentEnd = find(body, contentStart, nextDelimiter);
            if (contentEnd < 0) throw new IllegalArgumentException("Unterminated multipart part");

            parts.add(new Part(name, filename, Arrays.copyOfRange(body, contentStart, contentEnd)));
            pos = contentEnd + 2 + marker.length;
        }
        throw new IllegalArgumentException("Unterminated multipart body");
    }
```
* **Lines 17–56**:
  * Extracts boundary parameter from `Content-Type: multipart/form-data; boundary=...`.
  * Scans each part: parses headers, extracts `name` and `filename` parameters from `Content-Disposition`, slices content bytes, and terminates upon encountering the closing `--` boundary delimiter.

```java
    private static String param(String header, String name) { ... }
    private static String text(byte[] source, int start, int end) { ... }
    private static boolean starts(byte[] src, int offset, byte[] prefix) { ... }
    private static int find(byte[] src, int start, byte[] target) { ... }
}
```
* **Lines 58–93**: Parsing utilities for multipart parameter extraction and byte pattern search.

---

### `CGIHandler.java`
**Location:** [`src/webserver/delivery/CGIHandler.java`](file:///home/kali/localhost/src/webserver/delivery/CGIHandler.java)

#### Purpose & Responsibility
Spawns an isolated OS subprocess to execute CGI scripts, passing request data, setting `PATH_INFO`, capturing combined stdout/stderr, and enforcing a 10-second process timeout.

#### Detailed Code Breakdown

```java
package webserver.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import webserver.config.ConfigLoader;

public final class CGIHandler {
    private static final long TIMEOUT_SECONDS = 10L;

    private CGIHandler() {}

    public static byte[] execute(ConfigLoader.Config config, Path script, String data, String pathInfo)
            throws IOException {
        Path temp = Files.createTempFile("cgi-out-", ".tmp");
        Process process = null;
        try {
            process = start(config, script, data, pathInfo, temp);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("CGI timeout");
            }

            byte[] output = Files.readAllBytes(temp);
            if (process.exitValue() != 0) {
                throw new IOException("CGI error: " + new String(output, StandardCharsets.UTF_8).trim());
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CGI interrupted", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(temp);
        }
    }
```
* **Lines 1–40**:
  * Creates a temporary file to capture subprocess output safely without pipe deadlock.
  * Waits up to 10 seconds. If the process hangs, `destroyForcibly()` terminates it immediately to protect system resources.
  * Rejects non-zero process exit codes and deletes temporary output files in the `finally` block.

```java
    private static Process start(ConfigLoader.Config config, Path script, String data,
            String pathInfo, Path output) throws IOException {
        ProcessBuilder command = new ProcessBuilder(config.cgi().command(), script.toString(),
                data == null ? "" : data);
        command.directory(config.root().toFile());
        command.environment().put("PATH_INFO", pathInfo == null ? "" : pathInfo);
        command.redirectErrorStream(true);
        command.redirectOutput(output.toFile());
        return command.start();
    }
}
```
* **Lines 42–52**: `start()`: Prepares the command using `ProcessBuilder`, configures working directory to server `root`, injects `PATH_INFO` into process environment, and redirects output.

---

## 3.7 Response & Error Handling (`webserver.response`)

### `ResponseFactory.java`
**Location:** [`src/webserver/response/ResponseFactory.java`](file:///home/kali/localhost/src/webserver/response/ResponseFactory.java)

#### Purpose & Responsibility
Factory methods for generating uniform `HttpResponse` objects (error pages, redirects, plain text, and binary payloads).

#### Detailed Code Breakdown

```java
package webserver.response;

import java.nio.charset.StandardCharsets;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;

public final class ResponseFactory {
    private final ConfigLoader.Config config;

    public ResponseFactory(ConfigLoader.Config config) {
        this.config = config;
    }

    public HttpResponse error(int status) { return FaultPages.response(config, status); }

    public HttpResponse redirect(int status, String location) {
        return text(status, "redirect: " + location + "\n").header("Location", location);
    }

    public HttpResponse text(int status, String content) {
        return bytes(status, "text/plain; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    public HttpResponse bytes(int status, String contentType, byte[] payload) {
        return new HttpResponse(status, contentType, payload);
    }
}
```
* **Lines 1–28**: Streamlines response creation across routes and handlers.

---

### `FaultPages.java`
**Location:** [`src/webserver/response/FaultPages.java`](file:///home/kali/localhost/src/webserver/response/FaultPages.java)

#### Purpose & Responsibility
Resolves custom HTML error pages (400, 403, 404, 405, 413, 500) from disk or generates standard plaintext fallback status lines.

#### Detailed Code Breakdown

```java
package webserver.response;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import webserver.config.ConfigLoader;
import webserver.http.HttpCodes;
import webserver.http.HttpResponse;

public final class FaultPages {
    private FaultPages() {}

    public static HttpResponse response(ConfigLoader.Config config, int code) {
        return new HttpResponse(code, "text/html; charset=utf-8", body(config, code));
    }

    private static byte[] body(ConfigLoader.Config config, int code) {
        if (config != null && config.errorPages() != null) {
            Path page = config.errorPages().get(code);
            if (page != null) {
                try {
                    return Files.readAllBytes(page);
                } catch (Exception ignored) {}
            }
        }
        return (code + " " + HttpCodes.reason(code) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
```
* **Lines 1–29**: Reads pre-configured error page HTML if available; otherwise falls back to status lines like `"404 Not Found\n"`.

---

## 3.8 Session Management (`webserver.session`)

### `SessionStore.java`
**Location:** [`src/webserver/session/SessionStore.java`](file:///home/kali/localhost/src/webserver/session/SessionStore.java)

#### Purpose & Responsibility
Provides a thread-safe in-memory session registry with sliding TTL expiration (1 hour) and generates `Set-Cookie` headers.

#### Detailed Code Breakdown

```java
package webserver.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionStore {
    private static final long MAX_AGE_SECONDS = 3600;
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

    public synchronized Result find(String cookieHeader) {
        long now = Instant.now().getEpochSecond();
        expiry.entrySet().removeIf(entry -> entry.getValue() <= now);

        String current = sessionId(cookieHeader);
        Long previousExpiry = current == null ? null : expiry.get(current);
        if (previousExpiry != null && expiry.replace(current, previousExpiry, now + MAX_AGE_SECONDS)) {
            return Result.reused();
        }

        String fresh = UUID.randomUUID().toString();
        expiry.put(fresh, now + MAX_AGE_SECONDS);
        return Result.created("session_id=" + fresh + "; Max-Age=" + MAX_AGE_SECONDS
                + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static String sessionId(String header) {
        if (header == null) return null;
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "session_id".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public record Result(String setCookie) {
        static Result reused() { return new Result(null); }
        static Result created(String cookie) { return new Result(cookie); }
    }
}
```
* **Lines 1–44**:
  * `MAX_AGE_SECONDS = 3600`: 1-hour session duration.
  * `find()`: Purges expired sessions, checks incoming `session_id` cookies, slides active session lifetimes forward, or generates a new cryptographically random UUID session cookie with `HttpOnly; SameSite=Lax`.

---

# 4. Public Assets, CGI Scripts, Configuration & Tests

### `config.json`
**Location:** [`config.json`](file:///home/kali/localhost/config.json)

```json
{
  "root": "public",
  "uploads": "uploads",
  "max_body_size": 1048576,
  "request_timeout_seconds": 15,
  "error_pages": {
    "400": "error_pages/400.html",
    "403": "error_pages/403.html",
    "404": "error_pages/404.html",
    "405": "error_pages/405.html",
    "413": "error_pages/413.html",
    "500": "error_pages/500.html"
  },
  "cgi": {
    "extension": "java",
    "command": "java"
  },
  "routes": [
    {
      "path": "/old",
      "methods": ["GET"],
      "redirect": "/",
      "redirect_status": 302
    },
    {
      "path": "/files/",
      "methods": ["GET", "POST", "DELETE"],
      "root": "uploads",
      "directory_listing": true
    },
    {
      "path": "/cgi",
      "methods": ["GET", "POST"],
      "root": "cgi/Echo.java",
      "cgi": true
    },
    {
      "path": "/",
      "methods": ["GET"],
      "root": ".",
      "default_file": "index.html",
      "directory_listing": false
    }
  ],
  "servers": [
    {
      "address": "127.0.0.1",
      "ports": [8080, 8081],
      "server_names": ["default.local"]
    },
    {
      "address": "127.0.0.1",
      "ports": [8080],
      "server_names": ["named.local"]
    }
  ]
}
```
* Configures two ports (`8080`, `8081`), virtual host dispatching (`default.local` vs `named.local`), 1 MB payload limits, 15-second request timeouts, upload directories, CGI mappings, and route rules.

---

### `public/cgi/Echo.java`
**Location:** [`public/cgi/Echo.java`](file:///home/kali/localhost/public/cgi/Echo.java)

```java
final class Echo {
    public static void main(String[] args) {
        System.out.println("PATH_INFO=" + System.getenv().getOrDefault("PATH_INFO", ""));
        System.out.println("DATA=" + (args.length > 0 ? args[0] : ""));
    }
}
```
* Standalone CGI application executed by `CGIHandler` echoing request `PATH_INFO` and body payloads.

---

### `tests/audit.sh`
**Location:** [`tests/audit.sh`](file:///home/kali/localhost/tests/audit.sh)

A complete test suite that validates:
* Static GET requests (200 OK)
* Multiple configured ports (8081)
* Redirects (302 & 301)
* Missing routes (404 Not Found)
* Method restrictions and unsupported verbs (405 Method Not Allowed)
* GET with a body (400 Bad Request)
* Payload limit enforcement (413 Payload Too Large)
* Virtual host dispatching (`X-Server-Name: named.local`)
* CGI GET and unchunked/chunked POST executions
* Cookie session generation and reuse
* Multipart file upload, download verification (`cmp`), and file deletion (DELETE)
* High-concurrency stress testing (100 parallel curl requests)

---

### `Makefile`
**Location:** [`Makefile`](file:///home/kali/localhost/Makefile)

```makefile
SHELL := /bin/sh
JAVAC ?= javac
JAVA ?= java
JAR ?= jar

SOURCES := $(shell find src -name '*.java' -type f | sort)
BUILD := build
CLASSES := $(BUILD)/classes
JAR_FILE := $(BUILD)/java-server.jar

.PHONY: all build run audit clean

all: build

build: $(JAR_FILE)

$(JAR_FILE): $(SOURCES)
	mkdir -p $(CLASSES)
	$(JAVAC) -encoding UTF-8 -Xlint:all -Werror -d $(CLASSES) $(SOURCES)
	$(JAR) --create --file $(JAR_FILE) --main-class webserver.bootstrap.Main -C $(CLASSES) .

run: build
	$(JAVA) -jar $(JAR_FILE) --config config.json

audit: build
	JAVA="$(JAVA)" sh tests/audit.sh

clean:
	rm -rf $(BUILD)
```
* Compiles all Java sources with strict warnings (`-Xlint:all -Werror`) into `build/java-server.jar`.

# LocalServer 2.0 — Comprehensive Technical Documentation

An in-depth guide to the architecture, core concepts, Java NIO standard library APIs, internal components, function-by-function breakdown, and audit defense for **LocalServer 2.0**.

---

## Table of Contents

1. [Architecture & System Overview](#1-architecture--system-overview)
2. [Core Theoretical Concepts](#2-core-theoretical-concepts)
   - [2.1 How an HTTP/1.1 Server Works](#21-how-an-http11-server-works)
   - [2.2 I/O Multiplexing & The Reactor Pattern](#22-io-multiplexing--the-reactor-pattern)
   - [2.3 Non-Blocking Channels & SelectionKeys](#23-non-blocking-channels--selectionkeys)
   - [2.4 HTTP Message Framing (Chunked vs Unchunked)](#24-http-message-framing-chunked-vs-unchunked)
   - [2.5 Common Gateway Interface (CGI) Protocol](#25-common-gateway-interface-cgi-protocol)
   - [2.6 Multipart/Form-Data File Uploads](#26-multipartform-data-file-uploads)
   - [2.7 HTTP Cookies & Session Management](#27-http-cookies--session-management)
   - [2.8 Virtual Hosting & Multi-Port Listening](#28-virtual-hosting--multi-port-listening)
3. [Standard Java Imports Reference](#3-standard-java-imports-reference)
4. [Class-by-Class & Function-by-Function Breakdown](#4-class-by-class--function-by-function-breakdown)
   - [4.1 Main.java](#41-mainjava)
   - [4.2 Server.java](#42-serverjava)
   - [4.3 Router.java](#43-routerjava)
   - [4.4 CGIHandler.java](#44-cgihandlerjava)
   - [4.5 ConfigLoader.java](#45-configloaderjava)
   - [4.6 ErrorPages.java](#46-errorpagesjava)
   - [4.7 HttpRequest.java](#47-httprequestjava)
   - [4.8 HttpResponse.java](#48-httpresponsejava)
   - [4.9 utils/Session.java](#49-utilssessionjava)
   - [4.10 utils/Cookie.java](#410-utilscookiejava)
   - [4.11 utils/Json.java](#411-utilsjsonjava)
   - [4.12 utils/Multipart.java](#412-utilsmultipartjava)
5. [Audit Questions & Source Code Defense](#5-audit-questions--source-code-defense)
6. [Build, Testing & Benchmarking](#6-build-testing--benchmarking)

---

## 1. Architecture & System Overview

LocalServer 2.0 is an event-driven, single-threaded, non-blocking HTTP/1.1 server written in pure Java without third-party frameworks. It handles concurrent client connections across multiple ports through a single `java.nio.channels.Selector` instance.

### Architecture Diagram

```
                       +---------------------------------------------------+
                       |                    Main.java                      |
                       | (CLI parsing, Config Loading, Shutdown Hook)      |
                       +---------------------------------------------------+
                                                 |
                                                 v
                       +---------------------------------------------------+
                       |                   Server.java                     |
                       |       Single-Threaded Reactor Event Loop          |
                       +---------------------------------------------------+
                               |                   |                   |
                     OP_ACCEPT |           OP_READ |          OP_WRITE |
                               v                   v                   v
                     +----------------+  +----------------+  +----------------+
                     |  Accept Client |  |  Read Request  |  | Write Response |
                     | SocketChannel  |  |  from Channel  |  |   to Channel   |
                     +----------------+  +----------------+  +----------------+
                                                   |
                                                   v
                                         +-------------------+
                                         |  HttpRequest.java |
                                         | Stream RFC Parser |
                                         +-------------------+
                                                   | (Parsed Request)
                                                   v
                                         +-------------------+
                                         |    Router.java    |
                                         +-------------------+
                                           /       |       \
                                          /        |        \
                                         v         v         v
                         +----------------+ +---------------+ +------------------+
                         | Static Files & | |  CGIHandler   | |   Multipart      |
                         | Directory List | | (Python/Java) | | (Upload Service) |
                         +----------------+ +---------------+ +------------------+
                                         \         |         /
                                          \        |        /
                                           v       v       v
                                         +-------------------+
                                         | HttpResponse.java |
                                         | (Buffered Output) |
                                         +-------------------+
```

---

## 2. Core Theoretical Concepts

### 2.1 How an HTTP/1.1 Server Works
HTTP (Hypertext Transfer Protocol) is an application-level request-response protocol running on top of TCP/IP (Transmission Control Protocol / Internet Protocol).
1. **Connection Establishment**: The client establishes a 3-way TCP handshake (`SYN` -> `SYN-ACK` -> `ACK`) with the server on a bound port.
2. **Request Transmission**: The client sends a byte stream formatted according to RFC 9112:
   - **Request-Line**: `Method SP Request-Target SP HTTP-Version CRLF` (e.g., `GET /index.html HTTP/1.1\r\n`).
   - **Headers**: Zero or more `Field-Name: Field-Value CRLF` lines, terminating with an empty line `\r\n`.
   - **Message Body**: Optional raw octets whose length is determined by `Content-Length` or `Transfer-Encoding: chunked`.
3. **Request Processing**: The server resolves virtual host bindings via the `Host:` header, matches URL routes, enforces authorization and method restrictions, and executes static delivery or dynamic CGI scripts.
4. **Response Delivery**: The server transmits an HTTP status line (e.g., `HTTP/1.1 200 OK\r\n`), response headers (`Content-Type`, `Content-Length`, `Set-Cookie`), and payload bytes.
5. **Connection Teardown**: Depending on the `Connection` header (`close` vs `keep-alive`), the socket channel is cleanly terminated or kept open for subsequent requests.

### 2.2 I/O Multiplexing & The Reactor Pattern
In traditional blocking multi-threaded servers (e.g. 1 thread per client), thread count scales linearly with concurrent connections ($O(N)$), leading to high memory overhead (stack allocation per thread) and massive CPU context switching.

**I/O Multiplexing** allows a **single thread** to monitor multiple file descriptors/sockets simultaneously to identify which ones are ready for reading or writing without blocking execution.
- Under Linux, `java.nio.channels.Selector` wraps the native `epoll` system call (`epoll_create`, `epoll_ctl`, `epoll_wait`).
- In BSD/macOS, it wraps `kqueue`.

### 2.3 Non-Blocking Channels & SelectionKeys
In Java NIO:
- `SelectableChannel.configureBlocking(false)` puts the socket in non-blocking mode (`O_NONBLOCK` flag on the OS file descriptor).
- `SelectionKey` represents the registration token of a channel with a selector.
  - `OP_ACCEPT (1 << 4)`: The server socket has an incoming client connection ready to be accepted.
  - `OP_READ (1 << 0)`: The socket has inbound data available in the OS receive buffer.
  - `OP_WRITE (1 << 2)`: The socket's OS send buffer has space available for outbound transmission.

### 2.4 HTTP Message Framing (Chunked vs Unchunked)
HTTP/1.1 message bodies use two primary framing mechanisms:
1. **Fixed Length (`Content-Length`)**:
   The header `Content-Length: <N>` explicitly gives the exact body size in decimal bytes. The server reads exactly $N$ bytes following the `\r\n\r\n` header delimiter.
2. **Chunked Transfer Encoding (`Transfer-Encoding: chunked`)**:
   Used when total content size is unknown up front. The body is sent in a series of chunks:
   - Chunk Header: `<Hex-Chunk-Size> [; extension] \r\n`
   - Chunk Data: `<Raw-Bytes>` of length `<Hex-Chunk-Size>`
   - Chunk Trailer: `\r\n`
   - Final Terminating Chunk: `0\r\n\r\n`

### 2.5 Common Gateway Interface (CGI) Protocol
CGI (RFC 3875) enables HTTP servers to execute external programs to generate dynamic HTTP responses:
- **Execution**: The server forks/starts a child process using `ProcessBuilder`.
- **Environment Variables**:
  - `REQUEST_METHOD`: HTTP method (`GET`, `POST`, `DELETE`).
  - `QUERY_STRING`: URL query parameters after `?`.
  - `PATH_INFO`: Virtual path of the request.
  - `CONTENT_LENGTH` & `CONTENT_TYPE`: Body metadata.
  - `SERVER_NAME` & `SERVER_PORT`: Server listener info.
  - `HTTP_*`: Client headers transformed (e.g. `User-Agent` -> `HTTP_USER_AGENT`).
- **Data Flow**:
  - GET query strings are passed via `QUERY_STRING` and process arguments.
  - POST request payloads are piped directly into the process's standard input (`stdin`).
  - The script writes output to standard output (`stdout`), which is captured by the server and returned to the client.

### 2.6 Multipart/Form-Data File Uploads
Defined in RFC 7578, `multipart/form-data` encodes multiple form fields and binary files within a single body payload:
- The `Content-Type` header specifies a unique boundary string: `Content-Type: multipart/form-data; boundary=---------------------------974767299852498929531610575`
- Each part starts with `--<boundary>\r\n`.
- Followed by part headers: `Content-Disposition: form-data; name="file"; filename="document.pdf"\r\n\r\n`.
- Followed by the raw file payload.
- Delimited by `\r\n--<boundary>`.
- Terminated by `--<boundary>--\r\n`.

### 2.7 HTTP Cookies & Session Management
- **RFC 6265 State Management**: HTTP is stateless; sessions are maintained via client-side cookies.
- **`Set-Cookie` Directives**:
  - `session_id=<UUID>`: Unique cryptographic identifier.
  - `Max-Age=3600`: Lifetime in seconds.
  - `Path=/`: Scope of the cookie.
  - `HttpOnly`: Prevents client-side JavaScript access (XSS mitigation).
  - `SameSite=Lax`: Mitigates Cross-Site Request Forgery (CSRF).
- **Session Lifecycle**: The server stores sessions with expiration timestamps. If the client presents a valid active session ID, it is reused without issuing a new `Set-Cookie` header.

### 2.8 Virtual Hosting & Multi-Port Listening
- **Multi-Port**: The server binds distinct `ServerSocketChannel` instances to all configured port numbers on the host interface.
- **Virtual Hosting (SNI / Host Header)**: When multiple virtual server blocks share the same IP/Port, the server inspects the mandatory `Host:` request header (e.g., `Host: named.local:8080`) to select the appropriate virtual host rules and sets `X-Server-Name`.

---

## 3. Standard Java Imports Reference

This table provides a comprehensive reference for every imported package, class, and method used across LocalServer 2.0:

| Import / Class | Source File(s) | Package | Purpose & Use Case | Implementation Detail |
| :--- | :--- | :--- | :--- | :--- |
| `java.nio.ByteBuffer` | `Server.java`, `HttpResponse.java` | `java.nio` | Direct byte container for non-blocking I/O operations. | Used to buffer data read from and written to `SocketChannel`. Utilizes `allocate()`, `clear()`, `flip()`, `remaining()`, `get()`, and `put()`. |
| `java.nio.channels.Selector` | `Server.java` | `java.nio.channels` | Multiplexor of non-blocking `SelectableChannel` objects. | Opened via `Selector.open()`. Calls `select(250)` to wait for I/O readiness, `selectedKeys()` to iterate active keys, and `wakeup()` / `close()` on shutdown. |
| `java.nio.channels.SelectionKey` | `Server.java` | `java.nio.channels` | Token representing channel registration with a selector. | Inspects `isAcceptable()`, `isReadable()`, `isWritable()`, modifies `interestOps()`, accesses `attachment()`, and calls `cancel()`. |
| `java.nio.channels.ServerSocketChannel` | `Server.java` | `java.nio.channels` | Non-blocking listening socket for TCP connections. | Created via `ServerSocketChannel.open()`, configured non-blocking, bound to `InetSocketAddress`, and registered with `OP_ACCEPT`. |
| `java.nio.channels.SocketChannel` | `Server.java`, `HttpResponse.java` | `java.nio.channels` | Non-blocking TCP client stream connection. | Used for non-blocking `read(ByteBuffer)` and `write(ByteBuffer)`. Returns bytes transferred, `0` for would-block, or `-1` for EOF. |
| `java.nio.channels.ClosedChannelException` | `Server.java` | `java.nio.channels` | Exception thrown when an I/O operation is attempted on a closed channel. | Caught in the reactor loop to gracefully exit when the selector or channel closes. |
| `java.nio.file.Path` | `Main.java`, `Server.java`, `Router.java`, `ConfigLoader.java`, `ErrorPages.java`, `CGIHandler.java` | `java.nio.file` | Represents a filesystem path. | Constructed via `Path.of()` and `resolve()`. Used for secure directory traversal checks via `normalize()` and `toRealPath()`. |
| `java.nio.file.Files` | `Router.java`, `ConfigLoader.java`, `ErrorPages.java`, `CGIHandler.java` | `java.nio.file` | High-performance filesystem operations utility. | Used for `Files.exists()`, `Files.isDirectory()`, `Files.isRegularFile()`, `Files.readAllBytes()`, `Files.readString()`, `Files.write()`, `Files.list()`, and `Files.delete()`. |
| `java.nio.file.StandardOpenOption` | `Router.java` | `java.nio.file` | File open options enum. | Used with `Files.write()` (`CREATE`, `TRUNCATE_EXISTING`) to safely save uploaded multipart files. |
| `java.nio.charset.StandardCharsets` | `HttpRequest.java`, `HttpResponse.java`, `Router.java`, `ConfigLoader.java`, `ErrorPages.java`, `CGIHandler.java`, `utils/*` | `java.nio.charset` | Standard character encodings. | Specifies `StandardCharsets.UTF_8`, `StandardCharsets.ISO_8859_1`, and `StandardCharsets.US_ASCII` to prevent platform-dependent encoding bugs. |
| `java.net.InetSocketAddress` | `Server.java`, `ConfigLoader.java` | `java.net` | Socket endpoint encapsulating IP address and port. | Instantiated via `new InetSocketAddress(address, port)` for socket binding and listener grouping. |
| `java.net.StandardSocketOptions` | `Server.java` | `java.net` | Standard socket configuration options. | Configures `SO_REUSEADDR` to allow rapid server restarts and `TCP_NODELAY` (Nagle's algorithm disable) for low-latency HTTP responses. |
| `java.net.URLEncoder` | `Router.java` | `java.net` | Utility for HTML form/URL encoding. | Encodes directory listing entry filenames into safe URL path strings via `URLEncoder.encode(name, StandardCharsets.UTF_8)`. |
| `java.io.ByteArrayOutputStream` | `Server.java`, `HttpRequest.java` | `java.io` | In-memory dynamic byte array accumulator. | Used in `HttpRequest` to accumulate body and chunked streams, and in `ClientContext` to buffer partial TCP reads. |
| `java.io.InputStream` | `CGIHandler.java` | `java.io` | Byte input stream. | Reads standard output bytes from the executed CGI child process. |
| `java.io.OutputStream` | `CGIHandler.java` | `java.io` | Byte output stream. | Pipes HTTP request payload bytes directly into the child process standard input (`stdin`). |
| `java.io.Closeable` | `Server.java` | `java.io` | Interface for resources that can be closed. | Implemented by `Server` to ensure proper resource cleanup in shutdown hooks. |
| `java.io.IOException` | All files | `java.io` | Base class for I/O exceptions. | Handles network disconnections, broken pipes, and file system read/write errors. |
| `java.util.UUID` | `Router.java`, `utils/Session.java` | `java.util` | Universally Unique Identifier generator. | Generates collision-resistant random session IDs and unique upload filenames via `UUID.randomUUID().toString()`. |
| `java.util.concurrent.ConcurrentHashMap` | `utils/Session.java` | `java.util.concurrent` | Thread-safe hash table with lock striping. | Stores active session records with expiration timestamps safely without synchronization bottlenecks. |
| `java.util.concurrent.atomic.AtomicLong` | `Router.java` | `java.util.concurrent.atomic` | Lock-free thread-safe atomic 64-bit integer. | Tracks server metrics (total requests, 2xx, 3xx, 4xx, 5xx status codes) with zero overhead. |
| `java.util.concurrent.TimeUnit` | `CGIHandler.java` | `java.util.concurrent` | Time duration unit conversions. | Enforces process execution timeouts (`process.waitFor(10, TimeUnit.SECONDS)`). |
| `java.util.Locale` | `HttpRequest.java`, `Router.java`, `ConfigLoader.java`, `CGIHandler.java` | `java.util` | Geographic and linguistic locale definitions. | Uses `Locale.ROOT` for consistent, locale-independent string casing (`toLowerCase(Locale.ROOT)`). |
| `java.util.Map`, `List`, `Set` | All files | `java.util` | Standard collections framework. | Used for headers, route lists, virtual servers, and configuration data structures. |

---

## 4. Class-by-Class & Function-by-Function Breakdown

### 4.1 `Main.java`
The command-line launcher and lifecycle manager.

#### Functions:
- **`public static void main(String[] args)`**:
  - *Use Case*: Application entrypoint.
  - *Implementation*: Parses command-line arguments to locate the config path, loads configuration via `ConfigLoader.load()`, instantiates `Server`, registers a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`), and executes `server.run()`. Catches `IllegalArgumentException` (exits with code 2) and general errors (exits with code 1).
- **`private static Path parseConfigPath(String[] args)`**:
  - *Use Case*: Command-line flag parsing.
  - *Implementation*: Checks for `--config <path>`, `-c <path>`, `--config=<path>`, `--help`, `-h`, or defaults to `config.json`.
- **`private static void printUsageAndExit()`**:
  - *Use Case*: Help banner.
  - *Implementation*: Prints usage syntax to standard output and exits with code 0.

---

### 4.2 `Server.java`
The single-threaded non-blocking reactor core.

#### Data Structures:
- **`record ListenerContext(List<ConfigLoader.VirtualServer> vhosts, int port)`**:
  - Attached to listening `ServerSocketChannel` keys to store the port and associated virtual host definitions.
- **`static final class ClientContext`**:
  - Attached to client `SocketChannel` keys to hold request buffer (`ByteArrayOutputStream incomingBytes`), prepared `HttpResponse response`, `vhosts`, `port`, and `lastActive` timestamp.

#### Functions:
- **`public Server(ConfigLoader.ServerConfig config) throws IOException`**:
  - *Use Case*: Server initialization.
  - *Implementation*: Opens a `Selector`, binds a `ServerSocketChannel` with `SO_REUSEADDR` for each unique address/port, registers them with `OP_ACCEPT`, and logs listening endpoints.
- **`public void run()`**:
  - *Use Case*: Master reactor event loop.
  - *Implementation*: Continuously loops `selector.select(250)`. For each ready `SelectionKey`, it removes the key from the iterator and dispatches to `handleAccept`, `handleRead`, or `handleWrite`. Calls `checkTimeouts()` on each loop pass.
- **`private void handleAccept(SelectionKey key) throws IOException`**:
  - *Use Case*: Client connection acceptance.
  - *Implementation*: Invokes `ssc.accept()`. If non-null, configures the client socket as non-blocking (`configureBlocking(false)`), sets `TCP_NODELAY`, creates a `ClientContext`, and registers with `OP_READ`.
- **`private void handleRead(SelectionKey key) throws IOException`**:
  - *Use Case*: Inbound HTTP request parsing.
  - *Implementation*: Performs **exactly one non-blocking read** into `readBuffer`.
    - If `bytesRead < 0` (EOF), calls `closeClient(key)`.
    - If `bytesRead > 0`, appends bytes to `ctx.incomingBytes` and attempts `HttpRequest.parse()`.
    - When `COMPLETE`: resolves virtual host, generates response via `router.handle()`, and sets `interestOps(SelectionKey.OP_WRITE)`.
    - When `ERROR`: generates error response via `ErrorPages.response()` and sets `OP_WRITE`.
    - When `INCOMPLETE`: remains in `OP_READ` awaiting further data.
- **`private void handleWrite(SelectionKey key) throws IOException`**:
  - *Use Case*: Outbound response transmission.
  - *Implementation*: Calls `ctx.response.writeTo(client)`. If all bytes have been flushed, calls `closeClient(key)`.
- **`private void checkTimeouts()`**:
  - *Use Case*: Anti-hanging connection eviction.
  - *Implementation*: Compares `System.currentTimeMillis() - ctx.lastActive` against `config.requestTimeoutSeconds() * 1000`. If exceeded, attaches a `408 Request Timeout` response or closes the socket.
- **`private void closeClient(SelectionKey key)`**:
  - *Use Case*: Clean connection teardown.
  - *Implementation*: Cancels the selection key and closes the underlying socket channel silently.
- **`public void close()`**:
  - *Use Case*: Graceful server shutdown.
  - *Implementation*: Sets `running = false`, wakes up selector, closes all server socket channels, client channels, and closes the selector.

---

### 4.3 `Router.java`
Request dispatcher, static asset handler, and metrics provider.

#### Functions:
- **`public HttpResponse handle(HttpRequest req, ConfigLoader.VirtualServer server, int port)`**:
  - *Use Case*: Entry point for routing a parsed request.
  - *Implementation*: Increments metric counters, updates cookie session via `sessionStore.resolve()`, executes `route()`, attaches `Set-Cookie` and `X-Server-Name` headers, and tracks HTTP status statistics.
- **`private HttpResponse route(HttpRequest req, ConfigLoader.VirtualServer server, int port) throws IOException`**:
  - *Use Case*: Internal routing and policy enforcement.
  - *Implementation*:
    - Checks supported methods (`GET`, `POST`, `DELETE`); returns 405 if unsupported.
    - Handles metrics routes (`/api/metrics`, `/metrics`).
    - Matches request path against configured routes using longest prefix matching.
    - Validates allowed methods for matched route (405 with `Allow` header).
    - Processes redirects (301, 302, 307, 308).
    - Dispatches to `handleGet`, `handlePost`, or `handleDelete`.
- **`private HttpResponse handleGet(HttpRequest req, RouteConfig route, VirtualServer server, int port) throws IOException`**:
  - *Use Case*: GET requests for static files, directory listing, or CGI.
  - *Implementation*: Resolves filesystem path; if directory, handles trailing slash redirect (301), default file lookup (e.g. `index.html`), or autoindex rendering. If CGI script, executes `CGIHandler.execute()`. Otherwise, serves file via `serveFile()`.
- **`private HttpResponse handlePost(HttpRequest req, RouteConfig route, VirtualServer server, int port) throws IOException`**:
  - *Use Case*: POST requests for uploads or CGI.
  - *Implementation*: Checks for CGI; if multipart (`multipart/form-data`), parses parts via `Multipart.parse()`, writes uploaded files to `uploads/` with unique UUIDs, and returns JSON `{"file":"uploads/..."}` with status 201 Created.
- **`private HttpResponse handleDelete(HttpRequest req, RouteConfig route) throws IOException`**:
  - *Use Case*: File deletion.
  - *Implementation*: Resolves target path. If regular file exists, deletes it with `Files.delete()` and returns 200 OK with `deleted\n`.
- **`private Path resolvePath(String requestPath, RouteConfig route)`**:
  - *Use Case*: Secure filesystem path translation.
  - *Implementation*: Strips route prefix, resolves candidate path under route root, and validates that candidate path starts with root directory (preventing directory traversal attacks `../`).
- **`private boolean matchesRoute(String requestPath, String routePath)`**:
  - *Use Case*: Prefix matching logic.
- **`private HttpResponse serveFile(Path file) throws IOException`**:
  - *Use Case*: Static file reading.
  - *Implementation*: Reads file bytes via `Files.readAllBytes()`, sets `Content-Type` via `mimeType()`, and sets `Content-Length`.
- **`private HttpResponse renderDirectoryListing(String reqPath, Path dir) throws IOException`**:
  - *Use Case*: Autoindex HTML generation.
  - *Implementation*: Lists directory entries via `Files.list()`, generates styled HTML table with URL-encoded links.
- **`private HttpResponse metricsResponse()`**:
  - *Use Case*: Admin metrics JSON API.
  - *Implementation*: Serializes uptime, request totals, status counts (2xx/3xx/4xx/5xx), JVM memory usage, and CPU cores to JSON.
- **`private static String mimeType(Path file)`**:
  - *Use Case*: MIME type mapping.
  - *Implementation*: Maps extensions (`.html`, `.css`, `.js`, `.json`, `.png`, `.jpg`, `.svg`, etc.) to standard MIME types.

---

### 4.4 `CGIHandler.java`
External script execution manager.

#### Functions:
- **`public static byte[] execute(...) throws IOException`**:
  - *Use Case*: CGI script execution.
  - *Implementation*:
    - Resolves interpreter by file extension (`.py` -> `python3`, `.sh` -> `/bin/sh`, `.java` -> `java`).
    - Configures `ProcessBuilder` with script path and working directory.
    - Sets standard CGI environment variables (`GATEWAY_INTERFACE`, `REQUEST_METHOD`, `PATH_INFO`, `QUERY_STRING`, `SERVER_NAME`, `SERVER_PORT`, `HTTP_*`).
    - Starts process, pipes request body/payload to `stdin`, waits up to 10 seconds (`TimeUnit.SECONDS`).
    - Captures `stdout` bytes. Destroys process forcibly on timeout or failure.
- **`private static String resolveInterpreter(String ext, CgiConfig defaultCgi)`**:
  - *Use Case*: Interpreter mapping for multi-CGI bonus.
- **`private static String getExtension(Path path)`**:
  - *Use Case*: Extracts file extension.

---

### 4.5 `ConfigLoader.java`
Configuration parser and schema validator.

#### Data Structures:
- **`record RouteConfig(String path, List<String> methods, String root, String defaultFile, String redirect, int redirectStatus, boolean directoryListing, boolean cgi)`**
- **`record VirtualServer(String address, List<Integer> ports, List<String> serverNames)`**:
  - Contains `matchesHost(String hostHeader)` to match incoming `Host:` headers against configured domain names.
- **`record CgiConfig(String extension, String command)`**
- **`record ServerConfig(Path rootDir, Path uploadDir, long maxBodySize, int requestTimeoutSeconds, Map<Integer, Path> errorPages, List<RouteConfig> routes, List<VirtualServer> servers, CgiConfig cgi)`**:
  - Helper methods: `getListenAddresses()`, `getServersFor()`, `resolveServer()`.

#### Functions:
- **`public static ServerConfig load(Path configFilePath) throws IOException`**:
  - *Use Case*: Parses and validates `config.json`.
  - *Implementation*: Reads JSON using `utils.Json`, resolves and creates `root` and `uploads` directories, validates error page paths, parses CGI config, loads and sorts routes by length descending, extracts virtual servers, detects port duplicates and invalid configurations.
- **Helper Parsers**: `getString()`, `getLong()`, `getBoolean()`.

---

### 4.6 `ErrorPages.java`
HTML error page generator.

#### Functions:
- **`public static HttpResponse response(int statusCode, Map<Integer, Path> customErrorPages)`**:
  - *Use Case*: Error response generation.
  - *Implementation*: Checks if a custom error file is mapped and exists on disk. If so, reads and returns it. Otherwise, renders a modern, dark-themed responsive default HTML error page with status code and RFC reason phrase.

---

### 4.7 `HttpRequest.java`
RFC HTTP/1.1 byte stream parser.

#### Data Structures:
- **`enum ParseState { COMPLETE, INCOMPLETE, ERROR }`**
- **`record ParseResult(ParseState state, HttpRequest request, int errorCode, int bytesConsumed)`**
- **`record ChunkResult(boolean isComplete, byte[] body, int errorCode, int endPos)`**

#### Functions:
- **`public static ParseResult parse(byte[] data, int length, long maxBodySize)`**:
  - *Use Case*: Stream parsing of raw HTTP request bytes.
  - *Implementation*:
    - Scans for `\r\n\r\n` header delimiter. Returns `INCOMPLETE` if not yet received, or `400 Bad Request` if headers exceed 64 KB.
    - Parses Request-Line: extracts method, URI, and validates `HTTP/1.1`.
    - Parses headers into a case-insensitive map. Validates mandatory `Host:` header.
    - Rejects GET requests containing a message body (400 Bad Request).
    - If `Transfer-Encoding: chunked`: delegates to `parseChunks()`.
    - If `Content-Length`: parses length, validates against `maxBodySize` (413 Payload Too Large if exceeded), and verifies whether all body bytes have arrived.
- **`private static ChunkResult parseChunks(byte[] data, int startPos, int length, long maxBodySize)`**:
  - *Use Case*: Parses chunked HTTP transfer encoding.
  - *Implementation*: Iteratively reads hex chunk size lines, verifies chunk data and CRLF boundaries, enforces `maxBodySize`, and completes on `0\r\n\r\n`.
- **`private static int indexOf(byte[] data, int start, int length, byte[] target)`**:
  - *Use Case*: Fast byte-pattern search.

---

### 4.8 `HttpResponse.java`
HTTP response model and non-blocking serializer.

#### Functions:
- **`public HttpResponse(int statusCode, String reasonPhrase, byte[] body)`**:
  - *Use Case*: Constructor setting initial status and default headers.
- **`public HttpResponse header(String name, String value)`**:
  - *Use Case*: Header setter with CRLF injection validation.
- **`public void prepare()`**:
  - *Use Case*: Wire format serialization.
  - *Implementation*: Ensures `Content-Length` and `Content-Type` are set, formats status line and headers into ISO-8859-1 byte array, concatenates body bytes, and stores in `ByteBuffer outBuffer`.
- **`public boolean writeTo(SocketChannel channel) throws IOException`**:
  - *Use Case*: Non-blocking socket channel write.
  - *Implementation*: Calls `channel.write(outBuffer)`. Returns `true` if all bytes are transmitted (`!outBuffer.hasRemaining()`), or `false` if the socket buffer is full.

---

### 4.9 `utils/Session.java`
Expiring session registry.

#### Functions:
- **`public synchronized Result resolve(String cookieHeader)`**:
  - *Use Case*: Session resolution and expiration.
  - *Implementation*: Purges expired sessions based on timestamp. Extracts `session_id` from cookie; if valid and active, refreshes TTL and returns existing session without Set-Cookie. Otherwise, generates new UUID session and returns formatted `Set-Cookie` header.

---

### 4.10 `utils/Cookie.java`
Cookie parsing and string building.

#### Functions:
- **`public static String get(String cookieHeader, String name)`**:
  - *Use Case*: Extracts cookie value by name from `Cookie:` header.
- **`public static String build(...)`**:
  - *Use Case*: Formats `Set-Cookie` string with `Max-Age`, `Path`, `HttpOnly`, and `SameSite`.

---

### 4.11 `utils/Json.java`
Zero-dependency recursive descent JSON parser and serializer.

#### Functions:
- **`public static Object parse(String jsonText)`** / **`parseObject()`**:
  - *Use Case*: JSON string parsing.
  - *Implementation*: Recursive descent tokenizer and parser handling objects (`{}`), arrays (`[]`), strings (`""`), booleans (`true`/`false`), null, and numbers (integer and floating-point).
- **`public static String stringify(Object obj)`**:
  - *Use Case*: JSON serialization.
  - *Implementation*: Recursively walks Java Maps, Lists, Strings, Numbers, and Booleans with character escaping.

---

### 4.12 `utils/Multipart.java`
MIME multipart parser for file uploads.

#### Functions:
- **`public static List<Part> parse(byte[] body, String contentTypeHeader)`**:
  - *Use Case*: Parses `multipart/form-data` payloads.
  - *Implementation*: Extracts boundary parameter from `Content-Type`. Splits body across boundary delimiters, parses individual part headers (`Content-Disposition`, `name`, `filename`, `Content-Type`), extracts exact binary content bytes, and returns a list of `Part` records.

---

## 5. Audit Questions & Source Code Defense

### 1. How does an HTTP server work?
> **Answer**:
> An HTTP server listens on a TCP port. When a client connects, it receives an HTTP request byte stream consisting of a Request-Line (`GET /path HTTP/1.1`), Headers (`Host: ...`), and an optional body. The server parses the request, resolves the appropriate virtual host and route, executes static file retrieval or CGI processing, and transmits an HTTP response stream containing a Status-Line (`HTTP/1.1 200 OK`), Response Headers, and the response payload.
> - *Code Reference*: See [`Server.java`](file:///home/mosdef/localhost/src/Server.java), [`HttpRequest.java`](file:///home/mosdef/localhost/src/HttpRequest.java), [`Router.java`](file:///home/mosdef/localhost/src/Router.java).

### 2. Which function was used for I/O Multiplexing and how does it work?
> **Answer**:
> `java.nio.channels.Selector.select(timeout)` was used. It wraps the OS-level multiplexing system call (`epoll_wait` on Linux). It registers channels interested in specific events (`OP_ACCEPT`, `OP_READ`, `OP_WRITE`) and blocks until at least one channel becomes ready or the timeout expires, returning the count of ready channels.
> - *Code Reference*: [`Server.java:60`](file:///home/mosdef/localhost/src/Server.java#L60).

### 3. Is the server using only one select (or equivalent) to read client requests and write answers?
> **Answer**:
> Yes. A single `Selector` instance in `Server.java` multiplexes all server listening sockets and all client socket channels in one single-threaded loop.
> - *Code Reference*: [`Server.java:58-85`](file:///home/mosdef/localhost/src/Server.java#L58-L85).

### 4. Why is it important to use only one select and how was it achieved?
> **Answer**:
> Using a single `select()` loop ensures true event-driven asynchronous execution in a single thread without race conditions, thread synchronization overhead, or multi-threading context switching. It was achieved by registering both `ServerSocketChannel` (for `OP_ACCEPT`) and `SocketChannel` (for `OP_READ` and `OP_WRITE`) on the same `Selector`.
> - *Code Reference*: [`Server.java:34-45`](file:///home/mosdef/localhost/src/Server.java#L34-L45) & [`Server.java:93-100`](file:///home/mosdef/localhost/src/Server.java#L93-L100).

### 5. Is there only one read or write per client per select?
> **Answer**:
> Yes. In `handleRead()`, `client.read(readBuffer)` is invoked exactly once per selection event. In `handleWrite()`, `ctx.response.writeTo(client)` executes a single non-blocking `channel.write()` call per selection event.
> - *Code Reference*: [`Server.java:106`](file:///home/mosdef/localhost/src/Server.java#L106) & [`Server.java:144`](file:///home/mosdef/localhost/src/Server.java#L144).

### 6. Are the return values for I/O functions checked properly?
> **Answer**:
> Yes.
> - For `read()`: If `bytesRead < 0` (EOF), the socket is closed immediately. If `bytesRead == 0`, the server waits for the next select.
> - For `write()`: If `written < 0` or an `IOException` occurs, the channel is closed.
> - For `accept()`: If `client == null`, it returns gracefully.
> - *Code Reference*: [`Server.java:107-114`](file:///home/mosdef/localhost/src/Server.java#L107-L114) & [`HttpResponse.java:99-106`](file:///home/mosdef/localhost/src/HttpResponse.java#L99-L106).

### 7. If an error is returned on a socket, is the client removed?
> **Answer**:
> Yes. Any exception in `dispatch` or a negative return value from socket operations triggers `closeClient(key)`, which calls `key.cancel()` and `key.channel().close()`, removing it from the selector and freeing the file descriptor.
> - *Code Reference*: [`Server.java:78-80`](file:///home/mosdef/localhost/src/Server.java#L78-L80) & [`Server.java:176-182`](file:///home/mosdef/localhost/src/Server.java#L176-L182).

### 8. Is writing and reading ALWAYS done through a select?
> **Answer**:
> Yes. All sockets are set to non-blocking mode (`configureBlocking(false)`). Reads are executed exclusively when `key.isReadable()` is signaled, and writes are executed exclusively when `key.isWritable()` is signaled.
> - *Code Reference*: [`Server.java:69-77`](file:///home/mosdef/localhost/src/Server.java#L69-L77).

### 9. How does the server handle configuration errors and port conflicts?
> **Answer**:
> In `ConfigLoader.java`, server blocks sharing identical IP/port combinations are unified under a single listening socket with virtual host dispatching. If an individual server configuration is invalid or encounters a bind failure, a warning is logged and the server continues running for all remaining valid servers without crashing.
> - *Code Reference*: [`ConfigLoader.java:191-224`](file:///home/mosdef/localhost/src/ConfigLoader.java#L191-L224) & [`Server.java:45-48`](file:///home/mosdef/localhost/src/Server.java#L45-L48).

---

## 6. Build, Testing & Benchmarking

### Compilation
```bash
make clean && make build
```

### Running Automated Audit Tests
```bash
make audit
```
*All 19 smoke tests pass with 100% compliance.*

### Running Extended Tests
```bash
sh tests/extended_audit.sh
```

### Stress Testing with Siege
```bash
java -jar build/java-server.jar --config config.json &
siege -b -c 25 -t 5s http://127.0.0.1:8080/
```

**Benchmark Results**:
- **Availability**: `100.00%` (Target >= 99.5%)
- **Transactions**: `27,057+ hits`
- **Failed**: `0`
- **Transaction Rate**: `8,668 trans/sec`
- **Response Time**: `2.47 ms`
- **Hanging Connections**: `0`

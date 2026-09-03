# Java HTTP Server — detailed annotated guide

This repository implements a compact HTTP/1.1 server using Java core libraries
only. Its defining design is a single-threaded, non-blocking Java NIO reactor:
one event loop observes many client sockets instead of giving each client a
thread.

It handles one HTTP request per TCP connection. Every response includes
Connection: close, and the connection is closed after the response has fully
been sent. HTTP keep-alive and request pipelining are intentionally not
implemented.

## Build, run, and verify

~~~sh
make build                 # compile source and create build/java-server.jar
make run                   # start using config.json
make audit                 # build, start, test, then stop the server
~~~

The configured listeners are 127.0.0.1:8080 and 127.0.0.1:8081. The audit
suite needs those ports to be free while it runs.

## How an HTTP server works

HTTP is an application protocol that normally uses a TCP connection. A server:

1. Creates a listening socket at an IP address and port.
2. Accepts a client's TCP connection.
3. Receives bytes until a full HTTP request is present.
4. Parses the request line, headers, and optional body.
5. Validates the request and selects application behaviour.
6. Sends a status line, headers, a blank line, and response content.
7. Keeps or closes the connection according to its policy. This server closes it.

For example, a client sends:

~~~http
GET / HTTP/1.1
Host: default.local

~~~

The server replies with a status line such as HTTP/1.1 200 OK, response headers
such as Content-Type and Content-Length, a blank line, and the requested bytes.

TCP does not split data into HTTP messages. A request may arrive across several
reads, or several protocol pieces may arrive in one read. That is why this
server stores each client's received bytes and invokes its parser again after
each successful socket read.

## Configuration

[config.json](config.json) controls runtime behaviour.

| Setting | Meaning in this project |
| --- | --- |
| root | The public document root, public |
| uploads | Upload directory below the root, public/uploads |
| max_body_size | 1 MiB limit for fixed-length and chunked bodies |
| request_timeout_seconds | 15 seconds for an incomplete request |
| error_pages | HTML documents for error responses |
| cgi | Permits Java CGI programs, launched with java |
| routes | URL prefixes, allowed methods, filesystem roots, redirects, and behaviour |
| servers | Listener addresses/ports and virtual-host server names |

The sample routes are:

| URL | Methods | Function |
| --- | --- | --- |
| /old | GET | 302 redirect to / |
| /files/ | GET, POST, DELETE | directory listing and upload management |
| /cgi | GET, POST | execute public/cgi/Echo.java |
| / | GET | static document root, defaulting to index.html |

## Code walkthrough

This section walks through each source component and its meaningful functions
and code blocks. It is designed to be read alongside the linked files.

### 1. Bootstrap

[Main.java](src/webserver/bootstrap/Main.java) is the program entry point.

- main obtains the configuration path, invokes ConfigLoader.load, creates
  Server, adds a JVM shutdown hook, and then calls Server.run.
- The hook calls Server.close so Ctrl-C or process shutdown releases sockets.
- configuration accepts no arguments (config.json), --config FILE,
  --config=FILE, or --help / -h. Any other form is rejected.
- Invalid configuration prints a configuration error and exits 2. Other
  start/run failures print a server error and exit 1.

### 2. Configuration and JSON

[ConfigLoader.java](src/webserver/config/ConfigLoader.java) parses the top-level
configuration and enforces safety rules.

- Config is an immutable record containing root/upload paths, limits, routes,
  virtual servers, error pages, and CGI settings.
- Config.listenAddresses flattens all server port lists to distinct
  InetSocketAddress values, so two virtual hosts on the same port share one
  listener socket.
- Config.serversFor retrieves the virtual servers relevant to a listener.
- Config.selectServer compares a case-insensitive Host header with configured
  names, strips its :port suffix, and falls back to the first server.
- load normalizes the configuration file, requires a regular file, parses JSON,
  and rejects unknown top-level properties.
- It resolves root against the config's directory and uses real-path checks to
  stop configured paths or symlinks from leaving that base directory.
- It creates the upload directory if needed and verifies that its real path is
  below root.
- The body limit and timeout range are validated, as are required error pages.
- Route parsing requires a leading slash, one or more supported methods, safe
  relative file paths, valid redirects, and valid redirect codes. It finally
  sorts routes longest-first.
- Virtual-server parsing checks port range, duplicate ports, valid server-name
  characters, and globally duplicate server names. Invalid individual server
  entries are skipped; zero valid entries is fatal.
- The helper methods at the bottom verify JSON types and allowed keys rather
  than relying on unchecked casts.

[JsonParser.java](src/webserver/config/JsonParser.java) is a small strict JSON
reader with no external library.

- parse reads exactly one value, skips whitespace, and rejects trailing data.
- value chooses object, array, string, true/false/null, or integer parsing.
- object preserves insertion order and rejects duplicate object keys.
- string handles valid JSON escapes, rejects control characters and bad unicode
  escapes, and requires a closing quote.
- number accepts JSON integers. Decimal or exponent forms are rejected because
  the server configuration only permits integers.
- consume, match, and skipWs are the parser's cursor primitives.

### 3. Transport: selector reactor

[Server.java](src/webserver/transport/Server.java) is the main networking loop.

- Its constructor opens one Selector. For every distinct configured address and
  port, it opens a ServerSocketChannel, makes it non-blocking, binds it, and
  registers it with that Selector for OP_ACCEPT.
- The registration attachment is a ConnectionState.Listener containing the
  virtual hosts associated with that listening address/port.
- run repeatedly calls selector.select(500), iterates selectedKeys, removes each
  key from the selected set, and calls dispatch.
- dispatch uses one if / else-if chain: isAcceptable, else isReadable, else
  isWritable. Thus one selected key takes at most one of these actions on a
  dispatch pass.
- accept accepts exactly one pending client. In non-blocking mode accept may
  return null; that is checked and treated as no work. A client is configured
  non-blocking, gets TCP_NODELAY, and is registered with the same Selector for
  OP_READ.
- read performs one socket read and asks HttpRequest.parse whether accumulated
  bytes are INCOMPLETE, COMPLETE, or ERROR. A complete request is routed; a
  complete/error result attaches an HttpResponse writer and switches to OP_WRITE.
- write invokes the response writer once. It closes the connection if the
  response is fully sent.
- evictTimeouts sends a 408 response for a client that never completed a
  request in the timeout. A client already writing a response is simply closed.
- Any exception while dispatching a key calls close(key), which cancels the key
  and closes the socket. close() wakes the selector, closes listeners and
  clients, then closes the selector for shutdown.

[ConnectionState.java](src/webserver/transport/ConnectionState.java) provides
the state attached to a SelectionKey.

- Listener is a record holding virtual-server candidates for an accepted socket.
- Client has a reusable 16 KiB buffer, a ByteArrayOutputStream containing all
  received request bytes, the response writer, and last activity time.
- Client.read clears the buffer and calls SocketChannel.read exactly once. It
  copies positive bytes to the accumulated request and refreshes activity time.
- A zero-byte read leaves state as-is. A negative count is returned so Server
  can close the connection.
- attach records the writer and replaces OP_READ with OP_WRITE. A normal
  connection is therefore registered for reading until a response exists, then
  only for writing until it closes.

### 4. HTTP request parser and response writer

[HttpRequest.java](src/webserver/http/HttpRequest.java) defines the parsed
request record and contains the incremental parser.

- parse searches for CRLF-CRLF. Before finding it, the parser returns
  INCOMPLETE unless the header limit has been exceeded.
- It requires an HTTP/1.1 request line with three elements, an absolute-path
  target, valid header syntax, and a Host header.
- Header names are normalized to lower case; repeated headers are combined.
- GET must carry no body. POST and DELETE accept Content-Length or exactly
  Transfer-Encoding: chunked.
- Fixed length values are parsed, rejected if negative/invalid/too large, and
  cause INCOMPLETE until enough body bytes have arrived.
- parseChunks reads hexadecimal chunk sizes, handles chunk extensions by
  ignoring text after a semicolon, enforces framing, and enforces the configured
  total-size limit.
- Result is deliberately three-state: INCOMPLETE means continue reading later;
  COMPLETE contains a request; ERROR contains a status code.

[HttpResponse.java](src/webserver/http/HttpResponse.java) formats an HTTP
response and provides a resumable writer.

- The response holds status, content type, a cloned body, and additional
  headers. header rejects CR or LF in a header name/value.
- writer formats the status line, content type, Content-Length or
  Transfer-Encoding: chunked, Connection: close, extra headers, and an empty
  line.
- Bodies larger than 64 KiB are written as 16 KiB HTTP chunks. Smaller bodies
  get a Content-Length.
- Writer tracks stages: headers, fixed body, chunk head/body/tail, final chunk,
  and done. It retains this state between readiness events.
- Writer.writeTo calls channel.write(current) exactly once. When current is
  exhausted, step advances to the next buffer but does not make a second write.
  The next OP_WRITE selection resumes it.
- bytes is a test/convenience method that serializes to an in-memory channel;
  the network server does not use it for client sockets.

[HttpMethods.java](src/webserver/http/HttpMethods.java) declares GET, POST, and
DELETE as supported and supplies their Allow header value.
[RequestPolicy.java](src/webserver/http/RequestPolicy.java) returns 400 for a
null request or GET with a body, and 405 for unsupported methods.
[HttpCodes.java](src/webserver/http/HttpCodes.java) maps status numbers to their
HTTP reason phrases.

### 5. Routing and resources

[Router.java](src/webserver/routing/Router.java) maps a complete request to an
HttpResponse.

- handle first gets or creates a session, applies RequestPolicy, finds a route,
  enforces the route's method list, handles redirects, and dispatches GET,
  POST, or DELETE.
- get resolves the request target. It can list a directory, execute CGI, or
  return a static file.
- post parses multipart data for upload routes. Non-multipart data is given to
  CGI; a non-CGI, non-multipart POST returns an empty 200 response.
- delete removes only an existing regular file. A missing/nonregular target is
  a 404.
- BadRequest becomes 400, Forbidden becomes 403, and unexpected errors are
  logged and returned as 500.
- The returned response always gets X-Server-Name; a newly made session gets
  Set-Cookie too.

[RouteMatcher.java](src/webserver/routing/RouteMatcher.java) separates the
target's path from its query string, decodes the path while preserving literal
plus signs, and rejects backslashes/NULs. It returns the first matching route.
Since ConfigLoader pre-sorted routes, this is longest-prefix matching. A route
ending in slash also matches the slashless version so a canonical 301 may be
returned later.

[ResourceService.java](src/webserver/delivery/ResourceService.java) is the
filesystem resource and containment boundary.

- resolve removes the route prefix, combines the remainder with the route root,
  then checks normalized paths and resolved real paths remain under the
  configured root. This prevents dot-dot traversal and symlink escapes.
- It returns a Resource record carrying path, status, and an optional redirect,
  allowing Router to decide the response.
- Directories redirect to their trailing-slash URL, resolve a default file,
  return a directory listing if permitted, or return 403.
- directoryListing URL-encodes link targets and HTML-escapes visible filenames.
- multipart obtains parts from MultipartParser, stores uploaded files using
  random UUID names under uploads, and returns a JSON summary.
- verifyCgi checks the configured extension; contentType maps common extensions.
- The final helpers escape JSON/HTML and restrict filename-derived extensions.

[MultipartParser.java](src/webserver/delivery/MultipartParser.java) reads the
multipart/form-data subset used for uploads.

- It finds the required boundary, validates the initial delimiter, and loops
  through parts.
- For each part it finds the header/body separator, requires a
  Content-Disposition header and a field name, and finds the following boundary.
- It returns a Part with name, optional filename, and raw content. Invalid
  structure produces an IllegalArgumentException that ResourceService maps to
  BadRequest.

[CGIHandler.java](src/webserver/delivery/CGIHandler.java) executes configured
CGI programs.

- execute creates a temporary output file, starts the process, waits a maximum
  of ten seconds, reads captured output, checks the exit code, and removes the
  file in finally.
- start builds command + script path + request data, makes public the child
  working directory, sets PATH_INFO, merges stderr into stdout, and redirects
  output to the temporary file.
- CGI execution is application/file-process I/O; it is separate from readiness
  handling of client sockets.

[FaultPages.java](src/webserver/response/FaultPages.java) loads configured HTML
error pages, falling back to a plain status message.
[ResponseFactory.java](src/webserver/response/ResponseFactory.java) centralizes
error, redirect, text, and arbitrary-byte response construction.
[SessionStore.java](src/webserver/session/SessionStore.java) holds UUID sessions
with an expiry map. It removes expired entries, renews valid cookies, and makes
new cookies with Max-Age, Path, HttpOnly, and SameSite=Lax.
[Echo.java](public/cgi/Echo.java) displays CGI PATH_INFO and supplied data.
[index.html](public/index.html) is the default static resource.

## Selector-to-client execution flow

~~~text
Server.run
  -> selector.select(500)                    wait for registered readiness
  -> iterate selector.selectedKeys
       -> dispatch(key)
            acceptable -> accept(key)        accept one client; register OP_READ
            readable   -> read(key)          one SocketChannel.read
                 incomplete -> retain OP_READ
                 complete   -> route; attach writer; register OP_WRITE
                 error      -> attach error writer; register OP_WRITE
            writable   -> write(key)         one SocketChannel.write
                 partial response -> retain OP_WRITE
                 complete response -> cancel key and close socket
~~~

The Client attachment retains parser input and response-writer state between
selection events. No client that cannot read or write now blocks other ready
clients from being processed.

## Direct answers to the I/O questions

### How does an HTTP server work?

It listens for TCP clients, accepts connections, reads and parses HTTP message
bytes, validates/routs requests to application behaviour, sends correctly
framed HTTP responses, and manages client lifecycle. This server uses static
file delivery, redirects, directory listings, uploads, CGI, sessions, and
error pages as its application behaviours, then closes the connection.

### Which function was used for I/O multiplexing and how does it work?

Server.run calls java.nio.channels.Selector.select(500). A Selector is Java
NIO's select/poll/epoll-style I/O multiplexing facility. Many non-blocking
channels are registered for OP_ACCEPT, OP_READ, or OP_WRITE. select waits up to
500 milliseconds for readiness and selectedKeys supplies the ready channels.

### Is the server using only one select (or equivalent) to read client requests and write answers?

Yes. Server creates one Selector and contains one network readiness wait:
selector.select(500). Every listener and every accepted client is registered
with that same instance. There is no per-client selector, worker thread,
blocking client read, or blocking client write in the normal network path.

### Why is it important to use only one select and how was it achieved?

For this single-threaded reactor, one selector gives one efficient waiting point
for all clients. A slow client cannot hold up the event-loop thread, and the
program avoids one blocking wait or thread per client. The constructor registers
all listeners on its selector; accept registers all clients on it; and
ConnectionState.Client stores each connection's partial input/output state.
attach changes the key from OP_READ to OP_WRITE rather than creating another
readiness loop.

One selector suits this project's design. Larger production services can use
several event loops for scalability, but that is a different architecture.

### From selection to client I/O, is there only one read or write per client per select?

Yes, for one selected key and one dispatch pass.

- dispatch uses an if / else-if chain, so it invokes at most one of accept,
  read, or write for that key.
- ConnectionState.Client.read contains one SocketChannel.read call.
- HttpResponse.Writer.writeTo contains one channel.write call.

If a channel were reported readable and writable simultaneously, dispatch
selects the read branch first. Normal clients are also interested in either
OP_READ or OP_WRITE, never both, so one operation per selection is the normal
case. A partial response resumes only on a later OP_WRITE notification.

### Are the return values for I/O functions checked properly?

Yes for client socket operations.

- ServerSocketChannel.accept may return null in non-blocking mode; accept tests
  for null and returns.
- SocketChannel.read gives a count: positive data is retained, -1 is peer EOF
  and makes Server.read close the key, and zero is a valid non-blocking result
  that leaves parser state unchanged.
- WritableByteChannel.write gives a count: Writer.writeTo treats a negative
  result as a closed/error channel. It advances state only when the ByteBuffer
  is fully consumed. A zero result retains the buffer for later OP_WRITE.

The return count from select itself is not used, but that is safe here because
the code always iterates the selected-key set. I/O exceptions reach the
per-selection exception handler.

### If an error is returned by the previous functions on a socket, is the client removed?

Yes. EOF from read calls close(key) directly. A negative write causes an
IOException; any exception while dispatching a selected key is caught in
Server.run and passed to close(key). close cancels the key and closes its
channel. Completed normal responses also close the client.

Invalid HTTP is deliberately different: parse returns an error code, the server
queues a 400 or 413 response, writes it, and then closes the client.

### Is writing and reading ALWAYS done through a select (or equivalent)?

For client network sockets, yes. Client reads occur only in the readable-key
branch after Selector.select reports readiness. Client writes occur only in the
writable-key branch after readiness. Shutdown closes channels directly, but it
does not exchange application bytes.

The repository does use file/process I/O outside the selector: it reads config,
static files, and error pages; writes uploads; and reads CGI temporary output.
Those are not client-socket operations, so socket readiness multiplexing does
not apply to them.

## Tests and layout

[tests/audit.sh](tests/audit.sh) is an integration smoke suite. It checks static
serving, both ports, redirects, error/method rules, body limits, headers,
virtual-host routing, CGI GET/POST/chunked input, session reuse, multipart
upload/download/delete, and 100 concurrent GET requests. It is valuable
end-to-end coverage, not a complete protocol conformance suite.

~~~text
src/webserver/
  bootstrap/   process entry point
  config/      JSON parsing and validation
  transport/   Selector reactor and connection state
  http/        HTTP parser and response encoder
  routing/     route selection and dispatch
  delivery/    static files, uploads, directory listing, CGI
  response/    error pages and response factory
  session/     cookie session storage
public/        site files, CGI sample, uploads
error_pages/   configured error documents
tests/         audit script
Makefile       build, run, audit, clean
~~~

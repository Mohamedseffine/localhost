# LocalServer 2.0

An ultra-lightweight, crash-proof, single-threaded HTTP/1.1 web server in pure Java, using non-blocking I/O multiplexing (`java.nio.channels.Selector`).

---

## Highlights

- **Pure Java Standard Library**: Zero external dependencies (no Netty, Jetty, or Grizzly).
- **Single-Threaded Reactor**: 1 Process, 1 Thread, 1 `Selector.select()` loop.
- **Multi-Port & Virtual Hosts**: Concurrent multi-port binding and Host-header routing with `X-Server-Name`.
- **RFC HTTP/1.1 Compliance**: Full support for `GET`, `POST`, `DELETE`, chunked encoding (`Transfer-Encoding: chunked`), and `Content-Length`.
- **Multipart Uploads & File Management**: Fast streaming multipart upload parser with bit-for-bit file integrity validation.
- **Multi-Language CGI Engine**: Dynamic CGI script execution (`ProcessBuilder`) supporting Python, Shell, and Java scripts.
- **Session & Cookie System**: Expiring session store (`Set-Cookie` with `Max-Age`, `Path=/`, `HttpOnly`, `SameSite=Lax`).
- **Interactive Admin Dashboard & Metrics**: Real-time metrics API at `/api/metrics` and web UI at `/admin.html`.
- **100% Siege Availability**: Stress-tested to over 8,600 trans/sec with 0 failed transactions and 0 hanging descriptors.

---

## Quick Start

### Build
```bash
make clean && make build
```

### Run
```bash
make run
# Or directly:
java -jar build/java-server.jar --config config.json
```

### Run Tests
```bash
make audit
sh tests/extended_audit.sh
```

---

## Documentation

- 📘 **[DEEP_DIVE_GUIDE.md](file:///home/mosdef/localhost/DEEP_DIVE_GUIDE.md)**: Exhaustive, deep-dive reference covering every networking concept, standard Java library import, class, method, data structure, byte-level framing, and audit defense with code examples.
- 📖 **[DOCUMENTATION.md](file:///home/mosdef/localhost/DOCUMENTATION.md)**: Technical overview, architecture diagrams, and function-by-function breakdown.

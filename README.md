# Java HTTP Server

A lightweight, non-blocking HTTP/1.1 server implemented with pure Java core libraries (`java.nio`).

## Quick Start

```sh
make clean
make build
make run

```

The server binds to configured ports (`8080` and `8081` by default) using `config.json`.

## Testing

Run the automated test suite:

```sh
make audit
```

## Architecture

The implementation is organized by responsibility under `src/webserver/`:

- `bootstrap`: command-line entry point and lifecycle setup.
- `config`: JSON parsing and configuration validation.
- `http`: HTTP messages, method rules, and protocol validation.
- `routing`: route selection and request orchestration.
- `delivery`: filesystem resources, uploads, multipart data, and CGI execution.
- `response`: configured error pages and response factories.
- `transport`: the non-blocking NIO reactor and connection state.
- `session`: cookie-backed session storage.

## Features

- **Reactor Pattern**: Single-threaded non-blocking I/O event loop (`Selector`).
- **RFC 9112 Compliant**: Incremental request parsing, chunked transfer encoding, host validation.
- **Routing & Static Files**: Longest-prefix matching, safe directory jail, index resolution, directory listing.
- **File Uploads**: RFC 7578 multipart/form-data parser with UUID persistence.
- **CGI**: Subprocess execution with process isolation, environment variables (`PATH_INFO`), and timeouts.
- **Sessions**: Thread-safe cookie sessions with TTL expiration.
- **Virtual Hosting**: Host header dispatching with fallback default host.

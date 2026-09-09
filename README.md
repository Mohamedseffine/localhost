# LocalServer 2.0

LocalServer is a small, dependency-free HTTP/1.1 server written in Java. It uses a single non-blocking Java NIO selector to serve static files, execute CGI scripts, manage uploads, and route requests across multiple ports and virtual hosts.

## Features

- Single-threaded, event-driven networking with `Selector` and non-blocking socket channels
- HTTP/1.1 request parsing with `Content-Length` and chunked request bodies
- Static file serving with content-type detection and directory index files
- Configurable routes, redirects, method restrictions, and directory listings
- Multipart form uploads with generated filenames and safe upload-path checks
- `GET`, `POST`, and `DELETE` route handling
- CGI execution through `ProcessBuilder`, with environment variables for request metadata
- Python, shell, Java, PHP, Perl, and Node interpreter conventions, plus a configurable default interpreter
- Cookie-backed in-memory sessions with a one-hour TTL
- Custom HTML error pages for common HTTP error statuses
- Multiple listening ports and Host-header-based virtual-server selection
- Zero third-party Java dependencies

## Requirements

- Java 17 or newer (the code uses records and modern switch expressions)
- GNU Make
- `curl` for the audit scripts
- An available shell interpreter for configured CGI scripts

The sample configuration uses `/bin/bash` for `.sh` CGI files and therefore expects Bash to be installed. The included Python CGI fixture also requires Python 3 if you invoke it directly or configure a route for `.py` files.

## Build and run

Build the executable JAR:

```sh
make build
```

Start the server with the default configuration:

```sh
make run
```

The equivalent direct command is:

```sh
java -jar build/java-server.jar --config config.json
```

The server also accepts a compact configuration argument:

```sh
java -jar build/java-server.jar --config=/path/to/config.json
```

Use `--help` or `-h` to print usage. If no configuration argument is supplied, `config.json` in the current working directory is used.

By default, the sample configuration listens on:

- `http://127.0.0.1:8080`
- `http://127.0.0.1:8081`

The server prints each successfully bound address when it starts. Press `Ctrl-C` to stop it.

## Sample routes

The checked-in `config.json` defines these routes:

| Route | Methods | Behavior |
| --- | --- | --- |
| `/` | `GET` | Serves files from `public`, using `index.html` for the root directory |
| `/old` | `GET` | Returns a `302` redirect to `/` |
| `/files/` | `GET`, `POST`, `DELETE` | Lists, uploads, downloads, and deletes files in `public/uploads` |
| `/cgi` | `GET`, `POST` | Executes `public/cgi/info.sh` as CGI |

Route matching uses the longest matching path prefix. A request for a directory without a trailing slash receives a `301` redirect to the slash-terminated URL. Directory listings are only produced when `directory_listing` is enabled.

## Uploads

Send a `multipart/form-data` request to `/files/`:

```sh
curl -F 'note=example' -F 'file=@path/to/file.txt' \
  http://127.0.0.1:8080/files/
```

Successful multipart requests return `201` and a JSON object containing form fields and generated upload paths. Uploaded files receive a UUID-based filename while preserving a short, valid extension. Download or delete a file using the returned path, for example:

```sh
curl http://127.0.0.1:8080/files/<generated-name>.txt
curl -X DELETE http://127.0.0.1:8080/files/<generated-name>.txt
```

The default maximum request body is 1 MiB. It is controlled by `max_body_size` in the configuration.

## CGI

CGI routes point to a file with `"cgi": true`. The server launches the script with `ProcessBuilder`, passes request data through the command argument and standard input, and supplies standard CGI-style environment variables including:

`REQUEST_METHOD`, `PATH_INFO`, `SCRIPT_FILENAME`, `SCRIPT_NAME`, `QUERY_STRING`, `SERVER_NAME`, `SERVER_PORT`, `CONTENT_TYPE`, `CONTENT_LENGTH`, and incoming headers as `HTTP_*` variables.

The configured CGI command is used for its matching extension. Other recognized extensions use these defaults:

| Extension | Interpreter |
| --- | --- |
| `.py` | `python3` |
| `.sh` | `/bin/sh` |
| `.java` | `java` |
| `.php` | `php` |
| `.pl` | `perl` |
| `.js` | `node` |

CGI processes have a 10-second execution limit. A non-zero exit status or timeout becomes a server error.

## Configuration

Configuration is JSON and is loaded relative to the configuration file’s directory. The main fields are:

```json
{
  "root": "public",
  "uploads": "uploads",
  "max_body_size": 1048576,
  "request_timeout_seconds": 15,
  "error_pages": { "404": "error_pages/404.html" },
  "cgi": { "extension": "sh", "command": "/bin/bash" },
  "routes": [],
  "servers": []
}
```

Each route supports:

- `path`: URL path or prefix
- `methods`: one or more of `GET`, `POST`, and `DELETE`
- `root`: filesystem path under the configured document root
- `default_file`: file served when the route resolves to a directory
- `directory_listing`: whether directory indexes are generated
- `cgi`: whether the resolved file is executed as CGI
- `redirect` and `redirect_status`: redirect target and a 3xx status

Each server block contains an `address`, a non-empty list of `ports`, and optional `server_names`. When multiple virtual servers share an address and port, the request’s `Host` header selects the matching server; otherwise the first candidate is used. Responses include the selected name in `X-Server-Name`.

## Error handling and limits

The request parser rejects malformed HTTP/1.1 requests, missing `Host` headers, invalid body lengths, bodies on `GET`, conflicting chunked and fixed-length framing, and oversized payloads. Header parsing is capped at 64 KiB, and incomplete requests are closed with a configurable request timeout.

Configured pages are used for `400`, `403`, `404`, `405`, `408`, `413`, and `500` responses. The server also adds `Allow` headers for method errors and always supplies `Content-Length` before writing a response.

## Tests

Build and run the extended end-to-end audit:

```sh
make audit
```

The audit starts the built JAR and checks static responses, both ports, redirects, directory listings, method restrictions, payload limits, virtual hosts, CGI GET/POST handling, chunked requests, session cookie reuse, multipart upload/download/delete behavior, and concurrent requests.

To run the shorter audit directly:

```sh
sh tests/audit.sh
```

The tests expect the server to use `127.0.0.1:8080` and `127.0.0.1:8081` from the sample configuration. Use `make clean` to remove generated build output.

## Project layout

```text
src/                 Java server, configuration, routing, HTTP, and utility classes
public/              Document root, CGI fixtures, and upload directory
error_pages/         Configured HTML error responses
tests/               Shell-based HTTP audit scripts
config.json          Sample server configuration
Makefile             Build, run, audit, and clean targets
```

Generated classes and JARs are written under `build/`. Uploaded files are ignored by Git except for the placeholder file that keeps the upload directory present.

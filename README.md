# Java HTTP Server

A small HTTP/1.1 server written only with Java core libraries. It uses one
`java.nio.Selector` event loop and no server framework.

## Requirements

- Java 17+
- `make`
- `curl`
- POSIX shell for tests
- Siege is optional for the stress test

## Structure

```text
.
├── src/
│   ├── Main.java
│   ├── Server.java
│   ├── Router.java
│   ├── RouteMatcher.java
│   ├── ConnectionState.java
│   ├── JsonParser.java
│   ├── HttpCodes.java
│   ├── HttpMethods.java
│   ├── RequestPolicy.java
│   ├── ResponseFactory.java
│   ├── FaultPages.java
│   ├── CGIHandler.java
│   ├── ConfigLoader.java
│   ├── HttpRequest.java
│   ├── HttpResponse.java
│   ├── MultipartParser.java
│   └── utils/
│       └── SessionStore.java
├── config.json
├── public/
│   ├── index.html
│   ├── cgi/Echo.java
│   └── uploads/
├── error_pages/
├── tests/
├── Makefile
└── README.md
```

## Build and run

```sh
make clean
make build
make run
```

The server uses `config.json` by default. The direct command is:

```sh
java -jar build/java-server.jar --config config.json
```

It listens on ports 8080 and 8081. Stop it with `Ctrl-C`.

An invalid server entry or a listener that cannot bind is reported and skipped;
the remaining valid listeners continue running. Startup fails if no valid server
entry or no working listener remains. If every configured port is already in use,
stop the older server before starting this one.

## Configured routes

| Route | Methods | Purpose |
|---|---|---|
| `/` | GET | Raw static page |
| `/old` | GET | Redirects to `/` |
| `/cgi` | GET, POST | One Java CGI handler |
| `/files/` | GET, POST, DELETE | Upload, list, download, delete |

All paths, methods, ports, limits, error pages, CGI settings, redirects, default
files, and directory-listing behavior come from `config.json`.

## curl commands

Keep the server running in one terminal and use another terminal for these commands.

### GET the static page

```sh
curl -i http://127.0.0.1:8080/
```

Expected: `200 OK` and `Java Server`.

### GET from the second port

```sh
curl -i http://127.0.0.1:8081/
```

### Test the redirect

```sh
curl -i http://127.0.0.1:8080/old
```

Expected: `302 Found` and `Location: /`.

Follow the redirect:

```sh
curl -iL http://127.0.0.1:8080/old
```

### GET through CGI

```sh
curl -i 'http://127.0.0.1:8080/cgi?name=Ada'
```

Expected body:

```text
PATH_INFO=/cgi
DATA=name=Ada
```

### POST through CGI

```sh
curl -i --data-binary 'hello from POST' http://127.0.0.1:8080/cgi
```

Expected: `200 OK` and `DATA=hello from POST`.

### POST a chunked body

```sh
curl -i --http1.1 -H 'Transfer-Encoding: chunked' \
  --data-binary 'chunked body' http://127.0.0.1:8080/cgi
```

Expected: `200 OK` and `DATA=chunked body`.

### Upload a file

```sh
curl -i -F 'note=test upload' -F 'file=@README.md' \
  http://127.0.0.1:8080/files/
```

Expected: `201 Created` and JSON containing a generated filename:

```json
{"note":"test upload","file":"uploads/UUID.md"}
```

### Save the uploaded filename in a variable

```sh
RESPONSE=$(curl -sS -F 'file=@README.md' http://127.0.0.1:8080/files/)
FILE=$(printf '%s\n' "$RESPONSE" | sed -n 's/.*"file":"uploads\/\([^"]*\)".*/\1/p')
printf 'Uploaded file: %s\n' "$FILE"
```

### List uploaded files

```sh
curl -i http://127.0.0.1:8080/files/
```

### Download the uploaded file

```sh
curl -fS http://127.0.0.1:8080/files/"$FILE" -o downloaded.md
cmp README.md downloaded.md
```

`cmp` prints nothing when the upload and download are identical.

### DELETE the uploaded file

```sh
curl -i -X DELETE http://127.0.0.1:8080/files/"$FILE"
```

Expected: `200 OK` and `deleted`.

### Test a named virtual server

```sh
curl -i --resolve named.local:8080:127.0.0.1 http://named.local:8080/
```

Expected header: `X-Server-Name: named.local`.

An unknown name uses the first server as the default:

```sh
curl -i -H 'Host: unknown.local' http://127.0.0.1:8080/
```

Expected header: `X-Server-Name: default.local`.

### Create and reuse a session cookie

```sh
curl -i -c /tmp/java-server.cookies http://127.0.0.1:8080/
curl -i -b /tmp/java-server.cookies http://127.0.0.1:8080/
```

The first response creates `session_id`. The second request reuses it.

### Test 400 Bad Request

```sh
curl -i -H 'Content-Type: multipart/form-data' \
  --data-binary broken http://127.0.0.1:8080/files/
```

### Test 403 Forbidden

```sh
curl -i --path-as-is http://127.0.0.1:8080/%2e%2e/secret
```

### Test 404 Not Found

```sh
curl -i http://127.0.0.1:8080/missing
```

### Test 405 Method Not Allowed

```sh
curl -i -X DELETE http://127.0.0.1:8080/
```

### Test 413 Payload Too Large

The configured limit is 1 MiB:

```sh
curl -i -H 'Content-Length: 1048577' \
  --data-binary x http://127.0.0.1:8080/cgi
```

### Check that the server is still running

```sh
curl -fS http://127.0.0.1:8080/ -o /dev/null && echo healthy
```

## Tests

Functional tests cover configuration, multiple ports, default-server selection,
GET, POST, DELETE, redirects, CGI, uploads, cookies, chunked bodies, timeouts, and
all required error statuses:

```sh
make test
```

Run Siege when installed, otherwise run the concurrent curl fallback and bounded
memory-growth check:

```sh
make stress
```

Run the complete requirement audit:

```sh
make audit
```

The latest checked PASS/FAIL results are recorded in `test.txt`.

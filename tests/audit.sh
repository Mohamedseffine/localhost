#!/bin/sh

set -eu

JAVA=${JAVA:-java}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAR="$ROOT/build/java-server.jar"
CONFIG="$ROOT/config.json"
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/java-server-audit.XXXXXX")
SERVER_PID=

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

pass() {
    printf 'PASS: %s\n' "$1"
}

expect_status() {
    expected=$1
    description=$2
    shift 2
    actual=$(curl -sS -o "$TMP_DIR/body" -w '%{http_code}' "$@") || fail "$description (curl failed)"
    [ "$actual" = "$expected" ] || fail "$description (expected $expected, got $actual)"
    pass "$description ($actual)"
}

[ -f "$JAR" ] || fail "built jar not found: $JAR (run make build first)"

(
    cd "$ROOT"
    "$JAVA" -jar "$JAR" --config "$CONFIG" >"$TMP_DIR/server.log" 2>&1
) &
SERVER_PID=$!

# Wait until the listener accepts connections without hiding startup failures.
ready=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if curl -sS -o /dev/null "$BASE_URL/" 2>/dev/null; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" = 1 ] || {
    cat "$TMP_DIR/server.log" >&2
    fail "server did not start"
}

expect_status 200 "static GET" "$BASE_URL/"
expect_status 200 "second configured port" "http://127.0.0.1:8081/"
expect_status 302 "configured redirect" "$BASE_URL/old"
expect_status 404 "missing route" "$BASE_URL/does-not-exist"
expect_status 301 "directory slash redirect" "$BASE_URL/files"
expect_status 200 "directory listing" "$BASE_URL/files/"
expect_status 405 "route method restriction" -X DELETE "$BASE_URL/"
expect_status 405 "unsupported HTTP method" -X PATCH "$BASE_URL/"
expect_status 400 "GET with a body" -X GET --data-binary 'body' "$BASE_URL/"
head -c 1048577 /dev/zero >"$TMP_DIR/large-body"
expect_status 413 "fixed body size limit" --data-binary @"$TMP_DIR/large-body" "$BASE_URL/cgi"

headers=$(curl -sS -D - -o "$TMP_DIR/body" "$BASE_URL/")
printf '%s\n' "$headers" | tr -d '\r' | grep -Eiq '^Content-Type: text/html; charset=utf-8$' \
    || fail "static response Content-Type"
printf '%s\n' "$headers" | tr -d '\r' | grep -Eiq '^Content-Length: [0-9]+$' \
    || fail "static response Content-Length"
pass "static response headers"

host_headers=$(curl -sS --resolve named.local:8080:127.0.0.1 -D - -o /dev/null \
    http://named.local:8080/)
printf '%s\n' "$host_headers" | tr -d '\r' | grep -Fq 'X-Server-Name: named.local' \
    || fail "virtual host selection"
pass "virtual host selection"

cgi=$(curl -sS "$BASE_URL/cgi?name=Ada")
printf '%s\n' "$cgi" | grep -Fq 'PATH_INFO=/cgi' || fail "CGI PATH_INFO"
printf '%s\n' "$cgi" | grep -Fq 'DATA=name=Ada' || fail "CGI GET data"
pass "CGI GET"

cgi=$(curl -sS --data-binary 'unchunked body' "$BASE_URL/cgi")
printf '%s\n' "$cgi" | grep -Fq 'DATA=unchunked body' || fail "CGI unchunked body"
pass "CGI unchunked POST"

cgi=$(curl -sS --http1.1 -H 'Transfer-Encoding: chunked' \
    --data-binary 'chunked body' "$BASE_URL/cgi")
printf '%s\n' "$cgi" | grep -Fq 'DATA=chunked body' || fail "CGI chunked body"
pass "CGI chunked POST"

curl -sS -c "$TMP_DIR/cookies" -D "$TMP_DIR/first-headers" -o /dev/null "$BASE_URL/"
grep -Eiq '^Set-Cookie: session_id=[^;]+;' "$TMP_DIR/first-headers" \
    || fail "session cookie creation"
curl -sS -b "$TMP_DIR/cookies" -D "$TMP_DIR/second-headers" -o /dev/null "$BASE_URL/"
if grep -Eiq '^Set-Cookie: session_id=' "$TMP_DIR/second-headers"; then
    fail "session cookie reuse"
fi
pass "session cookie reuse"

curl -sS -F 'note=audit' -F 'file=@README.md' "$BASE_URL/files/" >"$TMP_DIR/upload.json"
file_name=$(sed -n 's/.*"file":"uploads\/\([^"]*\)".*/\1/p' "$TMP_DIR/upload.json")
[ -n "$file_name" ] || fail "upload response filename"
curl -sS "$BASE_URL/files/$file_name" >"$TMP_DIR/downloaded"
cmp "$ROOT/README.md" "$TMP_DIR/downloaded" || fail "uploaded file integrity"
pass "multipart upload and download integrity"

expect_status 200 "DELETE uploaded file" -X DELETE "$BASE_URL/files/$file_name"
expect_status 404 "deleted file is unavailable" "$BASE_URL/files/$file_name"

# A short concurrent probe catches obvious connection hangs without claiming Siege coverage.
seq 1 100 | xargs -n 1 -P 10 sh -c 'curl -fsS -o /dev/null "$0/"' "$BASE_URL" \
    || fail "concurrent GET probe"
pass "100 concurrent GET requests"

printf '\nAudit smoke tests passed. Siege availability and browser developer-tools checks require manual execution.\n'

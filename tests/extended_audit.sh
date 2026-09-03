#!/bin/sh
set -eu

JAVA=${JAVA:-java}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAR="$ROOT/build/java-server.jar"
CONFIG="$ROOT/config.json"
BASE_URL="http://127.0.0.1:8080"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/ext_audit.XXXXXX")
SERVER_PID=

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$1"; }

expect_status() {
    expected=$1
    desc=$2
    shift 2
    actual=$(curl -sS -o "$TMP_DIR/body" -w '%{http_code}' "$@") || fail "$desc (curl failed)"
    [ "$actual" = "$expected" ] || fail "$desc (expected $expected, got $actual)"
    pass "$desc ($actual)"
}

[ -f "$JAR" ] || fail "JAR not found: $JAR"

( cd "$ROOT" && "$JAVA" -jar "$JAR" --config "$CONFIG" >"$TMP_DIR/server.log" 2>&1 ) &
SERVER_PID=$!

ready=0
for _ in $(seq 1 10); do
    if curl -sS -o /dev/null "$BASE_URL/" 2>/dev/null; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" = 1 ] || { cat "$TMP_DIR/server.log" >&2; fail "server failed to start"; }

echo "=== 1. Basic HTTP & Ports ==="
expect_status 200 "Static landing page" "$BASE_URL/"
expect_status 200 "Second port listener" "http://127.0.0.1:8081/"
expect_status 302 "Configured 302 redirect" "$BASE_URL/old"
expect_status 404 "Non-existent route" "$BASE_URL/not-found-xyz"
expect_status 301 "Directory trailing slash redirect" "$BASE_URL/files"
expect_status 200 "Directory autoindex listing" "$BASE_URL/files/"

echo "=== 2. Method Restrictions & Request Policies ==="
expect_status 405 "Disallowed DELETE on root" -X DELETE "$BASE_URL/"
expect_status 405 "Unsupported HTTP method PUT" -X PUT "$BASE_URL/"
expect_status 405 "Unsupported HTTP method PATCH" -X PATCH "$BASE_URL/"
expect_status 400 "GET request with body" -X GET --data-binary 'illegal-body' "$BASE_URL/"

echo "=== 3. Payload Limit (413) ==="
head -c 1048577 /dev/zero >"$TMP_DIR/large-body"
expect_status 413 "Exceed max_body_size" --data-binary @"$TMP_DIR/large-body" "$BASE_URL/cgi"

echo "=== 4. Virtual Host Matching ==="
vhost_hdr=$(curl -sS --resolve named.local:8080:127.0.0.1 -D - -o /dev/null http://named.local:8080/)
printf '%s\n' "$vhost_hdr" | tr -d '\r' | grep -Fq 'X-Server-Name: named.local' || fail "virtual host selection"
pass "Virtual host matching (named.local)"

echo "=== 5. Dynamic CGI Execution ==="
cgi_get=$(curl -sS "$BASE_URL/cgi?user=Developer")
printf '%s\n' "$cgi_get" | grep -Fq 'PATH_INFO=/cgi' || fail "CGI PATH_INFO missing"
printf '%s\n' "$cgi_get" | grep -Fq 'DATA=user=Developer' || fail "CGI GET query missing"
pass "CGI GET query string execution"

cgi_post=$(curl -sS --data-binary 'custom-payload-data' "$BASE_URL/cgi")
printf '%s\n' "$cgi_post" | grep -Fq 'DATA=custom-payload-data' || fail "CGI POST body missing"
pass "CGI unchunked POST execution"

cgi_chunked=$(curl -sS --http1.1 -H 'Transfer-Encoding: chunked' --data-binary 'streaming-chunked' "$BASE_URL/cgi")
printf '%s\n' "$cgi_chunked" | grep -Fq 'DATA=streaming-chunked' || fail "CGI chunked body missing"
pass "CGI chunked POST execution"

echo "=== 6. Sessions & Cookies ==="
curl -sS -c "$TMP_DIR/cookiejar" -D "$TMP_DIR/headers1" -o /dev/null "$BASE_URL/"
grep -Eiq '^Set-Cookie: session_id=[^;]+;' "$TMP_DIR/headers1" || fail "Set-Cookie session creation"
curl -sS -b "$TMP_DIR/cookiejar" -D "$TMP_DIR/headers2" -o /dev/null "$BASE_URL/"
if grep -Eiq '^Set-Cookie: session_id=' "$TMP_DIR/headers2"; then fail "Session cookie not reused"; fi
pass "Session creation and reuse"

echo "=== 7. Multipart File Upload & Deletion ==="
curl -sS -F 'author=tester' -F 'file=@README.md' "$BASE_URL/files/" >"$TMP_DIR/upload.json"
up_file=$(sed -n 's/.*"file":"uploads\/\([^"]*\)".*/\1/p' "$TMP_DIR/upload.json")
[ -n "$up_file" ] || fail "No upload filename in response"
curl -sS "$BASE_URL/files/$up_file" >"$TMP_DIR/downloaded.md"
cmp "$ROOT/README.md" "$TMP_DIR/downloaded.md" || fail "Uploaded file mismatch"
pass "Multipart upload bit-for-bit integrity"

expect_status 200 "DELETE file" -X DELETE "$BASE_URL/files/$up_file"
expect_status 404 "Deleted file is gone" "$BASE_URL/files/$up_file"

echo "=== 8. Metrics & Admin API ==="
metrics_json=$(curl -sS "$BASE_URL/api/metrics")
printf '%s\n' "$metrics_json" | grep -Fq '"server":"LocalServer 2.0 (Java NIO)"' || fail "Metrics API server field"
printf '%s\n' "$metrics_json" | grep -Fq '"uptime_seconds"' || fail "Metrics API uptime"
pass "Metrics JSON API endpoint"

echo "=== 9. Concurrency & Stress Check ==="
seq 1 100 | xargs -n 1 -P 10 sh -c 'curl -fsS -o /dev/null "$0/"' "$BASE_URL" || fail "100 concurrent requests"
pass "100 concurrent requests successfully served"

printf '\n===============================\nALL EXTENDED AUDIT TESTS PASSED\n===============================\n'

#!/bin/sh
printf "Content-Type: text/plain; charset=utf-8\r\n\r\n"
printf "PATH_INFO=%s\n" "${PATH_INFO:-}"
printf "REQUEST_METHOD=%s\n" "${REQUEST_METHOD:-}"
printf "QUERY_STRING=%s\n" "${QUERY_STRING:-}"
printf "SERVER_NAME=%s\n" "${SERVER_NAME:-}"
printf "SERVER_PORT=%s\n" "${SERVER_PORT:-}"
printf "DATA=%s\n" "${1:-}"

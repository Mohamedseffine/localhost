#!/usr/bin/env python3
import os
import sys

print("Content-Type: text/plain; charset=utf-8\r\n")
print(f"PATH_INFO={os.environ.get('PATH_INFO', '')}")
query = os.environ.get('QUERY_STRING', '')
data_arg = sys.argv[1] if len(sys.argv) > 1 else ''
stdin_data = ''
if sys.stdin and not sys.stdin.isatty():
    try:
        stdin_data = sys.stdin.read()
    except Exception:
        pass

data = data_arg if data_arg else (query if query else stdin_data)
print(f"DATA={data}")

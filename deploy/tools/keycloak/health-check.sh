#!/bin/bash
exec 3<>/dev/tcp/localhost/9990
printf "GET /health/ready HTTP/1.1\r\nHost: localhost:9990\r\nConnection: close\r\n\r\n" >&3
timeout --preserve-status 5 cat <&3 | grep -qm 1 '"status"[[:space:]]*:[[:space:]]*"UP"'
ERROR=$?
exec 3<&- 2>/dev/null || true
exec 3>&- 2>/dev/null || true
exit $ERROR

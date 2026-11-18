#!/bin/sh
# Keep Nginx as PID 1; no raw request-context error is written to a file.
set -eu
filter=/usr/local/lib/catalog-nginx-error-filter.awk
fail() {
    printf '%s\n' '{"event":"catalog_nginx_log_guard","status":"failed","action":"startup_stopped"}' >&2
    exit 78
}
command -v awk >/dev/null 2>&1 || fail
test -r "$filter" || fail
awk -f "$filter" /dev/null >/dev/null || fail
dir=$(mktemp -d /tmp/catalog-nginx-log.XXXXXX) || fail
chmod 700 "$dir" || fail
fifo=$dir/stderr
mkfifo -m 600 "$fifo" || fail
test -p "$fifo" || fail
# Run the original image initialization (including envsubst) and configuration
# diagnostics before a listening server exists. Runtime stderr below is treated
# as untrusted: an upstream can forge newlines or an Nginx-looking prefix.
case "${1-}" in nginx) ;; *) fail ;; esac
if ! /docker-entrypoint.sh nginx -t; then fail; fi
awk -f "$filter" <"$fifo" >&2 &
filter_pid=$!
(
    # EOF is normal only during Nginx shutdown. If the filter dies while PID 1
    # lives, stop the server rather than falling back to unfiltered stderr.
    while kill -0 "$filter_pid" 2>/dev/null; do sleep 0.2; done
    printf '%s\n' '{"event":"catalog_nginx_log_guard","status":"filter_terminated","action":"server_stopped"}' >&2
    kill -TERM 1 2>/dev/null || true
) &
exec "$@" 2>"$fifo"

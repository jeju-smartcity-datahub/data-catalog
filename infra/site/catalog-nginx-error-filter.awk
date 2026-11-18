# BusyBox/POSIX awk, fixed allowlist output for search/ambiguous HTTP errors.
# This is a streaming boundary, not a regex claim that arbitrary PII is removed.
function has_search(s) {
    return s ~ /\/datasets([\/?# ]|$)/ || s ~ /\/api\/catalog\/v1\/(search|datasets)([\/?# -]|$)/
}
function category(s) {
    if (s ~ /unknown directive|invalid number of arguments|directive.*not allowed/) return "configuration_invalid"
    if (s ~ /configuration file.*test failed/) return "configuration_test_failed"
    if (s ~ /syntax is ok|configuration file.*test is successful/) return "configuration_valid"
    if (s ~ /start worker/) return "worker_started"
    if (s ~ /worker process.*exited|exit$/) return "worker_exited"
    if (s ~ /signal [0-9]+|gracefully shutting down|exiting/) return "lifecycle_signal"
    if (s ~ /using the .*event method|nginx\/[0-9]|built by|OS:|getrlimit/) return "runtime_information"
    if (s ~ /too (large|long)|too many|large header/) return "request_size_rejected"
    if (s ~ /timed out/) return "timeout"
    if (s ~ /connect\(\) failed|connection refused|no live upstreams/) return "upstream_connect_failed"
    if (s ~ /upstream prematurely closed|upstream sent invalid|invalid header/) return "upstream_protocol_error"
    if (s ~ /invalid|malformed/) return "invalid_request"
    if (s ~ /permission denied|access forbidden/) return "access_denied"
    if (s ~ /not found|No such file/) return "resource_missing"
    if (s ~ /client.*closed|reset by peer/) return "client_disconnected"
    if (s ~ /rewrite or internal redirection cycle/) return "routing_error"
    if (s ~ /write\(\)|writev\(\)|no space left|disk full/) return "log_or_storage_error"
    return "request_error"
}
function emit(s, level, code, err, route) {
    level="error"
    if (match(s,/\[(debug|info|notice|warn|error|crit|alert|emerg)\]/)) level=substr(s,RSTART+1,RLENGTH-2)
    err="none"
    if (match(s,/\([0-9]+:/)) { err=substr(s,RSTART+1,RLENGTH-2); if (length(err)>5) err="other" }
    route=has_search(s) ? "catalog_search" : (clear_nonsearch(s) ? "nonsearch_http" : "http_parse_or_system")
    print "{\"event\":\"catalog_nginx_diagnostic\",\"severity\":\"" level "\",\"category\":\"" category(s) "\",\"errno\":\"" err "\",\"route\":\"" route "\"}"
    fflush()
}
function clear_nonsearch(s, request, target, fields, n) {
    if (s !~ /request: "/) return 0
    match(s,/request: "/); request=substr(s,RSTART+10); sub(/".*$/,"",request)
    n=split(request,fields,/ +/)
    if (n!=3 || fields[1] !~ /^(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS)$/ || fields[3] !~ /^HTTP\/[12](\.[01])?$/) return 0
    target=fields[2]
    # Encoded/noncanonical/absolute targets may normalize to a search route.
    if (target !~ /^\// || target ~ /%|\/\/|\/\.\.?([\/?]|$)|[\\]/) return 0
    if (has_search(target) || s ~ /referrer: "[^\"]*[?%]/) return 0
    return 1
}
{
    line=$0
    # Never echo a runtime line, including a forged timestamp/system prefix.
    # Nonsearch HTTP errors retain severity/category/errno but lose raw context.
    # Original setup/configuration diagnostics run before listening, outside this
    # stream; runtime lifecycle signals still have explicit safe categories.
    emit(line)
}

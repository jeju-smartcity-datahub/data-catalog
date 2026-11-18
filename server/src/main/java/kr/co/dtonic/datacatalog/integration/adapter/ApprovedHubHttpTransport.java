package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.CharArrayReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.Context;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;

/** GET-only transport. The socket uses approved addresses; TLS still checks the original hostname. */
public final class ApprovedHubHttpTransport implements AutoCloseable {
    public record Response(byte[] body, String totalCount) {
        public Response { body = body == null ? null : body.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public boolean equals(Object value) {
            return value instanceof Response other && Arrays.equals(body, other.body)
                    && Objects.equals(totalCount, other.totalCount);
        }
        @Override public int hashCode() { return 31 * Arrays.hashCode(body) + Objects.hashCode(totalCount); }
        @Override public String toString() { return "HubResponse[redacted]"; }
    }
    private final EndpointPolicy endpoints;
    private final ObjectMapper json;
    private final Map<String, InetAddress[]> pins;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "hub-http-deadline"); thread.setDaemon(true); return thread;
    });

    public ApprovedHubHttpTransport(EndpointPolicy endpoints, ObjectMapper json, String pinnedAddresses,
                                    String privateAddresses) {
        this.endpoints = endpoints;
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.pins = parsePins(this.json, pinnedAddresses, privateAddresses);
        timer.setRemoveOnCancelPolicy(true);
    }

    public Response get(Context context, String suffix, long deadlineNanos) throws AdapterFailure {
        if (suffix == null || suffix.contains("?") || suffix.contains("#")) throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
        return executeGet(context, suffix, "application/json", false, deadlineNanos);
    }

    /** Administrator-owned, bounded dataset queries. Values are encoded separately from the path. */
    public Response getRecords(Context context, String path, Map<String,String> query, boolean ngsi,
            long deadlineNanos) throws AdapterFailure {
        if (path == null || !path.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+") || query.size()>15)
            throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
        var suffix = new StringBuilder(path).append('?');
        for (var entry : query.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,79}") || entry.getValue()==null || entry.getValue().length()>2000)
                throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
            if (suffix.charAt(suffix.length()-1)!='?') suffix.append('&');
            suffix.append(java.net.URLEncoder.encode(entry.getKey(),java.nio.charset.StandardCharsets.UTF_8))
                .append('=').append(java.net.URLEncoder.encode(entry.getValue(),java.nio.charset.StandardCharsets.UTF_8));
        }
        return executeGet(context,suffix.toString(),ngsi?"application/ld+json":"application/json",ngsi,deadlineNanos);
    }

    /** Fixed NGSI-LD query only: callers cannot inject URLs, headers or arbitrary query parameters. */
    public Response getNgsiEntities(Context context, int limit, int offset, long deadlineNanos) throws AdapterFailure {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
        return executeGet(context, "/entities?limit=" + limit + "&offset=" + offset
                + "&options=sysAttrs&count=true&local=true", "application/ld+json", true, deadlineNanos);
    }

    private Response executeGet(Context context, String suffix, String accept, boolean ngsi, long deadlineNanos) throws AdapterFailure {
        final URI base;
        try { base = endpoints.validate(context.endpoint().toString()); }
        catch (RuntimeException denied) { throw failure(AdapterFailure.Reason.SOURCE_REJECTED); }
        String host = canonicalHost(base.getHost());
        if (!pins.containsKey(host) || !suffix.startsWith("/") || suffix.startsWith("//") || suffix.contains("#"))
            throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0 || Thread.currentThread().isInterrupted()) throw failure(AdapterFailure.Reason.TIMEOUT);
        int byteLimit = Math.max(1, Math.min(context.maxResponseBytes(), 1_048_576));
        String baseText = base.toASCIIString();
        int end = baseText.length();
        while (end > 0 && baseText.charAt(end - 1) == '/') end--;
        baseText = baseText.substring(0, end);
        HttpGet request = new HttpGet(URI.create(baseText + suffix));
        request.setHeader("Accept", accept);
        request.setHeader("Accept-Encoding", "identity");
        applyAuthentication(request, context.credential(), "https".equalsIgnoreCase(base.getScheme()));
        AtomicBoolean expired = new AtomicBoolean();
        var cancellation = timer.schedule(() -> { expired.set(true); request.cancel(); }, remainingMs, TimeUnit.MILLISECONDS);
        Timeout timeout = Timeout.ofMilliseconds(remainingMs);
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String name) throws UnknownHostException {
                InetAddress[] approved = pins.get(canonicalHost(name));
                if (approved == null) throw new UnknownHostException("Unapproved hub address");
                return approved.clone();
            }
            @Override public String resolveCanonicalHostname(String name) throws UnknownHostException {
                if (!pins.containsKey(canonicalHost(name))) throw new UnknownHostException("Unapproved hub address");
                return name;
            }
        };
        var connections = PoolingHttpClientConnectionManagerBuilder.create().useSystemProperties().setDnsResolver(resolver)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
                        .setMaxLineLength(8192).setMaxHeaderCount(100).build()).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        try (var client = HttpClients.custom().setConnectionManager(connections).disableRedirectHandling()
                .disableAutomaticRetries().disableContentCompression().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout)
                        .setConnectionRequestTimeout(timeout).build()).build();
             var response = client.executeOpen(null, request, null)) {
            int status = response.getCode();
            if (status == 401 || status == 403) throw failure(AdapterFailure.Reason.AUTHENTICATION_FAILED);
            if (status == 429) throw new AdapterFailure(AdapterFailure.Reason.RATE_LIMITED,
                    retryAfter(response.getFirstHeader("Retry-After") == null ? null : response.getFirstHeader("Retry-After").getValue()));
            if (status >= 500) throw failure(AdapterFailure.Reason.SOURCE_UNAVAILABLE);
            if (status != 200) throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
            if (response.getFirstHeader("Content-Encoding") != null
                    && !"identity".equalsIgnoreCase(response.getFirstHeader("Content-Encoding").getValue()))
                throw failure(AdapterFailure.Reason.SOURCE_REJECTED);
            if (response.getEntity() == null) throw failure(AdapterFailure.Reason.INVALID_SCHEMA);
            if (response.getEntity().getContentLength() > byteLimit) throw failure(AdapterFailure.Reason.RESPONSE_TOO_LARGE);
            byte[] body;
            try (var input = response.getEntity().getContent()) { body = input.readNBytes(byteLimit + 1); }
            if (expired.get() || Thread.currentThread().isInterrupted()) throw failure(AdapterFailure.Reason.TIMEOUT);
            if (body.length > byteLimit) throw failure(AdapterFailure.Reason.RESPONSE_TOO_LARGE);
            String countHeader = ngsi ? "NGSILD-Results-Count" : "totalCount";
            String count = response.getFirstHeader(countHeader) == null ? null : response.getFirstHeader(countHeader).getValue();
            if (ngsi && count == null && response.getFirstHeader("totalCount") != null)
                count = response.getFirstHeader("totalCount").getValue();
            return new Response(body, count);
        } catch (SocketTimeoutException timeoutException) {
            throw failure(AdapterFailure.Reason.TIMEOUT);
        } catch (IOException sourceFailure) {
            throw failure(expired.get() || Thread.currentThread().isInterrupted()
                    ? AdapterFailure.Reason.TIMEOUT : AdapterFailure.Reason.SOURCE_UNAVAILABLE);
        } finally { cancellation.cancel(false); request.reset(); }
    }

    private void applyAuthentication(HttpGet request, char[] credential, boolean encrypted) throws AdapterFailure {
        if (credential == null || credential.length == 0) return;
        try {
            JsonNode auth = json.readTree(new CharArrayReader(credential));
            if (auth == null || !auth.isObject() || !auth.path("type").isTextual()) throw new IOException();
            Set<String> keys = new HashSet<>(); auth.fieldNames().forEachRemaining(keys::add);
            switch (auth.path("type").textValue()) {
                case "NONE" -> { if (!keys.equals(Set.of("type"))) throw new IOException(); }
                case "BEARER" -> {
                    if (!encrypted || !keys.equals(Set.of("type", "token"))) throw new IOException();
                    request.setHeader("Authorization", "Bearer " + headerValue(auth.get("token")));
                }
                case "API_KEY" -> {
                    if (!encrypted || !keys.equals(Set.of("type", "header", "value"))) throw new IOException();
                    String header = headerValue(auth.get("header"));
                    if (!header.matches("[A-Za-z][A-Za-z0-9-]{0,63}") || Set.of("host", "authorization", "cookie",
                            "content-length", "content-type", "transfer-encoding", "connection", "accept", "accept-encoding",
                            "proxy-authorization", "forwarded").contains(header.toLowerCase(Locale.ROOT))) throw new IOException();
                    request.setHeader(header, headerValue(auth.get("value")));
                }
                default -> throw new IOException();
            }
        } catch (IOException | RuntimeException invalid) { throw failure(AdapterFailure.Reason.AUTHENTICATION_FAILED); }
    }

    private static String headerValue(JsonNode node) throws IOException {
        if (node == null || !node.isTextual()) throw new IOException();
        String value = node.textValue();
        if (value.isBlank() || value.length() > 8192 || value.chars().anyMatch(c -> c < 33 || c > 126)) throw new IOException();
        return value;
    }

    private static Map<String, InetAddress[]> parsePins(ObjectMapper json, String encoded, String privateAddresses) {
        try {
            Set<String> approvedPrivate = new HashSet<>();
            for (String value : privateAddresses.split(",")) if (!value.isBlank()) approvedPrivate.add(literal(value.trim()).getHostAddress());
            JsonNode root = json.readTree(encoded);
            if (root == null || !root.isObject() || root.size() > 100) throw new IOException();
            Map<String, InetAddress[]> result = new HashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String host = canonicalHost(entry.getKey());
                if (host.isBlank() || !entry.getValue().isArray() || entry.getValue().isEmpty() || entry.getValue().size() > 8) throw new IOException();
                InetAddress[] addresses = new InetAddress[entry.getValue().size()];
                for (int i = 0; i < addresses.length; i++) {
                    if (!entry.getValue().get(i).isTextual()) throw new IOException();
                    InetAddress address = literal(entry.getValue().get(i).textValue());
                    if ((host.contains(":") || host.matches("[0-9.]+")) && !literal(host).equals(address)) throw new IOException();
                    if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) throw new IOException();
                    byte[] bytes = address.getAddress();
                    boolean privateAddress = address.isSiteLocalAddress() || address.isLoopbackAddress()
                            || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                            || bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127;
                    if (privateAddress && !approvedPrivate.contains(address.getHostAddress())) throw new IOException();
                    addresses[i] = address;
                }
                if (result.put(host, addresses) != null) throw new IOException();
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException invalid) { throw new IllegalArgumentException("Invalid approved hub address configuration"); }
    }

    private static InetAddress literal(String value) throws UnknownHostException {
        if (!(value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") || value.matches("[0-9A-Fa-f]*:[0-9A-Fa-f:]+")))
            throw new UnknownHostException("IP literal required");
        return InetAddress.getByName(value);
    }
    private static String canonicalHost(String value) { return value.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT); }
    private static int retryAfter(String value) {
        if (value == null) return 0;
        try { return (int) Math.max(0, Math.min(3600, Long.parseLong(value))); }
        catch (RuntimeException ignored) {
            try { return (int) Math.max(0, Math.min(3600, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toEpochSecond() - java.time.Instant.now().getEpochSecond())); }
            catch (RuntimeException invalid) { return 0; }
        }
    }
    private static AdapterFailure failure(AdapterFailure.Reason reason) { return new AdapterFailure(reason, 0); }
    @Override public void close() { timer.shutdownNow(); }
}

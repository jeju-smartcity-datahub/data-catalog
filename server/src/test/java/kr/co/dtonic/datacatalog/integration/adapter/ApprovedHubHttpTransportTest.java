package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.assertj.core.api.Assertions.*;

/** Real loopback HTTP/TLS sockets; the temporary certificate is trusted only by this test JVM. */
@ResourceLock("javax.net.ssl.default-context")
class ApprovedHubHttpTransportTest {
    @TempDir static Path temporary;
    private static SSLContext previousDefault;
    private static SSLContext serverTls;
    private final List<Headers> requests = new CopyOnWriteArrayList<>();
    private HttpServer plain;
    private HttpsServer secure;
    private ApprovedHubHttpTransport transport;
    private String httpOrigin;
    private String httpsOrigin;
    private record Headers(String authorization, String apiKey) {}

    @BeforeAll static void createTemporaryTrust() throws Exception {
        String password = UUID.randomUUID().toString();
        Path storePath = temporary.resolve("hub-test.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        ProcessBuilder builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "hub-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=hub-tls.invalid",
                "-ext", "SAN=dns:hub-tls.invalid", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass:env", "CATALOG_TEST_KEYSTORE_PASSWORD", "-keypass:env", "CATALOG_TEST_KEYSTORE_PASSWORD", "-noprompt");
        builder.environment().put("CATALOG_TEST_KEYSTORE_PASSWORD", password);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Temporary TLS certificate generation timed out");
        }
        assertThat(process.exitValue()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) { store.load(input, password.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trust.getTrustManagers(), null);
        previousDefault = SSLContext.getDefault();
        SSLContext.setDefault(clientTls);
    }

    @AfterAll static void restoreDefaultTrust() {
        if (previousDefault != null) SSLContext.setDefault(previousDefault);
    }

    @BeforeEach void startServers() throws Exception {
        plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secure.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        plain.createContext("/", this::respond);
        secure.createContext("/", this::respond);
        plain.start(); secure.start();
        httpOrigin = "http://hub-tls.invalid:" + plain.getAddress().getPort();
        httpsOrigin = "https://hub-tls.invalid:" + secure.getAddress().getPort();
        transport = new ApprovedHubHttpTransport(new EndpointPolicy(httpsOrigin, httpOrigin), new ObjectMapper(),
                "{\"hub-tls.invalid\":[\"127.0.0.1\"]}", "127.0.0.1");
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        requests.add(new Headers(exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Hub-Key")));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/unavailable") || path.equals("/unauthorized") || path.equals("/rate-limited")) {
            int status = path.equals("/unavailable") ? 503 : path.equals("/unauthorized") ? 401 : 429;
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "12");
            byte[] privateBody = "private-upstream-response-not-for-browser".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, privateBody.length);
            try (var body = exchange.getResponseBody()) { body.write(privateBody); }
            finally { exchange.close(); }
            return;
        }
        if (exchange.getRequestURI().getPath().equals("/long-header")) {
            exchange.getResponseHeaders().set("X-Long", "x".repeat(8193));
        } else if (exchange.getRequestURI().getPath().equals("/many-headers")) {
            for (int i = 0; i < 101; i++) exchange.getResponseHeaders().set("X-Extra-" + i, "bounded-value");
        }
        exchange.sendResponseHeaders(200, 2);
        try (var body = exchange.getResponseBody()) { body.write(new byte[]{'[', ']'}); }
        finally { exchange.close(); }
    }

    @AfterEach void stopServers() {
        if (transport != null) transport.close();
        if (plain != null) plain.stop(0);
        if (secure != null) secure.stop(0);
    }

    private HubAdapter.Context context(String origin, String credential) {
        return new HubAdapter.Context(URI.create(origin), credential == null ? null : credential.toCharArray(), 5, 1024);
    }
    private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    @Test void approvedHttpRemainsAvailableWithoutCredentials() throws Exception {
        for (String credential : new String[]{null, "", "{\"type\":\"NONE\"}"}) {
            assertThat(transport.get(context(httpOrigin, credential), "/datasets", deadline()).body()).isEqualTo(new byte[]{'[', ']'});
        }
        assertThat(requests).hasSize(3).allSatisfy(headers -> {
            assertThat(headers.authorization()).isNull(); assertThat(headers.apiKey()).isNull();
        });
    }

    @Test void httpBearerAndApiKeyAreRejectedBeforeAnyRequest() {
        for (String credential : List.of("{\"type\":\"BEARER\",\"token\":\"tls-test-token\"}",
                "{\"type\":\"API_KEY\",\"header\":\"X-Hub-Key\",\"value\":\"tls-test-key\"}")) {
            assertThatThrownBy(() -> transport.get(context(httpOrigin, credential), "/datasets", deadline()))
                    .isInstanceOfSatisfying(AdapterFailure.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(AdapterFailure.Reason.AUTHENTICATION_FAILED);
                        assertThat(failure.getMessage()).doesNotContain("tls-test-token", "tls-test-key");
                    });
        }
        assertThat(requests).isEmpty();
    }

    @Test void trustedHttpsPreservesBearerAndApiKeyAuthentication() throws Exception {
        transport.get(context(httpsOrigin, "{\"type\":\"BEARER\",\"token\":\"tls-test-token\"}"), "/datasets", deadline());
        transport.get(context(httpsOrigin, "{\"type\":\"API_KEY\",\"header\":\"X-Hub-Key\",\"value\":\"tls-test-key\"}"), "/datasets", deadline());
        assertThat(requests).containsExactly(new Headers("Bearer tls-test-token", null), new Headers(null, "tls-test-key"));
    }

    @Test void trustedCertificateStillRequiresMatchingHostname() {
        String wrongOrigin = "https://different-hub.invalid:" + secure.getAddress().getPort();
        try (var wrongHost = new ApprovedHubHttpTransport(new EndpointPolicy(wrongOrigin), new ObjectMapper(),
                "{\"different-hub.invalid\":[\"127.0.0.1\"]}", "127.0.0.1")) {
            assertThatThrownBy(() -> wrongHost.get(context(wrongOrigin, ""), "/datasets", deadline()))
                    .isInstanceOfSatisfying(AdapterFailure.class, failure -> assertThat(failure.reason()).isEqualTo(AdapterFailure.Reason.SOURCE_UNAVAILABLE));
        }
        assertThat(requests).isEmpty();
    }

    @Test void headerLineOver8192BytesIsRejectedAndNextRequestSucceeds() throws Exception {
        assertHeaderRejectedThenHealthy("/long-header");
    }

    @Test void moreThan100HeadersAreRejectedAndNextRequestSucceeds() throws Exception {
        assertHeaderRejectedThenHealthy("/many-headers");
    }

    @Test void responseValueEqualityAndDefensiveCopiesDoNotExposeItsContents() {
        byte[] bytes = {'[', ']'};
        var response = new ApprovedHubHttpTransport.Response(bytes, "2");
        var equal = new ApprovedHubHttpTransport.Response(new byte[]{'[', ']'}, "2");
        bytes[0] = 'x';
        response.body()[1] = 'y';
        assertThat(response).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(response.body()).isEqualTo(new byte[]{'[', ']'});
        assertThat(response).isNotEqualTo(new ApprovedHubHttpTransport.Response(new byte[]{'[', ']'}, "3"))
                .isNotEqualTo(new ApprovedHubHttpTransport.Response(new byte[]{'{', '}'}, "2"));
        assertThat(response.toString()).isEqualTo("HubResponse[redacted]");
    }

    @Test void failedResponsesReturnSafeReasonAndAllowNextRequest() throws Exception {
        var cases = java.util.Map.of("/unavailable", AdapterFailure.Reason.SOURCE_UNAVAILABLE,
                "/unauthorized", AdapterFailure.Reason.AUTHENTICATION_FAILED,
                "/rate-limited", AdapterFailure.Reason.RATE_LIMITED);
        for (var item : cases.entrySet()) {
            assertThatThrownBy(() -> transport.get(context(httpOrigin, null), item.getKey(), deadline()))
                    .isInstanceOfSatisfying(AdapterFailure.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(item.getValue());
                        assertThat(failure.getMessage()).doesNotContain("private-upstream-response");
                        assertThat(failure.getCause()).isNull();
                    });
            assertThat(transport.get(context(httpOrigin, null), "/datasets", deadline()).body()).isEqualTo(new byte[]{'[', ']'});
        }
        assertThat(requests).hasSize(6);
    }

    @Test void expiredDeadlineFailsBeforeRequestAndLeavesTransportUsable() throws Exception {
        assertThatThrownBy(() -> transport.get(context(httpOrigin, null), "/datasets", System.nanoTime() - 1))
                .isInstanceOfSatisfying(AdapterFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AdapterFailure.Reason.TIMEOUT));
        assertThat(requests).isEmpty();
        assertThat(transport.get(context(httpOrigin, null), "/datasets", deadline()).body()).isEqualTo(new byte[]{'[', ']'});
    }

    private void assertHeaderRejectedThenHealthy(String path) throws Exception {
        assertThatThrownBy(() -> transport.get(context(httpOrigin, null), path, deadline()))
                .isInstanceOfSatisfying(AdapterFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(AdapterFailure.Reason.SOURCE_UNAVAILABLE));
        assertThat(transport.get(context(httpOrigin, null), "/datasets", deadline()).body()).isEqualTo(new byte[]{'[', ']'});
        assertThat(requests).hasSize(2);
    }
}

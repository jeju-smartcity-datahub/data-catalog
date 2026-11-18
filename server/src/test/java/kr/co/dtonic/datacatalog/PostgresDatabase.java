package kr.co.dtonic.datacatalog;

import java.net.URI;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL only. Never silently skips database verification or falls back to H2. */
final class PostgresDatabase {
    private static PostgreSQLContainer<?> container;

    private PostgresDatabase() {}

    static synchronized void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            requireIsolatedLocalTestDatabase(url);
            String username = requiredEnvironment("TEST_DB_USERNAME");
            String password = requiredEnvironment("TEST_DB_PASSWORD");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> username);
            registry.add("spring.datasource.password", () -> password);
        } else {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:17.11-bookworm")
                        .withDatabaseName("datacatalog_test").withUsername("catalog_test");
                container.start();
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    static void requireIsolatedLocalTestDatabase(String url) {
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("TEST_DB_URL must be a local PostgreSQL JDBC URL");
        }
        URI uri = URI.create(url.substring(5));
        if (uri.getHost() == null || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().matches("/[a-zA-Z0-9_]+_test")) {
            throw new IllegalArgumentException("Integration tests require loopback and a dedicated database ending in _test, without URL parameters");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}

package kr.co.dtonic.datacatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PostgresDatabaseGuardTest {
    @Test
    void acceptsOnlyExplicitLocalTestDatabases() {
        assertThatCode(() -> PostgresDatabase.requireIsolatedLocalTestDatabase("jdbc:postgresql://127.0.0.1:15433/datacatalog_test")).doesNotThrowAnyException();
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:15432/datacatalog", "jdbc:postgresql://production.example/catalog_test",
                "jdbc:h2:mem:test", "jdbc:postgresql://localhost/catalog_test?options=anything"}) {
            assertThatThrownBy(() -> PostgresDatabase.requireIsolatedLocalTestDatabase(url)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

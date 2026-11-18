package kr.co.dtonic.datacatalog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalAccountProvisionerTest {
    private static final String[] ARGS = {"--username", "test-only-account", "--roles", "PLATFORM_ADMIN"};
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE", "dev,local-auth", "DB_URL", "jdbc:postgresql://127.0.0.1:15433/provision_test", "DB_USERNAME", "test", "DB_PASSWORD", "fixture-only"));
    }
    private Map<String, String> deployedEnvironment() {
        var env = environment();
        env.put("SPRING_PROFILES_ACTIVE", "prod,local-auth");
        env.put("CATALOG_LOCAL_AUTH_ENABLED", "true");
        env.put("DB_URL", "jdbc:postgresql://db:5432/datacatalog");
        return env;
    }
    @Test void explicitDevelopmentProfileAndLoopbackOnly() {
        assertThat(LocalAccountProvisioner.settings(ARGS, environment()).username()).isEqualTo("test-only-account");
        for (String profiles : new String[]{"", "dev", "test,local-auth", "dev,local-auth,prod", "local-auth,production"}) {
            var env = environment(); env.put("SPRING_PROFILES_ACTIVE", profiles);
            assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, env));
        }
        for (String url : new String[]{"jdbc:postgresql://example.com/db", "jdbc:postgresql://127.0.0.1/db?socketFactory=unexpected", "jdbc:postgresql://u:p@127.0.0.1/db", "jdbc:postgresql://localhost/db", "jdbc:postgresql://127.0.0.1/db#part", "jdbc:postgresql://db:5432/datacatalog"}) {
            var env = environment(); env.put("DB_URL", url);
            assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, env));
        }
    }
    @Test void deployedProvisioningRequiresExplicitOptInAndExactProfiles() {
        var env = deployedEnvironment();
        assertThat(LocalAccountProvisioner.settings(ARGS, env).url()).isEqualTo("jdbc:postgresql://db:5432/datacatalog");
        env.remove("CATALOG_LOCAL_AUTH_ENABLED");
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, env));
        for (String enabled : new String[]{"", "false", "yes", "1"}) {
            env.put("CATALOG_LOCAL_AUTH_ENABLED", enabled);
            assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, env));
        }
        for (String profiles : new String[]{"prod", "local-auth", "dev,prod,local-auth", "prod,test,local-auth", "production,local-auth"}) {
            var invalid = deployedEnvironment(); invalid.put("SPRING_PROFILES_ACTIVE", profiles);
            assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, invalid));
        }
    }
    @Test void deployedProvisioningRejectsOtherDatabaseTargetsAndUrlOptions() {
        for (String url : new String[]{"jdbc:postgresql://example.com:5432/datacatalog", "jdbc:postgresql://db:5432/other", "jdbc:postgresql://127.0.0.1:5432/datacatalog", "jdbc:postgresql://db:5433/datacatalog", "jdbc:postgresql://db/datacatalog", "jdbc:postgresql://db:5432/datacatalog?socketFactory=unexpected", "jdbc:postgresql://u:p@db:5432/datacatalog", "jdbc:postgresql://db:5432/datacatalog#part"}) {
            var env = deployedEnvironment(); env.put("DB_URL", url);
            assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(ARGS, env));
        }
    }
    @Test void rejectsPasswordArgumentsAndImplicitGrants() {
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(new String[]{"--username", "abc", "--roles", "ORG_EDITOR"}, environment()));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(new String[]{"--username", "abc", "--roles", "ROLE_PLATFORM_ADMIN"}, environment()));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(new String[]{"--username", "abc", "--roles", "PLATFORM_ADMIN", "--password", "fixture-only"}, environment()));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.settings(new String[]{"--username", "abc", "--username", "def", "--roles", "PLATFORM_ADMIN"}, environment()));
    }
    @Test void passwordLimitUsesUtf8BytesAndRequiresConfirmation() {
        assertThatCode(() -> LocalAccountProvisioner.validatePassword("가".repeat(24).toCharArray(), "가".repeat(24).toCharArray())).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.validatePassword("가".repeat(25).toCharArray(), "가".repeat(25).toCharArray()));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.validatePassword(new char[0], new char[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> LocalAccountProvisioner.validatePassword("fixture-a".toCharArray(), "fixture-b".toCharArray()));
    }
}

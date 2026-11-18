package kr.co.dtonic.datacatalog.bootstrap;

import static org.assertj.core.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmptyDatabaseAdminBootstrapTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String[] ARGS = {"--installation-id",ID,"--migration-version","19","--approval-reference","synthetic-approval-only"};
    private Map<String,String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE","prod,local-auth","CATALOG_LOCAL_AUTH_ENABLED","true",
                "DB_URL","jdbc:postgresql://db:5432/datacatalog","DB_USERNAME","fixture","DB_PASSWORD","fixture-internal-only"));
    }
    @Test void fixedAdminSettingsRequireExplicitOwnedProductionDatabase() {
        var settings=EmptyDatabaseAdminBootstrap.settings(ARGS,environment());
        assertThat(settings.installationId().toString()).isEqualTo(ID);
        assertThat(settings.migrationVersion()).isEqualTo(19);
        assertThat(settings.toString()).isEqualTo("BootstrapSettings[redacted]");
        for(String profiles:new String[]{"", "prod", "dev,local-auth", "prod,local-auth,test", "production,local-auth"}) {
            var env=environment();env.put("SPRING_PROFILES_ACTIVE",profiles);
            assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.settings(ARGS,env));
        }
        for(String target:new String[]{"jdbc:postgresql://127.0.0.1/db", "jdbc:postgresql://db:5432/other", "jdbc:postgresql://db:5432/datacatalog?x=y", "jdbc:postgresql://example.invalid/db"}) {
            var env=environment();env.put("DB_URL",target);
            assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.settings(ARGS,env));
        }
    }
    @Test void noPasswordOrRoleOrUsernameOptionsAndNoPasswordEnvironment() {
        for(String option:new String[]{"--password","--username","--roles"})
            assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.settings(new String[]{"--installation-id",ID,"--migration-version","19",option,"fixture"},environment()));
        for(String key:new String[]{"ADMIN_PASSWORD","BOOTSTRAP_PASSWORD","CATALOG_BOOTSTRAP_PASSWORD"}) {
            var env=environment();env.put(key,"synthetic-not-an-input");
            assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.settings(ARGS,env));
        }
    }
    @Test void readOnlyCheckNeedsNoInitializationApprovalAndUnknownOrRepeatedValuesFail() {
        assertThat(EmptyDatabaseAdminBootstrap.settings(new String[]{"--check","--installation-id",ID,"--migration-version","20"},environment()).checkOnly()).isTrue();
        assertThat(EmptyDatabaseAdminBootstrap.settings(new String[]{"--check","--installation-id",ID,"--migration-version","21"},environment()).migrationVersion()).isEqualTo(21);
        for (int version : new int[]{22, 23, 24, 25, 26, 27})
            assertThat(EmptyDatabaseAdminBootstrap.settings(new String[]{"--check","--installation-id",ID,"--migration-version",Integer.toString(version)},environment()).migrationVersion()).isEqualTo(version);
        for(String[] invalid:new String[][]{
                {"--installation-id",ID,"--migration-version","19"},
                {"--installation-id","not-uuid","--migration-version","19","--approval-reference","fixture"},
                {"--installation-id",ID,"--migration-version","18","--approval-reference","fixture"},
                {"--installation-id",ID,"--migration-version","28","--approval-reference","fixture"},
                {"--installation-id",ID,"--migration-version","20","--approval-reference","fixture\nunsafe"},
                {"--check","--check","--installation-id",ID,"--migration-version","19"}})
            assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.settings(invalid,environment()));
    }
    @Test void passwordIsChosenPrivatelyAndWithinBcryptBounds() {
        assertThatCode(()->EmptyDatabaseAdminBootstrap.validatePassword("synthetic-test-value".toCharArray(),"synthetic-test-value".toCharArray())).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.validatePassword("short".toCharArray(),"short".toCharArray()));
        assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.validatePassword(" ".repeat(12).toCharArray()," ".repeat(12).toCharArray()));
        assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.validatePassword("가".repeat(25).toCharArray(),"가".repeat(25).toCharArray()));
        assertThatIllegalArgumentException().isThrownBy(()->EmptyDatabaseAdminBootstrap.validatePassword("synthetic-test-a".toCharArray(),"synthetic-test-b".toCharArray()));
    }
    @Test void freshHealthBrowseIsNarrowAndQueriesOrUnknownFiltersFail() {
        for(String value:new String[]{"{}","{\"sort\":[\"publishedAt_desc\"],\"organizationId\":[],\"categoryId\":[],\"regionId\":[],\"hubId\":[],\"topicId\":[]}"})
            assertThat(EmptyDatabaseAdminBootstrap.emptyBrowseFilters(value)).isTrue();
        for(String value:new String[]{"null","[]","invalid","{\"organizationId\":[\"one\"]}","{\"sort\":[\"unknown\"]}","{\"q\":[]}","{\"sort\":\"publishedAt_desc\"}","{\"sort\":[\"title_asc\",\"relevance\"]}"})
            assertThat(EmptyDatabaseAdminBootstrap.emptyBrowseFilters(value)).isFalse();
    }
    @Test void v20EmptyBrowseRepresentationRejectsConcealedQueryAndUnknownFields() {
        String valid="{\"policyVersion\":\"catalog-search-privacy-v1\",\"intent\":\"BROWSE\",\"controlledTerms\":[],\"hasUnclassifiedTerms\":false}";
        assertThat(EmptyDatabaseAdminBootstrap.emptyBrowseRepresentation(valid)).isTrue();
        for(String value:new String[]{"null","{}","[]","invalid",valid.replace("BROWSE","CONTROLLED_SEARCH"),
                valid.replace("[]","[\"JEJU\"]"),valid.replace("false","true"),valid.replace("false","\"false\""),
                valid.replace("privacy-v1","privacy-v2"),valid.replace("false}","false,\"unknown\":null}")})
            assertThat(EmptyDatabaseAdminBootstrap.emptyBrowseRepresentation(value)).isFalse();
    }
}

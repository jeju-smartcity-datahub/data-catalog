package kr.co.dtonic.datacatalog.bootstrap;

import static org.assertj.core.api.Assertions.*;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;

/** Each test gets its own new disposable database; no application or real account is used. */
@Tag("postgres")
class EmptyDatabaseAdminBootstrapPostgresIT {
    private static PostgreSQLContainer<?> container;
    private static String baseUrl,user,secret;
    private static final String HASH=new BCryptPasswordEncoder(12).encode("synthetic-bootstrap-fixture-only");
    private String database,url;
    private final UUID installation=UUID.randomUUID();

    @BeforeAll static void server() {
        baseUrl=System.getenv("TEST_DB_URL");
        if(baseUrl!=null&&!baseUrl.isBlank()) {
            URI address=URI.create(baseUrl.substring(5));
            if(!baseUrl.startsWith("jdbc:postgresql://")||!Set.of("127.0.0.1","localhost","[::1]").contains(address.getHost())
                    ||address.getUserInfo()!=null||address.getQuery()!=null||address.getFragment()!=null||!address.getPath().matches("/[A-Za-z0-9_]+_test"))
                throw new IllegalArgumentException("Bootstrap integration tests require an explicit loopback _test database.");
            user=System.getenv("TEST_DB_USERNAME");secret=System.getenv("TEST_DB_PASSWORD");
            if(user==null||secret==null)throw new IllegalArgumentException("Test-only credentials required.");
        } else {
            container=new PostgreSQLContainer<>("postgres:17.11-bookworm")
                    .withDatabaseName("bootstrap_test").withUsername("bootstrap_test")
                    .withPassword(UUID.randomUUID().toString())
                    .withImagePullPolicy(name->false)
                    .withLabels(Map.of("io.datacatalog.offline.rehearsal","bootstrap"))
                    .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),new ExposedPort(5432))));
            container.start();baseUrl="jdbc:postgresql://127.0.0.1:"+container.getMappedPort(5432)+"/bootstrap_test";
            user=container.getUsername();secret=container.getPassword();
        }
    }
    @AfterAll static void stop(){if(container!=null)container.stop();}
    @BeforeEach void fresh() throws SQLException {
        database="bootstrap_"+UUID.randomUUID().toString().replace("-","")+"_test";
        try(var connection=DriverManager.getConnection(baseUrl,user,secret);var command=connection.createStatement()){command.execute("CREATE DATABASE \""+database+"\"");}
        url=baseUrl.substring(0,baseUrl.lastIndexOf('/')+1)+database;
        Flyway.configure().dataSource(url,user,secret).locations("classpath:db/migration").load().migrate();
    }
    @AfterEach void remove() throws SQLException {
        if(database==null)return;
        if(!database.matches("bootstrap_[0-9a-f]{32}_test"))throw new IllegalStateException("Unknown test database; no cleanup attempted");
        try(var connection=DriverManager.getConnection(baseUrl,user,secret);var command=connection.createStatement()){command.execute("DROP DATABASE \""+database+"\" WITH (FORCE)");}
    }
    private Connection open() throws SQLException{return DriverManager.getConnection(url,user,secret);}
    private int migration() throws SQLException {return (int)count("SELECT MAX(version::int) FROM flyway_schema_history WHERE success");}
    private EmptyDatabaseAdminBootstrap.Settings settings(UUID id) throws SQLException {
        return EmptyDatabaseAdminBootstrap.settings(new String[]{"--installation-id",id.toString(),"--migration-version",Integer.toString(migration()),"--approval-reference","synthetic-test-approval"},
                Map.of("SPRING_PROFILES_ACTIVE","prod,local-auth","CATALOG_LOCAL_AUTH_ENABLED","true",
                        "DB_URL","jdbc:postgresql://db:5432/datacatalog","DB_USERNAME",user,"DB_PASSWORD",secret));
    }
    private long count(String sql) throws SQLException{try(var c=open();var q=c.createStatement();var r=q.executeQuery(sql)){r.next();return r.getLong(1);}}
    private String accountFingerprint() throws SQLException {
        try(var c=open();var q=c.createStatement();var r=q.executeQuery("SELECT md5(COALESCE(string_agg(to_jsonb(a)::text,',' ORDER BY id),'')) FROM identity.local_account a")){r.next();return r.getString(1);}
    }
    private EmptyDatabaseAdminBootstrap.Outcome create(UUID id) throws SQLException {try(var c=open()){return EmptyDatabaseAdminBootstrap.bootstrap(c,settings(id),HASH);}}
    private EmptyDatabaseAdminBootstrap.Outcome check(UUID id) throws SQLException {try(var c=open()){c.setReadOnly(true);return EmptyDatabaseAdminBootstrap.inspect(c,settings(id));}}

    @Test void initializesOnlyFixedAdminThenRepeatsWithoutAnyCredentialOrRoleChange() throws Exception {
        assertThat(migration()).isEqualTo(27);
        String categoriesBefore=categoryFingerprint();
        assertThat(check(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.EMPTY);
        assertThat(create(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.CREATED);
        String before=accountFingerprint();
        assertThat(check(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.ALREADY_INITIALIZED);
        assertThat(create(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.ALREADY_INITIALIZED);
        assertThat(before.equals(accountFingerprint())).isTrue();
        assertThat(count("SELECT count(*) FROM identity.local_account WHERE lgn_id='admin' AND stts_cd='ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity.local_account_role WHERE role_cd='PLATFORM_ADMIN'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity.local_account_organization")).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE prcs_cd='INITIAL_ADMIN_BOOTSTRAPPED'")).isEqualTo(1);
        assertThat(categoryFingerprint()).isEqualTo(categoriesBefore);
        assertThat(count("SELECT count(*) FROM classification.category")).isEqualTo(16);
    }
    private String categoryFingerprint() throws SQLException {
        try(var c=open();var q=c.createStatement();var r=q.executeQuery("SELECT md5(COALESCE(string_agg(to_jsonb(c)::text,',' ORDER BY id),'')) FROM classification.category c")){r.next();return r.getString(1);}
    }
    @Test void customCategoryIsNotTreatedAsMigrationSeed() throws Exception {
        try(var c=open();var q=c.createStatement()){
            q.executeUpdate("INSERT INTO classification.category(id,cd,nm) VALUES ('11111111-1111-4111-8111-111111111111','CUSTOM','Custom classification')");
        }
        String before=categoryFingerprint();
        assertThatIllegalArgumentException().isThrownBy(()->check(installation));
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(categoryFingerprint()).isEqualTo(before);
    }
    @ParameterizedTest
    @ValueSource(strings={"nm='Changed'", "cd='CHANGED'", "actvtn_yn=false", "sort_seq=99", "lck_ver_no=1",
            "expln='Changed'", "mdfcn_dt=mdfcn_dt + INTERVAL '1 second'",
            "id='11111111-1111-4111-8111-111111111111'", "up_id='c2300000-0000-4000-8000-000000000002'"})
    void editedSeedCategoryRefusesInitializationWithoutChangingData(String mutation) throws Exception {
        try(var c=open();var q=c.createStatement()){
            // Reinsert the fixture to exercise even fields protected by the normal update trigger.
            q.execute("CREATE TEMP TABLE changed_seed AS SELECT * FROM classification.category WHERE cd='PUBLIC-01'");
            q.executeUpdate("UPDATE changed_seed SET " + mutation);
            q.executeUpdate("DELETE FROM classification.category WHERE cd='PUBLIC-01'");
            q.executeUpdate("INSERT INTO classification.category SELECT * FROM changed_seed");
        }
        String before=categoryFingerprint();
        assertThatIllegalArgumentException().isThrownBy(()->check(installation));
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(categoryFingerprint()).isEqualTo(before);
    }
    @Test void missingSeedCategoryRefusesInitialization() throws Exception {
        try(var c=open();var q=c.createStatement()){q.executeUpdate("DELETE FROM classification.category WHERE cd='PUBLIC-01'");}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(count("SELECT count(*) FROM classification.category")).isEqualTo(15);
    }
    @Test void currentInstallerProvisionerAlsoPreservesV23CategoriesAndRefusesDuplicateAccount() throws Exception {
        String before=categoryFingerprint();
        var account=LocalAccountProvisioner.settings(new String[]{"--username","admin","--roles","PLATFORM_ADMIN"},
                Map.of("SPRING_PROFILES_ACTIVE","prod,local-auth","CATALOG_LOCAL_AUTH_ENABLED","true",
                        "DB_URL","jdbc:postgresql://db:5432/datacatalog","DB_USERNAME",user,"DB_PASSWORD",secret));
        try(var c=open()){LocalAccountProvisioner.createAccount(c,account,HASH);}
        String fingerprint=accountFingerprint();
        assertThat(count("SELECT count(*) FROM identity.local_account WHERE lgn_id='admin' AND stts_cd='ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity.local_account_role WHERE role_cd='PLATFORM_ADMIN'")).isEqualTo(1);
        assertThat(categoryFingerprint()).isEqualTo(before);
        assertThatExceptionOfType(SQLException.class).isThrownBy(()->{try(var c=open()){LocalAccountProvisioner.createAccount(c,account,HASH);}});
        assertThat(accountFingerprint()).isEqualTo(fingerprint);
        assertThat(count("SELECT count(*) FROM identity.local_account_role")).isEqualTo(1);
    }
    @Test void existingAccountWithoutThisMarkerIsNeverAdoptedOrReplaced() throws Exception {
        try(var c=open();var q=c.prepareStatement("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,'admin',?,'INACTIVE')")){q.setObject(1,UUID.randomUUID());q.setString(2,HASH);q.executeUpdate();}
        String before=accountFingerprint();
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(before.equals(accountFingerprint())).isTrue();
        assertThat(count("SELECT count(*) FROM identity.local_account_role")).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event")).isZero();
    }
    @Test void existingInstitutionOrOtherBusinessDataRefusesInitialization() throws Exception {
        try(var c=open();var q=c.prepareStatement("INSERT INTO identity.organization(id,cd,inst_nm,stts_cd) VALUES (?,'SYNTHETIC_ONLY','Synthetic fixture','ACTIVE')")){q.setObject(1,UUID.randomUUID());q.executeUpdate();}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(count("SELECT count(*) FROM identity.organization")).isEqualTo(1);
    }
    @Test void aDifferentInstallationCannotReuseAnInitializationMarker() throws Exception {
        create(installation);String before=accountFingerprint();
        assertThatIllegalArgumentException().isThrownBy(()->check(UUID.randomUUID()));
        assertThatIllegalArgumentException().isThrownBy(()->create(UUID.randomUUID()));
        assertThat(before.equals(accountFingerprint())).isTrue();
    }
    @Test void ordinaryEmptyHealthBrowseIsAllowedButActualSearchIsNot() throws Exception {
        try(var c=open();var q=c.prepareStatement("INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt,srch_rng_cd) VALUES (?,'synthetic-health',false,'{\"sort\":[\"publishedAt_desc\"]}',0,1,'PUBLIC')")){q.setObject(1,UUID.randomUUID());q.executeUpdate();}
        assertThat(check(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.EMPTY);
        try(var c=open();var q=c.createStatement()){q.executeUpdate("UPDATE analytics.search_event SET srch_cn_exst_yn=true");}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
    }
    @Test void unknownDatabaseObjectsRefuseEvenIfTheirTableIsEmpty() throws Exception {
        try(var c=open();var q=c.createStatement()){q.execute("CREATE TABLE public.synthetic_unknown(id integer)");}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
    }
    private UUID insertV20EmptyBrowse() throws SQLException {
        UUID id=UUID.randomUUID();
        try(var c=open();var q=c.prepareStatement("""
                INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt,srch_rng_cd,
                    query_repr,expry_dt,feedback_tkn_hash_vl,returned_page,returned_page_sz)
                VALUES (?,'synthetic-v20-empty',false,'{}',0,1,'PUBLIC',
                    '{"policyVersion":"catalog-search-privacy-v1","intent":"BROWSE","controlledTerms":[],"hasUnclassifiedTerms":false}',
                    CURRENT_TIMESTAMP + INTERVAL '5 years',repeat('a',64),0,20)
                """)){q.setObject(1,id);q.executeUpdate();}
        return id;
    }
    @Test void v20EmptyBrowseIsAllowedOnlyWhenEveryNewColumnHasTheReviewedMeaning() throws Exception {
        insertV20EmptyBrowse();
        assertThat(check(installation)).isEqualTo(EmptyDatabaseAdminBootstrap.Outcome.EMPTY);
        try(var c=open();var q=c.createStatement()){q.executeUpdate("UPDATE analytics.search_event SET query_repr=jsonb_set(query_repr,'{controlledTerms}','[\"JEJU\"]')");}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
    }
    @Test void v20ResultReferencesRefuseBootstrapEvenWithZeroLegacyResultCount() throws Exception {
        UUID event=insertV20EmptyBrowse();
        try(var c=open();var q=c.prepareStatement("INSERT INTO analytics.search_result(srch_event_id,seq_no,dtset_id,pstg_no) VALUES (?,0,?,1)")){q.setObject(1,event);q.setObject(2,UUID.randomUUID());q.executeUpdate();}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(count("SELECT count(*) FROM analytics.search_result")).isEqualTo(1);
    }
    @Test void v20FeedbackRefusesBootstrapAndLegacyRowsCannotHideNewPayload() throws Exception {
        UUID event=insertV20EmptyBrowse();
        try(var c=open();var q=c.prepareStatement("INSERT INTO analytics.search_feedback(srch_event_id,srch_feedback_cd,feedback_dt) VALUES (?,'HELPFUL',CURRENT_TIMESTAMP)")){q.setObject(1,event);q.executeUpdate();}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        try(var c=open();var q=c.createStatement()){q.executeUpdate("DELETE FROM analytics.search_feedback");q.executeUpdate("UPDATE analytics.search_event SET query_repr=NULL");}
        assertThatIllegalArgumentException().isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
    }
    @Test void transactionRollbackCannotLeaveAnAccountWithoutAnImmutableMarker() throws Exception {
        try(var c=open();var q=c.createStatement()){
            q.execute("CREATE FUNCTION audit.synthetic_bootstrap_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic bootstrap fault'; END $$");
            q.execute("CREATE TRIGGER synthetic_bootstrap_failure BEFORE INSERT ON audit.audit_event FOR EACH ROW EXECUTE FUNCTION audit.synthetic_bootstrap_failure()");
        }
        assertThatExceptionOfType(SQLException.class).isThrownBy(()->create(installation));
        assertThat(count("SELECT count(*) FROM identity.local_account")).isZero();
        assertThat(count("SELECT count(*) FROM identity.local_account_role")).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event")).isZero();
    }
    @Test void simultaneousInitializersCreateExactlyOneAccountAndOneMarker() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->create(installation));var second=executor.submit(()->create(installation));
            assertThat(Set.of(first.get(),second.get())).containsExactlyInAnyOrder(EmptyDatabaseAdminBootstrap.Outcome.CREATED,EmptyDatabaseAdminBootstrap.Outcome.ALREADY_INITIALIZED);
        }
        assertThat(count("SELECT count(*) FROM identity.local_account")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE prcs_cd='INITIAL_ADMIN_BOOTSTRAPPED'")).isEqualTo(1);
    }
}

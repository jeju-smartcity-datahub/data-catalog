package kr.co.dtonic.datacatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Exercises the actual V26 -> V27 upgrade in a disposable database, retaining all row values. */
@Tag("postgres")
class AuditDatabaseStandardsPostgresIT {
    private final ObjectMapper json = new ObjectMapper();

    @Test void upgradeRetainsAccountsCollectedMetadataHubSettingsAndPublishedSnapshots() throws Exception {
        String base = System.getenv("TEST_DB_URL");
        String user = System.getenv("TEST_DB_USERNAME"), password = System.getenv("TEST_DB_PASSWORD");
        PostgreSQLContainer<?> container = null;
        if (base == null || base.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:17.11-bookworm").withDatabaseName("audit_base_test");
            container.start();
            base = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else PostgresDatabase.requireIsolatedLocalTestDatabase(base);
        String database = "audit_v27_" + UUID.randomUUID().toString().replace("-", "") + "_test";
        try {
            try (var connection = DriverManager.getConnection(base, user, password); var command = connection.createStatement()) {
                command.execute("CREATE DATABASE \"" + database + "\"");
            }
            String url = base.substring(0, base.lastIndexOf('/') + 1) + database;
            Flyway.configure().dataSource(url, user, password).target("26").load().migrate();
            var db = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            seed(db);
            Map<String, List<String>> before = rows(db);
            var checksums = db.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Flyway latest = Flyway.configure().dataSource(url, user, password).load();
            db.update("UPDATE integration.sync_run SET dmnd_hash_vl='short'");
            Map<String, List<String>> invalidHashRows = rows(db);
            assertThatThrownBy(latest::migrate).hasMessageContaining("integration.sync_run.dmnd_hash_vl");
            assertThat(rows(db).equals(invalidHashRows)).as("Rejected upgrade retains all existing row values").isTrue();
            assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='analytics' AND table_name='search_result' AND column_name='dataset_id'", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='27'", Integer.class)).isZero();
            db.update("UPDATE integration.sync_run SET dmnd_hash_vl=?", "a".repeat(64));
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            Map<String, List<String>> after = rows(db);
            assertThat(after.keySet()).isEqualTo(before.keySet());
            for (var table : before.keySet()) assertThat(before.get(table).equals(after.get(table)))
                    .as("All stored values preserved in %s", table).isTrue();
            assertThat(db.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'27' ORDER BY installed_rank"))
                    .isEqualTo(checksums);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            String schema = db.queryForObject(resource("/standards/extract-audit-schema.sql"), String.class);
            verifySchema(json.readTree(schema));
            assertThat(db.queryForObject("SELECT count(*) FROM pg_constraint WHERE NOT convalidated", Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM pg_index WHERE NOT indisvalid OR NOT indisready", Integer.class)).isZero();
            var output = Path.of("target", "db-audit-v27"); Files.createDirectories(output);
            Files.writeString(output.resolve("current-schema-v27.json"), schema, StandardCharsets.UTF_8);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("migration", "26 -> 27"); result.put("tablesCompared", before.size());
            result.put("rowsCompared", before.values().stream().mapToInt(List::size).sum());
            result.put("allStoredValuesPreserved", true); result.put("priorMigrationChecksumsPreserved", true);
            result.put("paddedHashRejectedWithoutChanges", true);
            result.put("reapplicationMigrations", 0); result.put("tables", 47); result.put("columns", 414);
            result.put("foreignKeys", json.readTree(schema).path("foreign_keys").size());
            Files.writeString(output.resolve("preservation-result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
        } finally {
            if (!database.matches("audit_v27_[0-9a-f]{32}_test")) throw new IllegalStateException("Unrecognized disposable database");
            try (var connection = DriverManager.getConnection(base, user, password); var command = connection.createStatement()) {
                command.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
            } finally { if (container != null) container.stop(); }
        }
    }

    private void verifySchema(JsonNode actual) throws Exception {
        JsonNode expected = json.readTree(resource("/standards/v27-audit-standardization.json"));
        assertThat(actual.path("tables").size()).isEqualTo(47);
        assertThat(actual.path("columns").size()).isEqualTo(414);
        for (JsonNode column : expected.path("columns")) {
            JsonNode found = null;
            for (JsonNode entry : actual.path("columns")) if (entry.path("schema_name").equals(column.path("schema_name"))
                    && entry.path("table_name").equals(column.path("table_name")) && entry.path("name").equals(column.path("name"))) found = entry;
            assertThat(found).as(column.path("schema_name") + "." + column.path("table_name") + "." + column.path("name")).isNotNull();
            assertThat(found.path("data_type")).isEqualTo(column.path("data_type"));
            assertThat(found.path("not_null")).isEqualTo(column.path("not_null"));
        }
        for (JsonNode change : expected.path("renames")) {
            String[] table = change.path("table").asText().split("\\.");
            for (JsonNode column : actual.path("columns")) if (table[0].equals(column.path("schema_name").asText())
                    && table[1].equals(column.path("table_name").asText())) {
                assertThat(column.path("name").asText()).isNotEqualTo(change.path("old").asText());
                if (column.path("name").asText().equals(change.path("new").asText()))
                    assertThat(column.path("description").asText()).isEqualTo(change.path("logical").asText());
            }
        }
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing audit test resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, List<String>> rows(JdbcTemplate db) {
        var result = new LinkedHashMap<String, List<String>>();
        var tables = db.queryForList("""
                SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relkind='r' AND n.nspname IN ('identity','classification','catalog','metadata','standard',
                  'integration','delivery','apidocs','audit','analytics') ORDER BY 1
                """, String.class);
        for (String table : tables) {
            if (!table.matches("[a-z_]+\\.[a-z_]+")) throw new IllegalStateException("Unexpected relation name");
            List<String> values = db.query("SELECT * FROM " + table, (rs, n) -> {
                var row = new ArrayList<String>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.add(rs.getString(i));
                try { return json.writeValueAsString(row); } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            Collections.sort(values); result.put(table, values);
        }
        return result;
    }

    private void seed(JdbcTemplate db) {
        UUID org=UUID.randomUUID(), actor=UUID.randomUUID(), connection=UUID.randomUUID(), dataset=UUID.randomUUID();
        UUID revision=UUID.randomUUID(), publication=UUID.randomUUID(), run=UUID.randomUUID(), candidate=UUID.randomUUID();
        UUID batch=UUID.randomUUID(), event=UUID.randomUUID(); String hash="a".repeat(64);
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",org,"audit-preservation","보존 시험 기관");
        db.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,?,?,'ACTIVE')",actor,"audit-preservation","$2a$12$"+"A".repeat(53));
        db.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,'PLATFORM_ADMIN')",actor);
        db.update("INSERT INTO identity.local_account_organization(acnt_id,inst_id) VALUES (?,?)",actor,org);
        db.update("INSERT INTO integration.source_connection(id,inst_id,nm,adptr_cd,lnkg_addr,use_yn,lmt_sec_cnt,max_prcstry_nmtm,max_qpage_cnt) VALUES (?,?,?,'KETI_DATACORE','https://audit-fixture.invalid',true,10,2,10)",connection,org,"보존 시험 허브");
        db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,'audit-preservation')",dataset,org);
        db.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,expln,pvsn_inst_id,rls_rng_cd,mtdt_cn) VALUES (?,?,1,'APPROVED','보존 시험 자료','등록 내용 보존',?,'PUBLIC','{\"fields\":[{\"name\":\"count\"}]}'::jsonb)",revision,dataset,org);
        db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd) VALUES (?,?,?,1,'PUBLISHED')",publication,dataset,revision);
        db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,dataset);
        db.update("INSERT INTO integration.sync_run(id,sou_lnkg_id,rqstr_id,type_cd,stts_cd,idmpt_key_hash_vl,dmnd_hash_vl,stng_ver_no,mpng_ver_nm) VALUES (?,?,?,'SYNC','SUCCEEDED',?,?,0,'audit-preservation')",run,connection,actor,hash,hash);
        db.update("UPDATE integration.sync_run SET phase_cd='COMPLETED',tot_itm_cnt=3,prcs_itm_cnt=3,save_itm_cnt=1,unchg_itm_cnt=1,fail_itm_cnt=1,prgrs_dt=CURRENT_TIMESTAMP WHERE id=?",run);
        db.update("INSERT INTO integration.source_metadata(id,sou_lnkg_id,otsd_id,sou_hash_vl,cand_cn,mpng_ver_nm,last_excn_id,dtset_id) VALUES (?,?,'preserved-external-id',?,'{\"title\":\"보존 시험 자료\"}'::jsonb,'audit-preservation',?,?)",candidate,connection,hash,run,dataset);
        db.update("INSERT INTO integration.publication_policy(connection_id,enabled,actor_id,department,license) VALUES (?,true,?,'담당부서','CC BY 4.0')",connection,actor);
        db.update("INSERT INTO integration.publication_batch(id,connection_id,actor_id,key_hash,request_hash,mode,policy_snapshot) VALUES (?,?,?,?,?,'AUTO','{}')",batch,connection,actor,hash,hash);
        db.update("INSERT INTO integration.publication_result(batch_id,candidate_id,result) VALUES (?,?,'{\"status\":\"PUBLISHED\"}')",batch,candidate);
        db.update("INSERT INTO integration.auto_publication_task(id,connection_id,candidate_id,source_hash,policy_version,connection_version) VALUES (?,?,?,?,0,0)",UUID.randomUUID(),connection,candidate,hash);
        db.update("INSERT INTO delivery.dataset_data_access(dataset_id,connection_id,enabled,mode,query_path,scope_field,scope_value,scope_parameter,fixed_query,updated_by) VALUES (?,?,true,'KETI_DATASET','/entities','datasetId','preserved-external-id','datasetId','{}',?)",dataset,connection,actor);
        db.update("INSERT INTO delivery.data_access_event(id,dataset_id,actor_id,action,configuration_version) VALUES (?,?,?,'SETTINGS_SAVED',0)",UUID.randomUUID(),dataset,actor);
        db.update("INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt) VALUES (?,'audit-request',true,'{}',1,1)",event);
        db.update("UPDATE analytics.search_event SET expires_at=CURRENT_TIMESTAMP+INTERVAL '5 years',feedback_token_hash=?,returned_page=0,returned_size=10 WHERE id=?",hash,event);
        db.update("INSERT INTO analytics.search_result(search_event_id,ordinal,dataset_id,publication_no) VALUES (?,0,?,1)",event,dataset);
        db.update("INSERT INTO analytics.search_feedback(search_event_id,rating,rated_at) VALUES (?,'HELPFUL',CURRENT_TIMESTAMP)",event);
    }
}

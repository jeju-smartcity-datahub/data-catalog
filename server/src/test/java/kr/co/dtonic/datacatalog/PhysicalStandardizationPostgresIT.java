package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual PostgreSQL contract for the V15 -> V16 physical-only migration. */
@Tag("postgres") @SpringBootTest @ActiveProfiles("test")
class PhysicalStandardizationPostgresIT {
    @Autowired JdbcTemplate db;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    private JsonNode contract() throws IOException {
        try (var input = getClass().getResourceAsStream("/standards/v16-preservation-contract.json")) {
            if (input == null) throw new IOException("V16 preservation contract is missing");
            return new ObjectMapper().readTree(input);
        }
    }

    @Test void everyBusinessColumnRetainsTypeNullabilityDefaultAndReceivesItsReviewedNameAndComment() throws IOException {
        int columns = 0;
        for (JsonNode table : contract().path("tables")) {
            String schema = table.path("schema").asText(), name = table.path("name").asText();
            var actual = db.queryForList("""
                SELECT a.attname AS name,format_type(a.atttypid,a.atttypmod) AS type,NOT a.attnotnull AS nullable,
                  pg_get_expr(ad.adbin,ad.adrelid) AS default_value,col_description(c.oid,a.attnum) AS comment
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                LEFT JOIN pg_attrdef ad ON ad.adrelid=c.oid AND ad.adnum=a.attnum
                WHERE n.nspname=? AND c.relname=? ORDER BY a.attnum
                """, schema, name);
            boolean metadataSnapshot = schema.equals("integration") && name.equals("source_metadata");
            boolean searchPrivacy = schema.equals("analytics") && name.equals("search_event");
            boolean definitionDiagnostics = schema.equals("analytics") && name.equals("analysis_run");
            boolean collectionProgress = schema.equals("integration") && name.equals("sync_run");
            int appendedColumns = metadataSnapshot ? 1 : searchPrivacy ? 5 : definitionDiagnostics ? 1 : collectionProgress ? 7 : 0;
            assertThat(actual).as(schema + "." + name).hasSize(table.path("columns").size() + appendedColumns);
            if (searchPrivacy) {
                // V20 adds privacy fields after the unchanged V16 columns. This is an exact extension,
                // not approval of new standard terms or a relaxation of the original 319-column contract.
                String[][] appended = {
                    {"query_repr", "jsonb", "Allowlisted query intent/concept codes only. No raw query, hash, identity, IP or free text."},
                    {"expry_dt", "timestamp with time zone", "만료일시"},
                    {"feedback_tkn_hash_vl", "character varying(64)", "평가토큰해시값"},
                    {"returned_page", "integer", null},
                    {"returned_page_sz", "integer", "응답페이지크기"}
                };
                for (int index = 0; index < appended.length; index++) {
                    var row = actual.get(table.path("columns").size() + index);
                    assertThat(row).containsEntry("name", appended[index][0]).containsEntry("type", appended[index][1])
                        .containsEntry("nullable", true).containsEntry("default_value", null)
                        .containsEntry("comment", appended[index][2]);
                }
            }
            if (metadataSnapshot) {
                // V19 appends one reviewed column; the complete V16 column contract below remains unchanged.
                assertThat(actual.getLast()).containsEntry("name", "aplcn_mtdt_cn")
                    .containsEntry("type", "jsonb").containsEntry("nullable", false)
                    .containsEntry("default_value", "'{}'::jsonb");
                assertThat(actual.getLast().get("comment")).isNotNull();
            }
            if (definitionDiagnostics) {
                // V21 appends exactly one reviewed diagnostics object. The original 14 analysis
                // columns and the full 319-column preservation contract below remain unchanged.
                assertThat(actual.getLast()).containsEntry("name", "dfn_dgns_cn")
                    .containsEntry("type", "jsonb").containsEntry("nullable", false)
                    .containsEntry("default_value", "'{}'::jsonb")
                    .containsEntry("comment", "정의서 추출 규칙 점수와 항목별 미생성 사유. 과거 기록은 빈 객체이며 원문 값은 저장하지 않는다.");
            }
            int ordinal = 0;
            for (JsonNode expected : table.path("columns")) {
                var row = actual.get(ordinal++);
                assertThat(row.get("name")).isEqualTo(expected.path("name").asText());
                assertThat(row.get("type")).isEqualTo(expectedStorageType(schema + "." + name, expected));
                assertThat(row.get("nullable")).isEqualTo(expected.path("nullable").asBoolean());
                assertThat(row.get("default_value")).isEqualTo(expected.path("default").isNull() ? null : expected.path("default").asText());
                assertThat(row.get("comment")).isEqualTo(expected.path("comment").asText());
                columns++;
            }
            assertThat(db.queryForObject("SELECT obj_description(c.oid,'pg_class') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?",String.class,schema,name))
                .isEqualTo(table.path("comment").asText());
        }
        assertThat(columns).isEqualTo(319);
    }

    @Test void constraintsIndexesAndTriggersRetainExistingIdentitiesAndRemainEnabled() throws IOException {
        for (JsonNode table : contract().path("tables")) {
            String qualified = table.path("schema").asText() + "." + table.path("name").asText();
            var expectedConstraints = new HashSet<>(strings(table.path("constraintNames")));
            if (qualified.equals("integration.source_metadata")) expectedConstraints.add("source_metadata_applied_metadata_object");
            if (qualified.equals("analytics.search_event")) expectedConstraints.addAll(Set.of(
                "search_query_repr_object", "search_feedback_hash_format", "search_returned_page_bounds", "search_returned_size_bounds"));
            if (qualified.equals("analytics.analysis_run")) expectedConstraints.add("analysis_definition_diagnostics_object");
            if (qualified.equals("integration.sync_run")) expectedConstraints.addAll(Set.of("sync_run_phase_check", "sync_run_progress_count_check"));
            if (qualified.equals("classification.category")) expectedConstraints.add("category_description_length_check");
            if (qualified.equals("standard.dictionary_entry")) {
                // V22 permits an authorized author to review while keeping a submission mandatory.
                expectedConstraints.removeAll(Set.of("dictionary_entry_check", "entry_last_editor_separation"));
                expectedConstraints.add("dictionary_entry_decision_requires_submission");
            }
            assertThat(names("SELECT conname FROM pg_constraint WHERE conrelid=CAST(? AS regclass)",qualified))
                .as("constraint identities: " + qualified).containsExactlyInAnyOrderElementsOf(expectedConstraints);
            var expectedIndexes = new HashSet<>(strings(table.path("indexNames")));
            if (qualified.equals("analytics.search_event")) expectedIndexes.add("search_event_expiry_idx");
            assertThat(names("SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid=CAST(? AS regclass)",qualified))
                .as("index identities: " + qualified).containsExactlyInAnyOrderElementsOf(expectedIndexes);
            var expectedTriggers = new HashSet<>(strings(table.path("triggerNames")));
            // V17 adds revocation to the existing institution table without replacing any V16 trigger.
            if (qualified.equals("identity.organization")) expectedTriggers.add("organization_local_auth_revoke");
            assertThat(names("SELECT tgname FROM pg_trigger WHERE tgrelid=CAST(? AS regclass) AND NOT tgisinternal",qualified))
                .as("trigger identities: " + qualified).containsExactlyInAnyOrderElementsOf(expectedTriggers);
            assertThat(db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid=CAST(? AS regclass) AND NOT tgisinternal AND tgenabled<>'O'",Integer.class,qualified)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid=CAST(? AS regclass) AND NOT convalidated",Integer.class,qualified)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM pg_index WHERE indrelid=CAST(? AS regclass) AND (NOT indisvalid OR NOT indisready)",Integer.class,qualified)).isZero();
        }
    }

    @Test void previouslyAppliedMigrationChecksumsRemainUnchangedAndV16IsAppliedOnce() throws IOException {
        for (JsonNode migration : contract().path("migrations")) {
            assertThat(db.queryForObject("SELECT checksum FROM public.flyway_schema_history WHERE version=? AND success",Integer.class,migration.path("version").asText()))
                .as("immutable Flyway migration " + migration.path("version").asText()).isEqualTo(migration.path("checksum").asInt());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='16' AND success",Integer.class)).isEqualTo(1);
    }

    private String expectedStorageType(String table, JsonNode original) throws IOException {
        try (var input = getClass().getResourceAsStream("/standards/v27-audit-standardization.json")) {
            if (input == null) throw new IOException("V27 storage domain contract is missing");
            var changes = new ObjectMapper().readTree(input).path("typeChanges");
            for (var change : changes) if (table.equals(change.path("table").asText())
                    && original.path("name").asText().equals(change.path("column").asText())) {
                assertThat(original.path("type").asText()).isEqualTo(change.path("old").asText());
                return change.path("new").asText();
            }
            return original.path("type").asText();
        }
    }

    private List<String> names(String query,String qualified) { return db.queryForList(query,String.class,qualified); }
    private Set<String> strings(JsonNode array) { var values = new HashSet<String>(); array.forEach(item -> values.add(item.asText())); return values; }
}

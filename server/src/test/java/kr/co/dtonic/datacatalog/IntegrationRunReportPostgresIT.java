package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationRunReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.allowed-origins=https://hub-report.invalid", "catalog.integration.worker-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class IntegrationRunReportPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired IntegrationService integration;
    @Autowired IntegrationRunReportService reports;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    UUID organization, actor, connection, run;
    Authentication editor;
    final String dangerousId = "=HYPERLINK(\"https://private.invalid\",\"NEVER_SOURCE_IDENTIFIER\")";
    final String hash = "a".repeat(64);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { PostgresDatabase.configure(r); }

    @BeforeEach void setup() {
        organization = UUID.randomUUID(); actor = UUID.randomUUID();
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", organization, organization.toString(), "Report synthetic organization");
        editor = principal(organization, "ORG_EDITOR");
        connection = integration.create(editor, new ConnectionInput(organization, "NEVER_CONNECTION_NAME", "UNCONTRACTED",
                "https://hub-report.invalid/private", "NEVER_SECRET_REFERENCE", false, 5, 1, 100)).id();
        run = UUID.randomUUID();
        db.update("""
            INSERT INTO integration.sync_run(id,sou_lnkg_id,rqstr_id,type_cd,stts_cd,idmpt_key_hash_vl,dmnd_hash_vl,
                stng_ver_no,mpng_ver_nm,prcstry_nmtm,crt_dt,bgng_dt,end_dt)
            VALUES (?,?,?,'SYNC','SUCCEEDED',?,?,0,'normalized-metadata-v1',1,?,?,?)
            """, run, connection, actor, "1".repeat(64), "2".repeat(64),
                OffsetDateTime.parse("2026-10-27T00:00:00Z"), OffsetDateTime.parse("2026-10-27T00:00:01Z"), OffsetDateTime.parse("2026-10-27T00:00:02Z"));
    }

    @Test void jsonAndCsvProjectActualRunItemsAndCandidateReferencesWithoutSourceValues() throws Exception {
        UUID candidate = candidate();
        item(dangerousId, "CANDIDATE", hash, null);
        item("urn:dataset:unchanged", "UNCHANGED", "b".repeat(64), null);
        item("urn:dataset:failed", "FAILED", null, "AUTHENTICATION_FAILED");
        var response = mvc.perform(get(path()).with(authentication(editor)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"hub-run-" + run + ".json\""))
                .andExpect(jsonPath("$.run.id").value(run.toString()))
                .andExpect(jsonPath("$.run.createdAt").value("2026-10-27T00:00:00Z"))
                .andExpect(jsonPath("$.summary.totalItems").value(3))
                .andExpect(jsonPath("$.summary.candidateItems").value(1))
                .andExpect(jsonPath("$.summary.unchangedItems").value(1))
                .andExpect(jsonPath("$.summary.failedItems").value(1))
                .andExpect(jsonPath("$.summary.currentCandidateReferences").value(1)).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode doc = json.readTree(body);
        JsonNode row = java.util.stream.StreamSupport.stream(doc.path("items").spliterator(), false)
                .filter(x -> hash.equals(x.path("sourceHash").asText())).findFirst().orElseThrow();
        assertThat(row.path("sourceReferenceSha256").asText()).isEqualTo(IntegrationService.digest(dangerousId));
        assertThat(row.path("candidateId").asText()).isEqualTo(candidate.toString());
        assertThat(row.path("candidateState").asText()).isEqualTo("NEW");
        assertThat(row.path("fieldCount").asInt()).isEqualTo(1);
        assertThat(row.path("evidenceCode").asText()).isEqualTo("NORMALIZED_CANDIDATE");
        var csv = mvc.perform(get(path()).param("format", "csv").with(authentication(editor)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF\"schemaVersion\",\"generatedAt\"").contains(hash, candidate.toString(), "AUTHENTICATION_FAILED");
        assertThat(csv.lines().count()).isEqualTo(4);
        for (String text : List.of(body, csv)) assertThat(text).doesNotContain(dangerousId, "NEVER_", "hub-report.invalid",
                "private.invalid", actor.toString(), "requestedBy", "secretReference", "endpoint", "sourceRevision", "description");
    }

    @Test void accessMatchesExistingRunBoundaryForAnonymousForeignWrongRoleReviewerAndPlatform() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).with(authentication(principal(UUID.randomUUID(), "ORG_EDITOR")))).andExpect(status().isNotFound());
        mvc.perform(get(path()).with(authentication(principal(organization, "ORG_PUBLISHER")))).andExpect(status().isForbidden());
        mvc.perform(get(path()).with(authentication(principal(organization, "ORG_REVIEWER")))).andExpect(status().isOk());
        mvc.perform(get(path()).with(authentication(principal(null, "PLATFORM_ADMIN")))).andExpect(status().isOk());
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", organization);
        mvc.perform(get(path()).with(authentication(editor))).andExpect(status().isForbidden());
    }

    @Test void laterSourceChangeCannotBePresentedAsHistoricalRunCandidate() throws Exception {
        candidate(); item(dangerousId, "CANDIDATE", hash, null);
        db.update("UPDATE integration.source_metadata SET sou_hash_vl=? WHERE sou_lnkg_id=?", "c".repeat(64), connection);
        var report = reports.report(run, editor);
        assertThat(report.items()).hasSize(1);
        assertThat(report.items().getFirst().sourceHash()).isEqualTo(hash);
        assertThat(report.items().getFirst().candidateId()).isNull();
        assertThat(report.items().getFirst().candidateState()).isNull();
        assertThat(report.items().getFirst().fieldCount()).isNull();
        assertThat(report.items().getFirst().evidenceCode()).isEqualTo("HISTORICAL_HASH_ONLY");
        assertThat(report.summary().currentCandidateReferences()).isZero();
    }

    @Test void reportRefusesMoreThanTenThousandItemsBeforeReadingPayload() throws Exception {
        db.update("""
            INSERT INTO integration.sync_item(id,sync_excn_id,otsd_id,stts_cd,err_cd,prcstry_nmtm)
            SELECT md5(? || ':' || n)::uuid,?, 'bounded-' || n,'FAILED','SOURCE_ITEM_INVALID',1
            FROM generate_series(1,10001) n
            """, run.toString(), run);
        mvc.perform(get(path()).with(authentication(editor))).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REPORT_TOO_LARGE"));
    }

    @Test void unsupportedFormatUnknownErrorAndEmptyRunRemainBoundedAndReadOnly() throws Exception {
        mvc.perform(get(path()).param("format", "pdf").with(authentication(editor)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        long before = db.queryForObject("SELECT count(*) FROM integration.connection_event WHERE lnkg_id=?", Long.class, connection);
        var empty = reports.download(run, "csv", editor);
        assertThat(new String(empty.content(), StandardCharsets.UTF_8).lines().count()).isEqualTo(2);
        item("urn:dataset:failed", "FAILED", null, "https://private.invalid/NEVER_ERROR_TOKEN");
        var body = new String(reports.download(run, "json", editor).content(), StandardCharsets.UTF_8);
        assertThat(body).contains("UNRECOGNIZED_ERROR").doesNotContain("private.invalid", "NEVER_ERROR_TOKEN");
        assertThat(db.queryForObject("SELECT count(*) FROM integration.connection_event WHERE lnkg_id=?", Long.class, connection)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT stts_cd FROM integration.sync_run WHERE id=?", String.class, run)).isEqualTo("SUCCEEDED");
    }

    private UUID candidate() throws Exception {
        UUID id = UUID.randomUUID();
        String metadata = json.writeValueAsString(Map.of("title", "NEVER_RAW_METADATA", "description", "NEVER_RAW_DESCRIPTION",
                "visibility", "PRIVATE", "accessState", "METADATA_ONLY", "details", Map.of("fields", List.of(Map.of("fieldPath", "NEVER_FIELD_NAME")))));
        db.update("INSERT INTO integration.source_metadata(id,sou_lnkg_id,otsd_id,sou_hash_vl,cand_cn,mpng_ver_nm,last_excn_id) VALUES (?,?,?,?,?::jsonb,'normalized-metadata-v1',?)",
                id, connection, dangerousId, hash, metadata, run);
        return id;
    }
    private void item(String id, String status, String sourceHash, String error) {
        db.update("INSERT INTO integration.sync_item(id,sync_excn_id,otsd_id,sou_hash_vl,stts_cd,err_cd,prcstry_nmtm) VALUES (?,?,?,?,?,?,1)",
                UUID.randomUUID(), run, id, sourceHash, status, error);
    }
    private String path() { return "/api/admin/v1/sync-runs/" + run + "/report"; }
    private Authentication principal(UUID org, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(), org == null ? Set.of() : Set.of(org)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}

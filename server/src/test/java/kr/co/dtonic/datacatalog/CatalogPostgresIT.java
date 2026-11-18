package kr.co.dtonic.datacatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogQueryService catalog;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresDatabase.configure(registry);
    }

    @Test
    void appliesMigrationOnRealPostgres17AndReportsHealth() throws Exception {
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("17.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success", Integer.class)).isEqualTo(1);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @Transactional
    void readsOnlyCurrentPublicApprovedPublicationsAndPaginates() throws Exception {
        UUID first = insertDataset("PUBLIC", "APPROVED", "PUBLISHED", true, "A public title");
        insertDataset("PUBLIC", "APPROVED", "PUBLISHED", true, "B public title");
        insertDataset("ORGANIZATION", "APPROVED", "PUBLISHED", true, "Organization secret");
        insertDataset("PRIVATE", "APPROVED", "PUBLISHED", true, "Private secret");
        insertDataset("PUBLIC", "DRAFT", null, false, "Draft secret");
        insertDataset("PUBLIC", "APPROVED", "WITHDRAWN", true, "Withdrawn secret");
        insertDataset("PUBLIC", "APPROVED", "PUBLISHED", false, "Old publication secret");
        assertThat(catalog.listPublished(0, 10).page().totalElements()).isEqualTo(2);
        assertThat(catalog.listPublished(0, 10).items()).extracting("title").containsExactly("B public title", "A public title");
        mvc.perform(get("/api/catalog/v1/datasets?page=1&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist());
        assertThat(catalog.listPublished(2, 1).items()).isEmpty();
    }

    @Test
    void failedWriteRollsBackAllEarlierChanges() {
        UUID organization = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertOrganization(organization);
            jdbc.update("INSERT INTO catalog.dataset(id, psn_inst_id, slug_nm) VALUES (?, ?, ?)", UUID.randomUUID(), organization, organization.toString());
            jdbc.update("INSERT INTO catalog.dataset(id, psn_inst_id, slug_nm) VALUES (?, ?, ?)", UUID.randomUUID(), organization, organization.toString());
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.organization WHERE id = ?", Integer.class, organization)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id = ?", Integer.class, organization)).isZero();
    }

    @Test
    @Transactional
    void rejectsCrossDatasetCurrentPublicationPointer() {
        UUID first = insertDataset("PUBLIC", "APPROVED", "PUBLISHED", true, "First");
        UUID second = insertDataset("PUBLIC", "APPROVED", "PUBLISHED", true, "Second");
        UUID otherPublication = jdbc.queryForObject("SELECT now_pstg_id AS current_publication_id FROM catalog.dataset WHERE id=?", UUID.class, second);
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?", otherPublication, first))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void approvedMetadataCannotBeRewritten() {
        UUID dataset = insertDataset("PUBLIC", "APPROVED", "PUBLISHED", true, "Immutable title");
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog.dataset_revision SET ttl='replacement' WHERE dtset_id=?", dataset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void draftCannotBePublished() {
        UUID dataset = insertDataset("PUBLIC", "DRAFT", null, false, "Pending review");
        UUID revision = jdbc.queryForObject("SELECT id FROM catalog.dataset_revision WHERE dtset_id=?", UUID.class, dataset);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd) VALUES (?,?,?,1,'PUBLISHED')",
                UUID.randomUUID(), dataset, revision)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void repeatedPublicationImportReturnsOneMetadataEntry() {
        var populator = new ResourceDatabasePopulator(new ClassPathResource("fixtures/repeatable-publication.sql"));
        populator.execute(dataSource);
        populator.execute(dataSource);
        var page = catalog.listPublished(0, 10);
        assertThat(page.page().totalElements()).isEqualTo(1);
        assertThat(page.items().getFirst().title()).isEqualTo("반복 등록 시험");
        assertThat(page.items().getFirst().accessState()).isEqualTo("METADATA_ONLY");
    }

    @Test
    void exportsGeneratedInternalOpenApiForFrontendTypes() throws Exception {
        String spec = mvc.perform(get("/v3/api-docs").with(user("contract-export")
                        .authorities(new SimpleGrantedAuthority("INTERNAL_API_DOCS"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/catalog/v1/datasets'].get.operationId").value("listPublishedDatasets"))
                .andReturn().getResponse().getContentAsString();
        var document = json.readTree(spec);
        assertThat(document.at("/paths").has("/api/admin/v1/datasets")).isTrue();
        assertThat(document.at("/paths").has("/api/catalog/v1/datasets/{datasetId}")).isTrue();
        assertThat(document.at("/components/schemas/DatasetSummary/properties/category/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(document.at("/components/schemas/DatasetSummary/required").toString()).contains("category");
        assertThat(document.at("/components/schemas/TermCandidatePage/properties/basis/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(document.at("/components/schemas/TermCandidatePage/required").toString()).contains("basis");
        assertThat(document.at("/components/schemas/TermFieldUsage/properties/latestPublication/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(document.at("/components/schemas/TermFieldUsage/properties/primaryCategoryName/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(document.at("/components/schemas/CategoryDetailsInput/properties/parentId/type").toString()).contains("null");
        assertThat(document.at("/paths/~1api~1catalog~1v1~1datasets/get/responses/200/content").has("application/json")).isTrue();
        assertThat(document.at("/paths/~1api~1admin~1v1~1organizations~1{organizationId}~1access/get/responses").has("401")).isTrue();
        assertThat(document.at("/paths/~1api~1admin~1v1~1organizations~1{organizationId}~1access/get/responses").has("403")).isTrue();
        assertCorrectedOperationContracts(document);
        assertAdministrationSecurityContract(document);
        assertInputAndResponsePresenceAreSeparate(document);
        assertSearchHistoryNullableContract(document);
        assertLocalReferencesResolve(document, document);
        assertThat(spec).doesNotContain("DB_PASSWORD", "jdbc:postgresql", "/api/data/v1");
        Path output = Path.of("target", "openapi.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, spec + System.lineSeparator());
    }

    @Test
    @Transactional
    void acceptsOptionalSharedInputFieldsWithoutWeakeningResponsePresence() throws Exception {
        UUID org = UUID.randomUUID();
        insertOrganization(org);
        var auth = new UsernamePasswordAuthenticationToken(new CatalogPrincipal(UUID.randomUUID(), Set.of(org)), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        String common = "\"title\":\"Optional input\",\"description\":\"\",\"providerOrganizationId\":\"" + org + "\",\"visibility\":\"PRIVATE\"";
        var created = mvc.perform(post("/api/admin/v1/datasets").with(authentication(auth)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"ownerOrganizationId\":\"" + org + "\",\"slug\":\"optional-" + UUID.randomUUID() + "\"," + common + ",\"details\":{}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.details.keywords").isEmpty()).andReturn().getResponse();
        var body = json.readTree(created.getContentAsString());
        assertThat(body.path("details").has("department")).isTrue();
        assertThat(body.path("details").path("department").isNull()).isTrue();
        String id = body.path("id").asText();
        var edited = mvc.perform(patch("/api/admin/v1/datasets/" + id + "/draft").with(authentication(auth)).with(csrf())
                .header("If-Match", created.getHeader("ETag")).contentType("application/json")
                .content("{" + common + ",\"details\":{\"department\":null,\"keywords\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.details.keywords").isEmpty()).andReturn().getResponse();
        String requiredField = "\"fieldPath\":\"a\",\"physicalName\":\"a\",\"displayName\":\"A\",\"description\":\"\",\"dataType\":\"STRING\"";
        var fields = mvc.perform(put("/api/admin/v1/datasets/" + id + "/draft/fields").with(authentication(auth)).with(csrf())
                .header("If-Match", edited.getHeader("ETag")).contentType("application/json")
                .content("{\"fields\":[{" + requiredField + "}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fields[0].nullable").value(false))
                .andExpect(jsonPath("$.fields[0].publicVisible").value(false)).andExpect(jsonPath("$.fields[0].ordinal").value(0))
                .andReturn().getResponse();
        mvc.perform(put("/api/admin/v1/datasets/" + id + "/draft/fields").with(authentication(auth)).with(csrf())
                .header("If-Match", fields.getHeader("ETag")).contentType("application/json")
                .content("{\"fields\":[{" + requiredField + ",\"nullable\":null,\"ordinal\":null,\"publicVisible\":null,\"maxLength\":null,\"unit\":null,\"approvedTermId\":null,\"mappingEvidence\":null}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fields[0].ordinal").value(0));
    }

    private void assertSearchHistoryNullableContract(JsonNode document) {
        var schemas = document.at("/components/schemas");
        for (String name : List.of("CatalogSearchPage", "DatasetPage")) {
            var schema = schemas.path(name);
            var feedback = schema.at("/properties/feedback");
            assertThat(feedback.path("anyOf")).hasSize(2);
            assertThat(feedback.at("/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/FeedbackOffer");
            assertThat(feedback.at("/anyOf/1/type").asText()).isEqualTo("null");
            assertThat(java.util.stream.StreamSupport.stream(schema.path("required").spliterator(), false)
                    .map(JsonNode::asText).toList()).contains("feedback");
        }
        var history = schemas.path("HistoryDetail");
        for (String field : List.of("queryRepresentation", "rating", "ratedAt", "returnedPage", "returnedSize")) {
            var property = history.path("properties").path(field);
            assertThat(property.path("anyOf")).hasSize(2);
            assertThat(property.at("/anyOf/0").isObject()).isTrue();
            assertThat(property.at("/anyOf/1/type").asText()).isEqualTo("null");
            assertThat(java.util.stream.StreamSupport.stream(history.path("required").spliterator(), false)
                    .map(JsonNode::asText).toList()).contains(field);
        }
        assertThat(schemas.at("/HistoryDetail/properties/queryRepresentation/anyOf/0/$ref").asText())
                .isEqualTo("#/components/schemas/SearchQueryRepresentation");
    }

    private void assertInputAndResponsePresenceAreSeparate(JsonNode document) {
        var schemas = document.at("/components/schemas");
        assertThat(schemas.path("MetadataDetailsInput").path("required").isEmpty()).isTrue();
        assertThat(schemas.path("MetadataDetails").path("required").size()).isEqualTo(7);
        assertThat(schemas.at("/MetadataDetails/properties/keywords/type").asText()).isEqualTo("array");
        assertThat(schemas.at("/MetadataDetailsInput/properties/keywords/anyOf/1/type").asText()).isEqualTo("null");
        for (String request : List.of("CreateDataset", "EditDraft")) {
            assertThat(schemas.path(request).at("/properties/details/anyOf/0/$ref").asText()).endsWith("/MetadataDetailsInput");
            assertThat(schemas.path(request).path("required").toString()).doesNotContain("details");
        }
        assertThat(schemas.at("/MetadataDraft/properties/details/$ref").asText()).endsWith("/MetadataDetails");
        assertThat(schemas.at("/FieldsInput/properties/fields/items/$ref").asText()).endsWith("/FieldDefinitionInput");
        assertThat(schemas.path("FieldDefinitionInput").path("required").size()).isEqualTo(5);
        assertThat(schemas.path("FieldDefinition").path("required").size()).isEqualTo(12);
        for (String primitive : List.of("nullable", "ordinal", "publicVisible")) {
            assertThat(schemas.path("FieldDefinitionInput").path("properties").path(primitive).at("/anyOf/1/type").asText()).isEqualTo("null");
            assertThat(schemas.path("FieldDefinition").path("properties").path(primitive).has("anyOf")).isFalse();
        }
        assertThat(schemas.at("/FieldSet/properties/fields/items/$ref").asText()).endsWith("/FieldDefinition");
    }

    private void assertCorrectedOperationContracts(JsonNode document) {
        for (String[] expected : List.of(
                new String[]{"/api/admin/v1/categories", "post", "201"},
                new String[]{"/api/admin/v1/categories/{id}", "delete", "204"},
                new String[]{"/api/admin/v1/connections", "post", "201"},
                new String[]{"/api/admin/v1/connections/{id}/checks", "post", "202"},
                new String[]{"/api/admin/v1/connections/{id}/sync-runs", "post", "202"},
                new String[]{"/api/admin/v1/definition-imports", "post", "201"},
                new String[]{"/api/admin/v1/definition-imports/{id}/applications", "post", "201"},
                new String[]{"/api/admin/v1/dictionaries", "post", "201"},
                new String[]{"/api/admin/v1/dictionaries/{dictionaryId}/entries", "post", "201"})) {
            var responses = document.path("paths").path(expected[0]).path(expected[1]).path("responses");
            assertThat(responses.has(expected[2])).as("%s %s documents actual success %s", expected[1], expected[0], expected[2]).isTrue();
            assertThat(responses.has("200")).as("%s %s must not invent 200", expected[1], expected[0]).isFalse();
            assertThat(responses.path(expected[2]).path("content").isEmpty())
                    .as("204 has no body; other corrected successes retain their response schema").isEqualTo("204".equals(expected[2]));
        }
        var session = document.path("paths").path("/api/admin/v1/session").path("get");
        for (var parameter : session.path("parameters")) {
            assertThat(parameter.path("name").asText()).as("CsrfToken is server injected, never a caller query").isNotEqualTo("csrf");
        }
    }

    private void assertAdministrationSecurityContract(JsonNode document) {
        var scheme = document.at("/components/securitySchemes/catalogSession");
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("cookie");
        assertThat(scheme.path("name").asText()).isEqualTo("JSESSIONID");
        assertThat(scheme.path("description").asText()).contains("CatalogPrincipal", "SSO", "미구현");
        assertThat(document.path("security").isEmpty()).as("Public reads have no global authentication requirement").isTrue();
        document.path("paths").fields().forEachRemaining(path -> {
            for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                var operation = path.getValue().path(method);
                if (operation.isMissingNode()) continue;
                boolean admin = path.getKey().startsWith("/api/admin/");
                boolean unsafe = !List.of("get", "head", "options").contains(method);
                boolean bootstrap = path.getKey().equals("/api/admin/v1/auth") && method.equals("get");
                boolean login = path.getKey().equals("/api/admin/v1/auth/login") && method.equals("post");
                if (admin) {
                    assertThat(operation.path("security").size()).isEqualTo(bootstrap || login ? 0 : 1);
                    if (!bootstrap && !login) assertThat(operation.at("/security/0/catalogSession").isArray()).isTrue();
                    assertThat(operation.path("responses").has("401")).isTrue();
                    if (!bootstrap) assertThat(operation.path("responses").has("403")).isTrue();
                } else {
                    assertThat(operation.path("security").isEmpty()).as("%s %s remains public", method, path.getKey()).isTrue();
                }
                int csrfHeaders = 0;
                for (var parameter : operation.path("parameters")) {
                    if (!"header".equals(parameter.path("in").asText())) continue;
                    String name = parameter.path("name").asText();
                    if (List.of("If-Match", "Idempotency-Key").contains(name)) {
                        assertThat(parameter.path("required").asBoolean()).as("%s %s requires %s", method, path.getKey(), name).isTrue();
                    }
                    if ("X-CSRF-TOKEN".equals(name)) {
                        csrfHeaders++;
                        assertThat(parameter.path("required").asBoolean()).as("Spring also accepts the _csrf request parameter").isFalse();
                        assertThat(parameter.path("description").asText()).contains("_csrf", "403", "csrfToken");
                    }
                }
                assertThat(csrfHeaders).as("CSRF header belongs only to unsafe admin operations").isEqualTo(admin && unsafe ? 1 : 0);
            }
        });
        var localLogin = document.path("paths").path("/api/admin/v1/auth/login").path("post");
        assertThat(localLogin.at("/responses/429/headers/Retry-After/schema/type").asText()).isEqualTo("integer");
        assertThat(localLogin.path("responses").has("204")).isTrue();
        assertThat(document.at("/components/schemas/LocalLoginRequest/properties/password/writeOnly").asBoolean()).isTrue();
        assertThat(document.at("/components/schemas/AuthBootstrapResponse/properties/csrfToken/anyOf").isArray()).isTrue();
        var definitionApply = document.path("paths").path("/api/admin/v1/definition-imports/{id}/applications").path("post");
        for (var parameter : definitionApply.path("parameters")) {
            if ("Idempotency-Key".equals(parameter.path("name").asText())) {
                assertThat(parameter.at("/schema/pattern").asText()).isEqualTo("[A-Za-z0-9_-]{8,128}");
            }
        }
    }

    private void assertLocalReferencesResolve(JsonNode document, JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            String reference = node.path("$ref").asText();
            if (reference.startsWith("#/")) {
                assertThat(document.at(reference.substring(1)).isMissingNode())
                        .as("Generated OpenAPI local reference must resolve: %s", reference).isFalse();
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) assertLocalReferencesResolve(document, child);
        }
    }

    private UUID insertDataset(String visibility, String state, String publicationState, boolean current, String title) {
        UUID organization = UUID.randomUUID();
        UUID dataset = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        insertOrganization(organization);
        jdbc.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)", dataset, organization, dataset.toString());
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd) VALUES (?,?,1,?,?,?,?)",
                revision, dataset, state, title, organization, visibility);
        if (publicationState != null) {
            UUID publication = UUID.randomUUID();
            jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt,rcntt_dt) VALUES (?,?,?,1,?,clock_timestamp(),CASE WHEN ?='WITHDRAWN' THEN clock_timestamp() ELSE NULL END)",
                    publication, dataset, revision, publicationState, publicationState);
            if (current) jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?", publication, dataset);
        }
        return dataset;
    }

    private void insertOrganization(UUID organization) {
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", organization, organization.toString(), "Integration test organization");
    }
}

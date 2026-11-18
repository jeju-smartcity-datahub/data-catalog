package kr.co.dtonic.datacatalog;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeliveryPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test @Transactional
    void publicDocsExposeExistingMetadataApisAndWithdrawalImmediatelyHidesThem() throws Exception {
        UUID dataset = dataset("PUBLIC", "PUBLIC P5 fixture");
        mvc.perform(get("/api/docs/v1/datasets/" + dataset)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.datasetId").value(dataset.toString()))
                .andExpect(jsonPath("$.publicationVersion").value(1))
                .andExpect(jsonPath("$.accessState").value("METADATA_ONLY"))
                .andExpect(jsonPath("$.documentKind").value("METADATA_API"))
                .andExpect(jsonPath("$.message").value("게시된 메타정보와 필드정보를 JSON API로 조회할 수 있습니다."))
                .andExpect(jsonPath("$.documents").value(org.hamcrest.Matchers.contains(
                        "/api/catalog/v1/datasets/" + dataset, "/api/catalog/v1/dcat")))
                .andExpect(jsonPath("$.endpoint").doesNotExist());
        mvc.perform(get("/api/catalog/v1/datasets/" + dataset)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessState").value("METADATA_ONLY"))
                .andExpect(jsonPath("$.access.accessState").value("METADATA_ONLY"))
                .andExpect(jsonPath("$.access.message").value("게시된 메타정보와 필드정보를 JSON API로 조회할 수 있습니다."))
                .andExpect(jsonPath("$.access.capabilities").value(org.hamcrest.Matchers.contains("METADATA_JSON", "DCAT_JSONLD")));
        mvc.perform(get("/api/catalog/v1/dcat")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(dataset.toString())));
        jdbc.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=CURRENT_TIMESTAMP WHERE dtset_id=?", dataset);
        mvc.perform(get("/api/docs/v1/datasets/" + dataset)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/catalog/v1/datasets/" + dataset)).andExpect(status().isNotFound());
    }

    @Test @Transactional
    void dcatFormatsShareOnlyCurrentPublicApprovedRowsAndRecheckStateBeforeConditionalResponse() throws Exception {
        UUID visible = dataset("PUBLIC", "P5 public dataset");
        UUID privateDataset = dataset("PRIVATE", "P5 PRIVATE SECRET");
        UUID organizationDataset = dataset("ORGANIZATION", "P5 ORGANIZATION SECRET");
        String response = mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Total-Count", "1")).andReturn().getResponse().getContentAsString();
        assertThat(response).contains(visible.toString()).doesNotContain(privateDataset.toString(), organizationDataset.toString(), "SECRET", "Distribution", "DataService");
        String etag = mvc.perform(get("/api/catalog/v1/dcat").accept("text/turtle"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/turtle"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(visible.toString())))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/api/catalog/v1/dcat/download").param("format", "turtle"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", etag))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"catalog-0.ttl\""));
        jdbc.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=CURRENT_TIMESTAMP WHERE dtset_id=?", visible);
        mvc.perform(get("/api/catalog/v1/dcat").accept("text/turtle").header("If-None-Match", etag))
                .andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "0"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(visible.toString()))));
    }

    @Test @Transactional
    void providerDeactivationAndPrivatePublicationHideDocs() throws Exception {
        UUID privateDataset = dataset("PRIVATE", "private");
        UUID visible = dataset("PUBLIC", "public");
        mvc.perform(get("/api/docs/v1/datasets/" + privateDataset)).andExpect(status().isNotFound());
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=(SELECT psn_inst_id AS owner_organization_id FROM catalog.dataset WHERE id=?)", visible);
        mvc.perform(get("/api/docs/v1/datasets/" + visible)).andExpect(status().isNotFound());
    }

    @Test @Transactional
    void fixtureContractCannotBeBoundToPublicPublication() {
        UUID dataset = dataset("PUBLIC", "fixture candidate");
        UUID service = contract(dataset, "FIXTURE");
        jdbc.update("UPDATE catalog.publication SET acs_stts_cd='LINK_READY' WHERE dtset_id=?", dataset);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO delivery.publication_service(dtset_id,pstg_id,srvc_ver_id) SELECT id,now_pstg_id AS current_publication_id,? FROM catalog.dataset WHERE id=?", service, dataset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional
    void documentsCannotReferenceAnotherSchemaAndAreNotPublicJustBecauseTheyExist() throws Exception {
        UUID dataset = dataset("PUBLIC", "candidate document");
        UUID service = contract(dataset, "FIXTURE");
        mvc.perform(get("/api/docs/v1/datasets/" + dataset)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentKind").value("METADATA_API"))
                .andExpect(jsonPath("$.documents").value(org.hamcrest.Matchers.contains(
                        "/api/catalog/v1/datasets/" + dataset, "/api/catalog/v1/dcat")));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO apidocs.api_document_version(id,srvc_ver_id,doc_rvsn_no,doc_type_cd,oapi_snpst_cn,spcfct_hash_vl,schm_hash_vl,crt_ver_nm) VALUES (?,?,1,'OPENAPI','{}',?,?,?)",
                UUID.randomUUID(), service, "a".repeat(64), "b".repeat(64), "test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional
    void generatedDocumentSnapshotCannotBeModified() {
        UUID dataset = dataset("PUBLIC", "immutable document");
        UUID service = contract(dataset, "FIXTURE");
        UUID document = UUID.randomUUID();
        jdbc.update("INSERT INTO apidocs.api_document_version(id,srvc_ver_id,doc_rvsn_no,doc_type_cd,oapi_snpst_cn,spcfct_hash_vl,schm_hash_vl,crt_ver_nm) VALUES (?,?,1,'OPENAPI','{}',?,?,?)",
                document, service, "a".repeat(64), "a".repeat(64), "test");
        assertThatThrownBy(() -> jdbc.update("UPDATE apidocs.api_document_version SET oapi_snpst_cn='{\"altered\":true}' WHERE id=?", document))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional
    void feedPaginationAndFormatsRejectUnboundedOrUnsupportedInput() throws Exception {
        dataset("PUBLIC", "one"); dataset("PUBLIC", "two");
        mvc.perform(get("/api/catalog/v1/dcat")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"));
        mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json;q=0.5,text/turtle;q=0.9"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/turtle"));
        mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json").param("size", "1"))
                .andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "2"))
                .andExpect(header().string("Link", "</api/catalog/v1/dcat?page=1&size=1>; rel=\"next\""));
        mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json").param("size", "501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalog/v1/dcat/download").param("format", "../../private")).andExpect(status().isBadRequest());
    }

    private UUID contract(UUID dataset, String verification) {
        UUID service = UUID.randomUUID();
        jdbc.update("INSERT INTO delivery.data_service_version(id,dtset_id,ctrt_ver_no,pvsn_type_cd,acs_plcy_cd,stts_cd,vrfc_lvl_cd,schm_snpst_cn,schm_hash_vl,ctrt_snpst_cn,ctrt_hash_vl) VALUES (?,?,1,'LINK','PUBLIC','VERIFIED',?,'{}',?,'{}',?)",
                service, dataset, verification, "a".repeat(64), "a".repeat(64));
        return service;
    }

    @Test @Transactional
    void dcatIncludesPublicSnapshotDescriptionsAndNeverImportsDraftOrPrivateProperties() throws Exception {
        UUID category = UUID.randomUUID(), region = UUID.randomUUID(), topic = UUID.randomUUID();
        jdbc.update("INSERT INTO classification.category(id,cd,nm) VALUES (?,?,?)", category, category.toString(), "현재 분류명");
        var metadata = json.createObjectNode();
        var details = metadata.putObject("details");
        details.put("department", "공개 담당부서"); details.put("license", "https://creativecommons.org/licenses/by/4.0/");
        details.put("updateFrequency", "매시간"); details.put("spatialCoverage", "제주도");
        details.put("temporalStart", "2026-01-01"); details.put("temporalEnd", "2026-10-28");
        details.putArray("keywords").add("주차").add("공공"); details.put("privateSecret", "DETAIL_SECRET");
        metadata.putObject("publicationLabels").put("providerOrganizationName", "게시 기관명").put("primaryCategoryName", "게시 분류명");
        var discovery = metadata.putObject("discovery");
        discovery.putArray("regions").addObject().put("id", region.toString()).put("name", "게시 지역명").put("secret", "REGION_SECRET");
        discovery.putArray("topics").addObject().put("id", topic.toString()).put("name", "게시 주제명");
        metadata.put("credentials", "CREDENTIAL_SECRET");
        UUID visible = dataset("PUBLIC", "DCAT public", category, json.writeValueAsString(metadata), java.time.OffsetDateTime.parse("2026-10-27T01:02:03Z"));
        dataset("PRIVATE", "PRIVATE_DATASET_SECRET", null, "{\"details\":{\"license\":\"PRIVATE_LICENSE_SECRET\",\"keywords\":[\"PRIVATE_KEYWORD_SECRET\"]}}", java.time.OffsetDateTime.now());
        UUID organization = jdbc.queryForObject("SELECT psn_inst_id FROM catalog.dataset WHERE id=?", UUID.class, visible);
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd,mtdt_cn) VALUES (?,?,2,'DRAFT','UNPUBLISHED_DRAFT_SECRET',?,'PUBLIC',?::jsonb)",
                UUID.randomUUID(), visible, organization, "{\"details\":{\"license\":\"DRAFT_LICENSE_SECRET\"}}");
        String ld = mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ttl = mvc.perform(get("/api/catalog/v1/dcat").accept("text/turtle")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String output : java.util.List.of(ld, ttl)) {
            assertThat(output).contains("https://creativecommons.org/licenses/by/4.0/", "주차", "공공", "공개 담당부서", "매시간", "제주도",
                    "2026-01-01", "2026-10-28", "2026-10-27T01:02:03Z", "게시 기관명", "게시 분류명", "게시 지역명", "게시 주제명")
                    .doesNotContain("SECRET", "현재 분류명", "Distribution", "DataService");
        }
        java.nio.file.Path directory = java.nio.file.Path.of("target", "dcat-rdf");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("public-feed.jsonld"), ld);
        java.nio.file.Files.writeString(directory.resolve("public-feed.ttl"), ttl);
        jdbc.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=CURRENT_TIMESTAMP WHERE dtset_id=?", visible);
        mvc.perform(get("/api/catalog/v1/dcat")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("creativecommons"))));
    }

    private UUID dataset(String visibility, String title) {
        return dataset(visibility, title, null, "{}", java.time.OffsetDateTime.now());
    }

    private UUID dataset(String visibility, String title, UUID category, String metadata, java.time.OffsetDateTime modified) {
        UUID organization = UUID.randomUUID(), dataset = UUID.randomUUID(), revision = UUID.randomUUID(), publication = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", organization, organization.toString(), "P5 fixture provider");
        jdbc.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)", dataset, organization, dataset.toString());
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd,main_clsf_id,mtdt_cn,mdfcn_dt) VALUES (?,?,1,'APPROVED',?,?,?,?,?::jsonb,?)", revision, dataset, title, organization, visibility, category, metadata, modified);
        jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd) VALUES (?,?,?,1,'PUBLISHED')", publication, dataset, revision);
        jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?", publication, dataset);
        return dataset;
    }
}

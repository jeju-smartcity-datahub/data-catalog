package kr.co.dtonic.datacatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.CreateDataset;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionImportService;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionParser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No surrounding test transaction: REQUIRES_NEW must observe committed reference data, as real HTTP requests do. */
@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DefinitionFailurePostgresIT {
    @Autowired JdbcTemplate db;@Autowired DefinitionImportService imports;@Autowired MetadataWorkflowService metadata;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    @Test void authorizedParseFailureSurvivesRollbackWithoutRawSourceOrPartialImport() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR","ORG_AUDITOR");
        var draft=metadata.create(new CreateDataset(org,"failure-"+UUID.randomUUID(),"실패 경계 시험","설명",org,null,"PRIVATE"),UUID.randomUUID().toString(),actor,"failure-test");
        byte[] invalid="{\"secret-field-test\":\"raw-private-value\",\"fields\":[null]}".getBytes(StandardCharsets.UTF_8);
        var file=new MockMultipartFile("file","sensitive-file-name.json","application/json",invalid);
        String response=mvc.perform(multipart("/api/admin/v1/definition-imports").file(file).param("organizationId",org.toString()).param("datasetId",draft.id().toString()).with(authentication(actor)).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEFINITION_PARSE_FAILED"))
                .andExpect(jsonPath("$.analysisRunId").isString()).andExpect(jsonPath("$.definitionDiagnostics.generatedFieldCount").value(0))
                .andExpect(jsonPath("$.definitionDiagnostics.unextractedFields[0].reasonCode").value("UNSUPPORTED_PROPERTY"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("raw-private-value","secret-field-test","sensitive-file-name","Unknown root property");
        var rows=db.queryForList("SELECT id,rqstr_id AS requested_by,dtset_rvsn_id AS dataset_revision_id,engn_ver_nm AS engine_version,sou_hash_vl AS source_hash,stts_cd AS status,err_cd AS error_code,data_artcl_cnt AS field_count,bss_cn::text AS evidence,bgng_dt AS started_at,end_dt AS finished_at FROM analytics.analysis_run WHERE inst_id=?",org);
        assertThat(rows).hasSize(1);var row=rows.getFirst();
        assertThat(row.get("requested_by")).isEqualTo(((CatalogPrincipal)actor.getPrincipal()).userId());
        assertThat(row.get("dataset_revision_id")).isEqualTo(draft.revisionId());assertThat(row.get("engine_version")).isEqualTo(DefinitionParser.VERSION);
        assertThat(row.get("source_hash")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(invalid)));
        assertThat(row.get("status")).isEqualTo("FAILED");assertThat(row.get("error_code")).isEqualTo("DEFINITION_PARSE_FAILED");assertThat(row.get("field_count")).isEqualTo(0);assertThat(row.get("evidence")).isEqualTo("[]");
        assertThat(row.get("started_at")).isNotNull();assertThat(row.get("finished_at")).isNotNull();
        assertThat(db.queryForObject("SELECT count(*) FROM standard.definition_import WHERE inst_id=?",Long.class,org)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE inst_id=? AND prcs_cd='DEFINITION_ANALYSIS_FAILED' AND trgt_id=?",Long.class,org,row.get("id"))).isEqualTo(1);
        assertThat(metadata.getDraft(draft.id(),actor).lockVersion()).isZero();assertThat(metadata.getDraftMetadata(draft.id(),actor).get("fields")).isNull();
        String persisted=db.queryForObject("SELECT to_jsonb(a)::text FROM analytics.analysis_run a WHERE id=?",String.class,row.get("id"));
        assertThat(persisted).doesNotContain("raw-private-value","secret-field-test","sensitive-file-name");
        assertThat(imports.analysisDiagnostics((UUID)row.get("id"),actor).unextractedFields().getFirst().reasonCode()).isEqualTo("UNSUPPORTED_PROPERTY");
    }
    @Test void unauthorizedOversizedEmptyAndWrongDatasetAreRejectedBeforeAnalysis(){
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");byte[] invalid="{\"fields\":[null]}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->imports.create(org,null,invalid,actor(organization(),"ORG_EDITOR"),"failure-test")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->imports.create(org,null,invalid,actor(org,"ORG_AUDITOR"),"failure-test")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->imports.create(org,null,new byte[0],actor,"failure-test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->imports.create(org,null,new byte[DefinitionParser.MAX_BYTES+1],actor,"failure-test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->imports.create(org,UUID.randomUUID(),invalid,actor,"failure-test")).isInstanceOf(DomainException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.analysis_run WHERE inst_id=?",Long.class,org)).isZero();
    }
    @Test void eachAcceptedAttemptIsRecordedOnceAndFailureNeverCreatesAnApplicableImport(){
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");byte[] invalid="{\"fields\":[]}".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<2;i++)assertThatThrownBy(()->imports.create(org,null,invalid,actor,"failure-test")).isInstanceOf(DomainException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.analysis_run WHERE inst_id=? AND stts_cd='FAILED' AND dfn_inpt_id IS NULL",Long.class,org)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM standard.definition_import WHERE inst_id=?",Long.class,org)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE inst_id=? AND prcs_cd='DEFINITION_ANALYSIS_FAILED'",Long.class,org)).isEqualTo(2);
    }
    @Test void mixedInvalidDefinitionPersistsSafeItemReasonsOutsideRollbackAndKeepsTheDraftUntouched() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR","ORG_AUDITOR");
        var draft=metadata.create(new CreateDataset(org,"mixed-"+UUID.randomUUID(),"항목 실패 시험","설명",org,null,"PRIVATE"),UUID.randomUUID().toString(),actor,"mixed-test");
        byte[] input=("{\"fields\":[{\"fieldPath\":\"private-path\",\"physicalName\":\"private-name\",\"displayName\":\"private-label\",\"description\":\"private-description\",\"dataType\":\"STRING\",\"nullable\":true,\"publicVisible\":true,\"ordinal\":0},"
                +"{\"fieldPath\":\"private-invalid-path\",\"displayName\":\"private-invalid-label\",\"description\":\"private-invalid-description\",\"dataType\":\"STRING\",\"nullable\":true,\"publicVisible\":true,\"ordinal\":1}]}").getBytes(StandardCharsets.UTF_8);
        String response=mvc.perform(multipart("/api/admin/v1/definition-imports").file(new MockMultipartFile("file","private-source-name.json","application/json",input))
                .param("organizationId",org.toString()).param("datasetId",draft.id().toString()).with(authentication(actor)).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.definitionDiagnostics.inputFieldCount").value(2))
                .andExpect(jsonPath("$.definitionDiagnostics.generatedFieldCount").value(0))
                .andExpect(jsonPath("$.definitionDiagnostics.unextractedFields[0].reasonCode").value("DOCUMENT_REJECTED"))
                .andExpect(jsonPath("$.definitionDiagnostics.unextractedFields[1].sourcePointer").value("/fields/1"))
                .andExpect(jsonPath("$.definitionDiagnostics.unextractedFields[1].reasonCode").value("REQUIRED_PROPERTY_MISSING"))
                .andExpect(jsonPath("$.definitionDiagnostics.unextractedFields[1].properties[0]").value("physicalName"))
                .andReturn().getResponse().getContentAsString();
        UUID analysis=UUID.fromString(json.readTree(response).path("analysisRunId").asText());
        var stored=imports.analysisDiagnostics(analysis,actor);assertThat(stored.generatedFieldCount()).isZero();
        assertThat(stored.unextractedFields()).hasSize(2);assertThat(stored.assessments()).isEmpty();
        String persisted=db.queryForObject("SELECT to_jsonb(a)::text FROM analytics.analysis_run a WHERE id=?",String.class,analysis);
        assertThat(persisted).contains("REQUIRED_PROPERTY_MISSING","DOCUMENT_REJECTED");
        assertThat(response+persisted).doesNotContain("private-path","private-name","private-label","private-description","private-invalid","private-source-name");
        assertThat(db.queryForObject("SELECT count(*) FROM standard.definition_import WHERE inst_id=?",Long.class,org)).isZero();
        assertThat(metadata.getDraft(draft.id(),actor).lockVersion()).isZero();assertThat(metadata.getDraftMetadata(draft.id(),actor).get("fields")).isNull();
        mvc.perform(get("/api/admin/v1/analysis-runs/"+analysis+"/definition-diagnostics").with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unextractedFields[1].reasonCode").value("REQUIRED_PROPERTY_MISSING"));
        mvc.perform(get("/api/admin/v1/analysis-runs/"+analysis+"/definition-diagnostics").with(authentication(actor(organization(),"ORG_AUDITOR"))))
                .andExpect(status().isForbidden());
    }
    @Test void legacyFailedAnalysisIsExplicitlyUnassessedAndDatabaseProtectsDiagnostics() {
        UUID org=organization();UUID id=UUID.randomUUID();var actor=actor(org,"ORG_AUDITOR");
        db.update("INSERT INTO analytics.analysis_run(id,inst_id,type_cd,engn_ver_nm,rqstr_id,stts_cd,data_artcl_cnt,sou_hash_vl,bss_cn,err_cd) VALUES (?,?,'DEFINITION_IMPORT','explicit-fields-json-v1',?,'FAILED',0,?,'[]'::jsonb,'DEFINITION_PARSE_FAILED')",id,org,UUID.randomUUID(),"0".repeat(64));
        assertThat(imports.analysisDiagnostics(id,actor).status()).isEqualTo("NOT_RECORDED");
        assertThat(imports.analysisDiagnostics(id,actor).assessments()).isEmpty();
        assertThatThrownBy(()->db.update("UPDATE analytics.analysis_run SET dfn_dgns_cn='{}'::jsonb WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    private UUID organization(){UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"분석 실패 시험기관");return id;}
    private Authentication actor(UUID org,String... roles){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());}
}

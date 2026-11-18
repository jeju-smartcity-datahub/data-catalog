package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import kr.co.dtonic.datacatalog.analytics.AnalyticsService;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.DraftFieldsService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.*;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionImportService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class DefinitionImportPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired DefinitionImportService imports;
    @Autowired DraftFieldsService fields;
    @Autowired MetadataWorkflowService metadata;
    @Autowired AnalyticsService analytics;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test @Transactional
    void uploadStoresBoundedEvidenceAndSelectionAppliesToDraftIdempotently() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR","ORG_AUDITOR");var draft=createDataset(org,actor);
        var source=imports.create(org,draft.id(),document(),actor,"definition-test");
        assertThat(source.sourceHash()).hasSize(64);assertThat(source.fields()).hasSize(2);
        assertThat(source.fields().getFirst().sourcePointer()).isEqualTo("/fields/0");
        assertThat(source.fields().getFirst().confidence().score()).isEqualTo(100);
        assertThat(imports.get(source.id(),actor).diagnostics()).isEqualTo(source.diagnostics());
        assertThat(imports.analysisDiagnostics(source.analysisRunId(),actor)).isEqualTo(source.diagnostics());
        var body=new ApplyDefinition(draft.id(),List.of(source.fields().getLast().id()));String key=UUID.randomUUID().toString();String tag=etag(draft,0);
        var result=imports.apply(source.id(),body,tag,key,actor,"definition-test");
        assertThat(result.fields().lockVersion()).isEqualTo(1);assertThat(result.fields().fields()).hasSize(1);
        assertThat(result.fields().fields().getFirst().fieldPath()).isEqualTo("count");
        assertThat(imports.apply(source.id(),body,tag,key,actor,"definition-test")).isEqualTo(result);
        assertThat(metadata.getDraft(draft.id(),actor).state()).isEqualTo("DRAFT");
        assertThat(metadata.getDraft(draft.id(),actor).lockVersion()).isEqualTo(1);
        assertThat(analytics.analysis(source.analysisRunId(),actor).kind()).isEqualTo("DEFINITION_IMPORT");
        assertThat(analytics.evidence(source.analysisRunId(),actor)).hasSize(2);
        assertThat(analytics.evidence(source.analysisRunId(),actor).getFirst().sourceHash()).isEqualTo(source.sourceHash());
        assertThat(analytics.evidence(source.analysisRunId(),actor).getFirst().confidence()).isEqualTo(source.fields().getFirst().confidence());
        assertThat(db.queryForObject("SELECT count(*) FROM standard.definition_application WHERE dfn_inpt_id=?",Long.class,source.id())).isEqualTo(1);
    }
    @Test @Transactional
    void rejectsUnknownSelectionStaleVersionAndCrossOrganizationWithoutChangingDraft() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");var draft=createDataset(org,actor);
        var source=imports.create(org,null,document(),actor,"definition-test");
        assertThatThrownBy(()->imports.apply(source.id(),new ApplyDefinition(draft.id(),List.of(UUID.randomUUID())),etag(draft,0),UUID.randomUUID().toString(),actor,"definition-test"))
                .isInstanceOf(IllegalArgumentException.class);
        fields.replace(draft.id(),etag(draft,0),new FieldsInput(List.of()),actor,"definition-test");
        assertThatThrownBy(()->imports.apply(source.id(),new ApplyDefinition(draft.id(),List.of(source.fields().getFirst().id())),etag(draft,0),UUID.randomUUID().toString(),actor,"definition-test"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(()->imports.get(source.id(),actor(organization(),"ORG_EDITOR"))).isInstanceOf(AccessDeniedException.class);
        assertThat(fields.get(draft.id(),actor).fields()).isEmpty();
    }
    @Test @Transactional
    void repeatedIdempotencyKeyWithDifferentSelectionConflicts() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");var draft=createDataset(org,actor);var source=imports.create(org,draft.id(),document(),actor,"definition-test");
        String key=UUID.randomUUID().toString();imports.apply(source.id(),new ApplyDefinition(draft.id(),List.of(source.fields().getFirst().id())),etag(draft,0),key,actor,"definition-test");
        assertThatThrownBy(()->imports.apply(source.id(),new ApplyDefinition(draft.id(),List.of(source.fields().getLast().id())),etag(draft,1),key,actor,"definition-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("멱등");
    }
    // Real request transactions: failure evidence is deliberately committed independently of import rollback.
    @Test
    void multipartEndpointRequiresTrustedAuthenticationAndParsesRealBytes() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");var file=new MockMultipartFile("file","definition.json","application/json",document());
        mvc.perform(multipart("/api/admin/v1/definition-imports").file(file).param("organizationId",org.toString()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/v1/definition-imports").file(file).param("organizationId",org.toString()).with(authentication(actor)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fieldCount").value(2)).andExpect(jsonPath("$.fields[0].sourcePointer").value("/fields/0"))
                .andExpect(jsonPath("$.fields[0].confidence.score").value(100)).andExpect(jsonPath("$.diagnostics.generatedFieldCount").value(2));
        var invalid=new MockMultipartFile("file","definition.json","application/json","{\"fields\":[null]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/admin/v1/definition-imports").file(invalid).param("organizationId",org.toString()).with(authentication(actor)).with(csrf()))
                .andExpect(status().isBadRequest());
    }
    @Test @Transactional
    void databaseRejectsImportAttachedToAnotherOrganizationsDataset() throws Exception {
        UUID org=organization();UUID otherOrg=organization();var draft=createDataset(otherOrg,actor(otherOrg,"ORG_EDITOR"));
        assertThatThrownBy(()->db.update("INSERT INTO standard.definition_import(id,inst_id,dtset_id,rqstr_id,sou_hash_vl,sou_sz,prsr_ver_nm,stts_cd,data_artcl_cnt,wrng_cn) VALUES (?,?,?,?,?,10,'test','VALIDATED',1,'[]')",
                UUID.randomUUID(),org,draft.id(),UUID.randomUUID(),"0".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test @Transactional
    void databaseRejectsApplicationWithForeignSourceField() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");var draft=createDataset(org,actor);var source=imports.create(org,draft.id(),document(),actor,"definition-test");
        assertThatThrownBy(()->db.update("INSERT INTO standard.definition_application(id,dfn_inpt_id,dtset_id,rvsn_id,rqstr_id,idmpt_key_hash_vl,dmnd_hash_vl,chc_data_artcl_id_cn,rslt_cn) VALUES (?,?,?,?,?,?,?,?::jsonb,'{}')",
                UUID.randomUUID(),source.id(),draft.id(),draft.revisionId(),UUID.randomUUID(),"1".repeat(64),"2".repeat(64),"[\""+UUID.randomUUID()+"\"]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test @Transactional
    void inactiveOrganizationCannotImport() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR");db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        byte[] content=document();assertThatThrownBy(()->imports.create(org,null,content,actor,"definition-test")).isInstanceOf(AccessDeniedException.class);
    }
    @Test @Transactional
    void reviewRequiredEvidenceSurvivesStorageAndSelectionWithoutFillingTheMissingDescription() throws Exception {
        UUID org=organization();var actor=actor(org,"ORG_EDITOR","ORG_AUDITOR");var draft=createDataset(org,actor);
        var tree=json.readTree(document());((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("fields").get(1)).put("description","");
        var source=imports.create(org,draft.id(),json.writeValueAsBytes(tree),actor,"definition-score-test");
        assertThat(source.fields()).extracting(f->f.confidence().score()).containsExactly(100,80);
        assertThat(imports.evidence(source.id(),actor).getLast().confidence().level()).isEqualTo("DESCRIPTION_REVIEW_REQUIRED");
        assertThat(imports.analysisDiagnostics(source.analysisRunId(),actor).assessments().getLast().confidence().score()).isEqualTo(80);
        var result=imports.apply(source.id(),new ApplyDefinition(draft.id(),List.of(source.fields().getLast().id())),etag(draft,0),UUID.randomUUID().toString(),actor,"definition-score-test");
        assertThat(result.fields().fields()).hasSize(1);assertThat(result.fields().fields().getFirst().description()).isEmpty();
        assertThat(metadata.getDraft(draft.id(),actor).state()).isEqualTo("DRAFT");
        mvc.perform(get("/api/admin/v1/analysis-runs/"+source.analysisRunId()+"/definition-diagnostics").with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETE")).andExpect(jsonPath("$.assessments[1].confidence.score").value(80));
        mvc.perform(get("/api/admin/v1/analysis-runs/"+source.analysisRunId()+"/definition-diagnostics").with(authentication(actor(organization(),"ORG_AUDITOR"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/v1/analysis-runs/"+source.analysisRunId()+"/definition-diagnostics").with(authentication(actor(org,"ORG_EDITOR"))))
                .andExpect(status().isForbidden());
    }
    private byte[] document() throws Exception {
        return json.writeValueAsBytes(Map.of("fields",List.of(
                new FieldDefinition("temperature","temperature","온도","측정 온도","NUMBER",true,null,"C",0,true,null,null),
                new FieldDefinition("count","count","건수","측정 건수","INTEGER",false,null,null,1,true,null,null))));
    }
    private MetadataDraft createDataset(UUID org,Authentication actor) { return metadata.create(new CreateDataset(org,"definition-"+UUID.randomUUID(),"정의서 시험","설명",org,null,"PRIVATE"),UUID.randomUUID().toString(),actor,"definition-test"); }
    private String etag(MetadataDraft draft,long version) { return GovernanceSupport.etag("dataset",draft.id().toString(),version); }
    private UUID organization() { var org=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",org,org.toString(),"정의서 시험 기관");return org; }
    private Authentication actor(UUID org,String... roles) { return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),"unused",Arrays.stream(roles).map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList()); }
}

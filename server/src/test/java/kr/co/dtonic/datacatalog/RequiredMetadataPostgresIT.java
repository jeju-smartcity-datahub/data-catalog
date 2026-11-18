package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic API/DB regressions. Legacy rows are prepared only in the owned isolated test database. */
@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class RequiredMetadataPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }
    private final UUID editor=UUID.randomUUID(),reviewer=UUID.randomUUID(),publisher=UUID.randomUUID();

    @Test void incompletePrivateWorkingDraftIsSavedButCannotEnterRegistrationReview() throws Exception {
        UUID org=organization();var input=payload(org,"PRIVATE");input.put("description","");input.remove("details");
        var draft=create(org,input);
        mvc.perform(get("/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft").with(authentication(identity(org,editor,"ORG_EDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DRAFT"));
        mvc.perform(submit(org,draft)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REQUIRED_METADATA_MISSING"))
                .andExpect(jsonPath("$.detail").value("등록 필수항목을 입력하세요: 설명, 담당부서, 라이선스, 키워드. 초안은 보관할 수 있습니다."));
        assertThat(db.queryForObject("SELECT count(*) FROM metadata.review_request WHERE dtset_id=?",Long.class,uuid(draft,"id"))).isZero();
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.path("id").asText())).andExpect(status().isNotFound());
    }

    @Test void nullBlankAndEmptyRequiredValuesAreIdentifiedBeforeSubmission() throws Exception {
        UUID org=organization();var cases=new ArrayList<Map.Entry<Map<String,Object>,String>>();
        for(String field:List.of("department","license")) for(Object value:new Object[]{null,""," \t\n","\u00a0"}) {
            var input=payload(org,"PUBLIC");details(input).put(field,value);cases.add(Map.entry(input,field.equals("department")?"담당부서":"라이선스"));
        }
        for(Object value:new Object[]{null,List.of()}) {
            var input=payload(org,"PUBLIC");details(input).put("keywords",value);cases.add(Map.entry(input,"키워드"));
        }
        for(String value:List.of(""," \n","\u00a0\u2007\u202f")) {
            var input=payload(org,"PUBLIC");input.put("description",value);cases.add(Map.entry(input,"설명"));
        }
        for(var item:cases) {
            var draft=create(org,item.getKey());
            var response=mvc.perform(submit(org,draft)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("REQUIRED_METADATA_MISSING")).andReturn();
            assertThat(body(response).path("detail").asText()).contains(item.getValue()).doesNotContain("safe-complete-description");
            assertThat(read(org,draft).path("state").asText()).isEqualTo("DRAFT");
        }
    }

    @Test void existingBeanAndNormalizationGuardsStillRejectMissingCoreValuesAndBlankKeywordEntries() throws Exception {
        UUID org=organization();
        for(String field:List.of("description","providerOrganizationId","visibility")) {
            var input=payload(org,"PRIVATE");input.put(field,null);
            mvc.perform(write(post("/api/admin/v1/datasets"),org,editor,"ORG_EDITOR").header("Idempotency-Key",key()).content(json.writeValueAsString(input)))
                    .andExpect(status().isBadRequest());
        }
        var invalidScope=payload(org,"PRIVATE");invalidScope.put("visibility","UNKNOWN");
        mvc.perform(write(post("/api/admin/v1/datasets"),org,editor,"ORG_EDITOR").header("Idempotency-Key",key()).content(json.writeValueAsString(invalidScope)))
                .andExpect(status().isBadRequest());
        var blankKeyword=payload(org,"PRIVATE");details(blankKeyword).put("keywords",List.of(""));
        mvc.perform(write(post("/api/admin/v1/datasets"),org,editor,"ORG_EDITOR").header("Idempotency-Key",key()).content(json.writeValueAsString(blankKeyword)))
                .andExpect(status().isBadRequest());
    }

    @Test void completeMetadataAllowsSameReviewerAndPublishesNormally() throws Exception {
        UUID org=organization();var draft=create(org,payload(org,"PUBLIC"));
        var review=body(mvc.perform(submit(org,draft)).andExpect(status().isCreated()).andReturn());
        var decision=body(mvc.perform(decide(org,review,editor,"APPROVE")).andExpect(status().isOk()).andReturn());
        var approved=read(org,draft);
        mvc.perform(publish(org,approved,decision.path("decisionId").asText())).andExpect(status().isCreated());
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.path("id").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.description").value("safe-complete-description"));
    }

    @Test void incompleteReplacementDoesNotChangeTheExistingPublishedSnapshot() throws Exception {
        UUID org=organization();var draft=create(org,payload(org,"PUBLIC"));
        var review=body(mvc.perform(submit(org,draft)).andExpect(status().isCreated()).andReturn());
        var decision=body(mvc.perform(decide(org,review,reviewer,"APPROVE")).andExpect(status().isOk()).andReturn());
        var approved=read(org,draft);var publication=body(mvc.perform(publish(org,approved,decision.path("decisionId").asText())).andExpect(status().isCreated()).andReturn());
        String path="/api/catalog/v1/datasets/"+draft.path("id").asText();
        JsonNode original=body(mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
        var copy=body(mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/drafts"),org,editor,"ORG_EDITOR")
                .header("If-Match",etag("dataset",read(org,draft))).header("Idempotency-Key",key())).andExpect(status().isOk()).andReturn());
        var edit=payload(org,"PUBLIC");edit.remove("ownerOrganizationId");edit.remove("slug");details(edit).put("license",null);
        var changed=body(mvc.perform(write(patch("/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft"),org,editor,"ORG_EDITOR")
                .header("If-Match",etag("dataset",copy)).content(json.writeValueAsString(edit))).andExpect(status().isOk()).andReturn());
        mvc.perform(submit(org,changed)).andExpect(status().isUnprocessableEntity());
        assertThat(body(mvc.perform(get(path)).andExpect(status().isOk()).andReturn())).isEqualTo(original);
        assertThat(db.queryForObject("SELECT now_pstg_id FROM catalog.dataset WHERE id=?",UUID.class,uuid(draft,"id"))).isEqualTo(uuid(publication,"id"));
    }

    @Test void aLegacyIncompleteReviewCannotBeApprovedButCanBeRejected() throws Exception {
        UUID org=organization();var input=payload(org,"PRIVATE");details(input).put("license",null);var draft=create(org,input);
        JsonNode review=legacySubmitted(org,draft);
        mvc.perform(decide(org,review,reviewer,"APPROVE")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REQUIRED_METADATA_MISSING"));
        assertThat(db.queryForObject("SELECT count(*) FROM metadata.review_decision WHERE rvw_dmnd_id=?",Long.class,uuid(review,"id"))).isZero();
        mvc.perform(decide(org,review,reviewer,"REJECT")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test void aLegacyIncompleteApprovedRevisionCannotBePublishedDirectly() throws Exception {
        UUID org=organization();var input=payload(org,"PUBLIC");details(input).put("keywords",List.of());var draft=create(org,input);
        JsonNode review=legacySubmitted(org,draft);UUID decision=UUID.randomUUID();
        // These labelled legacy workflow rows exist only inside this disposable test transaction.
        db.update("INSERT INTO metadata.review_decision(id,rvw_dmnd_id,rvw_cn_hash_vl,dcsn_cd,rvwr_id) VALUES (?,?,?,'APPROVE',?)",decision,uuid(review,"id"),review.path("contentHash").asText(),reviewer);
        db.update("UPDATE metadata.review_request SET stts_cd='APPROVED',lck_ver_no=lck_ver_no+1 WHERE id=?",uuid(review,"id"));
        db.update("UPDATE catalog.dataset_revision SET stts_cd='APPROVED' WHERE id=?",uuid(draft,"revisionId"));
        var approved=read(org,draft);
        mvc.perform(publish(org,approved,decision.toString())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REQUIRED_METADATA_MISSING"));
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.publication WHERE dtset_id=?",Long.class,uuid(draft,"id"))).isZero();
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.path("id").asText())).andExpect(status().isNotFound());
    }

    private JsonNode legacySubmitted(UUID org,JsonNode draft) throws Exception {
        String canonical=db.queryForObject("SELECT jsonb_build_object('title',ttl,'description',expln,'providerOrganizationId',pvsn_inst_id,'primaryCategoryId',main_clsf_id,'visibility',rls_rng_cd,'metadata',mtdt_cn)::text FROM catalog.dataset_revision WHERE id=?",String.class,uuid(draft,"revisionId"));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));UUID review=UUID.randomUUID();
        db.update("UPDATE catalog.dataset_revision SET stts_cd='IN_REVIEW',cn_hash_vl=? WHERE id=?",hash,uuid(draft,"revisionId"));
        db.update("INSERT INTO metadata.review_request(id,dtset_id,rvsn_id,cn_hash_vl,stts_cd,sbmsn_user_id) VALUES (?,?,?,?,'SUBMITTED',?)",review,uuid(draft,"id"),uuid(draft,"revisionId"),hash,editor);
        return body(mvc.perform(get("/api/admin/v1/review-requests/"+review).with(authentication(identity(org,reviewer,"ORG_REVIEWER")))).andExpect(status().isOk()).andReturn());
    }
    private Map<String,Object> payload(UUID org,String visibility) {
        var details=new LinkedHashMap<String,Object>();details.put("department","합성 관리부서");details.put("license","합성 검증 이용조건");details.put("keywords",List.of("합성","메타데이터"));
        var input=new LinkedHashMap<String,Object>();input.put("ownerOrganizationId",org);input.put("slug","required-"+UUID.randomUUID());input.put("title","필수항목 검증");input.put("description","safe-complete-description");input.put("providerOrganizationId",org);input.put("visibility",visibility);input.put("details",details);return input;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> details(Map<String,Object> input) { return (Map<String,Object>)input.get("details"); }
    private JsonNode create(UUID org,Map<String,Object> input) throws Exception { return body(mvc.perform(write(post("/api/admin/v1/datasets"),org,editor,"ORG_EDITOR").header("Idempotency-Key",key()).content(json.writeValueAsString(input))).andExpect(status().isCreated()).andReturn()); }
    private JsonNode read(UUID org,JsonNode draft) throws Exception { return body(mvc.perform(get("/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft").with(authentication(identity(org,editor,"ORG_EDITOR")))).andExpect(status().isOk()).andReturn()); }
    private MockHttpServletRequestBuilder submit(UUID org,JsonNode draft) { return write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/review-requests"),org,editor,"ORG_EDITOR").header("If-Match",etag("dataset",draft)).header("Idempotency-Key",key()); }
    private MockHttpServletRequestBuilder decide(UUID org,JsonNode review,UUID actor,String decision) throws Exception { return write(post("/api/admin/v1/review-requests/"+review.path("id").asText()+"/decisions"),org,actor,"ORG_REVIEWER").header("If-Match",etag("review",review)).header("Idempotency-Key",key()).content(json.writeValueAsString(Map.of("decision",decision,"reason","합성 검증 사유"))); }
    private MockHttpServletRequestBuilder publish(UUID org,JsonNode draft,String decision) throws Exception { return write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/publications"),org,publisher,"ORG_PUBLISHER").header("If-Match",etag("dataset",draft)).header("Idempotency-Key",key()).content(json.writeValueAsString(Map.of("revisionId",draft.path("revisionId").asText(),"reviewDecisionId",decision))); }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,UUID org,UUID actor,String role) { return request.with(authentication(identity(org,actor,role))).with(csrf()).contentType("application/json"); }
    private UsernamePasswordAuthenticationToken identity(UUID org,UUID actor,String role) { return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
    private UUID organization() { UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"합성 필수항목 기관");return id; }
    private String key() { return UUID.randomUUID().toString(); }
    private UUID uuid(JsonNode value,String field) { return UUID.fromString(value.path(field).asText()); }
    private String etag(String kind,JsonNode value) { return "\""+kind+":"+value.path("id").asText()+":"+value.path("lockVersion").asLong()+"\""; }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
}

package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class MetadataWorkflowPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    private final UUID editor=UUID.randomUUID(),reviewer=UUID.randomUUID(),publisher=UUID.randomUUID();

    @Test void completeWorkflowPublishesOnlyAfterApprovalAndWithdrawsImmediately() throws Exception {
        UUID org=organization();var draft=create(org,"PUBLIC");String id=draft.path("id").asText();
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isNotFound());
        var review=submit(org,draft,"submit-first");
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isNotFound());
        var decision=decide(org,review,"APPROVE",reviewer);
        var approved=readDraft(org,id);
        var publication=publish(org,approved,decision);
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("검토용 메타데이터"));
        assertThat(publication.path("accessState").asText()).isEqualTo("METADATA_ONLY");
        // An unpublished edit must never change the public snapshot.
        var copied=body(mvc.perform(write(post("/api/admin/v1/datasets/"+id+"/drafts"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",readDraft(org,id))).header("Idempotency-Key","copy-draft-1"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(write(patch("/api/admin/v1/datasets/"+id+"/draft"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",copied)).content(editPayload(org,"미공개 새 제목")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("검토용 메타데이터"));
        mvc.perform(write(post("/api/admin/v1/publications/"+publication.path("id").asText()+"/withdrawal"),org,publisher,"ORG_PUBLISHER")
                .header("If-Match",tag("publication",publication)).header("Idempotency-Key","withdraw-first")
                .content("{\"reason\":\"기관 공개 정책 변경\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WITHDRAWN"));
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_event WHERE dtset_id=?",Integer.class,UUID.fromString(id))).isEqualTo(7);
    }
    @Test void draftEditRequiresCurrentStrongEtagAndPreservesChangesOnConflict() throws Exception {
        UUID org=organization();var draft=create(org,"PRIVATE");String path="/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft";
        mvc.perform(write(patch(path),org,editor,"ORG_EDITOR").content(editPayload(org,"첫 수정")))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(write(patch(path),org,editor,"ORG_EDITOR").header("If-Match",tag("dataset",draft)).content(editPayload(org,"첫 수정")))
                .andExpect(status().isOk());
        mvc.perform(write(patch(path),org,editor,"ORG_EDITOR").header("If-Match",tag("dataset",draft)).content(editPayload(org,"두 번째 수정")))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(readDraft(org,draft.path("id").asText()).path("title").asText()).isEqualTo("첫 수정");
    }
    @Test void idempotentSubmissionReturnsSameResourceAndDifferentPayloadConflicts() throws Exception {
        UUID org=organization();var draft=create(org,"PUBLIC");var first=submit(org,draft,"same-request");var repeated=submit(org,draft,"same-request");
        assertThat(repeated).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM metadata.review_request WHERE dtset_id=?",Integer.class,UUID.fromString(draft.path("id").asText()))).isEqualTo(1);
        mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/review-requests"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",readDraft(org,draft.path("id").asText()))).header("Idempotency-Key","same-request"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void creatorLastEditorAndSubmitterCanApproveWithReviewerOrPlatformAuthority() throws Exception {
        UUID org=organization(),lastEditor=UUID.randomUUID(),submitter=UUID.randomUUID();
        for(String role:List.of("ORG_REVIEWER","PLATFORM_ADMIN")) for(UUID decisionActor:List.of(editor,lastEditor,submitter)) {
            var draft=create(org,"PUBLIC");
            var changed=body(mvc.perform(write(patch("/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft"),org,lastEditor,"ORG_EDITOR")
                    .header("If-Match",tag("dataset",draft)).content(editPayload(org,"검토용 메타데이터"))).andExpect(status().isOk()).andReturn());
            var review=body(mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/review-requests"),org,submitter,"ORG_EDITOR")
                    .header("If-Match",tag("dataset",changed)).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isCreated()).andReturn());
            String key=UUID.randomUUID().toString(),path="/api/admin/v1/review-requests/"+review.path("id").asText()+"/decisions";
            var decision=body(mvc.perform(write(post(path),org,decisionActor,role).header("If-Match",tag("review",review))
                    .header("Idempotency-Key",key).content("{\"decision\":\"APPROVE\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andReturn());
            var repeated=body(mvc.perform(write(post(path),org,decisionActor,role).header("If-Match",tag("review",review))
                    .header("Idempotency-Key",key).content("{\"decision\":\"APPROVE\"}"))
                    .andExpect(status().isOk()).andReturn());
            assertThat(repeated).isEqualTo(decision);
            assertThat(jdbc.queryForObject("SELECT rvwr_id FROM metadata.review_decision WHERE id=?",UUID.class,UUID.fromString(decision.path("decisionId").asText()))).isEqualTo(decisionActor);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM metadata.review_decision WHERE rvw_dmnd_id=?",Integer.class,UUID.fromString(review.path("id").asText()))).isEqualTo(1);
        }
    }
    @Test void ownSubmissionStillRequiresReviewerAuthorityOrganizationAndCurrentVersion() throws Exception {
        UUID org=organization(),other=organization();var draft=create(org,"PUBLIC");var review=submit(org,draft,"own-boundaries");
        String path="/api/admin/v1/review-requests/"+review.path("id").asText()+"/decisions";
        mvc.perform(write(post(path),org,editor,"ORG_EDITOR").header("If-Match",tag("review",review))
                .header("Idempotency-Key","own-no-review-role").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(write(post(path),other,editor,"ORG_REVIEWER").header("If-Match",tag("review",review))
                .header("Idempotency-Key","own-wrong-org").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(write(post(path),org,editor,"ORG_REVIEWER").header("If-Match","\"review:"+review.path("id").asText()+":99\"")
                .header("Idempotency-Key","own-stale-version").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isPreconditionFailed());
        decide(org,review,"APPROVE",editor);
    }
    @Test void databaseStillRejectsSelfDecisionForMismatchedContent() throws Exception {
        UUID org=organization();var draft=create(org,"PUBLIC");var review=submit(org,draft,"own-bad-hash");
        assertThatThrownBy(()->jdbc.update("INSERT INTO metadata.review_decision(id,rvw_dmnd_id,rvw_cn_hash_vl,dcsn_cd,rvwr_id) VALUES (?,?,?,'APPROVE',?)",
                UUID.randomUUID(),UUID.fromString(review.path("id").asText()),"0".repeat(64),editor)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void rejectionNeedsReasonAndResubmissionCreatesNewRevision() throws Exception {
        UUID org=organization();var draft=create(org,"PUBLIC");var review=submit(org,draft,"reject-submit");
        mvc.perform(write(post("/api/admin/v1/review-requests/"+review.path("id").asText()+"/decisions"),org,reviewer,"ORG_REVIEWER")
                .header("If-Match",tag("review",review)).header("Idempotency-Key","reject-no-reason").content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isBadRequest());
        decide(org,review,"REJECT",reviewer);
        var rejected=readDraft(org,draft.path("id").asText());
        var copied=body(mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/drafts"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",rejected)).header("Idempotency-Key","copy-rejected"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionNo").value(2)).andReturn());
        assertThat(copied.path("revisionId")).isNotEqualTo(draft.path("revisionId"));
        var newReview=submit(org,copied,"resubmit-revision");assertThat(newReview.path("id")).isNotEqualTo(review.path("id"));
    }
    @Test void crossOrganizationAndUntrustedRoleCannotReadOrMutate() throws Exception {
        UUID org=organization(),other=organization();var draft=create(org,"PRIVATE");String path="/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(actor(other,editor,"ORG_EDITOR"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(user("browser-role").roles("PLATFORM_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(write(patch(path),other,editor,"ORG_EDITOR").header("If-Match",tag("dataset",draft)).content(editPayload(other,"탈취 시도")))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path).with(actor(org,editor,"ORG_EDITOR")).contentType("application/json").content(editPayload(org,"csrf 없음")))
                .andExpect(status().isForbidden());
    }
    @Test void pendingAndApprovedContentCannotBeEditedAndAuditCannotBeRewritten() throws Exception {
        UUID org=organization();var draft=create(org,"PUBLIC");submit(org,draft,"freeze-submission");
        var pending=readDraft(org,draft.path("id").asText());
        mvc.perform(write(patch("/api/admin/v1/datasets/"+draft.path("id").asText()+"/draft"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",pending)).content(editPayload(org,"변경 시도"))).andExpect(status().isConflict());
        assertThatThrownBy(()->jdbc.update("UPDATE catalog.dataset_revision SET ttl='tamper' WHERE id=?",UUID.fromString(draft.path("revisionId").asText())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void auditRequiresAuditorRoleAndOnlyOwnOrganization() throws Exception {
        UUID org=organization();var draft=create(org,"PRIVATE");String path="/api/admin/v1/datasets/"+draft.path("id").asText()+"/audit-events";
        mvc.perform(get(path).with(actor(org,editor,"ORG_EDITOR"))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(actor(org,reviewer,"ORG_AUDITOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("DRAFT_CREATED")).andExpect(jsonPath("$.page.totalElements").value(1));
        assertThatThrownBy(()->jdbc.update("DELETE FROM audit.audit_event WHERE dtset_id=?",UUID.fromString(draft.path("id").asText())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void privateApprovedMetadataRemainsInvisibleAndPublishCannotSkipApproval() throws Exception {
        UUID org=organization();var draft=create(org,"PRIVATE");String id=draft.path("id").asText();
        mvc.perform(write(post("/api/admin/v1/datasets/"+id+"/publications"),org,publisher,"ORG_PUBLISHER")
                .header("If-Match",tag("dataset",draft)).header("Idempotency-Key","unapproved-publish")
                .content(json.writeValueAsString(Map.of("revisionId",draft.path("revisionId").asText(),"reviewDecisionId",UUID.randomUUID()))))
                .andExpect(status().isConflict());
        var decision=decide(org,submit(org,draft,"private-submit"),"APPROVE",reviewer);publish(org,readDraft(org,id),decision);
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isNotFound());
    }
    @Test void sessionContainsServerIdentityAndCsrfAndNoRuntimeLoginIsInstalled() throws Exception {
        UUID org=organization();mvc.perform(get("/api/admin/v1/session").with(actor(org,editor,"ORG_EDITOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(editor.toString()))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN")).andExpect(jsonPath("$.csrfToken").isString())
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/admin/v1/session").header("X-Role","PLATFORM_ADMIN").header("X-User-Id",editor.toString()))
                .andExpect(status().isUnauthorized());
    }
    @Test void inactiveOrganizationIsExcludedFromDraftAndReviewLists() throws Exception {
        UUID org=organization();var draft=create(org,"PRIVATE");submit(org,draft,"inactive-review");
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        mvc.perform(get("/api/admin/v1/datasets").with(actor(org,editor,"ORG_EDITOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/admin/v1/review-requests").with(actor(org,reviewer,"ORG_REVIEWER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/admin/v1/datasets").param("organizationId",org.toString()).with(actor(org,editor,"ORG_EDITOR")))
                .andExpect(status().isForbidden());
    }
    private JsonNode create(UUID org,String visibility) throws Exception {
        return body(mvc.perform(write(post("/api/admin/v1/datasets"),org,editor,"ORG_EDITOR").header("Idempotency-Key",UUID.randomUUID().toString())
                .content(json.writeValueAsString(Map.of("ownerOrganizationId",org,"slug","test-"+UUID.randomUUID(),"title","검토용 메타데이터",
                        "description","검토 전에는 공개하지 않음","providerOrganizationId",org,"visibility",visibility,
                        "details",Map.of("department","시험 담당부서","license","시험용 이용조건","keywords",List.of("검토시험"))))))
                .andExpect(status().isCreated()).andExpect(header().exists("ETag")).andExpect(header().exists("Location")).andReturn());
    }
    private JsonNode submit(UUID org,JsonNode draft,String key) throws Exception {
        return body(mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/review-requests"),org,editor,"ORG_EDITOR")
                .header("If-Match",tag("dataset",draft)).header("Idempotency-Key",key)).andExpect(status().isCreated()).andReturn());
    }
    private JsonNode decide(UUID org,JsonNode review,String decision,UUID actor) throws Exception {
        return body(mvc.perform(write(post("/api/admin/v1/review-requests/"+review.path("id").asText()+"/decisions"),org,actor,"ORG_REVIEWER")
                .header("If-Match",tag("review",review)).header("Idempotency-Key",UUID.randomUUID().toString())
                .content(json.writeValueAsString(Map.of("decision",decision,"reason","검토 사유")))).andExpect(status().isOk()).andReturn());
    }
    private JsonNode publish(UUID org,JsonNode draft,JsonNode decision) throws Exception {
        return body(mvc.perform(write(post("/api/admin/v1/datasets/"+draft.path("id").asText()+"/publications"),org,publisher,"ORG_PUBLISHER")
                .header("If-Match",tag("dataset",draft)).header("Idempotency-Key",UUID.randomUUID().toString())
                .content(json.writeValueAsString(Map.of("revisionId",draft.path("revisionId").asText(),"reviewDecisionId",decision.path("decisionId").asText()))))
                .andExpect(status().isCreated()).andReturn());
    }
    private JsonNode readDraft(UUID org,String id) throws Exception {return body(mvc.perform(get("/api/admin/v1/datasets/"+id+"/draft").with(actor(org,editor,"ORG_EDITOR"))).andExpect(status().isOk()).andReturn());}
    private UUID organization(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"검증 기관");return id;}
    private String editPayload(UUID org,String title) throws Exception {return json.writeValueAsString(Map.of("title",title,"description","변경 설명","providerOrganizationId",org,"visibility","PUBLIC"));}
    private JsonNode body(MvcResult response) throws Exception {return json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
    private static String tag(String kind,JsonNode resource){return "\""+kind+":"+resource.path("id").asText()+":"+resource.path("lockVersion").asLong()+"\"";}
    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,UUID org,UUID user,String role){return request.with(actor(org,user,role)).with(csrf()).contentType("application/json");}
    private static RequestPostProcessor actor(UUID org,UUID user,String role){return authentication(UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(user,Set.of(org)),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
}

package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.ApplyCandidate;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.CandidateDetail;
import kr.co.dtonic.datacatalog.integration.application.*;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.allowed-origins=https://hub-contract.invalid", "catalog.integration.worker-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationPostgresIT.ContractFixture.class)
@Transactional
class IntegrationPostgresIT {
    @Autowired IntegrationService service;
    @Autowired IntegrationWorker worker;
    @Autowired IntegrationRunStore store;
    @Autowired IntegrationMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestAdapter adapter;
    @Autowired IntegrationCandidateService candidates;
    @Autowired MetadataWorkflowService metadata;
    UUID organization;
    Authentication editor;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }
    @BeforeEach void setup() {
        organization = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", organization, organization.toString(), "Integration contract test");
        editor = auth(organization, "ORG_EDITOR");
        adapter.mode = "SUCCESS"; adapter.title = "Contract test dataset"; adapter.description = "Test only metadata"; adapter.calls.set(0);
        adapter.details=Map.of();adapter.fullScan=false;adapter.seenCheckpoints.clear();
    }

    @Test
    void storesConnectionWithWriteOnlySecretsAndScopedOptimisticUpdates() throws Exception {
        var input = input("UNCONTRACTED", false, "server/hub/opaque-reference", 5, 3);
        var view = service.create(editor, input);
        mvc.perform(get("/api/admin/v1/connections/" + view.id()).with(authentication(editor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"connection:" + view.id() + ":0\""))
                .andExpect(jsonPath("$.credentialConfigured").value(true)).andExpect(jsonPath("$.secretReference").doesNotExist())
                .andExpect(jsonPath("$.metadataEndpoint").value("https://hub-contract.invalid/metadata"))
                .andExpect(jsonPath("$.recordsEndpoint").value("https://hub-contract.invalid/metadata"))
                .andExpect(jsonPath("$.endpoint").doesNotExist()).andExpect(jsonPath("$.adapterAvailable").value(false));
        var updated = service.update(editor, view.id(), 0, input("UNCONTRACTED", false, null, 5, 3));
        assertThat(updated.credentialConfigured()).isTrue();
        assertThat(updated.lockVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.update(editor, view.id(), 0, input)).isInstanceOf(DomainException.class);
        mvc.perform(get("/api/admin/v1/connections/" + view.id()).with(authentication(auth(UUID.randomUUID(), "ORG_EDITOR"))))
                .andExpect(status().isNotFound());
        assertThat(service.events(editor, view.id())).hasSize(2);
        assertThat(json.writeValueAsString(service.events(editor, view.id()))).doesNotContain("opaque-reference", "hub-contract.invalid");
    }

    @Test
    void readerCannotMutateAndMissingAdapterReturns422WithoutCreatingSuccess() throws Exception {
        var view = service.create(editor, input("UNCONTRACTED", false, null, 5, 3));
        mvc.perform(post("/api/admin/v1/connections/" + view.id() + "/sync-runs").with(authentication(editor)).with(csrf())
                        .header("Idempotency-Key", "missing-adapter-01"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ADAPTER_UNAVAILABLE"));
        mvc.perform(delete("/api/admin/v1/connections/" + view.id()).with(authentication(auth(organization, "ORG_REVIEWER"))).with(csrf())
                        .header("If-Match", "\"connection:" + view.id() + ":0\""))
                .andExpect(status().isForbidden());
        assertThat(service.runs(editor, view.id())).isEmpty();
    }

    @Test
    void missingSecretStoreReturns503AndInactiveOrganizationCannotReadOrDispatch() throws Exception {
        var view = service.create(editor, input("TEST_CONTRACT", true, "server/hub/test-reference", 5, 3));
        mvc.perform(post("/api/admin/v1/connections/" + view.id() + "/checks").with(authentication(editor)).with(csrf())
                        .header("Idempotency-Key", "missing-secret-01"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SECRET_STORE_UNAVAILABLE"));
        assertThat(service.runs(editor, view.id())).isEmpty();
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", organization);
        mvc.perform(get("/api/admin/v1/connections/" + view.id()).with(authentication(editor))).andExpect(status().isForbidden());
    }

    @Test
    void idempotentDispatchDeduplicatesSourceAndKeepsCandidatesUnpublished() {
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "request-sync-001");
        assertThat(run.phase()).isEqualTo("PREPARING");
        assertThat(run.processedCount()).isZero();
        assertThat(service.requestRun(editor, connection.id(), "SYNC", "request-sync-001").id()).isEqualTo(run.id());
        assertThatThrownBy(() -> service.requestRun(editor, connection.id(), "SYNC", "request-sync-002")).isInstanceOf(DomainException.class);
        assertThat(worker.runOnce()).isTrue();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(service.run(editor,run.id()).phase()).isEqualTo("COMPLETED");
        assertThat(service.run(editor,run.id()).processedCount()).isEqualTo(1);
        assertThat(service.run(editor,run.id()).savedCount()).isEqualTo(1);
        assertThat(service.run(editor,run.id()).progressAt()).isNotNull();
        service.requestRun(editor, connection.id(), "SYNC", "request-sync-003");
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.source_metadata WHERE sou_lnkg_id=?", Integer.class, connection.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rvsn_no AS revision_no FROM integration.source_metadata WHERE sou_lnkg_id=?", Integer.class, connection.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cand_cn->>'visibility' FROM integration.source_metadata WHERE sou_lnkg_id=?", String.class, connection.id())).isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?", Integer.class, organization)).isZero();
    }

    @Test
    void partialFailureStoresSuccessfulItemsButDoesNotAdvanceFailedPage() {
        adapter.mode = "PARTIAL";
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "partial-sync-001");
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("PARTIAL_FAILED");
        assertThat(service.run(editor,run.id()).processedCount()).isEqualTo(2);
        assertThat(service.run(editor,run.id()).savedCount()).isEqualTo(1);
        assertThat(service.run(editor,run.id()).failedCount()).isEqualTo(1);
        assertThat(service.items(editor, run.id(), 0, 50).items()).extracting("status").containsExactlyInAnyOrder("CANDIDATE", "FAILED");
        assertThat(mapper.checkpoint(connection.id().toString())).isNull();
        adapter.mode = "SUCCESS";
        var retry = service.requestRun(editor, connection.id(), "SYNC", "partial-retry-001");
        worker.runOnce();
        assertThat(service.items(editor, retry.id(), 0, 50).items().getFirst().status()).isEqualTo("UNCHANGED");
        assertThat(mapper.checkpoint(connection.id().toString())).isEqualTo("cursor-1");
    }

    @Test
    void transientFailureHonorsRetryAfterAndRetriesFromDurableCheckpoint() {
        adapter.mode = "RETRY";
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "retry-sync-001");
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("RETRY_WAIT");
        assertThat(service.run(editor,run.id()).phase()).isEqualTo("RETRY_WAIT");
        assertThat(service.run(editor,run.id()).nextAttemptAt()).isAfter(service.run(editor,run.id()).progressAt());
        assertThat(jdbc.queryForObject("SELECT excn_psblty_dt > clock_timestamp()+interval '20 seconds' FROM integration.sync_run WHERE id=?", Boolean.class, run.id())).isTrue();
        jdbc.update("UPDATE integration.sync_run SET excn_psblty_dt=clock_timestamp()-interval '1 second' WHERE id=?", run.id());
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(service.run(editor, run.id()).attempt()).isEqualTo(2);
        assertThat(service.run(editor,run.id()).nextAttemptAt()).isNull();
    }

    @Test
    void authenticationFailureDoesNotRetryOrPersistUpstreamErrorText() {
        adapter.mode = "AUTH_FAILURE";
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "auth-failure-001");
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("FAILED");
        assertThat(service.run(editor, run.id()).errorCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(service.run(editor, run.id()).attempt()).isEqualTo(1);
    }

    @Test
    void timeoutFinishesWithoutUnlimitedRetry() {
        adapter.mode = "TIMEOUT";
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 1, 1));
        var run = service.requestRun(editor, connection.id(), "SYNC", "timeout-sync-001");
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("FAILED");
        assertThat(service.run(editor, run.id()).errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void expiredLeaseIsReclaimedAndOldWorkerCannotCommit() {
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "lease-sync-001");
        var first = store.claim();
        jdbc.update("UPDATE integration.sync_run SET joblse_expry_dt=clock_timestamp()-interval '1 second' WHERE id=?", run.id());
        var second = store.claim();
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(()->store.report(first,"FETCHING_DETAILS",3)).isInstanceOf(IllegalStateException.class);
        store.report(second,"FETCHING_DETAILS",3);
        assertThat(service.run(editor,run.id()).phase()).isEqualTo("FETCHING_DETAILS");
        assertThat(service.run(editor,run.id()).totalCount()).isEqualTo(3);
        var page = new HubAdapter.Page(List.of(HubAdapter.Item.success(new HubAdapter.Metadata("source-1", "r1", "Title", "Description"))), "after-lease", true);
        assertThat(store.savePage(first, page)).isFalse();
        assertThat(mapper.checkpoint(connection.id().toString())).isNull();
        assertThat(store.savePage(second, page)).isTrue();
        assertThat(mapper.checkpoint(connection.id().toString())).isEqualTo("after-lease");
        assertThat(store.savePage(second,page)).isTrue();
        assertThat(service.run(editor,run.id()).processedCount()).isEqualTo(1);
        assertThat(service.run(editor,run.id()).savedCount()).isEqualTo(1);
        assertThat(service.run(editor,run.id()).unchangedCount()).isZero();
    }

    @Test
    void resourceAddressValidationExplainsHowToCorrectInput() throws Exception {
        for(var entry:Map.of("KETI_DATACORE","datasets","NGSI_LD","entities").entrySet()) {
            var input=new ConnectionInput(organization,"Address validation",entry.getKey(),
                    "https://hub-contract.invalid/manager/"+entry.getValue(),null,true,5,1,10);
            mvc.perform(post("/api/admin/v1/connections").with(authentication(editor)).with(csrf())
                    .contentType("application/json").content(json.writeValueAsString(input)))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ENDPOINT_PATH_INVALID"));
        }
    }

    @Test
    void preventsConfigurationMutationDuringRunAndRejectsReusedKeyAfterUpdate() {
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        service.requestRun(editor, connection.id(), "SYNC", "config-sync-001");
        assertThatThrownBy(() -> service.disable(editor, connection.id(), 0)).isInstanceOf(DomainException.class);
        worker.runOnce();
        service.disable(editor, connection.id(), 0);
        assertThatThrownBy(() -> service.requestRun(editor, connection.id(), "SYNC", "config-sync-001")).isInstanceOf(DomainException.class);
    }

    @Test
    void cycleAndInvalidPageFailWithoutAdvancingCheckpoint() {
        adapter.mode = "CYCLE";
        var connection = service.create(editor, input("TEST_CONTRACT", true, null, 5, 3));
        var run = service.requestRun(editor, connection.id(), "SYNC", "cycle-sync-001");
        worker.runOnce();
        assertThat(service.run(editor, run.id()).status()).isEqualTo("FAILED");
        assertThat(service.run(editor, run.id()).errorCode()).isEqualTo("CHECKPOINT_CYCLE");
        assertThat(mapper.checkpoint(connection.id().toString())).isEqualTo("cycle");
    }

    @Test
    void unchangedIncrementalCheckpointIsAllowedAtCompletedPage() {
        var connection=service.create(editor,input("TEST_CONTRACT",true,null,5,3));
        service.requestRun(editor,connection.id(),"SYNC","unchanged-initial-001");worker.runOnce();
        adapter.mode="NO_CHANGES";
        var run=service.requestRun(editor,connection.id(),"SYNC","unchanged-final-001");worker.runOnce();
        assertThat(service.run(editor,run.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(mapper.checkpoint(connection.id().toString())).isEqualTo("cursor-1");
    }

    @Test
    void candidateApplicationCreatesPrivateDraftAndIsIdempotent() {
        var candidate=collectCandidate();
        var application=candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"candidate-apply-001",editor,"candidate-request-001");
        assertThat(candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"candidate-apply-001",editor,"candidate-request-002").id()).isEqualTo(application.id());
        var draft=metadata.getDraft(application.datasetId(),editor);
        assertThat(draft.state()).isEqualTo("DRAFT");assertThat(draft.visibility()).isEqualTo("PRIVATE");
        assertThat(draft.currentPublicationId()).isNull();
        assertThat(draft.title()).isEqualTo(adapter.title);
        assertThat(candidates.detail(candidate.candidate().id(),editor).candidate().state()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.candidate_application WHERE sou_mtdt_id=?",Integer.class,candidate.candidate().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_event WHERE dtset_id=? AND prcs_cd='DRAFT_CREATED'",Integer.class,draft.id())).isEqualTo(1);
    }

    @Test
    void sourceUpdatePreservesManualFieldsThatSourceDidNotChangeAndRejectsRealConflict() {
        var candidate=collectCandidate();
        var applied=candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"candidate-apply-001",editor,"candidate-request-001");
        var draft=metadata.getDraft(applied.datasetId(),editor);
        draft=metadata.edit(draft.id(),new EditDraft("Manual title",draft.description(),draft.providerOrganizationId(),null,draft.visibility()),
                MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),editor,"manual-edit-request");
        adapter.description="Source changed description";
        service.requestRun(editor,candidate.candidate().connectionId(),"SYNC","candidate-collect-002");worker.runOnce();
        var updated=candidates.detail(candidate.candidate().id(),editor);
        assertThat(updated.conflict()).isFalse();assertThat(updated.changes().getFirst().action()).isEqualTo("PRESERVE_CURRENT");
        candidates.apply(updated.candidate().id(),new ApplyCandidate(updated.targetEtag()),updated.etag(),"candidate-apply-002",editor,"candidate-request-002");
        draft=metadata.getDraft(applied.datasetId(),editor);
        assertThat(draft.title()).isEqualTo("Manual title");assertThat(draft.description()).isEqualTo(adapter.description);
        adapter.title="Conflicting source title";
        service.requestRun(editor,candidate.candidate().connectionId(),"SYNC","candidate-collect-003");worker.runOnce();
        var conflict=candidates.detail(candidate.candidate().id(),editor);
        assertThat(conflict.conflict()).isTrue();
        assertThatThrownBy(()->candidates.apply(conflict.candidate().id(),new ApplyCandidate(conflict.targetEtag()),conflict.etag(),"candidate-apply-003",editor,"candidate-request-003"))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("SOURCE_MANUAL_CONFLICT"));
        assertThat(metadata.getDraft(applied.datasetId(),editor).title()).isEqualTo("Manual title");
    }

    @Test
    void candidateAndTargetEtagsRejectStaleComparison() {
        var candidate=collectCandidate();
        adapter.title="Changed after comparison";
        service.requestRun(editor,candidate.candidate().connectionId(),"SYNC","candidate-refresh-001");worker.runOnce();
        assertThatThrownBy(()->candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"stale-candidate-001",editor,"candidate-request-001"))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("STALE_CANDIDATE"));
        var latest=candidates.detail(candidate.candidate().id(),editor);
        var application=candidates.apply(latest.candidate().id(),new ApplyCandidate(null),latest.etag(),"current-candidate-001",editor,"candidate-request-002");
        var before=candidates.detail(candidate.candidate().id(),editor);
        var draft=metadata.getDraft(application.datasetId(),editor);
        metadata.edit(draft.id(),new EditDraft(draft.title(),"Manual description",draft.providerOrganizationId(),null,draft.visibility()),
                MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),editor,"manual-edit-request");
        assertThatThrownBy(()->candidates.apply(before.candidate().id(),new ApplyCandidate(before.targetEtag()),before.etag(),"stale-target-001",editor,"candidate-request-003"))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("STALE_DATASET"));
    }

    @Test
    void collectedUpdateCreatesNewDraftAndPreservesApprovedPublication() {
        var candidate=collectCandidate();
        var application=candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"candidate-apply-001",editor,"candidate-request-001");
        var draft=metadata.getDraft(application.datasetId(),editor);
        draft=completeRegistrationMetadata(draft,draft.visibility());
        var review=metadata.submit(draft.id(),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),"source-submit-001",editor,"submit-request-001");
        var decision=metadata.decide(review.id(),new DecideReview("APPROVE",null),MetadataWorkflowService.etag("review",review.id(),review.lockVersion()),"source-review-001",auth(organization,"ORG_REVIEWER"),"review-request-001");
        draft=metadata.getDraft(application.datasetId(),editor);
        var publication=metadata.publish(draft.id(),new PublishRevision(draft.revisionId(),decision.decisionId()),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),
                "source-publish-001",auth(organization,"ORG_PUBLISHER"),"publish-request-001");
        adapter.title="New source revision";
        service.requestRun(editor,candidate.candidate().connectionId(),"SYNC","candidate-new-source");worker.runOnce();
        var updated=candidates.detail(candidate.candidate().id(),editor);
        var newApplication=candidates.apply(updated.candidate().id(),new ApplyCandidate(updated.targetEtag()),updated.etag(),"candidate-apply-002",editor,"candidate-request-002");
        var next=metadata.getDraft(application.datasetId(),editor);
        assertThat(next.state()).isEqualTo("DRAFT");assertThat(next.revisionNo()).isEqualTo(2);
        assertThat(next.currentPublicationId()).isEqualTo(publication.id());assertThat(next.revisionId()).isEqualTo(newApplication.revisionId());
        assertThat(jdbc.queryForObject("SELECT ttl AS title FROM catalog.dataset_revision WHERE id=?",String.class,publication.revisionId())).isEqualTo("Contract test dataset");
    }

    private CandidateDetail collectCandidate() {
        var connection=service.create(editor,input("TEST_CONTRACT",true,null,5,3));
        service.requestRun(editor,connection.id(),"SYNC","candidate-collect-001");worker.runOnce();
        return candidates.detail(candidates.list(connection.id(),0,20,editor).items().getFirst().id(),editor);
    }

    @Test
    void sourceSchemaAndDescriptorsAreRetainedAndAppliedOnlyToPrivateDraft() {
        adapter.details=hubDetails("Water level in metres");
        var candidate=collectCandidate();
        assertThat(candidate.metadata()).containsEntry("providerOrganization","Jeju external provider").containsKey("fields");
        assertThat(candidate.metadata()).doesNotContainKeys("dataStoreUri","authorization");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,organization)).isZero();
        var application=candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),"schema-apply-001",editor,"schema-request-001");
        var draft=metadata.getDraft(application.datasetId(),editor);
        assertThat(draft.visibility()).isEqualTo("PRIVATE");assertThat(draft.currentPublicationId()).isNull();
        assertThat(draft.providerOrganizationId()).isEqualTo(organization);assertThat(draft.primaryCategoryId()).isNull();
        assertThat(draft.regionIds()).isEmpty();assertThat(draft.details().license()).isEqualTo("CC BY 4.0");
        assertThat(draft.details().keywords()).containsExactly("river","water");assertThat(draft.details().spatialCoverage()).isEqualTo("Jeju");
        var stored=metadata.getDraftMetadata(draft.id(),editor);
        var fields=json.convertValue(stored.get("fields"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {});
        assertThat(fields).hasSize(1);assertThat(fields.getFirst()).containsEntry("fieldPath","water.level").containsEntry("description","Water level in metres").containsEntry("nullable",false);
        assertThat(fields.getFirst()).doesNotContainKey("sourceDataType");
        assertThat(json.valueToTree(stored.get("hubMetadata")).path("fields").get(0).path("sourceDataType").asText()).isEqualTo("Double");
    }

    @Test
    void schemaOnlyUpdateCreatesCandidateAndManualFieldEditsArePreservedOrConflict() {
        adapter.details=hubDetails("Initial meaning");var first=collectCandidate();
        var applied=candidates.apply(first.candidate().id(),new ApplyCandidate(null),first.etag(),"schema-edit-001",editor,"schema-request-001");
        var draft=metadata.getDraft(applied.datasetId(),editor);
        var fields=json.convertValue(metadata.getDraftMetadata(draft.id(),editor).get("fields"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {});
        fields.getFirst().put("description","Manually reviewed meaning");
        metadata.replaceFields(draft.id(),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),fields,editor,"manual-field-request");
        adapter.description="Changed dataset description";service.requestRun(editor,first.candidate().connectionId(),"SYNC","schema-collect-002");worker.runOnce();
        var unchangedSchema=candidates.detail(first.candidate().id(),editor);assertThat(unchangedSchema.conflict()).isFalse();
        candidates.apply(unchangedSchema.candidate().id(),new ApplyCandidate(unchangedSchema.targetEtag()),unchangedSchema.etag(),"schema-edit-002",editor,"schema-request-002");
        assertThat(json.valueToTree(metadata.getDraftMetadata(draft.id(),editor)).path("fields").get(0).path("description").asText()).isEqualTo("Manually reviewed meaning");
        adapter.details=hubDetails("Hub changed meaning");service.requestRun(editor,first.candidate().connectionId(),"SYNC","schema-collect-003");worker.runOnce();
        var conflict=candidates.detail(first.candidate().id(),editor);
        assertThat(conflict.candidate().revisionNo()).isEqualTo(3);assertThat(conflict.sourceHash()).isNotEqualTo(unchangedSchema.sourceHash());
        assertThat(conflict.changes()).anyMatch(c->c.field().equals("fields")&&c.conflict());
        assertThatThrownBy(()->candidates.apply(conflict.candidate().id(),new ApplyCandidate(conflict.targetEtag()),conflict.etag(),"schema-edit-003",editor,"schema-request-003"))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("SOURCE_MANUAL_CONFLICT"));
    }

    @Test
    void schemaUpdateCreatesDraftWithoutChangingPreviouslyPublishedFields() throws Exception {
        adapter.details=hubDetails("Published meaning");var first=collectCandidate();
        var applied=candidates.apply(first.candidate().id(),new ApplyCandidate(null),first.etag(),"publish-schema-001",editor,"schema-request-001");
        var draft=metadata.getDraft(applied.datasetId(),editor);
        draft=completeRegistrationMetadata(draft,"PUBLIC");
        var review=metadata.submit(draft.id(),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),"schema-submit-001",editor,"schema-submit-request");
        var decision=metadata.decide(review.id(),new DecideReview("APPROVE",null),MetadataWorkflowService.etag("review",review.id(),review.lockVersion()),"schema-review-001",auth(organization,"ORG_REVIEWER"),"schema-review-request");
        draft=metadata.getDraft(applied.datasetId(),editor);
        var publication=metadata.publish(draft.id(),new PublishRevision(draft.revisionId(),decision.decisionId()),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),
                "schema-publish-001",auth(organization,"ORG_PUBLISHER"),"schema-publish-request");
        adapter.details=hubDetails("Updated meaning");service.requestRun(editor,first.candidate().connectionId(),"SYNC","schema-published-002");worker.runOnce();
        var next=candidates.detail(first.candidate().id(),editor);assertThat(next.conflict()).isFalse();
        candidates.apply(next.candidate().id(),new ApplyCandidate(next.targetEtag()),next.etag(),"publish-schema-002",editor,"schema-request-002");
        var updated=metadata.getDraft(draft.id(),editor);assertThat(updated.state()).isEqualTo("DRAFT");assertThat(updated.revisionNo()).isEqualTo(2);
        assertThat(updated.currentPublicationId()).isEqualTo(publication.id());
        assertThat(jdbc.queryForObject("SELECT mtdt_cn->'fields'->0->>'description' FROM catalog.dataset_revision WHERE id=?",String.class,publication.revisionId())).isEqualTo("Published meaning");
        assertThat(json.valueToTree(metadata.getDraftMetadata(draft.id(),editor)).path("fields").get(0).path("description").asText()).isEqualTo("Updated meaning");
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.id()+"/fields")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fieldPath").value("water.level")).andExpect(jsonPath("$[0].description").value("Published meaning"));
    }

    @Test
    void fullScanStartsEachRunFromBeginningButRetriesResumeCurrentRun() {
        adapter.fullScan=true;adapter.mode="FULL_SCAN_RETRY";
        var connection=service.create(editor,input("TEST_CONTRACT",true,null,5,3));
        var run=service.requestRun(editor,connection.id(),"SYNC","full-scan-001");worker.runOnce();
        assertThat(service.run(editor,run.id()).status()).isEqualTo("RETRY_WAIT");
        jdbc.update("UPDATE integration.sync_run SET excn_psblty_dt=clock_timestamp()-interval '1 second' WHERE id=?",run.id());worker.runOnce();
        assertThat(service.run(editor,run.id()).status()).isEqualTo("SUCCEEDED");
        service.requestRun(editor,connection.id(),"SYNC","full-scan-002");worker.runOnce();
        assertThat(adapter.seenCheckpoints).containsExactly("<start>","page-1","page-1","<start>","page-1");
    }

    /** The operator supplements registration-only details; collected source fields remain intact. */
    private MetadataDraft completeRegistrationMetadata(MetadataDraft draft,String visibility) {
        var current=draft.details();
        var details=new MetadataDetails(current.department()==null?"시험 담당부서":current.department(),
                current.license()==null?"시험용 이용조건":current.license(),current.updateFrequency(),current.spatialCoverage(),
                current.temporalStart(),current.temporalEnd(),current.keywords().isEmpty()?List.of("수집시험"):current.keywords());
        return metadata.edit(draft.id(),new EditDraft(draft.title(),draft.description(),draft.providerOrganizationId(),draft.primaryCategoryId(),visibility,
                null,null,null,details),MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()),editor,"registration-details-request");
    }

    private Map<String,Object> hubDetails(String description) {
        var field=new LinkedHashMap<String,Object>();
        field.put("fieldPath","water.level");field.put("physicalName","water.level");field.put("displayName","level");field.put("description",description);
        field.put("dataType","NUMBER");field.put("sourceDataType","Double");field.put("nullable",false);field.put("publicVisible",true);field.put("ordinal",0);
        var details=new LinkedHashMap<String,Object>();details.put("providerOrganization","Jeju external provider");details.put("category","Environment");
        details.put("keywords",List.of("water","river"));details.put("targetRegions",List.of("Jeju"));details.put("license","CC BY 4.0");details.put("updateInterval","PT10M");
        details.put("fields",List.of(field));details.put("dataStoreUri","mongodb://do-not-store");details.put("authorization","do-not-store");return details;
    }

    @Test
    void fullScanCollectsLaterPagesAfterItemFailureAndRetainsPartialStateAcrossRetry() {
        for(String mode:List.of("FULL_SCAN_PARTIAL","FULL_SCAN_PARTIAL_RETRY")) {
            adapter.fullScan=true;adapter.mode=mode;adapter.calls.set(0);adapter.seenCheckpoints.clear();
            var connection=service.create(editor,input("TEST_CONTRACT",true,null,5,3));
            var run=service.requestRun(editor,connection.id(),"SYNC","partial-full-scan-001");worker.runOnce();
            if(mode.endsWith("RETRY")) {
                assertThat(service.run(editor,run.id()).status()).isEqualTo("RETRY_WAIT");
                assertThat(mapper.run(run.id().toString()).checkpointAfter()).isEqualTo("page-1");
                jdbc.update("UPDATE integration.sync_run SET excn_psblty_dt=clock_timestamp()-interval '1 second' WHERE id=?",run.id());worker.runOnce();
                assertThat(adapter.seenCheckpoints).containsExactly("<start>","page-1","page-1");
            } else assertThat(adapter.seenCheckpoints).containsExactly("<start>","page-1");
            assertThat(service.run(editor,run.id()).status()).isEqualTo("PARTIAL_FAILED");
            assertThat(service.run(editor,run.id()).errorCode()).isEqualTo("SOURCE_ITEMS_FAILED");
            assertThat(service.items(editor,run.id(),0,50).items()).extracting("status").containsExactlyInAnyOrder("FAILED","CANDIDATE");
            assertThat(candidates.list(connection.id(),0,20,editor).items()).extracting("externalId").containsExactly("source-1");
            assertThat(mapper.checkpoint(connection.id().toString())).isNull();
        }
    }

    private ConnectionInput input(String adapterCode, boolean enabled, String secret, int timeout, int attempts) {
        return new ConnectionInput(organization, "Test connection", adapterCode, "https://hub-contract.invalid/metadata", secret, enabled, timeout, attempts, 10);
    }
    private static Authentication auth(UUID org, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(), Set.of(org)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @TestConfiguration
    static class ContractFixture { @Bean TestAdapter testAdapter() { return new TestAdapter(); } }

    /** This adapter deliberately exists only under src/test. */
    static class TestAdapter implements HubAdapter {
        volatile String mode = "SUCCESS";
        volatile String title = "Contract test dataset";
        volatile String description = "Test only metadata";
        volatile Map<String,Object> details=Map.of();
        volatile boolean fullScan;
        final List<String> seenCheckpoints=new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        public String code() { return "TEST_CONTRACT"; }
        public Set<Capability> capabilities() { return Set.of(Capability.TEST_CONNECTION, Capability.DISCOVER_DATASETS); }
        public boolean resumesFromPreviousRun() { return !fullScan; }
        public void check(Context context) { }
        public Page discover(Context context, String checkpoint) throws AdapterFailure {
            int call = calls.incrementAndGet();
            seenCheckpoints.add(checkpoint==null?"<start>":checkpoint);
            if(mode.equals("FULL_SCAN_RETRY")&&call==2)throw new AdapterFailure(AdapterFailure.Reason.RATE_LIMITED,1);
            if(mode.equals("FULL_SCAN_PARTIAL_RETRY")&&call==2)throw new AdapterFailure(AdapterFailure.Reason.RATE_LIMITED,1);
            if (mode.equals("AUTH_FAILURE")) throw new AdapterFailure(AdapterFailure.Reason.AUTHENTICATION_FAILED, 0);
            if (mode.equals("RETRY") && call == 1) throw new AdapterFailure(AdapterFailure.Reason.RATE_LIMITED, 30);
            if (mode.equals("TIMEOUT")) {
                try { Thread.sleep(1500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            var success = Item.success(new Metadata("source-1", "revision-" + call, title, description,details));
            if(mode.startsWith("FULL_SCAN_PARTIAL"))return checkpoint==null
                    ?new Page(List.of(Item.failure("invalid-1")),"page-1",false):new Page(List.of(success),null,true);
            if(mode.equals("FULL_SCAN_RETRY"))return new Page(List.of(success),checkpoint==null?"page-1":"complete",checkpoint!=null);
            if (mode.equals("PARTIAL")) return new Page(List.of(success, Item.failure("source-2")), "cursor-1", true);
            if (mode.equals("CYCLE")) return new Page(List.of(success), "cycle", false);
            if (mode.equals("NO_CHANGES")) return new Page(List.of(), checkpoint, true);
            return new Page(List.of(success), checkpoint == null ? "cursor-1" : checkpoint + "x", true);
        }
    }
}

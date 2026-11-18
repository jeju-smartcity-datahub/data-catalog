package kr.co.dtonic.datacatalog;

import java.util.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.*;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.ApplyCandidate;
import kr.co.dtonic.datacatalog.integration.api.IntegrationPublicationModels.*;
import kr.co.dtonic.datacatalog.integration.application.*;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.allowed-origins=https://bulk-fixture.invalid","catalog.integration.worker-enabled=false"})
@ActiveProfiles("test")
@Import(IntegrationPublicationPostgresIT.Fixture.class)
class IntegrationPublicationPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired IntegrationService connections;
    @Autowired IntegrationWorker worker;
    @Autowired IntegrationCandidateService candidates;
    @Autowired IntegrationPublicationService publications;
    @Autowired MetadataWorkflowService metadata;
    @Autowired PublicationAdapter adapter;
    UUID org,actor,connection;
    Authentication auth;
    @DynamicPropertySource static void database(DynamicPropertyRegistry values){PostgresDatabase.configure(values);}
    @BeforeEach void setup(){
        org=UUID.randomUUID();actor=UUID.randomUUID();
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",org,org.toString(),"일괄 게시 시험 기관");
        db.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,?,?,'ACTIVE')",actor,"bulk-"+actor,"$2a$12$"+"A".repeat(53));
        for(String role:List.of("ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER"))db.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,?)",actor,role);
        db.update("INSERT INTO identity.local_account_organization(acnt_id,inst_id) VALUES (?,?)",actor,org);
        auth=authentication(actor,org,"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER");
        adapter.title="주차 정보";adapter.description="공영주차장 정보";adapter.secondDescription="";
        adapter.details=Map.of("category","교통","license","CC BY 4.0");adapter.count=2;
        connection=connections.create(auth,input(true)).id();
    }
    // These scenarios need committed transactions. Retire only this test's fixtures so
    // their publications and enabled connections cannot enter later queries or workers.
    @AfterEach void deactivateFixtures(){
        if(org==null)return;
        db.update("UPDATE integration.source_connection SET use_yn=false WHERE inst_id=?",org);
        db.update("UPDATE identity.local_account SET stts_cd='INACTIVE' WHERE id=?",actor);
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
    }
    @Test void commonValuesPublishOnlyReadyItemsAndPreserveSourceLicenseAndClassification(){
        collect();var ids=ids();var before=preview(ids);
        assertThat(before.items()).allSatisfy(i->assertThat(i.missingFields()).contains("담당부서"));
        save(false,"데이터 담당","공공누리 제1유형");
        var request=request(ids);var result=publications.publish(connection,request,key(),auth,"bulk-test");
        assertThat(result.published()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
        var good=result.items().stream().filter(i->i.status().equals("PUBLISHED")).findFirst().orElseThrow();
        var draft=metadata.getDraft(good.datasetId(),auth);
        assertThat(draft.visibility()).isEqualTo("PUBLIC");assertThat(draft.state()).isEqualTo("APPROVED");
        assertThat(draft.details().license()).isEqualTo("CC BY 4.0");assertThat(draft.details().department()).isEqualTo("데이터 담당");
        assertThat(draft.details().keywords()).containsExactly("교통");
        assertThat(db.queryForObject("SELECT nm FROM classification.category WHERE id=?",String.class,draft.primaryCategoryId())).isEqualTo("교통물류");
        assertThat(metadata.getDraftMetadata(draft.id(),auth)).containsKey("hubMetadata");
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isEqualTo(1);
        assertThat(publications.history(connection,auth).items()).hasSize(2);
        assertThat(db.queryForObject("SELECT count(*) FROM classification.category WHERE cd LIKE 'PUBLIC-%'",Integer.class)).isEqualTo(16);
    }
    @Test void replayAfterPolicyChangeReturnsOriginalResultAndRejectsChangedRequest(){
        adapter.count=1;collect();save(false,"담당 부서","L1");
        var request=request(ids());String key=key();
        var first=publications.publish(connection,request,key,auth,"replay");assertThat(first.published()).isEqualTo(1);
        save(false,"다른 부서","L2");
        assertThat(publications.publish(connection,request,key,auth,"replay")).isEqualTo(first);
        assertThatThrownBy(()->publications.publish(connection,new PublishCandidatesInput(request.items(),request.policyVersion()+1),key,auth,"changed"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(()->publications.publish(connection,request,key(),auth,"stale-policy"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("STALE_PUBLICATION_POLICY"));
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.publication p JOIN catalog.dataset d ON d.id=p.dtset_id WHERE d.psn_inst_id=?",Integer.class,org)).isEqualTo(1);
    }
    @Test void foreignOrMissingCandidateRejectsWholeBatchBeforeAnyResultsAreWritten(){
        adapter.count=1;collect();save(false,"담당 부서","L1");var own=ids().getFirst();
        UUID original=connection;connection=connections.create(auth,input(true)).id();collect();UUID foreign=ids().getFirst();connection=original;
        var good=request(List.of(own)).items().getFirst();
        for(UUID bad:List.of(foreign,UUID.randomUUID())){
            var request=new PublishCandidatesInput(List.of(good,new PublishCandidateInput(bad,"\"invalid\"",null)),publications.policy(connection,auth).lockVersion());
            assertThatThrownBy(()->publications.publish(connection,request,key(),auth,"foreign"))
                .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("CANDIDATE_NOT_FOUND"));
        }
        assertThat(db.queryForObject("SELECT count(*) FROM integration.publication_batch WHERE lnkg_id=?",Integer.class,connection)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isZero();
    }
    @Test void staleCandidateRollsBackAndEditorAloneCannotPublish(){
        adapter.count=1;collect();save(false,"담당 부서","L1");var request=request(ids());
        adapter.description="변경된 설명";collect();
        var result=publications.publish(connection,request,key(),auth,"stale");
        assertThat(result.items().getFirst().code()).isEqualTo("STALE_CANDIDATE");
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isZero();
        var editor=authentication(actor,org,"ORG_EDITOR");
        assertThatThrownBy(()->publications.preview(connection,new PublicationPreviewInput(ids()),editor)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void automaticQueuePublishesAcrossPollsAndDoesNotDuplicateUnchangedContent(){
        adapter.count=1;save(true,"공통 담당","공통 이용조건");collect();
        assertThat(taskCount()).isEqualTo(1);
        assertThat(publications.history(connection,auth).pendingCount()).isEqualTo(1);
        assertThat(publications.history(connection,auth).runningCount()).isZero();
        assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(publications.history(connection,auth).items()).anySatisfy(i->{assertThat(i.mode()).isEqualTo("AUTO");assertThat(i.status()).isEqualTo("PUBLISHED");});
        assertThat(publications.history(connection,auth).pendingCount()).isZero();
        assertThat(publications.history(connection,auth).runningCount()).isZero();
        UUID dataset=candidates.list(connection,0,20,auth).items().getFirst().datasetId();
        collect();assertThat(taskCount()).isEqualTo(1);assertThat(publications.runAutomaticOnce()).isFalse();
        adapter.description="변경 후 설명";collect();assertThat(taskCount()).isEqualTo(2);
        assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(metadata.getDraft(dataset,auth).description()).isEqualTo("변경 후 설명");
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.publication WHERE dtset_id=?",Integer.class,dataset)).isEqualTo(2);
    }
    @Test void policyDisableAccountRevocationAndConnectionChangesCancelPendingPublication(){
        adapter.count=1;save(true,"담당","조건");collect();save(false,"담당","조건");
        assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(publications.history(connection,auth).items()).anySatisfy(i->assertThat(i.code()).isEqualTo("POLICY_CHANGED"));
        save(true,"담당","조건");db.update("DELETE FROM identity.local_account_role WHERE acnt_id=? AND role_cd='ORG_PUBLISHER'",actor);
        assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(publications.history(connection,auth).items()).anySatisfy(i->assertThat(i.code()).isEqualTo("PUBLICATION_PERMISSION_DENIED"));
        db.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,'ORG_PUBLISHER')",actor);
        save(true,"담당","조건");connections.update(auth,connection,0,input(true));
        assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(publications.history(connection,auth).items()).anySatisfy(i->assertThat(i.code()).isEqualTo("CONNECTION_CHANGED"));
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isZero();
        collect();assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isEqualTo(1);
    }
    @Test void automaticPublicationPreservesManualDraftAndWithdrawnPublication(){
        adapter.count=1;collect();save(false,"담당","조건");var initial=publications.publish(connection,request(ids()),key(),auth,"first");
        UUID id=initial.items().getFirst().datasetId();var draft=metadata.getDraft(id,auth);
        draft=metadata.newDraft(id,etag(draft),key(),auth,"manual");
        save(true,"담당","조건");assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(metadata.getDraft(id,auth).revisionId()).isEqualTo(draft.revisionId());assertThat(metadata.getDraft(id,auth).state()).isEqualTo("DRAFT");
        assertThat(publications.history(connection,auth).items()).anySatisfy(i->assertThat(i.message()).contains("수동 관리 또는 비공개"));
        save(false,"담당","조건");var republished=publications.publish(connection,request(ids()),key(),auth,"manual-publish");
        assertThat(republished.published()).isEqualTo(1);
        UUID publication=republished.items().getFirst().publicationId();
        metadata.withdraw(publication,new Withdrawal("관리자 게시 철회"),MetadataWorkflowService.etag("publication",publication,0),key(),auth,"withdraw");
        save(true,"담당","조건");assertThat(publications.runAutomaticOnce()).isTrue();
        assertThat(metadata.getDraft(id,auth).currentPublicationId()).isNull();
        save(false,"담당","조건");assertThat(publications.publish(connection,request(ids()),key(),auth,"explicit-republish").published()).isEqualTo(1);
    }
    @Test void approvedPrivateRevisionBecomesNewPublicRevisionOnExplicitBatchPublish(){
        adapter.count=1;adapter.details=Map.of("department","허브 담당","license","허브 이용조건","keywords",List.of("주차"));collect();
        var candidate=candidates.detail(ids().getFirst(),auth);
        var applied=candidates.apply(candidate.candidate().id(),new ApplyCandidate(null),candidate.etag(),key(),auth,"apply");
        var draft=metadata.getDraft(applied.datasetId(),auth);assertThat(draft.details().department()).isEqualTo("허브 담당");
        var review=metadata.submit(draft.id(),etag(draft),key(),auth,"private-submit");
        metadata.decide(review.id(),new DecideReview("APPROVE","승인"),MetadataWorkflowService.etag("review",review.id(),review.lockVersion()),key(),auth,"private-approve");
        var result=publications.publish(connection,request(ids()),key(),auth,"make-public");assertThat(result.published()).isEqualTo(1);
        var published=metadata.getDraft(applied.datasetId(),auth);assertThat(published.visibility()).isEqualTo("PUBLIC");assertThat(published.revisionNo()).isEqualTo(2);
        assertThat(published.details().license()).isEqualTo("허브 이용조건");
    }
    @Test void unfinishedBatchDoesNotUseNewPolicyDefaultsAfterRestart(){
        collect();save(false,"기존 부서","기존 조건");var request=request(ids());String key=key();
        var first=publications.publish(connection,request,key,auth,"first");assertThat(first.published()).isEqualTo(1);
        var done=first.items().stream().filter(i->i.status().equals("PUBLISHED")).findFirst().orElseThrow();
        var unfinished=first.items().stream().filter(i->i.status().equals("FAILED")).findFirst().orElseThrow();
        // Represent a restart after the first item committed but before the next item ran.
        db.update("DELETE FROM integration.publication_result WHERE sou_cand_id=?",unfinished.candidateId());
        save(false,"새 부서","새 조건");var retried=publications.publish(connection,request,key,auth,"retry");
        assertThat(retried.items()).contains(done);
        assertThat(retried.items()).anySatisfy(i->{assertThat(i.candidateId()).isEqualTo(unfinished.candidateId());assertThat(i.code()).isEqualTo("POLICY_CHANGED");});
        assertThat(metadata.getDraft(done.datasetId(),auth).details().department()).isEqualTo("기존 부서");
    }
    private ConnectionInput input(boolean enabled){return new ConnectionInput(org,"시험 허브","BULK_TEST","https://bulk-fixture.invalid/metadata",null,enabled,5,1,10);}
    private void collect(){var run=connections.requestRun(auth,connection,"SYNC",key());assertThat(worker.runOnce()).isTrue();assertThat(connections.run(auth,run.id()).status()).isEqualTo("SUCCEEDED");}
    private List<UUID> ids(){return candidates.list(connection,0,100,auth).items().stream().map(i->i.id()).toList();}
    private PublicationPreview preview(List<UUID> ids){return publications.preview(connection,new PublicationPreviewInput(ids),auth);}
    private PublishCandidatesInput request(List<UUID> ids){var preview=preview(ids);return new PublishCandidatesInput(preview.items().stream().map(i->new PublishCandidateInput(i.candidateId(),i.etag(),i.targetEtag())).toList(),preview.policy().lockVersion());}
    private PublicationPolicy save(boolean enabled,String department,String license){var old=publications.policy(connection,auth);return publications.savePolicy(connection,new PublicationPolicyInput(enabled,department,license,List.of(),null),IntegrationPublicationService.policyEtag(old),auth,"policy-test");}
    private int taskCount(){return db.queryForObject("SELECT count(*) FROM integration.auto_publication_task WHERE lnkg_id=?",Integer.class,connection);}
    private static String key(){return UUID.randomUUID().toString();}
    private static String etag(MetadataDraft value){return MetadataWorkflowService.etag("dataset",value.id(),value.lockVersion());}
    private static Authentication authentication(UUID actor,UUID org,String... roles){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,Set.of(org)),null,Arrays.stream(roles).map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList());}
    @TestConfiguration static class Fixture{@Bean PublicationAdapter publicationAdapter(){return new PublicationAdapter();}}
    static class PublicationAdapter implements HubAdapter {
        String title,description,secondDescription;Map<String,Object> details;int count;
        public String code(){return "BULK_TEST";}
        public Set<Capability> capabilities(){return Set.of(Capability.DISCOVER_DATASETS,Capability.TEST_CONNECTION);}
        public void check(Context context){}
        public boolean resumesFromPreviousRun(){return false;}
        public Page discover(Context context,String checkpoint){
            var items=new ArrayList<Item>();items.add(Item.success(new Metadata("first",null,title,description,details)));
            if(count==2)items.add(Item.success(new Metadata("second",null,"설명 미제공 자료",secondDescription,Map.of("category","알 수 없는 분야"))));
            return new Page(items,null,true);
        }
    }
}

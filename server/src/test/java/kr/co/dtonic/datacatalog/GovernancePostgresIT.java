package kr.co.dtonic.datacatalog;

import java.time.OffsetDateTime;
import java.util.*;
import kr.co.dtonic.datacatalog.analytics.AnalyticsService;
import kr.co.dtonic.datacatalog.analytics.SearchEventRecorder;
import kr.co.dtonic.datacatalog.classification.CategoryService;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.DictionaryService;
import kr.co.dtonic.datacatalog.standard.application.DraftFieldsService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipService;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipModels.TermAttributes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernancePostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired CategoryService categories;
    @Autowired DictionaryService dictionaries;
    @Autowired DraftFieldsService fields;
    @Autowired MetadataWorkflowService metadata;
    @Autowired SearchEventRecorder searches;
    @Autowired AnalyticsService analytics;
    @Autowired MockMvc mvc;
    @Autowired DictionaryRelationshipService relationships;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test @Transactional
    void protectsCategoryReferencesAndChecksOptimisticLock() {
        var org=organization();var admin=actor(org,"PLATFORM_ADMIN");
        var category=categories.create(new CategoryInput(null,"cat-"+UUID.randomUUID(),"시험 분야",true,0),admin,"category-test");
        var id=UUID.fromString(category.id());var version=etag("category",category.id(),0);
        categories.update(id,version,new CategoryInput(null,category.code(),"수정 분야",true,1),admin,"category-test");
        assertThatThrownBy(()->categories.update(id,version,new CategoryInput(null,category.code(),"덮어쓰기",true,0),admin,"category-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("다른 사용자");
        createDataset(org,id,actor(org,"ORG_EDITOR"));
        assertThat(categories.get(id,admin).usageCount()).isEqualTo(1);
        assertThatThrownBy(()->categories.delete(id,etag("category",category.id(),1),admin,"category-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->categories.update(id,etag("category",category.id(),1),new CategoryInput(null,category.code(),"수정 분야",false,1),admin,"category-test"))
                .isInstanceOf(DomainException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE trgt_id=?",Long.class,id)).isEqualTo(2);
    }
    @Test @Transactional
    void databaseAlsoRejectsDisablingReferencedCategory() {
        var org=organization();var admin=actor(org,"PLATFORM_ADMIN");
        var category=categories.create(new CategoryInput(null,"cat-"+UUID.randomUUID(),"보호 분야",true,0),admin,"category-test");
        createDataset(org,UUID.fromString(category.id()),actor(org,"ORG_EDITOR"));
        assertThatThrownBy(()->db.update("UPDATE classification.category SET actvtn_yn=false WHERE id=?",UUID.fromString(category.id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test @Transactional
    void dictionaryWorkflowAllowsAuthorApprovalAndLocksApprovedEntries() {
        var org=organization();var editor=actor(org,"ORG_EDITOR","ORG_REVIEWER");
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"기관 용어","1"),editor,"dict-test");
        var entry=dictionaries.createEntry(UUID.fromString(dictionary.id()),word(),editor,"dict-test");var id=UUID.fromString(entry.id());
        dictionaries.submit(id,etag("dictionary-entry",entry.id(),0),editor,"dict-test");
        var approved=dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("APPROVE",null),editor,"dict-test");
        assertThat(approved.state()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy()).isEqualTo(approved.createdBy()).isEqualTo(approved.submittedBy());
        assertThatThrownBy(()->dictionaries.edit(id,etag("dictionary-entry",entry.id(),2),word(),editor,"dict-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->dictionaries.get(id,actor(organization(),"ORG_EDITOR"))).isInstanceOf(AccessDeniedException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE trgt_id=?",Long.class,id)).isEqualTo(3);
    }
    @Test @Transactional
    void lastEditorCanReviewAfterAnotherActorSubmits() {
        var org=organization();var author=actor(org,"ORG_EDITOR");var lastEditor=actor(org,"ORG_EDITOR","ORG_REVIEWER");var submitter=actor(org,"ORG_EDITOR");
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"분리 사전","1"),author,"dict-test");
        var entry=dictionaries.createEntry(UUID.fromString(dictionary.id()),word(),author,"dict-test");var id=UUID.fromString(entry.id());
        dictionaries.edit(id,etag("dictionary-entry",entry.id(),0),word(),lastEditor,"dict-test");
        dictionaries.submit(id,etag("dictionary-entry",entry.id(),1),submitter,"dict-test");
        var approved=dictionaries.decide(id,etag("dictionary-entry",entry.id(),2),new Decision("APPROVE",null),lastEditor,"dict-test");
        assertThat(approved.state()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy()).isEqualTo(approved.updatedBy());
    }
    @Test @Transactional
    void sameActorDictionaryApprovalRetainsRoleOrganizationAndVersionChecks() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"권한 검사","1"),editor,"dict-test");
        var entry=dictionaries.createEntry(UUID.fromString(dictionary.id()),word(),editor,"dict-test");var id=UUID.fromString(entry.id());
        dictionaries.submit(id,etag("dictionary-entry",entry.id(),0),editor,"dict-test");
        assertThatThrownBy(()->dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("APPROVE",null),editor,"dict-test"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("APPROVE",null),actor(organization(),"ORG_REVIEWER"),"dict-test"))
                .isInstanceOf(AccessDeniedException.class);
        var sameActor=UsernamePasswordAuthenticationToken.authenticated(editor.getPrincipal(),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_REVIEWER")));
        assertThatThrownBy(()->dictionaries.decide(id,etag("dictionary-entry",entry.id(),0),new Decision("APPROVE",null),sameActor,"dict-test"))
                .isInstanceOf(DomainException.class);
        var approved=dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("APPROVE",null),sameActor,"dict-test");
        assertThat(approved.decidedBy()).isEqualTo(approved.createdBy());
    }
    @Test @Transactional
    void databaseRejectsApprovedTermMutation() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");var term=approvedTerm(org,editor);
        assertThatThrownBy(()->db.update("UPDATE standard.dictionary_entry SET dfn_cn='changed' WHERE id=?",UUID.fromString(term.id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test @Transactional
    void rejectionRequiresReasonAndPreservesDecision() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");var reviewer=actor(org,"ORG_REVIEWER");
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"기관 용어","1"),editor,"dict-test");
        var entry=dictionaries.createEntry(UUID.fromString(dictionary.id()),word(),editor,"dict-test");var id=UUID.fromString(entry.id());
        dictionaries.submit(id,etag("dictionary-entry",entry.id(),0),editor,"dict-test");
        assertThatThrownBy(()->dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("REJECT"," "),reviewer,"dict-test")).isInstanceOf(IllegalArgumentException.class);
        var rejected=dictionaries.decide(id,etag("dictionary-entry",entry.id(),1),new Decision("REJECT","정의 보완 필요"),reviewer,"dict-test");
        assertThat(rejected.state()).isEqualTo("REJECTED");assertThat(rejected.decisionReason()).isEqualTo("정의 보완 필요");
    }
    @Test @Transactional
    void mapsOnlyApprovedTermsIntoDraftAndIncludesFieldsInReviewSnapshot() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");var approved=approvedTerm(org,editor);
        var draft=createDataset(org,null,editor);
        var definition=field(UUID.fromString(approved.id()),"승인 용어와 물리 타입 일치");
        var saved=fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(definition)),editor,"field-test");
        assertThat(saved.lockVersion()).isEqualTo(1);assertThat(fields.get(draft.id(),editor).fields()).containsExactly(definition);
        assertThatThrownBy(()->fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(definition)),editor,"field-test"))
                .isInstanceOf(DomainException.class);
        var review=metadata.submit(draft.id(),etag("dataset",draft.id().toString(),1),UUID.randomUUID().toString(),editor,"field-test");
        assertThat(review.contentHash()).hasSize(64);
        assertThatThrownBy(()->fields.replace(draft.id(),etag("dataset",draft.id().toString(),2),new FieldsInput(List.of(definition)),editor,"field-test"))
                .isInstanceOf(DomainException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.analysis_run WHERE dtset_rvsn_id=?",Long.class,draft.revisionId())).isEqualTo(1);
    }
    @Test @Transactional
    void rejectsUnapprovedCrossOrganizationAndDuplicateMappings() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");var draft=createDataset(org,null,editor);
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"초안 사전","1"),editor,"dict-test");
        var unapproved=dictionaries.createEntry(UUID.fromString(dictionary.id()),term(),editor,"dict-test");
        assertThatThrownBy(()->fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(field(UUID.fromString(unapproved.id()),"근거"))),editor,"field-test"))
                .isInstanceOf(DomainException.class).hasMessageContaining("승인된");
        var otherOrg=organization();var otherTerm=approvedTerm(otherOrg,actor(otherOrg,"ORG_EDITOR"));
        assertThatThrownBy(()->fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(field(UUID.fromString(otherTerm.id()),"근거"))),editor,"field-test"))
                .isInstanceOf(AccessDeniedException.class);
        var f=field(null,null);
        assertThatThrownBy(()->fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(f,f)),editor,"field-test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test @Transactional
    void exportsSafeDefinitionAndRecordsActualProvenance() {
        var org=organization();var editor=actor(org,"ORG_EDITOR");var draft=createDataset(org,null,editor);
        var field=new FieldDefinition("field","field","=HYPERLINK(\"x\")","줄1\n줄2","STRING",true,null,null,0,true,null,null);
        fields.replace(draft.id(),etag("dataset",draft.id().toString(),0),new FieldsInput(List.of(field)),editor,"export-test");
        var csv=new String(fields.export(draft.id(),"csv",editor),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(");assertThat(csv).startsWith("\uFEFFfieldPath");
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.analysis_run WHERE dtset_rvsn_id=? AND type_cd='DEFINITION_EXPORT'",Long.class,draft.revisionId())).isEqualTo(1);
        assertThatThrownBy(()->fields.export(draft.id(),"csv",actor(organization(),"ORG_EDITOR"))).isInstanceOf(AccessDeniedException.class);
    }
    @Test
    void recordsEverySearchWithIndependentEventAndNoRawQueryOrIp() {
        String request="telemetry-"+UUID.randomUUID();
        var first=searches.record(request,true,Map.of("categoryId",List.of("valid-code"),"q",List.of("personal@example.invalid"),"ip",List.of("127.0.0.1")),0,12);
        var second=searches.record(request,false,Map.of(),2,7);
        assertThat(first).isNotNull();assertThat(second).isNotNull();
        try {
            assertThat(first.eventId()).isNotEqualTo(second.eventId());
            assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_event WHERE dmnd_id=?",Long.class,request)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_event WHERE id IN (?,?)",Long.class,first.eventId(),second.eventId())).isEqualTo(2);
            String filter=db.queryForObject("SELECT nrmlz_srch_cnd_cn::text AS normalized_filters FROM analytics.search_event WHERE id=?",String.class,first.eventId());
            assertThat(filter).contains("categoryId").doesNotContain("valid-code","personal","127.0.0.1","\"q\"","\"ip\"");
            var stats=analytics.statistics(OffsetDateTime.now().minusMinutes(5),OffsetDateTime.now().plusMinutes(1),actor(UUID.randomUUID(),"PLATFORM_ADMIN"));
            assertThat(stats.searchCount()).isGreaterThanOrEqualTo(2);assertThat(stats.zeroResultCount()).isGreaterThanOrEqualTo(1);
            assertThatThrownBy(()->analytics.statistics(OffsetDateTime.now().minusDays(1),OffsetDateTime.now(),actor(UUID.randomUUID(),"ORG_AUDITOR")))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { db.update("DELETE FROM analytics.search_event WHERE id IN (?,?)",first.eventId(),second.eventId()); }
    }
    @Test
    void anonymousRequestsCannotReadGovernanceOrForgeRoleHeaders() throws Exception {
        mvc.perform(get("/api/admin/v1/dictionaries").header("X-Role","PLATFORM_ADMIN")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/categories")).andExpect(status().isUnauthorized());
    }
    private DictionaryEntry approvedTerm(UUID org,Authentication editor) {
        var dictionary=dictionaries.create(new DictionaryInput(org,"dict-"+UUID.randomUUID(),"승인 사전","1"),editor,"dict-test");
        var word=dictionaries.createEntry(UUID.fromString(dictionary.id()),word(),editor,"dict-test");
        var wordId=UUID.fromString(word.id());
        dictionaries.submit(wordId,etag("dictionary-entry",word.id(),0),editor,"dict-test");
        dictionaries.decide(wordId,etag("dictionary-entry",word.id(),1),new Decision("APPROVE",null),actor(org,"ORG_REVIEWER"),"dict-test");
        var domain=dictionaries.createEntry(UUID.fromString(dictionary.id()),new EntryInput("DOMAIN","numeric-temperature","온도 값","숫자 온도","NUMBER"),editor,"dict-test");
        var domainId=UUID.fromString(domain.id());
        dictionaries.submit(domainId,etag("dictionary-entry",domain.id(),0),editor,"dict-test");
        dictionaries.decide(domainId,etag("dictionary-entry",domain.id(),1),new Decision("APPROVE",null),actor(org,"ORG_REVIEWER"),"dict-test");
        var entry=dictionaries.createEntry(UUID.fromString(dictionary.id()),term(),editor,"dict-test");var id=UUID.fromString(entry.id());
        relationships.term(id,etag("dictionary-entry",entry.id(),0),new TermAttributes("temperature",domainId,List.of(wordId)),editor,"dict-test");
        dictionaries.submit(id,etag("dictionary-entry",entry.id(),1),editor,"dict-test");
        return dictionaries.decide(id,etag("dictionary-entry",entry.id(),2),new Decision("APPROVE",null),actor(org,"ORG_REVIEWER"),"dict-test");
    }
    private EntryInput word() { return new EntryInput("WORD","temperature","온도","관측 온도 단어","NUMBER"); }
    private EntryInput term() { return new EntryInput("TERM","temperature","온도","관측 온도","NUMBER"); }
    private FieldDefinition field(UUID term,String evidence) { return new FieldDefinition("temperature","temperature","온도","관측 온도","NUMBER",false,null,"C",0,true,term,evidence); }
    private MetadataDraft createDataset(UUID org,UUID category,Authentication editor) {
        return metadata.create(new CreateDataset(org,"governance-"+UUID.randomUUID(),"업무 시험","설명",org,category,"PRIVATE",null,null,null,
                new MetadataDetails("시험 담당부서","시험용 이용조건",null,null,null,null,List.of("업무시험"))),UUID.randomUUID().toString(),editor,"governance-test");
    }
    private UUID organization() {
        UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"시험 기관");return id;
    }
    private Authentication actor(UUID org,String... roles) {
        var principal=new CatalogPrincipal(UUID.randomUUID(),Set.of(org));
        return UsernamePasswordAuthenticationToken.authenticated(principal,"unused",Arrays.stream(roles).map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList());
    }
    private String etag(String kind,String id,long version) { return GovernanceSupport.etag(kind,id,version); }
}

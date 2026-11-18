package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.analytics.*;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary;
import kr.co.dtonic.datacatalog.catalog.application.CatalogSearchQuery;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static kr.co.dtonic.datacatalog.analytics.SearchHistoryContracts.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class SearchHistoryPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired SearchHistoryRepository repository;
    @Autowired SearchEventRecorder recorder;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    @Test @Transactional void httpSearchReturnsAndRecordsTheSamePublishedVersionWithoutFollowingLaterPublication()throws Exception{
        UUID organization=UUID.randomUUID(),dataset=UUID.randomUUID(),revision=UUID.randomUUID(),nextRevision=UUID.randomUUID(),publication=UUID.randomUUID(),next=UUID.randomUUID();
        String subject="snapshot"+UUID.randomUUID().toString().replace("-","");
        UUID eventId=null;
        try{
            db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",organization,organization.toString(),"Synthetic public provider");
            db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)",dataset,organization,dataset.toString());
            db.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,expln,pvsn_inst_id,rls_rng_cd,mtdt_cn) VALUES (?,?,1,'APPROVED',?,'Synthetic public description',?,'PUBLIC','{}'::jsonb)",revision,dataset,subject,organization);
            db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES (?,?,?,7,'PUBLISHED',clock_timestamp())",publication,dataset,revision);
            db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,dataset);
            var response=json.readTree(mvc.perform(get("/api/catalog/v1/search").param("q",subject)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(response.path("items")).hasSize(1);
            assertThat(response.path("items").get(0).path("publicationNo").asInt()).isEqualTo(7);
            eventId=UUID.fromString(response.path("feedback").path("eventId").asText());
            db.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,expln,pvsn_inst_id,rls_rng_cd,mtdt_cn) VALUES (?,?,2,'APPROVED',?,'Synthetic second public description',?,'PUBLIC','{}'::jsonb)",nextRevision,dataset,subject,organization);
            db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES (?,?,?,8,'PUBLISHED',clock_timestamp())",next,dataset,nextRevision);
            db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",next,dataset);
            assertThat(repository.detail(eventId,OffsetDateTime.now()).returnedResults()).containsExactly(new ResultReference(dataset,7,0));
            String stored=db.queryForObject("SELECT query_repr::text FROM analytics.search_event WHERE id=?",String.class,eventId);
            assertThat(stored).doesNotContain(subject);
        }finally{
            if(eventId!=null){
                UUID captured=eventId;var cleanup=new TransactionTemplate(transactions);
                cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                cleanup.executeWithoutResult(status->db.update("DELETE FROM analytics.search_event WHERE id=?",captured));
            }
        }
    }
    @Test void actualCaptureStoresOnlyControlledQueryAndReturnedPublicVersions()throws Exception{
        UUID dataset=UUID.randomUUID();
        var item=new DatasetSummary(dataset.toString(),"합성 공개자료","합성 설명",new DatasetSummary.NamedReference(UUID.randomUUID().toString(),"합성기관"),null,OffsetDateTime.now(),"METADATA_ONLY",List.of(),List.of(),List.of(),MetadataDetails.empty(),7,null);
        var offer=recorder.capture(true,SearchQueryRepresentation.from(CatalogSearchQuery.parse("제주 홍길동 person@example.invalid")),Map.of("organizationId",List.of(UUID.randomUUID().toString()),"sort",List.of("relevance")),List.of(item),1,9,0,10);
        assertThat(offer).isNotNull();
        try{
            var detail=repository.detail(offer.eventId(),OffsetDateTime.now());
            assertThat(detail.returnedResults()).containsExactly(new ResultReference(dataset,7,0));
            assertThat(detail.queryRepresentation().controlledTerms()).containsExactly("JEJU");
            String stored=db.queryForObject("SELECT row_to_json(e)::text FROM analytics.search_event e WHERE id=?",String.class,offer.eventId());
            assertThat(stored).doesNotContain("홍길동","person@example.invalid",offer.feedbackToken());
            assertThat(db.queryForObject("SELECT nrmlz_srch_cnd_cn::text FROM analytics.search_event WHERE id=?",String.class,offer.eventId())).doesNotContain(dataset.toString());
            assertThat(detail.expiresAt().toInstant()).isEqualTo(SearchRetentionPolicy.expiresAt(detail.occurredAt()).toInstant());
        }finally{db.update("DELETE FROM analytics.search_event WHERE id=?",offer.eventId());}
    }
    @Test @Transactional void ratingIsOnePerEventIdenticalRetryIsIdempotentAndOtherChoiceCannotOverwrite(){
        UUID id=event("2030-01-01T00:00:00Z","2035-01-01T00:00:00Z");String token="a".repeat(43);
        repository.feedback(new FeedbackInput(id,token,Rating.HELPFUL),OffsetDateTime.parse("2030-01-02T00:00:00Z"));
        repository.feedback(new FeedbackInput(id,token,Rating.HELPFUL),OffsetDateTime.parse("2030-01-03T00:00:00Z"));
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_feedback WHERE srch_event_id=?",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->repository.feedback(new FeedbackInput(id,token,Rating.NOT_HELPFUL),OffsetDateTime.parse("2030-01-03T00:00:00Z"))).isInstanceOf(DomainException.class);
        assertThat(db.queryForObject("SELECT srch_feedback_cd FROM analytics.search_feedback WHERE srch_event_id=?",String.class,id)).isEqualTo("HELPFUL");
    }
    @Test @Transactional void wrongOrUnknownCapabilityAndExpiredEventAreRejected(){
        UUID id=event("2030-01-01T00:00:00Z","2035-01-01T00:00:00Z");
        assertThatThrownBy(()->repository.feedback(new FeedbackInput(id,"b".repeat(43),Rating.HELPFUL),OffsetDateTime.parse("2030-01-02T00:00:00Z"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->repository.feedback(new FeedbackInput(UUID.randomUUID(),"a".repeat(43),Rating.HELPFUL),OffsetDateTime.parse("2030-01-02T00:00:00Z"))).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->repository.feedback(new FeedbackInput(id,"a".repeat(43),Rating.HELPFUL),OffsetDateTime.parse("2035-01-01T00:00:00Z"))).isInstanceOf(DomainException.class);
    }
    @Test @Transactional void exactExpiryCascadeDeletesEventResultsAndRatingAndCanRepeat(){
        UUID id=event("2024-02-29T12:34:56Z","2029-02-28T12:34:56Z");UUID keep=event("2024-03-01T00:00:00Z","2029-03-01T00:00:00Z");
        db.update("INSERT INTO analytics.search_result VALUES (?,?,?,?)",id,0,UUID.randomUUID(),3);
        db.update("INSERT INTO analytics.search_feedback VALUES (?,?,?)",id,"HELPFUL",OffsetDateTime.parse("2024-03-01T00:00:00Z"));
        assertThat(repository.purgeExpired(OffsetDateTime.parse("2029-02-28T12:34:55.999999Z"),1000)).isZero();
        assertThat(repository.purgeExpired(OffsetDateTime.parse("2029-02-28T12:34:56Z"),1000)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_result WHERE srch_event_id=?",Integer.class,id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_feedback WHERE srch_event_id=?",Integer.class,id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_event WHERE id=?",Integer.class,keep)).isEqualTo(1);
        assertThat(repository.purgeExpired(OffsetDateTime.parse("2029-02-28T12:34:56Z"),1000)).isZero();
    }
    @Test @Transactional void legacyExpiryIsComputedWithoutMigrationRewriteAndBatchIsBounded(){
        UUID legacy=UUID.randomUUID();db.update("INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt,ocrn_dt) VALUES (?,?,'false','{}',0,1,?)",legacy,legacy.toString(),OffsetDateTime.parse("2024-02-29T00:00:00Z"));
        assertThat(db.queryForObject("SELECT expry_dt IS NULL FROM analytics.search_event WHERE id=?",Boolean.class,legacy)).isTrue();
        assertThat(repository.purgeExpired(OffsetDateTime.parse("2029-02-28T00:00:00Z"),1)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM analytics.search_event WHERE id=?",Integer.class,legacy)).isZero();
    }
    @Test @Transactional void expiredDetailsAreUnavailableAndRecentLegacyIsExplicitlyIncomplete(){
        UUID id=event("2030-01-01T00:00:00Z","2035-01-01T00:00:00Z");
        assertThatThrownBy(()->repository.detail(id,OffsetDateTime.parse("2035-01-01T00:00:00Z"))).isInstanceOf(DomainException.class);
        db.update("UPDATE analytics.search_event SET query_repr=NULL WHERE id=?",id);
        var detail=repository.detail(id,OffsetDateTime.parse("2030-01-02T00:00:00Z"));
        assertThat(detail.legacyIncomplete()).isTrue();assertThat(detail.queryRepresentation()).isNull();assertThat(detail.returnedResults()).isEmpty();
    }
    private UUID event(String occurred,String expires){
        UUID id=UUID.randomUUID();String hash=hex("a".repeat(43));
        db.update("INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt,ocrn_dt,query_repr,expry_dt,feedback_tkn_hash_vl,returned_page,returned_page_sz) VALUES (?,?,'false','{}',0,1,?,?::jsonb,?,?,0,10)",id,id.toString(),OffsetDateTime.parse(occurred),"{\"policyVersion\":\"catalog-search-privacy-v1\",\"intent\":\"BROWSE\",\"controlledTerms\":[],\"hasUnclassifiedTerms\":false}",OffsetDateTime.parse(expires),hash);
        return id;
    }
    private String hex(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}catch(Exception e){throw new IllegalStateException(e);}}
}

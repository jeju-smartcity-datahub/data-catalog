package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.analytics.AnalyticsReportService;
import kr.co.dtonic.datacatalog.analytics.AnalyticsService;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
import static kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AnalyticsReportsPostgresIT {
    @Autowired JdbcTemplate db;@Autowired AnalyticsService analytics;@Autowired AnalyticsReportService reports;
    @Autowired ObjectMapper json;@Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}

    @Test @Transactional
    void searchListStatisticsAndImmutableReportUseTheSameFilters() throws Exception {
        OffsetDateTime from=time("2020-01-01T00:00:00Z"),to=from.plusDays(1);var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");
        search(from.plusHours(1),true,0,"trace-report-1");search(from.plusHours(2),true,5,"trace-report-2");search(from.plusHours(3),false,0,"trace-report-3");
        var input=searchInput("JSON",from,to,true,true);
        var report=reports.create(input,key(),admin,"analytics-test");
        assertThat(report.rowCount()).isEqualTo(1);
        assertThat(analytics.history(from,to,0,50,true,true,admin)).hasSize(1);
        assertThat(analytics.statistics(from,to,true,true,admin).searchCount()).isEqualTo(1);
        byte[] before=reports.download(report.id(),admin).bytes();var content=json.readTree(before);
        assertThat(content.path("statistics").path("searchCount").asInt()).isEqualTo(1);
        assertThat(content.path("rows").get(0).path("requestId").asText()).isEqualTo("trace-report-1");
        assertThat(report.contentHash()).isEqualTo(hash(before));
        assertThat(report.expiresAt()).isEqualTo(report.createdAt().plusHours(24));
        assertThat(report.cutoff()).isEqualTo(report.createdAt());
        search(from.plusHours(4),true,0,"trace-report-4");
        assertThat(analytics.statistics(from,to,true,true,admin).searchCount()).isEqualTo(2);
        assertThat(reports.download(report.id(),admin).bytes()).isEqualTo(before);
        assertThat(new String(before,StandardCharsets.UTF_8)).doesNotContain("rawQuery","queryText","\"q\"","\"ip\"");
    }

    @Test @Transactional
    void analysisFiltersAndStatisticsMatchReportAndLegacyListRemainsAvailable() throws Exception {
        UUID org=organization();var auditor=actor(org,"ORG_AUDITOR");OffsetDateTime from=time("2020-02-01T00:00:00Z"),to=from.plusDays(1);
        UUID selected=analysis(org,from.plusHours(1),"FIELD_MAPPING","engine-v1",3,2);
        analysis(org,from.plusHours(2),"DEFINITION_EXPORT","engine-v2",7,1);
        analysis(org,from.minusHours(1),"FIELD_MAPPING","engine-v1",9,0);
        assertThat(analytics.analyses(org,0,50,auditor)).hasSize(3);
        var list=analytics.analyses(org,0,50,from,to,"FIELD_MAPPING","SUCCEEDED","ENGINE-V1",auditor);
        assertThat(list).extracting(value->value.id()).containsExactly(selected.toString());
        var stats=analytics.analysisStatistics(org,from,to,"FIELD_MAPPING","SUCCEEDED","engine-v1",auditor);
        assertThat(stats).isEqualTo(new AnalysisStatistics(1,1,0,3,2));
        var report=reports.create(analysisInput(org,"JSON",from,to,"FIELD_MAPPING","SUCCEEDED","engine-v1"),key(),auditor,"analytics-test");
        var file=json.readTree(reports.download(report.id(),auditor).bytes());
        assertThat(file.path("rows").size()).isEqualTo(1);
        assertThat(file.path("statistics").path("evidenceCount").asInt()).isEqualTo(2);
        assertThat(report.filters().query()).isEqualTo("engine-v1");
        assertThatThrownBy(()->analytics.analyses(org,0,50,from,to,null,"COMPLETED",null,auditor)).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->analytics.analyses(org,0,50,from,to,null,null,"person@example.invalid",auditor)).isInstanceOf(DomainException.class);
    }

    @Test @Transactional
    void csvQuotesMultilineValuesAndNeutralizesSpreadsheetFormula() {
        UUID org=organization();var auditor=actor(org,"ORG_AUDITOR");var from=time("2020-03-01T00:00:00Z");
        analysis(org,from.plusHours(1),"FIELD_MAPPING"," \t=HYPERLINK(\"x\")\nnext",1,0);
        var report=reports.create(analysisInput(org,"CSV",from,from.plusDays(1),null,null,null),key(),auditor,"analytics-test");
        var text=new String(reports.download(report.id(),auditor).bytes(),StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF\"id\",\"organizationId\"");
        assertThat(text).contains("\"' \t=HYPERLINK(\"\"x\"\")\nnext\"");
        assertThat(report.fileName()).endsWith(".csv");
    }

    @Test @Transactional
    void failureFilterStatisticsAndReportExposeOnlySanitizedErrorCode() throws Exception {
        UUID org=organization();var auditor=actor(org,"ORG_AUDITOR");var from=time("2020-03-02T00:00:00Z");
        analysis(org,from.plusHours(1),"FIELD_MAPPING","engine-v1",3,2);
        db.update("INSERT INTO analytics.analysis_run(id,inst_id,type_cd,engn_ver_nm,rqstr_id,bgng_dt,end_dt,stts_cd,data_artcl_cnt,sou_hash_vl,bss_cn,err_cd) VALUES (?,?,'DEFINITION_IMPORT','field-parser-v1',?,?,?,'FAILED',0,?,'[]'::jsonb,'DEFINITION_PARSE_FAILED')",UUID.randomUUID(),org,UUID.randomUUID(),from.plusHours(2),from.plusHours(2),"b".repeat(64));
        assertThat(analytics.analysisStatistics(org,from,from.plusDays(1),null,null,null,auditor)).isEqualTo(new AnalysisStatistics(2,1,1,3,2));
        var failed=analytics.analyses(org,0,20,from,from.plusDays(1),"DEFINITION_IMPORT","FAILED",null,auditor);
        assertThat(failed).hasSize(1);assertThat(failed.getFirst().errorCode()).isEqualTo("DEFINITION_PARSE_FAILED");
        var report=reports.create(analysisInput(org,"JSON",from,from.plusDays(1),null,"FAILED",null),key(),auditor,"analytics-test");
        var document=json.readTree(reports.download(report.id(),auditor).bytes());
        assertThat(document.path("statistics").path("failedCount").asInt()).isEqualTo(1);
        assertThat(document.path("rows").get(0).path("errorCode").asText()).isEqualTo("DEFINITION_PARSE_FAILED");
        assertThat(document.path("rows").get(0).has("errorMessage")).isFalse();
    }

    @Test @Transactional
    void rechecksOwnerRoleAndActiveOrganizationBeforeMetadataOrFile() {
        UUID org=organization();var auditor=actor(org,"ORG_AUDITOR");var from=time("2020-04-01T00:00:00Z");
        var report=reports.create(analysisInput(org,"JSON",from,from.plusDays(1),null,null,null),key(),auditor,"analytics-test");
        assertThatThrownBy(()->reports.get(report.id(),actor(org,"ORG_AUDITOR"))).isInstanceOf(DomainException.class).satisfies(e->assertThat(((DomainException)e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        var principal=(CatalogPrincipal)auditor.getPrincipal();
        var lostRole=UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        assertThatThrownBy(()->reports.download(report.id(),lostRole)).isInstanceOf(AccessDeniedException.class);
        var wrongOrg=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(principal.userId(),Set.of(UUID.randomUUID())),null,auditor.getAuthorities());
        assertThatThrownBy(()->reports.get(report.id(),wrongOrg)).isInstanceOf(AccessDeniedException.class);
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        assertThatThrownBy(()->reports.get(report.id(),auditor)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->reports.download(report.id(),auditor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test @Transactional
    void expiresBothMetadataAndFileAndDatabaseProtectsSnapshot() {
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");UUID id=UUID.randomUUID();OffsetDateTime old=OffsetDateTime.now().minusDays(2);byte[] file="{}".getBytes(StandardCharsets.UTF_8);
        db.update("""
            INSERT INTO analytics.report(id,rqstr_id,type_cd,file_fmt_cd,inq_cnd_cn,crtr_dt,row_cnt,key_hash_vl,dmnd_hash_vl,cn_hash_vl,file_cn,crt_dt,expry_dt)
            VALUES (?,?,'SEARCH','JSON',CAST(? AS jsonb),?,0,?,?,?,?,?,?)
            """,id,((CatalogPrincipal)admin.getPrincipal()).userId(),"{\"from\":\"2020-01-01T00:00:00Z\",\"to\":\"2020-01-02T00:00:00Z\"}",old,"a".repeat(64),"b".repeat(64),hash(file),file,old,old.plusHours(24));
        assertThatThrownBy(()->reports.get(id,admin)).isInstanceOf(DomainException.class).satisfies(e->assertThat(((DomainException)e).status()).isEqualTo(HttpStatus.GONE));
        assertThatThrownBy(()->reports.download(id,admin)).isInstanceOf(DomainException.class).hasMessageContaining("만료");
        assertThatThrownBy(()->db.update("UPDATE analytics.report SET row_cnt=1 WHERE id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Transactional
    void rejectsInvalidPeriodsKindsAndTooManyRowsWithoutTruncation() {
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");var from=time("2020-05-01T00:00:00Z");
        assertThatThrownBy(()->reports.create(searchInput("JSON",from,from.plusDays(32),null,null),key(),admin,"analytics-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->reports.create(searchInput("JSON",from,from,null,null),key(),admin,"analytics-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->reports.create(searchInput("XML",from,from.plusDays(1),null,null),key(),admin,"analytics-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->reports.create(searchInput("JSON",from,from.plusDays(1),null,null),"short",admin,"analytics-test")).isInstanceOf(DomainException.class);
        db.update("INSERT INTO analytics.search_event(id,dmnd_id,ocrn_dt,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt) SELECT gen_random_uuid(),'limit-report',?,false,'{}'::jsonb,0,0 FROM generate_series(1,5001)",from.plusHours(1));
        assertThatThrownBy(()->reports.create(searchInput("JSON",from,from.plusDays(1),null,null),key(),admin,"analytics-test")).isInstanceOf(DomainException.class).satisfies(e->assertThat(((DomainException)e).code()).isEqualTo("REPORT_LIMIT_EXCEEDED"));
    }

    @Test @Transactional
    void excludesFutureDatedRowsBeyondOneSnapshotCutoff() {
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");var from=OffsetDateTime.now().minusDays(1);var future=OffsetDateTime.now().plusHours(1);
        search(future,true,777,"future-report-event");
        var report=reports.create(searchInput("JSON",from,from.plusDays(2),null,null),key(),admin,"analytics-test");
        assertThat(new String(reports.download(report.id(),admin).bytes(),StandardCharsets.UTF_8)).doesNotContain("future-report-event");
    }

    @Test @Transactional
    void idempotentReplayIsExactAndChangedFiltersConflict() {
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");var from=time("2020-06-01T00:00:00Z");String key=key();
        var input=searchInput("JSON",from,from.plusDays(1),null,null);var first=reports.create(input,key,admin,"analytics-test");
        assertThat(reports.create(input,key,admin,"analytics-test")).isEqualTo(first);
        assertThatThrownBy(()->reports.create(searchInput("CSV",from,from.plusDays(1),null,null),key,admin,"analytics-test")).isInstanceOf(DomainException.class).satisfies(e->assertThat(((DomainException)e).status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void concurrentRequestsCommitExactlyOneSnapshotAndReplayIt() throws Exception {
        // Immutable labelled reports/audit remain in _test; no development data or trigger bypass.
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");var from=time("2020-07-01T00:00:00Z");String key=key();var input=searchInput("JSON",from,from.plusDays(1),null,null);
        var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{start.await(10,TimeUnit.SECONDS);return reports.create(input,key,admin,"analytics-concurrency-test");});
            var b=workers.submit(()->{start.await(10,TimeUnit.SECONDS);return reports.create(input,key,admin,"analytics-concurrency-test");});
            start.countDown();var first=a.get(30,TimeUnit.SECONDS);var second=b.get(30,TimeUnit.SECONDS);
            assertThat(first).isEqualTo(second);
            assertThat(db.queryForObject("SELECT count(*) FROM analytics.report WHERE rqstr_id=?",Integer.class,((CatalogPrincipal)admin.getPrincipal()).userId())).isEqualTo(1);
        }
    }

    @Test @Transactional
    void httpEnforcesTrustedAuthCsrfAndReturnsDownloadHeaders() throws Exception {
        var admin=actor(UUID.randomUUID(),"PLATFORM_ADMIN");var from=time("2020-08-01T00:00:00Z");String body=json.writeValueAsString(searchInput("CSV",from,from.plusDays(1),null,null));
        mvc.perform(post("/api/admin/v1/analytics/reports").with(csrf()).header("X-Role","PLATFORM_ADMIN").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/v1/analytics/reports").with(authentication(admin)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/admin/v1/analytics/reports").with(authentication(admin)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn();
        String location=created.getResponse().getHeader("Location");
        mvc.perform(get(location+"/file").with(authentication(admin))).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().exists("Content-Disposition")).andExpect(header().exists("ETag"));
        mvc.perform(get("/api/admin/v1/analytics/analysis-statistics").with(authentication(admin)).param("organizationId",organization().toString()).param("from",from.toString()).param("to",from.plusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runCount").value(0)).andExpect(jsonPath("$.evidenceCount").value(0));
    }
    private AnalyticsReportRequest searchInput(String format,OffsetDateTime from,OffsetDateTime to,Boolean hasQuery,Boolean zeroResults) { return new AnalyticsReportRequest("SEARCH",format,null,from,to,null,null,null,hasQuery,zeroResults); }
    private AnalyticsReportRequest analysisInput(UUID org,String format,OffsetDateTime from,OffsetDateTime to,String kind,String status,String query) { return new AnalyticsReportRequest("ANALYSIS",format,org,from,to,kind,status,query,null,null); }
    private void search(OffsetDateTime at,boolean hasQuery,long count,String request) { db.update("INSERT INTO analytics.search_event(id,dmnd_id,ocrn_dt,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt) VALUES (?,?,?,?,'{\"categoryId\":[\"safe-code\"]}'::jsonb,?,12)",UUID.randomUUID(),request,at,hasQuery,count); }
    private UUID analysis(UUID org,OffsetDateTime at,String kind,String engine,int fields,int evidence) { UUID id=UUID.randomUUID();db.update("INSERT INTO analytics.analysis_run(id,inst_id,type_cd,engn_ver_nm,rqstr_id,bgng_dt,end_dt,stts_cd,data_artcl_cnt,sou_hash_vl,bss_cn) VALUES (?,?,?,?,?,?,?,'SUCCEEDED',?, ?,CAST(? AS jsonb))",id,org,kind,engine,UUID.randomUUID(),at,at,fields,"a".repeat(64),"["+String.join(",",java.util.Collections.nCopies(evidence,"{}"))+"]");return id; }
    private UUID organization() { UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"[시험] 분석 보고서 기관");return id; }
    private Authentication actor(UUID org,String...roles) { return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList()); }
    private static String key(){return UUID.randomUUID().toString();}
    private static OffsetDateTime time(String value){return OffsetDateTime.parse(value);}
    private static String hash(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);} }
}

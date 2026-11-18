package kr.co.dtonic.datacatalog;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.delivery.application.DatasetRecordsService;
import kr.co.dtonic.datacatalog.delivery.api.DataAccessModels.*;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.adapter.KetiDataCoreAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class DatasetRecordsPostgresIT {
    private static HttpServer hub;
    private static HttpServer broker;
    private static String origin;
    private static String recordsOrigin;
    private static final AtomicReference<String> RESPONSE=new AtomicReference<>();
    private static final AtomicReference<String> QUERY=new AtomicReference<>();
    private static final AtomicReference<String> ACCEPT=new AtomicReference<>();
    private static final AtomicReference<String> COUNT=new AtomicReference<>();
    private static final AtomicReference<String> COUNT_HEADER=new AtomicReference<>("NGSILD-Results-Count");
    private static final AtomicBoolean PAGINATED=new AtomicBoolean();
    private static final Queue<String> RECORD_REQUESTS=new ConcurrentLinkedQueue<>();
    private static final Queue<String> METADATA_REQUESTS=new ConcurrentLinkedQueue<>();
    @Autowired JdbcTemplate db;
    @Autowired DatasetRecordsService service;
    @Autowired MockMvc mvc;
    @Autowired KetiDataCoreAdapter metadataAdapter;
    @Autowired CatalogQueryService catalog;
    private UUID org,id,connection;
    private Authentication actor;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        PostgresDatabase.configure(registry);
        hub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        hub.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();METADATA_REQUESTS.add(path);
            String content=switch(path) {
                case "/datasets" -> "[{\"id\":\"A\"}]";
                case "/datasets/A" -> "{\"datasetBaseInfo\":{\"id\":\"A\",\"name\":\"Metadata A\",\"dataModelId\":\"ModelA\"},\"metadataInfo\":[]}";
                case "/datamodels/ModelA" -> "{\"id\":\"ModelA\",\"type\":\"Count\",\"attributes\":[{\"name\":\"count\",\"valueType\":\"Integer\"}]}";
                default -> "{\"error\":\"Records are served by the separate broker\"}";
            };
            byte[] body=content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(path.equals("/entities")?404:200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}finally{exchange.close();}
        });hub.start();origin="http://records-fixture.invalid:"+hub.getAddress().getPort();
        broker=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        broker.createContext("/entities",exchange->{
            QUERY.set(exchange.getRequestURI().getRawQuery());
            RECORD_REQUESTS.add(exchange.getRequestURI().getRawQuery());
            ACCEPT.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body=fixtureBody(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(COUNT.get()!=null) {
                exchange.getResponseHeaders().set(COUNT_HEADER.get(),COUNT.get());
            }
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}finally{exchange.close();}
        });broker.start();recordsOrigin="http://records-fixture.invalid:"+broker.getAddress().getPort();
        registry.add("catalog.integration.allowed-http-origins",()->origin+","+recordsOrigin);
        registry.add("catalog.integration.pinned-addresses",()->"{\"records-fixture.invalid\":[\"127.0.0.1\"]}");
        registry.add("catalog.integration.allowed-private-addresses",()->"127.0.0.1");
        registry.add("catalog.integration.keti.records-endpoint-mappings",()->"{\""+origin+"\":\""+recordsOrigin+"\"}");
    }
    @AfterAll static void close(){if(hub!=null)hub.stop(0);if(broker!=null)broker.stop(0);}
    @BeforeEach void prepare(){
        org=UUID.randomUUID();id=UUID.randomUUID();connection=UUID.randomUUID();
        UUID revision=UUID.randomUUID(),publication=UUID.randomUUID();
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES(?,?,?)",org,org.toString(),"Records test");
        db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES(?,?,?)",id,org,id.toString());
        db.update("""
            INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd,mtdt_cn)
            VALUES(?,?,1,'APPROVED','Records test',?,'PUBLIC',?::jsonb)
            """,revision,id,org,"{\"fields\":[{\"fieldPath\":\"count\",\"physicalName\":\"count\",\"displayName\":\"수\",\"dataType\":\"NUMBER\",\"unit\":\"명\",\"publicVisible\":true},{\"fieldPath\":\"secret\",\"publicVisible\":false}]}");
        db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES(?,?,?,1,'PUBLISHED',CURRENT_TIMESTAMP)",publication,id,revision);
        db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,id);
        db.update("INSERT INTO integration.source_connection(id,inst_id,nm,adptr_cd,lnkg_addr,use_yn,lmt_sec_cnt,max_prcstry_nmtm,max_qpage_cnt) VALUES(?,?,?,'KETI_DATACORE',?,true,5,1,10)",connection,org,"Records fixture",origin);
        actor=authentication(org,"ORG_EDITOR","ORG_PUBLISHER");
        RESPONSE.set("[{\"datasetId\":\"A\",\"count\":{\"type\":\"Property\",\"value\":12},\"secret\":\"HIDDEN\"}]");
        QUERY.set(null);
        ACCEPT.set(null);COUNT.set(null);COUNT_HEADER.set("NGSILD-Results-Count");PAGINATED.set(false);RECORD_REQUESTS.clear();METADATA_REQUESTS.clear();
    }
    @Test void requiresSuccessfulCheckThenExposesOnlyPublishedFieldsAndDownloads() throws Exception {
        var saved=save();assertThat(saved.ready()).isFalse();
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/records")).andExpect(status().isNotFound());
        var checked=service.check(id,DatasetRecordsService.etag(saved),actor);assertThat(checked.settings().ready()).isTrue();
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/records")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].count").value(12)).andExpect(jsonPath("$.items[0].secret").doesNotExist());
        assertThat(QUERY.get()).contains("datasetId=A","limit=11","offset=0");
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/records/download").param("format","csv").param("limit","10"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("\"count\"")));
    }
    @Test void rejectsForeignDatasetResponsesAndConfigurationVersionChanges(){
        var saved=save();service.check(id,DatasetRecordsService.etag(saved),actor);
        RESPONSE.set("[{\"datasetId\":\"OTHER\",\"count\":99}]");
        assertThatThrownBy(()->service.records(id,10,0)).hasMessage("허브의 데이터 조회 응답을 확인하지 못했습니다.");
        db.update("UPDATE integration.source_connection SET lck_ver_no=lck_ver_no+1 WHERE id=?",connection);
        assertThat(service.available(id)).isFalse();
    }
    @Test void rejectsCrossOrganizationAndEditorWithoutPublisher(){
        assertThatThrownBy(()->service.save(id,input(),DatasetRecordsService.etag(service.settings(id,actor)),authentication(org,"ORG_EDITOR")))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        UUID foreign=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES(?,?,?)",foreign,foreign.toString(),"Foreign");
        db.update("UPDATE integration.source_connection SET inst_id=? WHERE id=?",foreign,connection);
        assertThatThrownBy(this::save).hasMessage("데이터 조회 연결이 설정되지 않았거나 사용할 수 없습니다.");
    }
    @Test void withdrawnPublicationAndDisabledOrganizationCannotExposeRecords() throws Exception {
        var saved=save();service.check(id,DatasetRecordsService.etag(saved),actor);
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/records")).andExpect(status().isNotFound());
    }
    @Test void automaticallyQueriesOnlyCurrentPublishedKetiDatasetAndAllowsOptionalDatasetId() {
        addImportedProvenance();
        RESPONSE.set("[{\"count\":{\"type\":\"Property\",\"value\":7}}]");
        var settings=service.settings(id,actor);assertThat(settings.configured()).isFalse();assertThat(settings.ready()).isTrue();
        assertThat(service.records(id,10,0).items().getFirst().get("count")).isEqualTo(7);
        assertThat(QUERY.get()).contains("datasetId=A").doesNotContain("q=");
        var checked=service.check(id,DatasetRecordsService.etag(settings),actor);
        assertThat(checked.settings().configured()).isTrue();assertThat(checked.settings().ready()).isTrue();
        RESPONSE.set("[{\"datasetId\":\"OTHER\",\"count\":8}]");
        assertThatThrownBy(()->service.records(id,10,0)).hasMessage("허브의 데이터 조회 응답을 확인하지 못했습니다.");
    }
    @Test void metadataAndAutomaticRecordsUseDifferentExplicitlyConfiguredPorts() throws Exception {
        var page=metadataAdapter.discover(new HubAdapter.Context(URI.create(origin),null,5,1_048_576),null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().metadata().title()).isEqualTo("Metadata A");
        assertThat(METADATA_REQUESTS).containsExactly("/datasets","/datasets/A","/datamodels/ModelA");
        assertThat(QUERY.get()).isNull();
        addImportedProvenance();
        assertThat(service.records(id,10,0).items().getFirst().get("count")).isEqualTo(12);
        assertThat(QUERY.get()).contains("datasetId=A","limit=11","offset=0");
        assertThat(ACCEPT.get()).isEqualTo("application/ld+json");
        assertThat(METADATA_REQUESTS).doesNotContain("/entities");
        assertThat(db.queryForObject("SELECT lnkg_addr FROM integration.source_connection WHERE id=?",String.class,connection)).isEqualTo(origin);
    }
    @Test void explicitlySavedKetiSettingsUseTheSameBrokerOverride() {
        addImportedProvenance();
        var input=new DataAccessInput(connection,true,"KETI_DATASET","/entities","datasetId","A","datasetId",Map.of());
        var saved=service.save(id,input,DatasetRecordsService.etag(service.settings(id,actor)),actor);
        var checked=service.check(id,DatasetRecordsService.etag(saved),actor);
        assertThat(checked.settings().ready()).isTrue();
        assertThat(service.records(id,10,0).items().getFirst().get("count")).isEqualTo(12);
        assertThat(METADATA_REQUESTS).isEmpty();
        assertThat(QUERY.get()).contains("datasetId=A");
        assertThat(db.queryForObject("SELECT lnkg_addr FROM integration.source_connection WHERE id=?",String.class,connection)).isEqualTo(origin);
    }
    @Test void exactFinalPageDoesNotOfferAnEmptyNextPageOrInventTotalCount() throws Exception {
        addImportedProvenance();pageRows(10);
        var page=service.records(id,10,0);
        assertThat(page.items()).hasSize(10);assertThat(page.hasMore()).isFalse();assertThat(page.totalCount()).isNull();
        assertThat(page.fetchedAt()).isNotNull();assertThat(page.recordUpdatedAt()).isNull();assertThat(page.recordObservedAt()).isNull();
        assertThat(page.columns()).containsExactly(new RecordColumn("count","수","NUMBER","명"));
        pageRows(11);
        assertThat(service.records(id,10,0).hasMore()).isTrue();
        var last=service.records(id,10,10);
        assertThat(last.items()).hasSize(1);assertThat(last.hasMore()).isFalse();
    }
    @Test void preservesSuppliedCountsAcrossChunkedPaginationAndRejectsConflictingCounts() throws Exception {
        addImportedProvenance();pageRows(205);COUNT.set("205");
        var page=service.records(id,200,0);
        assertThat(page.items()).hasSize(200);assertThat(page.totalCount()).isEqualTo(205L);assertThat(page.hasMore()).isTrue();
        assertThat(RECORD_REQUESTS).hasSize(3);
        assertThat(RECORD_REQUESTS).anySatisfy(query->assertThat(query).contains("limit=1","offset=200"));
        var last=service.records(id,10,200);
        assertThat(last.items()).hasSize(5);assertThat(last.totalCount()).isEqualTo(205L);assertThat(last.hasMore()).isFalse();
        COUNT.set("300");assertThat(service.records(id,10,200).totalCount()).isNull();
        COUNT.set("-1");assertThat(service.records(id,10,200).totalCount()).isNull();
        COUNT.set("205");assertThat(service.records(id,10,210).totalCount()).isEqualTo(205L);
        COUNT_HEADER.set("totalCount");assertThat(service.records(id,10,200).totalCount()).isEqualTo(205L);
    }
    @Test void ngsiModeRequestsFilteredCountWithoutChangingKetiQueryContract() throws Exception {
        var settings=service.settings(id,actor);
        var saved=service.save(id,new DataAccessInput(connection,true,"NGSI_LD","/entities","datasetId","A","q",Map.of()),
                DatasetRecordsService.etag(settings),actor);
        service.check(id,DatasetRecordsService.etag(saved),actor);
        pageRows(10);COUNT.set("10");
        assertThat(service.records(id,10,0).totalCount()).isEqualTo(10L);
        assertThat(QUERY.get()).contains("count=true","q=datasetId%3D%3D%22A%22");
    }
    @Test void recordColumnsFollowCurrentPublicationEvenWhenHistoricalDetailHasOtherUnits() {
        var saved=save();service.check(id,DatasetRecordsService.etag(saved),actor);
        UUID revision=UUID.randomUUID(),publication=UUID.randomUUID();
        db.update("""
            INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd,mtdt_cn)
            VALUES(?,?,2,'APPROVED','Updated records',?,'PUBLIC',?::jsonb)
            """,revision,id,org,"{\"fields\":[{\"fieldPath\":\"count\",\"physicalName\":\"count\",\"displayName\":\"현재 수\",\"dataType\":\"INTEGER\",\"unit\":\"대\",\"publicVisible\":true}]}");
        db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES(?,?,?,2,'PUBLISHED',CURRENT_TIMESTAMP + interval '1 hour')",publication,id,revision);
        db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,id);
        assertThat(catalog.detail(id,1).fields().getFirst().unit()).isEqualTo("명");
        var current=service.records(id,10,0);
        assertThat(current.publicationNo()).isEqualTo(2);
        assertThat(current.columns()).containsExactly(new RecordColumn("count","현재 수","INTEGER","대"));
        assertThat(catalog.detail(id,null).freshness().firstPublishedAt()).isEqualTo(catalog.detail(id,1).publishedAt());
    }
    @Test void returnsOnlyCurrentPageTimestampsAndDoesNotSubstituteMetadataCollectionTime() throws Exception {
        addImportedProvenance();PAGINATED.set(true);
        RESPONSE.set("""
            [{"datasetId":"A","modifiedAt":"2026-11-01T01:00:00Z","count":{"type":"Property","value":1,"observedAt":"2026-11-01T00:30:00Z"}},
             {"datasetId":"A","modifiedAt":"2026-11-02T01:00:00Z","count":{"type":"Property","value":2,"observedAt":"2026-11-02T00:30:00Z"}}]
            """);
        var page=service.records(id,1,0);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.recordUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        assertThat(page.recordObservedAt()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:30:00Z"));
        assertThat(page.fetchedAt()).isAfter(page.recordUpdatedAt());
        assertThat(catalog.detail(id,null).freshness().metadataCollectedAt()).isNotEqualTo(page.recordUpdatedAt());
    }
    @Test void publicFreshnessSeparatesLastCollectedDatasetTimeFromLatestConnectionFailure() throws Exception {
        assertThat(catalog.detail(id,null).freshness().collectionStatus()).isEqualTo("NOT_LINKED");
        assertThat(catalog.detail(id,null).freshness().metadataCollectedAt()).isNull();
        addImportedProvenance();
        UUID run=db.queryForObject("SELECT last_excn_id FROM integration.source_metadata WHERE dtset_id=?",UUID.class,id);
        OffsetDateTime collected=OffsetDateTime.parse("2026-11-06T06:30:00Z");
        db.update("INSERT INTO integration.sync_item(id,sync_excn_id,otsd_id,stts_cd,prcstry_nmtm,mdfcn_dt) VALUES(?,?,'A','UNCHANGED',1,?)",UUID.randomUUID(),run,collected);
        String hash="b".repeat(64);
        db.update("""
            INSERT INTO integration.sync_run(id,sou_lnkg_id,rqstr_id,type_cd,stts_cd,idmpt_key_hash_vl,dmnd_hash_vl,stng_ver_no,mpng_ver_nm,err_cd,crt_dt)
            VALUES(?,?,?,'SYNC','FAILED',?,?,0,'test','PRIVATE_INTERNAL_ERROR',CURRENT_TIMESTAMP + interval '1 hour')
            """,UUID.randomUUID(),connection,UUID.randomUUID(),hash,hash);
        var detail=catalog.detail(id,null);
        assertThat(detail.freshness().metadataCollectedAt()).isEqualTo(collected);
        assertThat(detail.freshness().collectionStatus()).isEqualTo("FAILED");
        assertThat(catalog.listPublished(0,100).items().stream().filter(row->row.id().equals(id.toString())).findFirst().orElseThrow().freshness())
                .isEqualTo(detail.freshness());
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("PRIVATE_INTERNAL_ERROR"))))
                .andExpect(content().string(not(containsString(origin))));
        db.update("UPDATE integration.source_connection SET use_yn=false WHERE id=?",connection);
        assertThat(catalog.detail(id,null).freshness().collectionStatus()).isEqualTo("DISABLED");
        UUID foreign=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES(?,?,?)",foreign,foreign.toString(),"Foreign");
        db.update("UPDATE integration.source_connection SET inst_id=? WHERE id=?",foreign,connection);
        assertThat(catalog.detail(id,null).freshness().collectionStatus()).isEqualTo("NOT_LINKED");
        assertThat(catalog.detail(id,null).freshness().metadataCollectedAt()).isNull();
    }
    private void pageRows(int count) throws Exception {
        PAGINATED.set(true);
        RESPONSE.set(new ObjectMapper().writeValueAsString(java.util.stream.IntStream.range(0,count)
                .mapToObj(n->Map.of("datasetId","A","count",n)).toList()));
    }
    private static byte[] fixtureBody(String rawQuery) throws java.io.IOException {
        if(!PAGINATED.get())return RESPONSE.get().getBytes(StandardCharsets.UTF_8);
        Map<String,String> query=new HashMap<>();
        for(String part:rawQuery.split("&")) {
            var pair=part.split("=",2);query.put(pair[0],java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
        }
        var json=new ObjectMapper();var all=json.readTree(RESPONSE.get());var page=json.createArrayNode();
        int offset=Integer.parseInt(query.get("offset")),limit=Integer.parseInt(query.get("limit"));
        for(int i=offset;i<Math.min(all.size(),offset+limit);i++)page.add(all.get(i));
        return json.writeValueAsBytes(page);
    }
    private void addImportedProvenance() {
        UUID run=UUID.randomUUID(),source=UUID.randomUUID(),application=UUID.randomUUID(),actorId=UUID.randomUUID();
        UUID revision=db.queryForObject("SELECT rvsn_id FROM catalog.publication WHERE dtset_id=?",UUID.class,id);
        String hash="a".repeat(64);
        db.update("INSERT INTO integration.sync_run(id,sou_lnkg_id,rqstr_id,type_cd,stts_cd,idmpt_key_hash_vl,dmnd_hash_vl,stng_ver_no,mpng_ver_nm) VALUES(?,?,?,'SYNC','SUCCEEDED',?,?,0,'test')",run,connection,actorId,hash,hash);
        db.update("INSERT INTO integration.source_metadata(id,sou_lnkg_id,otsd_id,sou_hash_vl,cand_cn,mpng_ver_nm,last_excn_id,dtset_id) VALUES(?,?,'A',?,'{}','test',?,?)",source,connection,hash,run,id);
        db.update("INSERT INTO integration.candidate_application(id,sou_mtdt_id,sou_rvsn_no,sou_hash_vl,dtset_id,dtset_rvsn_id,dtset_lck_ver_no,prcs_user_id,dmnd_id,idmpt_key_hash_vl,dmnd_hash_vl) VALUES(?,?,1,?,?,?,0,?,'test',?,?)",application,source,hash,id,revision,actorId,hash,hash);
    }
    private DataAccessView save(){return service.save(id,input(),DatasetRecordsService.etag(service.settings(id,actor)),actor);}
    private DataAccessInput input(){return new DataAccessInput(connection,true,"JSON_ARRAY","/entities","datasetId","A","datasetId",Map.of());}
    private Authentication authentication(UUID owner,String...roles){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(owner)),null,
        Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());}
}

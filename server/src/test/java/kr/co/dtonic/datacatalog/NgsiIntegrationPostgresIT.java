package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.ApplyCandidate;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.application.IntegrationCandidateService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationWorker;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual NGSI adapter socket -> worker -> PostgreSQL -> required review and publication boundary. */
@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.worker-enabled=false","catalog.integration.ngsi-ld.page-size=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NgsiIntegrationPostgresIT {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final AtomicReference<String> MODIFIED=new AtomicReference<>("2026-10-27T00:00:00Z");
    private static final AtomicReference<String> TITLE=new AtomicReference<>("Synthetic NGSI metadata");
    private static HttpServer hub;
    private static String origin;
    @Autowired IntegrationService integrations;
    @Autowired IntegrationWorker worker;
    @Autowired IntegrationCandidateService candidates;
    @Autowired MetadataWorkflowService metadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties)throws IOException{
        PostgresDatabase.configure(properties);
        hub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        hub.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();int offset=0;
            for(String pair:exchange.getRequestURI().getQuery()==null?new String[0]:exchange.getRequestURI().getQuery().split("&"))
                if(pair.startsWith("offset="))offset=Integer.parseInt(pair.substring(7));
            String body;
            try{body=offset>=2?"[]":"["+entity(path.contains("repeat")?"A":offset==0?"A":"B",path.contains("bad-context"))+"]";}
            catch(Exception unexpected){throw new IOException(unexpected);}
            exchange.getResponseHeaders().set("Content-Type","application/ld+json");
            exchange.getResponseHeaders().set("NGSILD-Results-Count",path.contains("bad-count")?"3":"2");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}finally{exchange.close();}
        });
        hub.start();origin="http://ngsi-pg.invalid:"+hub.getAddress().getPort();
        properties.add("catalog.integration.allowed-http-origins",()->origin);
        properties.add("catalog.integration.pinned-addresses",()->"{\"ngsi-pg.invalid\":[\"127.0.0.1\"]}");
        properties.add("catalog.integration.allowed-private-addresses",()->"127.0.0.1");
    }
    @AfterAll static void stop(){if(hub!=null)hub.stop(0);}
    @BeforeEach void reset(){MODIFIED.set("2026-10-27T00:00:00Z");TITLE.set("Synthetic NGSI metadata");}
    private static String entity(String suffix,boolean badContext)throws Exception{
        return JSON.writeValueAsString(Map.of("id","urn:ngsi-ld:Environment:"+suffix,"type","Environment",
            "@context",List.of(badContext?"https://unconfigured.invalid/context":"https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld",
                Map.of("@vocab","https://schema.example/","name","https://schema.org/name","description","https://schema.org/description")),
            "modifiedAt",MODIFIED.get(),"name",Map.of("type","Property","value",TITLE.get()+" "+suffix),
            "description",Map.of("type","Property","value","Synthetic source schema only"),
            "temperature",Map.of("type","Property","value",928374.125,"unitCode","CEL","observedAt",MODIFIED.get()),
            "locatedIn",Map.of("type","Relationship","object","urn:ngsi-ld:Location:SyntheticLab")));
    }
    private UUID organization(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),"Synthetic NGSI organization");return id;}
    private Authentication user(UUID org,String... roles){return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(org)),null,java.util.Arrays.stream(roles).map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList());}
    private kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionView connection(UUID org,Authentication actor,String prefix){
        return integrations.create(actor,new ConnectionInput(org,"Synthetic NGSI","NGSI_LD",origin+prefix,null,true,10,1,10));
    }
    private String collect(Authentication actor,UUID connection,String key){var run=integrations.requestRun(actor,connection,"SYNC",key);assertThat(worker.runOnce()).isTrue();return integrations.run(actor,run.id()).status();}
    private String etag(MetadataDraft draft){return "\"dataset:"+draft.id()+":"+draft.lockVersion()+"\"";}

    @Test void actualNgsiWorkerPreservesSchemaAndAuditWithoutObservationRevisionChurn()throws Exception{
        UUID org=organization();var editor=user(org,"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER");
        var connection=connection(org,editor,"/ngsi-ld/v1");assertThat(connection.adapterAvailable()).isTrue();
        var check=integrations.requestRun(editor,connection.id(),"CHECK","ngsi-check-0001");worker.runOnce();
        assertThat(integrations.run(editor,check.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(collect(editor,connection.id(),"ngsi-collect-001")).isEqualTo("SUCCEEDED");
        var listing=candidates.list(connection.id(),0,20,editor);assertThat(listing.items()).hasSize(2);
        var summary=listing.items().stream().filter(c->c.externalId().endsWith(":A")).findFirst().orElseThrow();
        var detail=candidates.detail(summary.id(),editor);
        assertThat(detail.metadata()).containsEntry("sourceEntityId","urn:ngsi-ld:Environment:A").containsEntry("sourceModifiedAt","2026-10-27T00:00:00Z");
        assertThat(json.writeValueAsString(detail)).doesNotContain("928374.125","Location:SyntheticLab");
        var applied=candidates.apply(summary.id(),new ApplyCandidate(null),detail.etag(),"ngsi-apply-0001",editor,"ngsi-request-0001");
        var draft=metadata.getDraft(applied.datasetId(),editor);
        assertThat(draft.visibility()).isEqualTo("PRIVATE");assertThat(draft.currentPublicationId()).isNull();
        assertThat(json.valueToTree(metadata.getDraftMetadata(draft.id(),editor)).path("hubMetadata").has("sourceModifiedAt")).isFalse();
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.id())).andExpect(status().isNotFound());
        final var incomplete=draft;
        assertThatThrownBy(()->metadata.submit(incomplete.id(),etag(incomplete),"ngsi-review-bad",editor,"ngsi-request-bad"))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("REQUIRED_METADATA_MISSING"));
        draft=metadata.edit(draft.id(),new EditDraft(draft.title(),draft.description(),org,null,"PUBLIC",null,null,null,
            new MetadataDetails("Synthetic department","Synthetic license",null,null,null,null,List.of("synthetic"))),etag(draft),editor,"ngsi-edit-0001");
        var review=metadata.submit(draft.id(),etag(draft),"ngsi-review-0001",editor,"ngsi-request-0002");
        String reviewTag="\"review:"+review.id()+":"+review.lockVersion()+"\"";
        metadata.decide(review.id(),new DecideReview("APPROVE",null),reviewTag,"ngsi-approve-001",editor,"ngsi-request-0003");
        draft=metadata.getDraft(draft.id(),editor);
        metadata.publish(draft.id(),new PublishRevision(draft.revisionId(),draft.reviewDecisionId()),etag(draft),"ngsi-publish-001",editor,"ngsi-request-0004");
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.id())).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Synthetic NGSI metadata A"));
        long lock=metadata.getDraft(draft.id(),editor).lockVersion();
        MODIFIED.set("2026-10-27T01:00:00Z");
        assertThat(collect(editor,connection.id(),"ngsi-collect-002")).isEqualTo("SUCCEEDED");
        var refreshed=candidates.detail(summary.id(),editor);
        assertThat(refreshed.candidate().revisionNo()).isEqualTo(1);assertThat(refreshed.sourceHash()).isEqualTo(detail.sourceHash());
        assertThat(refreshed.metadata()).containsEntry("sourceModifiedAt","2026-10-27T01:00:00Z");
        assertThat(refreshed.changes()).allSatisfy(change->assertThat(change.sourceChanged()).isFalse());
        assertThat(metadata.getDraft(draft.id(),editor).lockVersion()).isEqualTo(lock);
        TITLE.set("Changed metadata title");
        assertThat(collect(editor,connection.id(),"ngsi-collect-003")).isEqualTo("SUCCEEDED");
        var updated=candidates.detail(summary.id(),editor);assertThat(updated.candidate().revisionNo()).isEqualTo(2);
        candidates.apply(summary.id(),new ApplyCandidate(updated.targetEtag()),updated.etag(),"ngsi-apply-0002",editor,"ngsi-request-0005");
        assertThat(metadata.getDraft(draft.id(),editor).title()).isEqualTo("Changed metadata title A");
        mvc.perform(get("/api/catalog/v1/datasets/"+draft.id())).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Synthetic NGSI metadata A"));
    }

    @Test void invalidSourcesAreNotSuccessfulEmptyScansAndModeCannotMixExistingCandidates(){
        UUID org=organization();var editor=user(org,"ORG_EDITOR");
        var invalid=connection(org,editor,"/bad-context/v1");
        assertThat(collect(editor,invalid.id(),"ngsi-invalid-001")).isEqualTo("PARTIAL_FAILED");
        assertThat(candidates.list(invalid.id(),0,20,editor).items()).isEmpty();
        for(String path:List.of("/bad-count/v1","/repeat/v1")){
            var bad=connection(org,editor,path);assertThat(collect(editor,bad.id(),"ngsi-invalid-"+UUID.randomUUID())).isEqualTo("FAILED");
            assertThat(integrations.runs(editor,bad.id()).getFirst().errorCode()).isEqualTo("INVALID_SCHEMA");
        }
        var active=connection(org,editor,"/ngsi-ld/v1");
        assertThatThrownBy(()->integrations.update(editor,active.id(),active.lockVersion(),new ConnectionInput(org,"Changed mode","KETI_DATACORE",null,null,true,10,1,10)))
            .isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo("ADAPTER_IMMUTABLE"));
        assertThat(integrations.get(editor,active.id()).adapterCode()).isEqualTo("NGSI_LD");
    }
}

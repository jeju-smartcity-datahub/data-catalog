package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.adapter.KetiDataCoreAdapter;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.ApplyCandidate;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.application.IntegrationCandidateService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationWorker;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/** Real adapter bean, real HTTP socket, real PostgreSQL, and explicit draft application in one flow. */
@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.worker-enabled=false", "catalog.integration.keti.page-size=1"})
@ActiveProfiles("test")
@Transactional
class KetiIntegrationPostgresIT {
    private static final String MODEL_ID = "https://schema.invalid/Parking/1.0";
    private static final List<Request> REQUESTS = new CopyOnWriteArrayList<>();
    private static HttpServer hub;
    private static String origin;
    private static Path secrets;
    private record Request(String method,String path,String host,String key) {}
    @Autowired IntegrationService integrations;
    @Autowired IntegrationWorker worker;
    @Autowired IntegrationCandidateService candidates;
    @Autowired MetadataWorkflowService metadata;
    @Autowired KetiDataCoreAdapter adapter;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) throws IOException {
        PostgresDatabase.configure(properties);
        hub = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        hub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            REQUESTS.add(new Request(exchange.getRequestMethod(),exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Host"),exchange.getRequestHeaders().getFirst("X-Hub-Key")));
            String response = response(path);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            if (path.equals("/manager/datasets")) exchange.getResponseHeaders().set("totalCount","2");
            exchange.sendResponseHeaders(response.equals("{}")?404:200,body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        hub.start();
        origin = "http://keti-fixture.invalid:"+hub.getAddress().getPort();
        secrets = Files.createTempDirectory("keti-pg-secrets-");
        properties.add("catalog.integration.allowed-http-origins",() -> origin);
        properties.add("catalog.integration.pinned-addresses",() -> "{\"keti-fixture.invalid\":[\"127.0.0.1\"]}");
        properties.add("catalog.integration.allowed-private-addresses",() -> "127.0.0.1");
        properties.add("catalog.integration.secret-directory",() -> secrets.toString());
    }

    @AfterAll static void stopFixture() throws IOException {
        if (hub != null) hub.stop(0);
        if (secrets != null) { Files.deleteIfExists(secrets.resolve("manager.json")); Files.deleteIfExists(secrets); }
    }

    @Test void readsRealKetiAdapterThroughWorkerAndAppliesUnpublishedSchemaWithoutDuplicateRevision() throws Exception {
        UUID organization = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",organization,organization.toString(),"KETI end-to-end fixture");
        var editor = UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(organization)),null,
                List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        assertThat(adapter.code()).isEqualTo("KETI_DATACORE");
        var connection = integrations.create(editor,new ConnectionInput(organization,"KETI local HTTP fixture","KETI_DATACORE",
                origin+"/manager",null,true,5,1,10));
        assertThat(connection.adapterAvailable()).isTrue();
        assertThat(connection.credentialConfigured()).isFalse();
        var check = integrations.requestRun(editor,connection.id(),"CHECK","keti-check-0001");
        assertThat(worker.runOnce()).isTrue();
        assertThat(integrations.run(editor,check.id()).status()).isEqualTo("SUCCEEDED");
        var firstRun = integrations.requestRun(editor,connection.id(),"SYNC","keti-sync-0001");
        assertThat(worker.runOnce()).isTrue();
        assertThat(integrations.run(editor,firstRun.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(integrations.items(editor,firstRun.id(),0,20).items()).extracting("status").containsExactly("CANDIDATE","CANDIDATE");
        var collected = candidates.list(connection.id(),0,20,editor);
        assertThat(collected.items()).hasSize(2);
        var summary = collected.items().stream().filter(candidate -> candidate.externalId().equals("jeju-a")).findFirst().orElseThrow();
        var candidate = candidates.detail(summary.id(),editor);
        assertThat(candidate.metadata()).containsEntry("datamodelId",MODEL_ID).containsEntry("providerOrganization","제주 데이터허브");
        assertThat(json.writeValueAsString(candidate)).doesNotContain("only-test-key","internal-storage.invalid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,organization)).isZero();

        var applied = candidates.apply(summary.id(),new ApplyCandidate(null),candidate.etag(),"keti-apply-0001",editor,"keti-request-0001");
        var draft = metadata.getDraft(applied.datasetId(),editor);
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThat(draft.visibility()).isEqualTo("PRIVATE");
        assertThat(draft.currentPublicationId()).isNull();
        assertThat(draft.details().license()).isEqualTo("CC BY 4.0");
        assertThat(draft.details().keywords()).containsExactly("주차");
        var stored = json.valueToTree(metadata.getDraftMetadata(draft.id(),editor));
        assertThat(stored.path("fields").size()).isEqualTo(3);
        assertThat(stored.path("fields").get(0).path("dataType").asText()).isEqualTo("DATETIME");
        assertThat(stored.path("fields").get(0).path("nullable").asBoolean()).isFalse();
        assertThat(stored.path("fields").get(2).path("fieldPath").asText()).isEqualTo("parking.available");
        assertThat(stored.path("fields").get(2).path("description").asText()).isEqualTo("사용 가능한 주차면");
        assertThat(stored.path("fields").get(0).has("sourceDataType")).isFalse();
        assertThat(stored.path("hubMetadata").path("fields").get(0).path("sourceDataType").asText()).isEqualTo("Date");

        var secondRun = integrations.requestRun(editor,connection.id(),"SYNC","keti-sync-0002");
        assertThat(worker.runOnce()).isTrue();
        assertThat(integrations.run(editor,secondRun.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(integrations.items(editor,secondRun.id(),0,20).items()).extracting("status").containsExactly("UNCHANGED","UNCHANGED");
        assertThat(candidates.list(connection.id(),0,20,editor).items()).allSatisfy(item -> assertThat(item.revisionNo()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.source_metadata WHERE sou_lnkg_id=?",Integer.class,connection.id())).isEqualTo(2);
        assertThat(metadata.getDraft(draft.id(),editor).lockVersion()).isEqualTo(draft.lockVersion());
        assertThat(REQUESTS).hasSize(13).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.host()).startsWith("keti-fixture.invalid:");
            assertThat(request.key()).isNull();
            assertThat(request.path()).doesNotContain("?");
        });
        assertThat(REQUESTS.stream().filter(request -> request.path().equals("/manager/datamodels/"+MODEL_ID))).hasSize(4);
    }

    private static String response(String path) {
        if (path.equals("/manager/datasets")) return "[{\"id\":\"jeju-b\"},{\"id\":\"jeju-a\"}]";
        if (path.equals("/manager/datasets/jeju-a") || path.equals("/manager/datasets/jeju-b")) {
            String id = path.substring(path.lastIndexOf('/')+1);
            return """
                    {"id":"%s","name":"제주 주차면 현황","description":"공영주차장 정보",
                    "providerOrganization":"제주 데이터허브","keyword":["주차"],"targetRegions":"제주",
                    "license":"CC BY 4.0","dataModelId":"%s","dataStoreUri":["http://internal-storage.invalid/private"]}
                    """.formatted(id,MODEL_ID);
        }
        if (path.equals("/manager/datamodels/"+MODEL_ID)) return """
                {"id":"%s","type":"Parking","context":["https://schema.invalid/context"],"attributes":[
                 {"name":"observedTime","valueType":"Date","attributeType":"Property","isRequired":true},
                 {"name":"parking","valueType":"Object","attributeType":"Property","objectMembers":[
                  {"name":"available","valueType":"Integer","description":"사용 가능한 주차면"}]}]}
                """.formatted(MODEL_ID);
        return "{}";
    }
}

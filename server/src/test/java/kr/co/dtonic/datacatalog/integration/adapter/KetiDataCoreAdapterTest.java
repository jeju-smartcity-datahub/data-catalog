package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class KetiDataCoreAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Reply> responses = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ApprovedHubHttpTransport http;
    private KetiDataCoreAdapter adapter;
    private String origin;
    private record Reply(int status, String body, Map<String,String> headers, long delayMillis) {}
    private record Call(String method, String path, String host, String authorization, String apiKey) {}
    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Host"), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Hub-Key")));
            Reply reply = responses.getOrDefault(exchange.getRequestURI().getPath(), new Reply(404,"{}",Map.of(),0));
            try {
                if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key,value));
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), 0); // chunked: exercise streaming byte limit, not just Content-Length
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException disconnected) { /* request cancellation is part of timeout/body-limit tests */ }
            finally { exchange.close(); }
        });
        server.start();
        origin = "http://hub-test.invalid:" + server.getAddress().getPort();
        http = new ApprovedHubHttpTransport(new EndpointPolicy("", origin), json,
                "{\"hub-test.invalid\":[\"127.0.0.1\"]}", "127.0.0.1");
        adapter = new KetiDataCoreAdapter(http, json, 1);
    }
    @AfterEach void stop() { http.close(); server.stop(0); }
    private HubAdapter.Context context(String secret) {
        return new HubAdapter.Context(URI.create(origin + "/manager"), secret.toCharArray(), 3, 1048576);
    }
    private void reply(String suffix, String body) { responses.put("/manager" + suffix, new Reply(200,body,Map.of(),0)); }

    @Test void wrappedJejuDetailsResolveModelWithoutCredentialsAndPreserveMetadata() throws Exception {
        reply("/datasets", """
                [{"id":"SPEED_BUMP","name":"보호구역내 과속방지턱 위치","metasetId":"basic",
                  "classification":"교통","ownership":"GIS","qualityCheckEnabled":false}]
                """);
        reply("/datasets/SPEED_BUMP", """
                {"datasetBaseInfo":{
                  "id":"SPEED_BUMP","name":"보호구역내 과속방지턱 위치",
                  "description":"JEJU GIS 출처 보호구역내 과속방지턱 위치",
                  "updateInterval":"1th 00:00","category":"교통",
                  "providerOrganization":"GIS","providerSystem":"GIS","isProcessed":"원천데이터",
                  "ownership":"jeju","license":"CC BY","datasetItems":"data","targetRegions":"Nationwide",
                  "qualityCheckEnabled":false,"dataModelId":"G_SPEED_BUMP","enabled":true,
                  "provisioningRequestId":"not-catalog-metadata","provisioningEventTime":1686552235990,
                  "createdAt":"2023-06-12T06:43:55.956Z","modifiedAt":"2023-06-12T06:43:55.956Z"},
                 "metasetInfo":{"id":"basic","definition":"기본 메타데이터 셋"},
                 "metadataInfo":[
                  {"metadataName":"CLASSIFICATION","metadataValue":"교통","isMandatory":"MANDATORY","metasetId":null,"datasetId":null},
                  {"metadataName":"OWNERSHIP","metadataValue":"GIS","isMandatory":"MANDATORY","metasetId":null,"datasetId":null},
                  {"metadataName":"PERMISSION","metadataValue":"all","isMandatory":"MANDATORY","metasetId":null,"datasetId":null},
                  {"metadataName":"SOURCE","metadataValue":"-","isMandatory":"MANDATORY","metasetId":null,"datasetId":null},
                  {"metadataName":"DICTIONARY","metadataValue":"-","isMandatory":"MANDATORY","metasetId":null,"datasetId":null}]}
                """);
        reply("/datamodels/G_SPEED_BUMP", """
                {"id":"G_SPEED_BUMP","type":"SpeedBump","typeUri":"https://schema.invalid/SpeedBump",
                 "context":["https://schema.invalid/context"],"attributes":[
                  {"name":"height","valueType":"Double","attributeType":"Property",
                   "description":"높이","isRequired":true,"maxLength":"3,3","unitCode":"CMT"},
                  {"name":"address","valueType":"Object","attributeType":"Property","objectMembers":[
                   {"name":"road","valueType":"String","description":"도로명","maxLength":"100"}]}]}
                """);
        var page = adapter.discover(context(""), null);
        assertThat(page.complete()).isTrue();
        assertThat(page.items()).hasSize(1);
        var metadata = page.items().getFirst().metadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.title()).isEqualTo("보호구역내 과속방지턱 위치");
        assertThat(metadata.description()).isEqualTo("JEJU GIS 출처 보호구역내 과속방지턱 위치");
        assertThat(metadata.details()).containsEntry("providerOrganization", "GIS")
                .containsEntry("providerSystem", "GIS").containsEntry("ownership", "jeju")
                .containsEntry("category", "교통").containsEntry("license", "CC BY")
                .containsEntry("updateInterval", "1th 00:00").containsEntry("targetRegions", List.of("Nationwide"))
                .containsEntry("datamodelId", "G_SPEED_BUMP").containsEntry("type", "SpeedBump")
                .containsEntry("sourceModifiedAt", "2023-06-12T06:43:55.956Z");
        List<Map<String,Object>> fields = (List<Map<String,Object>>)metadata.details().get("fields");
        assertThat(fields).hasSize(3);
        assertThat(fields.getFirst()).containsEntry("fieldPath", "height").containsEntry("description", "높이")
                .containsEntry("dataType", "NUMBER").containsEntry("sourceDataType", "Double")
                .containsEntry("nullable", false).containsEntry("unit", "CMT").containsEntry("maxLength", null);
        assertThat(fields.get(2)).containsEntry("fieldPath", "address.road").containsEntry("description", "도로명")
                .containsEntry("dataType", "STRING").containsEntry("maxLength", 100);
        assertThat(json.writeValueAsString(metadata)).doesNotContain("not-catalog-metadata", "provisioning", "PERMISSION", "metasetInfo");
        assertThat(calls).extracting(Call::path).containsExactly("/manager/datasets", "/manager/datasets/SPEED_BUMP",
                "/manager/datamodels/G_SPEED_BUMP");
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.authorization()).isNull();
            assertThat(call.apiKey()).isNull();
        });
    }

    @Test void wrappedModelFailuresAreNotReportedAsSuccessfulEmptyFields() throws Exception {
        reply("/datasets", "[{\"id\":\"a\"}]");
        reply("/datasets/a", "{\"datasetBaseInfo\":{\"id\":\"a\",\"name\":\"A\",\"dataModelId\":\"M\"}}");
        assertThatThrownBy(() -> adapter.discover(context(""), null)).isInstanceOfSatisfying(AdapterFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(AdapterFailure.Reason.SOURCE_REJECTED));
        for (String model : List.of(
                "{\"id\":\"Other\",\"type\":\"M\",\"attributes\":[]}",
                "{\"id\":\"M\",\"type\":\"M\"}",
                "{\"id\":\"M\",\"type\":\"M\",\"attributes\":[{\"name\":\"height\",\"valueType\":\"Double\",\"maxLength\":\"invalid\"}]}")) {
            reply("/datamodels/M", model);
            var item = adapter.discover(context(""), null).items().getFirst();
            assertThat(item.metadata()).isNull();
            assertThat(item.errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        }
    }

    @Test void wrappedDatasetsShareModelCacheAndModelChangesChangeTheirRevision() throws Exception {
        adapter = new KetiDataCoreAdapter(http, json, 10);
        reply("/datasets", "[{\"id\":\"a\"},{\"id\":\"b\"}]");
        for (String id : List.of("a", "b")) {
            reply("/datasets/" + id, "{\"datasetBaseInfo\":{\"id\":\"" + id
                    + "\",\"name\":\"A\",\"modifiedAt\":\"2023-06-12T06:43:55.956Z\",\"dataModelId\":\"M\"}}");
        }
        reply("/datamodels/M", "{\"id\":\"M\",\"type\":\"M\",\"attributes\":[{\"name\":\"height\",\"valueType\":\"Double\"}]}");
        var first = adapter.discover(context(""), null);
        assertThat(first.items()).hasSize(2).allSatisfy(item -> assertThat(item.metadata()).isNotNull());
        assertThat(calls).filteredOn(call -> call.path().equals("/manager/datamodels/M")).hasSize(1);
        reply("/datamodels/M", "{\"id\":\"M\",\"type\":\"M\",\"attributes\":[{\"name\":\"height\",\"valueType\":\"Double\",\"description\":\"높이\"}]}");
        var updated = adapter.discover(context(""), null);
        assertThat(updated.items().getFirst().metadata().sourceRevision())
                .isNotEqualTo(first.items().getFirst().metadata().sourceRevision());
        assertThat(calls).filteredOn(call -> call.path().equals("/manager/datamodels/M")).hasSize(2);
    }

    @Test void wrappedDetailsValidateTheirOwnIdentityAndUseKnownMetadataOnly() throws Exception {
        reply("/datasets", "[{\"id\":\"a\"}]");
        reply("/datasets/a", """
                {"datasetBaseInfo":{"id":"a","name":"A"},"metadataInfo":[
                {"metadataName":"CLASSIFICATION","metadataValue":"교통"},
                {"metadataName":"OWNERSHIP","metadataValue":"GIS"},
                {"metadataName":"PRIVATE_CONFIGURATION","metadataValue":"must-not-be-retained"}]}
                """);
        var metadata = adapter.discover(context(""), null).items().getFirst().metadata();
        assertThat(metadata.details()).containsEntry("category", "교통").containsEntry("ownership", "GIS")
                .containsEntry("fields", List.of()).doesNotContainKey("datamodelId");
        assertThat(json.writeValueAsString(metadata)).doesNotContain("must-not-be-retained");
        for (String body : List.of(
                "{\"datasetBaseInfo\":null,\"id\":\"a\",\"name\":\"Do not fall back\"}",
                "{\"datasetBaseInfo\":{\"id\":\"wrong\",\"name\":\"Wrong\"}}",
                "{\"datasetBaseInfo\":{\"id\":\"a\",\"name\":\"A\",\"dataModelId\":\"M\",\"datamodelId\":\"Other\"}}")) {
            reply("/datasets/a", body);
            assertThat(adapter.discover(context(""), null).items().getFirst().errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        }
        assertThat(calls).noneSatisfy(call -> assertThat(call.path()).contains("datamodels"));
    }

    @Test void actualReadRequestsNormalizeDatasetModelAndPreserveSourceSemantics() throws Exception {
        reply("/datasets", "[{\"id\":\"jeju-parking\"}]");
        reply("/datasets/jeju-parking", """
                {"id":"jeju-parking","name":"제주 공영주차장","description":"주차면 현황",
                "providerOrganization":"제주","providerSystem":"Data Manager","ownership":"제주도",
                "keyword":["주차"],"targetRegions":"제주","dataModelId":"Parking/1.0",
                "providingApiUri":"https://data.jeju.invalid/entities","dataStoreUri":["secret-internal-kafka:9092"]}
                """);
        reply("/datamodels/Parking/1.0", """
                {"id":"Parking/1.0","type":"Parking","typeUri":"https://schema.invalid/Parking",
                "context":["https://schema.invalid/context"],"attributes":[
                  {"name":"capacity","valueType":"Integer","attributeType":"Property","isRequired":true,
                   "description":"주차면수","hasUnitCode":true,"hasObservedAt":true,"attributeUri":"https://schema.invalid/capacity"},
                  {"name":"address","valueType":"Object","attributeType":"Property","objectMembers":[
                    {"name":"road","valueType":"String","maxLength":"100"}]},
                  {"name":"location","valueType":"GeoJson","attributeType":"GeoProperty"},
                  {"name":"operator","valueType":"String","attributeType":"Relationship"}]}
                """);
        var page = adapter.discover(context(""),null);
        assertThat(page.complete()).isTrue();
        assertThat(page.nextCheckpoint()).isNull();
        assertThat(adapter.resumesFromPreviousRun()).isFalse();
        assertThat(adapter.capabilities()).doesNotContain(HubAdapter.Capability.FETCH_RECORDS,HubAdapter.Capability.FETCH_SAMPLE);
        var metadata = page.items().getFirst().metadata();
        assertThat(metadata.title()).isEqualTo("제주 공영주차장");
        assertThat(metadata.details()).containsEntry("keywords",List.of("주차")).containsEntry("targetRegions",List.of("제주"));
        assertThat(json.writeValueAsString(metadata)).doesNotContain("secret-internal-kafka");
        List<Map<String,Object>> fields = (List<Map<String,Object>>)metadata.details().get("fields");
        assertThat(fields).hasSize(5);
        assertThat(fields.get(0)).containsEntry("dataType","INTEGER").containsEntry("nullable",false)
                .containsEntry("sourceDataType","Integer").containsEntry("hasUnitCode",true).containsEntry("unit",null);
        assertThat(fields.get(2)).containsEntry("fieldPath","address.road").containsEntry("physicalName","address.road")
                .containsEntry("maxLength",100).containsEntry("ordinal",2);
        assertThat(fields.get(3)).containsEntry("dataType","OBJECT");
        assertThat(fields.get(4)).containsEntry("attributeType","Relationship").containsEntry("dataType","STRING");
        assertThat(calls).extracting(Call::path).containsExactly("/manager/datasets","/manager/datasets/jeju-parking","/manager/datamodels/Parking/1.0");
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.authorization()).isNull();
            assertThat(call.host()).startsWith("hub-test.invalid:");
        });
    }

    @Test void publicSpecAliasesAndDatasetWithoutModelAreAcceptedButConflictsAreNot() throws Exception {
        adapter = new KetiDataCoreAdapter(http,json,10);
        reply("/datasets","[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]");
        reply("/datasets/a","{\"id\":\"a\",\"name\":\"A\",\"keywords\":[\"항목\"],\"datamodelId\":\"M\"}");
        reply("/datamodels/M","{\"id\":\"M\",\"type\":\"M\",\"attributes\":[]}");
        reply("/datasets/b","{\"id\":\"b\",\"name\":\"B\",\"providingApiUri\":\"https://data.invalid/api?token=private\"}");
        reply("/datasets/c","{\"id\":\"c\",\"name\":\"C\",\"dataModelId\":\"M\",\"datamodelId\":\"Other\"}");
        var page = adapter.discover(context(""),null);
        assertThat(page.items().get(0).metadata().details()).containsEntry("datamodelId","M");
        assertThat(page.items().get(1).metadata().details()).doesNotContainKeys("fields","providingApiUri");
        assertThat(page.items().get(2).errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        assertThat(calls).allSatisfy(call -> assertThat(call.authorization()).isNull());
    }

    @Test void fullScanSortedCheckpointDetectsChangesAndNewRunStartsAtBeginning() throws Exception {
        reply("/datasets","[{\"id\":\"b\"},{\"id\":\"a\"}]");
        reply("/datasets/a","{\"id\":\"a\",\"name\":\"A\"}");
        reply("/datasets/b","{\"id\":\"b\",\"name\":\"B\"}");
        var first = adapter.discover(context(""),null);
        assertThat(first.items().getFirst().externalId()).isEqualTo("a");
        assertThat(first.complete()).isFalse();
        var second = adapter.discover(context(""),first.nextCheckpoint());
        assertThat(second.items().getFirst().externalId()).isEqualTo("b");
        assertThat(second.complete()).isTrue();
        assertThat(adapter.discover(context(""),null).items().getFirst().externalId()).isEqualTo("a");
        reply("/datasets","[{\"id\":\"a\"},{\"id\":\"c\"}]");
        assertThatThrownBy(() -> adapter.discover(context(""),first.nextCheckpoint())).isInstanceOfSatisfying(AdapterFailure.class,
                e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.INVALID_SCHEMA));
        assertThat(calls).allSatisfy(call -> assertThat(call.path()).doesNotContain("?"));
    }

    @Test void rejectsPartialWrappedDuplicateAndMalformedListings() throws Exception {
        for (String body : List.of("{\"data\":[]}","[{\"id\":\"same\"},{\"id\":\"same\"}]","[] {}","[{\"id\":\"../admin\"}]")) {
            reply("/datasets",body);
            assertThatThrownBy(() -> adapter.check(context(""))).isInstanceOfSatisfying(AdapterFailure.class,
                    e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.INVALID_SCHEMA));
        }
        responses.put("/manager/datasets",new Reply(200,"[]",Map.of("totalCount","200"),0));
        assertThatThrownBy(() -> adapter.check(context(""))).isInstanceOf(AdapterFailure.class);
        responses.put("/manager/datasets",new Reply(200,"[]",Map.of("totalCount","0"),0));
        adapter.check(context(""));
    }

    @Test void wrongModelIdAndDuplicateNestedFieldsFailItem() throws Exception {
        reply("/datasets","[{\"id\":\"a\"}]");
        reply("/datasets/a","{\"id\":\"a\",\"name\":\"A\",\"dataModelId\":\"M\"}");
        reply("/datamodels/M","{\"id\":\"Wrong\",\"type\":\"M\",\"attributes\":[]}");
        assertThat(adapter.discover(context(""),null).items().getFirst().errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        reply("/datamodels/M","{\"id\":\"M\",\"type\":\"M\",\"attributes\":[{\"name\":\"n\",\"valueType\":\"String\"},{\"name\":\"n\",\"valueType\":\"Double\"}]}");
        assertThat(adapter.discover(context(""),null).items().getFirst().errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
    }

    @Test void largeSchemasSplitAtNormalizedPageLimitAndOversizedItemIsExplicitFailure() throws Exception {
        adapter = new KetiDataCoreAdapter(http,json,10);
        reply("/datasets","[{\"id\":\"a\"},{\"id\":\"b\"}]");
        reply("/datasets/a","{\"id\":\"a\",\"name\":\"A\",\"dataModelId\":\"M\"}");
        reply("/datasets/b","{\"id\":\"b\",\"name\":\"B\",\"dataModelId\":\"M\"}");
        List<Map<String,Object>> attributes = new ArrayList<>();
        for (int i=0;i<60;i++) attributes.add(Map.of("name","f"+i,"valueType","String","description","x".repeat(3000)));
        reply("/datamodels/M",json.writeValueAsString(Map.of("id","M","type","M","attributes",attributes)));
        var first = adapter.discover(context(""),null);
        assertThat(first.complete()).isFalse();
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().metadata()).isNotNull();
        var second = adapter.discover(context(""),first.nextCheckpoint());
        assertThat(second.complete()).isTrue();
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().externalId()).isEqualTo("b");
        for (int i=60;i<85;i++) attributes.add(Map.of("name","f"+i,"valueType","String","description","x".repeat(3000)));
        reply("/datamodels/M",json.writeValueAsString(Map.of("id","M","type","M","attributes",attributes)));
        assertThat(adapter.discover(context(""),null).items()).allSatisfy(item -> {
            assertThat(item.metadata()).isNull(); assertThat(item.errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        });
    }

    @Test void uriModelIdStaysOnApprovedManagerAndKetiDateRetainsTimestampMeaning() throws Exception {
        String modelId = "https://schema.invalid/Parking/1.0";
        reply("/datasets","[{\"id\":\"a\"}]");
        reply("/datasets/a",json.writeValueAsString(Map.of("id","a","name","A","dataModelId",modelId)));
        reply("/datamodels/"+modelId,json.writeValueAsString(Map.of("id",modelId,"type","Parking","attributes",
                List.of(Map.of("name","observedTime","valueType","Date")))));
        var metadata = adapter.discover(context(""),null).items().getFirst().metadata();
        assertThat(metadata).isNotNull();
        var fields = (List<Map<String,Object>>) metadata.details().get("fields");
        assertThat(fields.getFirst()).containsEntry("dataType","DATETIME").containsEntry("sourceDataType","Date");
        assertThat(calls).extracting(Call::path).containsExactly("/manager/datasets","/manager/datasets/a",
                "/manager/datamodels/https://schema.invalid/Parking/1.0");
        assertThat(calls).allSatisfy(call -> assertThat(call.host()).startsWith("hub-test.invalid:"));
        for (String invalidId : List.of("https://schema.invalid/a/../b","https://name:secret@schema.invalid/M",
                "https://schema.invalid/M?key=secret","https://schema.invalid/M#part")) {
            reply("/datasets/a",json.writeValueAsString(Map.of("id","a","name","A","dataModelId",invalidId)));
            assertThat(adapter.discover(context(""),null).items().getFirst().errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        }
        assertThat(calls.stream().filter(call -> call.path().contains("datamodels"))).hasSize(1);
    }

    @Test void httpCredentialsAndInvalidSecretsNeverSendRequests() throws Exception {
        reply("/datasets","[]");
        for (String bad : List.of("{\"type\":\"API_KEY\",\"header\":\"X-Hub-Key\",\"value\":\"local-key\"}",
                "{\"type\":\"BEARER\",\"token\":\"local-token\"}",
                "token-only","{\"type\":\"OAUTH\"}","{\"type\":\"API_KEY\",\"header\":\"Host\",\"value\":\"bad.invalid\"}",
                "{\"type\":\"BEARER\",\"token\":\"line\\r\\nbreak\"}"))
            assertThatThrownBy(() -> adapter.check(context(bad))).isInstanceOfSatisfying(AdapterFailure.class,
                    e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.AUTHENTICATION_FAILED));
        assertThat(calls).isEmpty();
    }

    @Test void mapsStatusWithoutBodyLeakAndNeverFollowsRedirect() throws Exception {
        Map<Integer,AdapterFailure.Reason> cases = Map.of(401,AdapterFailure.Reason.AUTHENTICATION_FAILED,
                403,AdapterFailure.Reason.AUTHENTICATION_FAILED,429,AdapterFailure.Reason.RATE_LIMITED,
                503,AdapterFailure.Reason.SOURCE_UNAVAILABLE,302,AdapterFailure.Reason.SOURCE_REJECTED);
        for (var entry : cases.entrySet()) {
            responses.put("/manager/datasets",new Reply(entry.getKey(),"private-error-token",
                    Map.of("Retry-After","99999","Location",origin+"/private"),0));
            assertThatThrownBy(() -> adapter.check(context(""))).isInstanceOfSatisfying(AdapterFailure.class,e -> {
                assertThat(e.reason()).isEqualTo(entry.getValue()); assertThat(e.getMessage()).doesNotContain("private-error-token");
                if (entry.getKey()==429) assertThat(e.retryAfterSeconds()).isEqualTo(3600);
            });
        }
        assertThat(calls).hasSize(5).allSatisfy(call -> assertThat(call.path()).isEqualTo("/manager/datasets"));
    }

    @Test void boundsChunkedResponseAndCancelsSlowRequest() throws Exception {
        reply("/datasets","[" + " ".repeat(3000) + "]");
        var small = new HubAdapter.Context(URI.create(origin+"/manager"),new char[0],3,1024);
        assertThatThrownBy(() -> adapter.check(small)).isInstanceOfSatisfying(AdapterFailure.class,
                e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.RESPONSE_TOO_LARGE));
        responses.put("/manager/datasets",new Reply(200,"[]",Map.of(),1500));
        var shortTimeout = new HubAdapter.Context(URI.create(origin+"/manager"),new char[0],1,1024);
        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.check(shortTimeout)).isInstanceOfSatisfying(AdapterFailure.class,
                e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.TIMEOUT));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(2500);
    }

    @Test void exactHttpOriginAndApprovedPinsRequired() throws Exception {
        assertThatThrownBy(() -> new EndpointPolicy("","").validate(origin)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new EndpointPolicy("",origin).validate(origin+"/a/../admin")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ApprovedHubHttpTransport(new EndpointPolicy("",origin),json,
                "{\"hub-test.invalid\":[\"127.0.0.1\"]}","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedHubHttpTransport(new EndpointPolicy("",origin),json,
                "{\"127.0.0.1\":[\"8.8.8.8\"]}","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovedHubHttpTransport(new EndpointPolicy("",origin),json,
                "{\"hub-test.invalid\":[\"169.254.169.254\"]}","169.254.169.254")).isInstanceOf(IllegalArgumentException.class);
        try (var noPins = new ApprovedHubHttpTransport(new EndpointPolicy("",origin),json,"{}","")) {
            assertThatThrownBy(() -> noPins.get(context(""),"/datasets",System.nanoTime()+TimeUnit.SECONDS.toNanos(1)))
                    .isInstanceOfSatisfying(AdapterFailure.class,e -> assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.SOURCE_REJECTED));
        }
        assertThat(calls).isEmpty();
    }

    @Test void mountedSecretsAreBoundedAndCannotEscapeDirectory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("jeju"));
        String secret = "{\"type\":\"BEARER\",\"token\":\"only-test\"}";
        Files.writeString(root.resolve("jeju/data-manager.json"),secret);
        var provider = new MountedFileSecretProvider(root);
        assertThat(provider.available()).isTrue();
        assertThat(provider.resolve("jeju/data-manager")).isEqualTo(secret.toCharArray());
        for (String bad : List.of("../outside","jeju/../data-manager","/absolute","jeju/data-manager.json","missing"))
            assertThatThrownBy(() -> provider.resolve(bad)).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getMessage()).doesNotContain(root.toString(),"only-test"));
        Files.writeString(root.resolve("large.json"),"x".repeat(16385));
        assertThatThrownBy(() -> provider.resolve("large")).isInstanceOf(DomainException.class);
    }
}

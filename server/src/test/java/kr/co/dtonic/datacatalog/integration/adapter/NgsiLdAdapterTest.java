package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NgsiLdAdapterTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Map<Integer,Reply> pages=new ConcurrentHashMap<>();
    private final List<Call> calls=new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ApprovedHubHttpTransport transport;
    private NgsiLdAdapter adapter;
    private String origin;
    private record Reply(int status,String body,String count) {}
    private record Call(String path,String accept,String auth) {}

    @BeforeEach void start() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String uri=exchange.getRequestURI().toString();
            calls.add(new Call(uri,exchange.getRequestHeaders().getFirst("Accept"),exchange.getRequestHeaders().getFirst("Authorization")));
            int offset=0;
            for(String pair:exchange.getRequestURI().getQuery()==null?new String[0]:exchange.getRequestURI().getQuery().split("&"))
                if(pair.startsWith("offset="))offset=Integer.parseInt(pair.substring(7));
            Reply reply=pages.getOrDefault(offset,new Reply(200,"[]",null));
            if(reply.count()!=null)exchange.getResponseHeaders().set("NGSILD-Results-Count",reply.count());
            exchange.getResponseHeaders().set("Content-Type","application/ld+json");
            byte[] bytes=reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(),bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}finally{exchange.close();}
        });
        server.start();origin="http://ngsi-fixture.invalid:"+server.getAddress().getPort();
        transport=new ApprovedHubHttpTransport(new EndpointPolicy("",origin),json,"{\"ngsi-fixture.invalid\":[\"127.0.0.1\"]}","127.0.0.1");
        adapter=new NgsiLdAdapter(transport,json,1,"{}");
    }
    @AfterEach void stop(){transport.close();server.stop(0);}
    private HubAdapter.Context context(){return new HubAdapter.Context(URI.create(origin+"/broker/ngsi-ld/v1"),new char[0],10,1048576);}
    private void page(int offset,String body,String count){pages.put(offset,new Reply(200,body,count));}
    private String entity(String suffix,String title,String modified,double value)throws Exception{
        return json.writeValueAsString(Map.of("id","urn:ngsi-ld:Environment:"+suffix,"type","Environment",
            "@context",List.of("https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld",Map.of("@vocab","https://schema.example/","name","https://schema.org/name")),
            "name",Map.of("type","Property","value",title),"modifiedAt",modified,
            "temperature",Map.of("type","Property","value",value,"unitCode","CEL","observedAt",modified),
            "locatedIn",Map.of("type","Relationship","object","urn:ngsi-ld:Location:SyntheticLab")));
    }
    private void invalid(Throwing action){assertThatThrownBy(action::run).isInstanceOfSatisfying(AdapterFailure.class,e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.INVALID_SCHEMA));}
    private interface Throwing {void run()throws Exception;}

    @Test void fixedQueriesReadBothPagesAndProjectOnlyMetadata()throws Exception{
        page(0,"["+entity("B","Synthetic B","2026-10-27T00:00:00Z",928374.125)+"]","2");
        page(1,"["+entity("A","Synthetic A","2026-10-27T00:00:00Z",928374.125)+"]","2");
        var first=adapter.discover(context(),null);
        assertThat(first.complete()).isFalse();assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().externalId()).isEqualTo("urn:ngsi-ld:Environment:A");
        var metadata=first.items().getFirst().metadata();
        assertThat(metadata.title()).isEqualTo("Synthetic A");
        assertThat(metadata.details()).containsKeys("contextTerms","sourceEntityId","sourceModifiedAt","entityTypes");
        assertThat(json.writeValueAsString(metadata)).doesNotContain("928374.125","SyntheticLab","synthetic-only");
        var fields=json.valueToTree(metadata.details().get("fields"));
        assertThat(fields.toString()).contains("Relationship","https://schema.example/locatedIn");
        var second=adapter.discover(context(),first.nextCheckpoint());
        assertThat(second.complete()).isTrue();assertThat(second.items().getFirst().externalId()).endsWith(":B");
        assertThat(adapter.resumesFromPreviousRun()).isFalse();
        assertThat(adapter.capabilities()).doesNotContain(HubAdapter.Capability.FETCH_RECORDS,HubAdapter.Capability.FETCH_SAMPLE);
        assertThat(calls).hasSize(4).allSatisfy(call->{
            assertThat(call.path()).matches("/broker/ngsi-ld/v1/entities\\?limit=1&offset=[01]&options=sysAttrs&count=true&local=true");
            assertThat(call.accept()).isEqualTo("application/ld+json");assertThat(call.auth()).isNull();
        });
    }

    @Test void absentCountUsesBoundedEmptyTerminationAndEmptyBrokerIsValid()throws Exception{
        page(0,"["+entity("A","A","2026-10-27T00:00:00Z",1.5)+"]",null);page(1,"[]",null);
        assertThat(adapter.discover(context(),null).complete()).isTrue();
        assertThat(calls).hasSize(2);page(0,"[]","0");calls.clear();
        assertThat(adapter.discover(context(),null).items()).isEmpty();assertThat(calls).hasSize(1);
    }

    @Test void rejectsCountsRepeatingPagesWrappedBodyAndDuplicateKeys()throws Exception{
        String row=entity("A","A","2026-10-27T00:00:00Z",1.5);
        page(0,"["+row+"]","2");page(1,"[]","2");invalid(()->adapter.discover(context(),null));
        page(1,"["+row+"]","2");invalid(()->adapter.discover(context(),null));
        page(1,"[]","1");invalid(()->adapter.discover(context(),null));
        for(String body:List.of("{\"data\":[]}","[] {}","[{\"id\":\"urn:a\",\"id\":\"urn:b\"}]")){
            page(0,body,null);invalid(()->adapter.discover(context(),null));
        }
        page(0,"[]","10001");
        assertThatThrownBy(()->adapter.discover(context(),null)).isInstanceOfSatisfying(AdapterFailure.class,e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.RESPONSE_TOO_LARGE));
    }

    @Test void invalidEntityContextIsExplicitItemFailureAndCheckRejectsIt()throws Exception{
        String bad=entity("A","A","2026-10-27T00:00:00Z",1.5).replace("https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld","https://unapproved.invalid/context");
        page(0,"["+bad+"]","1");
        var result=adapter.discover(context(),null);
        assertThat(result.items().getFirst().errorCode()).isEqualTo("SOURCE_ITEM_INVALID");
        assertThat(result.items().getFirst().metadata()).isNull();invalid(()->adapter.check(context()));
        assertThat(calls).allSatisfy(call->assertThat(call.path()).startsWith("/broker/ngsi-ld/v1/entities?"));
    }

    @Test void semanticCursorDetectsChangedMetadataButNotObservationOnlyUpdates()throws Exception{
        page(0,"["+entity("A","A","2026-10-27T00:00:00Z",1.5)+"]","2");
        page(1,"["+entity("B","B","2026-10-27T00:00:00Z",1.5)+"]","2");
        var first=adapter.discover(context(),null);
        page(0,"["+entity("A","A","2026-10-27T01:00:00Z",8.5)+"]","2");
        assertThat(adapter.discover(context(),first.nextCheckpoint()).complete()).isTrue();
        page(0,"["+entity("A","Changed title","2026-10-27T01:00:00Z",8.5)+"]","2");
        invalid(()->adapter.discover(context(),first.nextCheckpoint()));
        invalid(()->adapter.discover(context(),"malformed"));
    }

    @Test void typedRequestRetainsAuthenticationOriginAndByteLimitDefenses()throws Exception{
        for(var pair:Map.of(401,AdapterFailure.Reason.AUTHENTICATION_FAILED,302,AdapterFailure.Reason.SOURCE_REJECTED).entrySet()){
            pages.put(0,new Reply(pair.getKey(),"{\"message\":\"never-copy-secret\"}",null));
            assertThatThrownBy(()->adapter.check(context())).isInstanceOfSatisfying(AdapterFailure.class,e->{
                assertThat(e.reason()).isEqualTo(pair.getValue());assertThat(e.getMessage()).doesNotContain("never-copy-secret");
            });
        }
        calls.clear();
        var denied=new HubAdapter.Context(URI.create("http://not-approved.invalid/broker"),new char[0],2,1048576);
        assertThatThrownBy(()->adapter.check(denied)).isInstanceOfSatisfying(AdapterFailure.class,e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.SOURCE_REJECTED));
        assertThat(calls).isEmpty();
        assertThatThrownBy(()->transport.get(context(),"/entities?anything=unrestricted",System.nanoTime()+1000000000L)).isInstanceOf(AdapterFailure.class);
        assertThatThrownBy(()->transport.getNgsiEntities(context(),101,0,System.nanoTime()+1000000000L)).isInstanceOf(AdapterFailure.class);
        assertThat(calls).isEmpty();
        page(0,"["+entity("A","A","2026-10-27T00:00:00Z",1.5)+"]","1");
        var tiny=new HubAdapter.Context(context().endpoint(),new char[0],2,32);
        assertThatThrownBy(()->adapter.check(tiny)).isInstanceOfSatisfying(AdapterFailure.class,e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.RESPONSE_TOO_LARGE));
    }

    @Test void normalizedSnapshotExpansionHasItsOwnMemoryLimit()throws Exception{
        adapter=new NgsiLdAdapter(transport,json,10,"{}");
        var template=json.createObjectNode();template.put("id","urn:ngsi-ld:Sensor:template");template.put("type","Sensor");
        template.set("@context",json.valueToTree(Map.of("@vocab","https://schema.example/")));
        for(int i=0;i<450;i++)template.set("field"+i,json.valueToTree(Map.of("type","Property","value",928374.125)));
        var projected=new NgsiLdMetadataNormalizer(json,"{}").normalize(template);
        int one=kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata.encode(projected.details()).getBytes(StandardCharsets.UTF_8).length;
        assertThat(one).isBetween(120*1024,219*1024);
        int total=(16*1024*1024)/one+3;
        assertThat(total).isLessThan(900);
        long rawBytes=0;
        for(int offset=0;offset<total;offset+=10){
            var body=json.createArrayNode();
            for(int index=offset;index<Math.min(total,offset+10);index++){
                var source=template.deepCopy();source.put("id","urn:ngsi-ld:Sensor:sample"+index);body.add(source);
            }
            String encoded=json.writeValueAsString(body);int size=encoded.getBytes(StandardCharsets.UTF_8).length;
            assertThat(size).isLessThan(1048576);rawBytes+=size;page(offset,encoded,Integer.toString(total));
        }
        assertThat(rawBytes).isLessThan(16*1024*1024);
        var bounded=new HubAdapter.Context(context().endpoint(),new char[0],30,1048576);
        assertThatThrownBy(()->adapter.discover(bounded,null)).isInstanceOfSatisfying(AdapterFailure.class,
            e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.RESPONSE_TOO_LARGE));
    }
}

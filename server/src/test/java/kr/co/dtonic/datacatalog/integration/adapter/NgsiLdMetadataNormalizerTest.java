package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NgsiLdMetadataNormalizerTest {
    private static final String NS="https://uri.etsi.org/ngsi-ld/";
    private final ObjectMapper json=new ObjectMapper();
    private final NgsiLdMetadataNormalizer normalizer=new NgsiLdMetadataNormalizer(json,"{}");

    private ObjectNode base()throws Exception {
        return (ObjectNode)json.readTree("""
          {"id":"urn:ngsi-ld:Sensor:synthetic001","type":"Sensor",
           "@context":["https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context.jsonld",
             {"Sensor":"https://example.org/Sensor","temperature":"https://example.org/temperature",
              "name":"https://schema.org/name","description":"https://schema.org/description"}],
           "name":{"type":"Property","value":"Synthetic sample metadata"},
           "description":{"type":"Property","value":"Schema only"},
           "temperature":{"type":"Property","value":21.5,"unitCode":"CEL","observedAt":"2026-10-27T10:00:00Z"}}
          """);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> fields(HubAdapter.Metadata metadata){return (List<Map<String,Object>>)metadata.details().get("fields");}
    private Map<String,Object> field(HubAdapter.Metadata metadata,String uri){return fields(metadata).stream().filter(f->uri.equals(f.get("attributeUri"))).findFirst().orElseThrow();}
    private void refused(JsonNode entity) {
        assertThatThrownBy(()->normalizer.normalize(entity)).isInstanceOfSatisfying(AdapterFailure.class,e->assertThat(e.reason()).isEqualTo(AdapterFailure.Reason.INVALID_SCHEMA));
    }

    @Test void normalizedPropertiesProjectSchemaAndNeverRetainObservationOrRelationshipTargets()throws Exception {
        var source=base();
        source.set("owner",json.readTree("{\"type\":\"Relationship\",\"object\":\"urn:ngsi-ld:PrivatePerson:very-secret\"}"));
        source.set("active",json.readTree("{\"type\":\"Property\",\"value\":true}"));
        var result=normalizer.normalize(source);
        assertThat(result.externalId()).isEqualTo(source.path("id").asText());
        assertThat(result.title()).isEqualTo("Synthetic sample metadata");
        var temperature=field(result,"https://example.org/temperature");
        assertThat(temperature).containsEntry("dataType","NUMBER").containsEntry("unit","CEL").containsEntry("hasObservedAt",true);
        assertThat(field(result,NS+"default-context/owner")).containsEntry("attributeType","Relationship").containsEntry("dataType","STRING");
        String encoded=json.writeValueAsString(result.details());
        assertThat(encoded).doesNotContain("very-secret","21.5","2026-10-27T10:00:00Z","\"value\"","\"object\"");
    }

    @Test void normalizedJsonValueArraysRemainArraysIncludingEmptyAndSingleton()throws Exception {
        for(String value:List.of("[1,2]","[1]","[]","[\"a\",\"b\"]","[{\"@value\":\"actual array member\"}]")) {
            var source=base();source.set("samples",json.readTree("{\"type\":\"Property\",\"value\":"+value+"}"));
            assertThat(field(normalizer.normalize(source),NS+"default-context/samples")).containsEntry("dataType","ARRAY");
        }
    }

    @Test void expandedJsonLdPreservesSemanticAttributeIriAndInfersTypedTimestamp()throws Exception {
        var source=json.readTree("""
          {"@id":"urn:ngsi-ld:Sensor:expanded","@type":["https://example.org/Sensor"],
           "https://schema.org/name":[{"@type":["https://uri.etsi.org/ngsi-ld/Property"],"https://uri.etsi.org/ngsi-ld/hasValue":[{"@value":"Expanded synthetic sample"}]}],
           "https://example.org/capturedAt":[{"@type":["https://uri.etsi.org/ngsi-ld/Property"],"https://uri.etsi.org/ngsi-ld/hasValue":[{"@type":"http://www.w3.org/2001/XMLSchema#dateTime","@value":"2026-10-27T10:00:00+09:00"}]}],
           "https://example.org/linked":[{"@type":["https://uri.etsi.org/ngsi-ld/Relationship"],"https://uri.etsi.org/ngsi-ld/hasObject":[{"@id":"urn:ngsi-ld:Thing:private-target"}]}]}
          """);
        var result=normalizer.normalize(source);
        assertThat(result.title()).isEqualTo("Expanded synthetic sample");
        assertThat(field(result,"https://example.org/capturedAt")).containsEntry("dataType","DATETIME");
        assertThat(field(result,"https://example.org/capturedAt").get("fieldPath").toString()).startsWith("uri_").hasSize(36);
        assertThat(json.writeValueAsString(result.details())).doesNotContain("private-target","2026-10-27T10:00:00+09:00");
    }

    @Test void xsdDateAndDateTimeLexicalValuesAreValidatedRatherThanGuessed()throws Exception {
        for(var test:List.of(new String[]{"date","2024-02-29Z","DATE"},new String[]{"dateTime","2026-10-27T10:00:00","DATETIME"},new String[]{"dateTimeStamp","2026-10-27T10:00:00Z","DATETIME"})) {
            var source=base();source.set("typed",json.readTree("{\"type\":\"Property\",\"value\":{\"@type\":\"http://www.w3.org/2001/XMLSchema#"+test[0]+"\",\"@value\":\""+test[1]+"\"}}"));
            assertThat(field(normalizer.normalize(source),NS+"default-context/typed")).containsEntry("dataType",test[2]);
        }
        for(var test:List.of(new String[]{"date","2025-02-29"},new String[]{"dateTime","not-a-time"},new String[]{"dateTimeStamp","2026-10-27T10:00:00"})) {
            var source=base();source.set("typed",json.readTree("{\"type\":\"Property\",\"value\":{\"@type\":\"http://www.w3.org/2001/XMLSchema#"+test[0]+"\",\"@value\":\""+test[1]+"\"}}"));refused(source);
        }
    }

    @Test void configuredContextsAreResolvedLocallyWithPrefixAndVocab()throws Exception {
        String configuration="""
          {"https://example.org/local-context.jsonld":{"@context":{"@vocab":"https://example.org/vocab/","@version":1.1,"@protected":true,"ex":{"@id":"https://example.org/","@prefix":true},"reading":{"@id":"ex:reading","@container":"@set","@protected":false}}}}
          """;
        var mapper=new NgsiLdMetadataNormalizer(json,configuration);
        var source=(ObjectNode)json.readTree("{\"id\":\"urn:ngsi-ld:Sensor:configured\",\"type\":\"Sensor\",\"@context\":\"https://example.org/local-context.jsonld\",\"reading\":{\"type\":\"Property\",\"value\":12}}");
        var result=mapper.normalize(source);
        assertThat(result.details().get("entityTypes")).isEqualTo(List.of("https://example.org/vocab/Sensor"));
        assertThat(field(result,"https://example.org/reading")).containsEntry("dataType","INTEGER");
        assertThat(result.details().get("context")).isEqualTo(List.of("https://example.org/local-context.jsonld"));
    }

    @Test void unknownContextAndSensitiveContextAddressesAreRefusedWithoutNetworkOrSourceMessages()throws Exception {
        for(String context:List.of("https://unknown.invalid/context.jsonld","https://user:secret@example.org/context","https://example.org/context?token=secret")) {
            var source=base();source.put("@context",context);refused(source);
        }
    }

    @Test void contextAliasingCyclesAndDuplicateSemanticAttributesAreRejected()throws Exception {
        var source=base();source.set("@context",json.readTree("{\"first\":\"second\",\"second\":\"first\"}"));refused(source);
        source=base();source.set("@context",json.readTree("{\"id\":\"https://evil.invalid/id\"}"));refused(source);
        source=base();source.set("https://example.org/temperature",source.get("temperature").deepCopy());refused(source);
    }

    @Test void configRejectsRemoteWrappersUnknownControlsAndUnsupportedContainerSemantics()throws Exception {
        for(String body:List.of("\"https://external.invalid/context\"","{\"@context\":[{}]}","{\"@base\":\"https://example.org/\"}","{\"x\":{\"@id\":\"https://example.org/x\",\"@container\":\"@list\"}}","{\"x\":{\"@id\":\"https://example.org/x\",\"@prefix\":false}}","{\"x\":{\"@id\":\"https://example.org/x\",\"@protected\":\"yes\"}}","{\"x\":{\"@id\":\"https://example.org/x\",\"@type\":[]}}")) {
            String config="{\"https://example.org/context\":"+body+"}";
            assertThatThrownBy(()->new NgsiLdMetadataNormalizer(json,config)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid NGSI-LD context mapping configuration");
        }
    }

    @Test void contextConfigurationHasBoundedUriCountAndDuplicateKeyDetection()throws Exception {
        var config=json.createObjectNode();for(int i=0;i<33;i++)config.set("https://example.org/context/"+i,json.createObjectNode());
        assertThatThrownBy(()->new NgsiLdMetadataNormalizer(json,json.writeValueAsString(config))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NgsiLdMetadataNormalizer(json,"{\"https://example.org/c\":{},\"https://example.org/c\":{}}" )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void multipleTypesNestedSchemasAndGeoPropertyAreProjectedWithoutValues()throws Exception {
        var source=base();source.set("type",json.readTree("[\"Sensor\",\"https://example.org/Device\"]"));
        source.set("location",json.readTree("{\"type\":\"GeoProperty\",\"value\":{\"type\":\"Point\",\"coordinates\":[126.1,33.4]}}"));
        ((ObjectNode)source.get("temperature")).set("accuracy",json.readTree("{\"type\":\"Property\",\"value\":0.000237}"));
        var result=normalizer.normalize(source);
        assertThat(result.details().get("entityTypes")).isEqualTo(List.of("https://example.org/Device","https://example.org/Sensor"));
        assertThat(field(result,NS+"default-context/location")).containsEntry("dataType","OBJECT");
        assertThat(field(result,NS+"default-context/accuracy").get("fieldPath").toString()).contains(".");
        assertThat(json.writeValueAsString(result.details())).doesNotContain("126.1","33.4","0.000237");
    }

    @Test void metadataRevisionIgnoresObservationAndSourceModificationTimeButKeepsProvenance()throws Exception {
        var first=base();first.put("modifiedAt","2026-10-27T10:00:00Z");
        var second=first.deepCopy();second.put("modifiedAt","2026-10-27T11:00:00Z");
        ((ObjectNode)second.get("temperature")).put("value",902.5).put("observedAt","2026-10-27T11:00:00Z");
        var a=normalizer.normalize(first);var b=normalizer.normalize(second);
        assertThat(a.sourceRevision()).isEqualTo(b.sourceRevision());
        assertThat(a.details().get("sourceModifiedAt")).isEqualTo("2026-10-27T10:00:00Z");
        assertThat(b.details().get("sourceModifiedAt")).isEqualTo("2026-10-27T11:00:00Z");
        ((ObjectNode)second.get("name")).put("value","Different descriptive title");
        assertThat(normalizer.normalize(second).sourceRevision()).isNotEqualTo(a.sourceRevision());
    }

    @Test void malformedDatesRelationshipsAndUnsupportedAttributeKindsAreBoundedFailures()throws Exception {
        var source=base();source.put("modifiedAt","bad-time");refused(source);
        source=base();source.set("owner",json.readTree("{\"type\":\"Relationship\",\"object\":[]}"));refused(source);
        source=base();source.set("owner",json.readTree("{\"type\":\"Relationship\",\"object\":\"https://example.org/id?token=secret\"}"));refused(source);
        source=base();source.set("language",json.readTree("{\"type\":\"LanguageProperty\",\"languageMap\":{\"ko\":\"data\"}}"));refused(source);
    }

    @Test void languageTagsValidateSubtagsWithoutRecursiveMatching() throws Exception {
        for (String tag : List.of("ko", "en-US", "zh-Hant-TW", "de-CH-1996", "a" + "-b".repeat(49))) {
            var source = base();
            ((ObjectNode) source.get("name")).set("value", json.createObjectNode().put("@value", "공개 이름").put("@language", tag));
            assertThat(normalizer.normalize(source).title()).isEqualTo("공개 이름");
        }
        for (String tag : List.of("", "-ko", "ko-", "ko--KR", "123", "abcdefghi", "ko-123456789",
                "ko_KR", "a" + "-b".repeat(49) + "!", "a-".repeat(10000))) {
            var source = base();
            ((ObjectNode) source.get("name")).set("value", json.createObjectNode().put("@value", "공개 이름").put("@language", tag));
            refused(source);
        }
    }

    @Test void sourceIdentifiersAndDescriptiveFieldsAreBounded()throws Exception {
        var source=base();source.put("id","https://example.org/entity?token=secret");refused(source);
        source=base();((ObjectNode)source.get("name")).put("value","x".repeat(301));refused(source);
        source=base();((ObjectNode)source.get("description")).put("value","");
        assertThat(normalizer.normalize(source).description()).isEmpty();
    }

    @Test void allSupportedGeoGeometryKindsValidateAndDiscardCoordinates()throws Exception {
        for(String geometry:List.of(
            "{\"type\":\"Point\",\"coordinates\":[126.1,33.4,10]}",
            "{\"type\":\"MultiPoint\",\"coordinates\":[[126.1,33.4],[126.2,33.5]]}",
            "{\"type\":\"LineString\",\"coordinates\":[[126.1,33.4],[126.2,33.5]]}",
            "{\"type\":\"MultiLineString\",\"coordinates\":[[[126.1,33.4],[126.2,33.5]]]}",
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.1,33.4],[126.2,33.4],[126.2,33.5],[126.1,33.4]]]}",
            "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[126.1,33.4],[126.2,33.4],[126.2,33.5],[126.1,33.4]]]]}",
            "{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[126.1,33.4]}]}")) {
            var source=base();source.set("location",json.readTree("{\"type\":\"GeoProperty\",\"value\":"+geometry+"}"));
            var result=normalizer.normalize(source);
            assertThat(field(result,NS+"default-context/location")).containsEntry("dataType","OBJECT");
            assertThat(json.writeValueAsString(result.details())).doesNotContain("126.1","33.4","coordinates","geometries");
        }
    }

    @Test void malformedGeoCoordinateShapeAndRingClosureAreRejected()throws Exception {
        for(String geometry:List.of(
            "{\"type\":\"Point\",\"coordinates\":[126.1]}",
            "{\"type\":\"Point\",\"coordinates\":[\"126.1\",33.4]}",
            "{\"type\":\"Point\",\"coordinates\":[181,33.4]}",
            "{\"type\":\"LineString\",\"coordinates\":[[126.1,33.4]]}",
            "{\"type\":\"Polygon\",\"coordinates\":[[[126.1,33.4],[126.2,33.4],[126.2,33.5],[126.3,33.5]]]}",
            "{\"type\":\"GeometryCollection\",\"geometries\":{}}",
            "{\"type\":\"Bogus\",\"coordinates\":[126.1,33.4]}")) {
            var source=base();source.set("location",json.readTree("{\"type\":\"GeoProperty\",\"value\":"+geometry+"}"));refused(source);
        }
    }
}

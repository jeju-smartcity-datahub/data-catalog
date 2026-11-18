package kr.co.dtonic.datacatalog.delivery.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import kr.co.dtonic.datacatalog.catalog.api.DatasetDetail.PublishedField;
import kr.co.dtonic.datacatalog.delivery.api.DataAccessModels.DataAccessInput;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatasetRecordsServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private PublishedField field(String name){return new PublishedField(name,name,name,"","STRING",true,"");}
    @Test void projectsOnlyAllowlistedFieldsAndUnwrapsNgsiProperties() throws Exception {
        var row=json.readTree("""
            {"datasetId":"A","secret":"PRIVATE","speed":{"type":"Property","value":40},
             "address":{"type":"Property","value":{"city":"제주","private":"HIDDEN"}},"road":{"type":"Relationship","object":"road:1"}}
            """);
        var result=DatasetRecordsService.project(List.of(row),List.of(field("speed"),field("address.city"),field("road")),json).getFirst();
        assertThat(result).containsExactly(entry("speed",40),entry("address.city","제주"),entry("road","road:1"));
        assertThat(result).doesNotContainKeys("datasetId","secret","address.private");
    }
    @Test void deniesObjectsWithUnpublishedChildrenAndPreservesScalarArrays() throws Exception {
        var result=DatasetRecordsService.project(List.of(json.readTree("{\"data\":{\"secret\":\"PRIVATE\"},\"tags\":[\"A\",\"B\"]}")),List.of(field("data"),field("tags")),json).getFirst();
        assertThat(result.get("data")).isNull();assertThat(result.get("tags")).isEqualTo(List.of("A","B"));
    }
    @Test void readsNgsiChildAttributesAlongsidePropertyValue() throws Exception {
        var row=json.readTree("{\"speed\":{\"type\":\"Property\",\"value\":40,\"accuracy\":{\"type\":\"Property\",\"value\":0.9},\"secret\":\"HIDDEN\"}}");
        var result=DatasetRecordsService.project(List.of(row),List.of(field("speed"),field("speed.accuracy")),json).getFirst();
        assertThat(result).containsExactly(entry("speed",40),entry("speed.accuracy",0.9));
    }
    @Test void exposesOnlyGeoJsonTypeAndNumericCoordinates() throws Exception {
        var result=DatasetRecordsService.project(List.of(json.readTree("{\"location\":{\"type\":\"GeoProperty\",\"value\":{\"type\":\"Point\",\"coordinates\":[126.5,33.4],\"secret\":\"HIDDEN\"}}}")),List.of(field("location")),json).getFirst();
        assertThat(json.writeValueAsString(result)).contains("coordinates","126.5").doesNotContain("secret","HIDDEN");
    }
    @Test void rejectsUrlTraversalAndQueryInjection() {
        for(String path:List.of("//other/entities","/a/../entities","/entities?url=x","/entities%2fother","http://host/entities"))
            assertThatThrownBy(()->DatasetRecordsService.validate(input(path,"datasetId",Map.of()))).isInstanceOf(kr.co.dtonic.datacatalog.common.DomainException.class);
    }
    @Test void rejectsSecretOrPaginationFixedQueryOverrides() {
        for(String key:List.of("apiKey","token","limit","offset","q","datasetId","url"))
            assertThatThrownBy(()->DatasetRecordsService.validate(input("/entities","datasetId",Map.of(key,"A")))).isInstanceOf(kr.co.dtonic.datacatalog.common.DomainException.class);
    }
    @Test void acceptsBoundedTypeConstraintButRequiresDatasetScope() {
        assertThatCode(()->DatasetRecordsService.validate(input("/entities","datasetId",Map.of("type","Road")))).doesNotThrowAnyException();
        assertThatThrownBy(()->DatasetRecordsService.validate(input("/entities","datasetId;secret",Map.of()))).isInstanceOf(kr.co.dtonic.datacatalog.common.DomainException.class);
    }
    @Test void ketiModePinsDatasetQueryContract() {
        assertThatCode(()->DatasetRecordsService.validate(new DataAccessInput(UUID.randomUUID(),true,"KETI_DATASET","/entities","datasetId","A","datasetId",Map.of()))).doesNotThrowAnyException();
        assertThatThrownBy(()->DatasetRecordsService.validate(new DataAccessInput(UUID.randomUUID(),true,"KETI_DATASET","/entities","type","Road","type",Map.of()))).isInstanceOf(kr.co.dtonic.datacatalog.common.DomainException.class);
    }
    @Test void timestampsUseOnlyReturnedRowsAndPublicAttributesWithoutInventingMissingDates() throws Exception {
        var first=json.readTree("""
            {"modifiedAt":"2026-11-07T12:00:00Z",
             "speed":{"type":"Property","value":40,"observedAt":"2026-11-07T12:05:00Z","modifiedAt":"2026-11-07T12:06:00Z"},
             "secret":{"type":"Property","value":"PRIVATE","observedAt":"2026-12-31T12:00:00Z"}}
            """);
        var second=json.readTree("{\"speed\":{\"type\":\"Property\",\"value\":50,\"observedAt\":\"invalid\"}}");
        assertThat(DatasetRecordsService.latestRecordTimestamp(List.of(first,second),List.of(field("speed")),"observedAt"))
                .isEqualTo(java.time.OffsetDateTime.parse("2026-11-07T12:05:00Z"));
        assertThat(DatasetRecordsService.latestRecordTimestamp(List.of(first,second),List.of(field("speed")),"modifiedAt"))
                .isEqualTo(java.time.OffsetDateTime.parse("2026-11-07T12:06:00Z"));
        assertThat(DatasetRecordsService.latestRecordTimestamp(List.of(second),List.of(field("speed")),"observedAt")).isNull();
        assertThat(DatasetRecordsService.latestRecordTimestamp(List.of(),List.of(field("speed")),"modifiedAt")).isNull();
    }
    @Test void acceptsOnlyNonnegativeBoundedUpstreamCount() {
        assertThat(DatasetRecordsService.parseCount("0")).isZero();
        assertThat(DatasetRecordsService.parseCount("1001")).isEqualTo(1001L);
        for(String value:List.of("-1","unknown","1.1","9223372036854775808","1e4",""))
            assertThat(DatasetRecordsService.parseCount(value)).isNull();
        assertThat(DatasetRecordsService.parseCount(null)).isNull();
    }
    private DataAccessInput input(String path,String scope,Map<String,String> query){return new DataAccessInput(UUID.randomUUID(),true,"NGSI_LD",path,scope,"A","q",query);}
}

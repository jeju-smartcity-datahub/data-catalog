package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.persistence.CatalogMapper;
import kr.co.dtonic.datacatalog.catalog.persistence.PublicationRow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogHubMetadataTest {
    @Test void legacyPublicationWithoutHubMetadataRemainsReadable() {
        var result = detail("{}");
        assertThat(result.hubMetadata().providerOrganization()).isNull();
        assertThat(result.hubMetadata().dataModelId()).isNull();
        assertThat(result.hubMetadata().sourceModifiedAt()).isNull();
        assertThat(result.fields()).isEmpty();
    }

    @Test void malformedOrStructuredHubValuesAreNotExposedAsPublicDescriptions() {
        var result = detail("""
                {"hubMetadata":{"providerOrganization":{"private":"PRIVATE_VALUE"},
                "providerSystem":"  GIS  ","ownership":"invalid\\nvalue","datamodelId":42,
                "sourceModifiedAt":"invalid-date"}}
                """);
        assertThat(result.hubMetadata().providerOrganization()).isNull();
        assertThat(result.hubMetadata().providerSystem()).isEqualTo("GIS");
        assertThat(result.hubMetadata().ownership()).isNull();
        assertThat(result.hubMetadata().dataModelId()).isNull();
        assertThat(result.hubMetadata().sourceModifiedAt()).isNull();
    }

    @Test void publicHubApiAddressExcludesCredentialsAndNonHttpSchemes() throws Exception {
        var json = new ObjectMapper();
        for (var value : java.util.List.of("http://192.0.2.22:8080/data", "https://hub.example/data")) {
            var metadata = json.writeValueAsString(java.util.Map.of("hubMetadata", java.util.Map.of("providingApiUri", value)));
            assertThat(detail(metadata).hubMetadata().providingApiUri()).isEqualTo(value);
        }
        for (var value : java.util.List.of("javascript:alert(1)", "https://user:secret@hub.example/data",
                "https://hub.example/data?token=secret", "https://hub.example/data#secret", "/relative")) {
            var metadata = json.writeValueAsString(java.util.Map.of("hubMetadata", java.util.Map.of("providingApiUri", value)));
            assertThat(detail(metadata).hubMetadata().providingApiUri()).isNull();
        }
    }

    private kr.co.dtonic.datacatalog.catalog.api.DatasetDetail detail(String metadata) {
        var id = UUID.randomUUID();
        var row = new PublicationRow(id.toString(), "게시 데이터", "설명",
                UUID.randomUUID().toString(), "제주도청", null, null, OffsetDateTime.parse("2026-11-02T00:00:00Z"),
                "METADATA_ONLY", 1, metadata,null);
        var mapper = new CatalogMapper() {
            @Override public PublicationRow detail(UUID datasetId, Integer publicationNo) { return row; }
        };
        return new CatalogQueryService(mapper, new ObjectMapper()).detail(id, null);
    }
}

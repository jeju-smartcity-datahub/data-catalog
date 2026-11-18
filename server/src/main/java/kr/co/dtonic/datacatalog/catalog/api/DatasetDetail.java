package kr.co.dtonic.datacatalog.catalog.api;

import java.time.OffsetDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary.NamedReference;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;

/** Explicit public allowlist; raw metadata JSON, reviews and credentials never leave the server. */
public record DatasetDetail(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NamedReference organization,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "분류 미지정 시 null") NamedReference category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime publishedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int publicationNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PublishedField> fields,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DatasetAccess access,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> regions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> hubs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> topics,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetadataDetails details,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PublishedHubMetadata hubMetadata,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CatalogFreshness freshness) {
    public record PublishedHubMetadata(
            @Schema(nullable = true) String providerOrganization,
            @Schema(nullable = true) String providerSystem,
            @Schema(nullable = true) String ownership,
            @Schema(nullable = true) String dataModelId,
            @Schema(nullable = true) OffsetDateTime sourceModifiedAt,
            @Schema(nullable = true, description = "허브에서 제공한 API 주소") String providingApiUri) {}

    public record PublishedField(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fieldPath,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String physicalName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String dataType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean nullable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String unit) {}
}

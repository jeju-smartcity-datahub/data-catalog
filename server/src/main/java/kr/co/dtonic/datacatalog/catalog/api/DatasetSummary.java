package kr.co.dtonic.datacatalog.catalog.api;

import java.time.OffsetDateTime;
import java.util.List;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatasetSummary(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NamedReference organization,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "분류 미지정 시 null") NamedReference category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime publishedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"METADATA_ONLY", "LINK_READY", "SERVICE_READY", "UNAVAILABLE"}) String accessState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> regions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> hubs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NamedReference> topics,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetadataDetails details,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,minimum="1",description="The exact publication version represented by this response row") int publicationNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CatalogFreshness freshness) {
    public record NamedReference(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {}
}

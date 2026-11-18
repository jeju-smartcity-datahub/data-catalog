package kr.co.dtonic.datacatalog.catalog.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record CatalogFacets(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String countMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FacetValue> organizations,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FacetValue> categories,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FacetValue> regions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FacetValue> hubs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FacetValue> topics) {
    public record FacetValue(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long count,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean selected) {}
}

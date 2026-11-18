package kr.co.dtonic.datacatalog.catalog.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatasetAccess(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int publicationNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> capabilities) {}

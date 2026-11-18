package kr.co.dtonic.datacatalog.catalog.api;

import java.time.OffsetDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatasetVersion(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int publicationNo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime publishedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean current) {}

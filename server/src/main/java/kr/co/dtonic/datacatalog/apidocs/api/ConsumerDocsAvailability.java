package kr.co.dtonic.datacatalog.apidocs.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공개 데이터셋의 메타정보 API와 데이터 이용 상태")
public record ConsumerDocsAvailability(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String datasetId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String publicationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int publicationVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"METADATA_ONLY", "LINK_READY", "SERVICE_READY", "UNAVAILABLE"}) String accessState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"METADATA_API", "RECORDS_API", "UNAVAILABLE"}) String documentKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "메타정보 JSON·DCAT(JSON-LD)·데이터 조회 API의 상대 경로") List<String> documents) {}

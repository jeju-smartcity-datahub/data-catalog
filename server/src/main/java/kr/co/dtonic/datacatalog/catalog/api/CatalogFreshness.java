package kr.co.dtonic.datacatalog.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record CatalogFreshness(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="최초 공개 게시 시각") OffsetDateTime firstPublishedAt,
        @Schema(nullable=true,description="해당 데이터셋 메타정보의 마지막 수집 시각") OffsetDateTime metadataCollectedAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="연결의 최근 메타정보 수집 상태",
                allowableValues={"SUCCEEDED","PARTIAL_FAILED","FAILED","QUEUED","RUNNING","RETRY_WAIT","DISABLED","NOT_LINKED","NOT_COLLECTED"})
        String collectionStatus) { }

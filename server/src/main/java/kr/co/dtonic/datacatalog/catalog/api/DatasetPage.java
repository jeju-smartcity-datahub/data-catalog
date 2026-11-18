package kr.co.dtonic.datacatalog.catalog.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record DatasetPage(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DatasetSummary> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PageMetadata page,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) kr.co.dtonic.datacatalog.analytics.SearchHistoryContracts.FeedbackOffer feedback) {
    public DatasetPage(List<DatasetSummary> items,PageMetadata page){this(items,page,null);}
    public record PageMetadata(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int number,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalElements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalPages) {}
}

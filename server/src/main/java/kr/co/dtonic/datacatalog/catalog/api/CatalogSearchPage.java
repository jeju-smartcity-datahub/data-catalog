package kr.co.dtonic.datacatalog.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CatalogSearchPage(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<DatasetSummary> items,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DatasetPage.PageMetadata page,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Interpretation interpretation,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Match> matches,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) kr.co.dtonic.datacatalog.analytics.SearchHistoryContracts.FeedbackOffer feedback) {
    @Schema(name="CatalogQueryInterpretation")
    public record Interpretation(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String ruleVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> terms,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String message) {}
    @Schema(name="CatalogSearchMatch")
    public record Match(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String datasetId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String summary,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Reason> reasons,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED, description="Deterministic rule points; not probability or measured accuracy") int score,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String scoreRule,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String detailPath) {}
    @Schema(name="CatalogSearchReason")
    public record Reason(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String term,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String field,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) int points) {}
}

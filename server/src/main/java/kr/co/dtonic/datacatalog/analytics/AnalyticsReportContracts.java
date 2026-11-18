package kr.co.dtonic.datacatalog.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AnalyticsReportContracts {
    private AnalyticsReportContracts() {}

    public record AnalyticsReportRequest(
            @NotNull @Pattern(regexp="SEARCH|ANALYSIS") @Schema(allowableValues={"SEARCH","ANALYSIS"}) String kind,
            @NotNull @Pattern(regexp="JSON|CSV") @Schema(allowableValues={"JSON","CSV"}) String format,
            UUID organizationId, @NotNull OffsetDateTime from, @NotNull OffsetDateTime to,
            @Pattern(regexp="DEFINITION_EXPORT|FIELD_MAPPING|DEFINITION_IMPORT") String analysisKind,
            @Pattern(regexp="SUCCEEDED|FAILED") String status,
            @Size(max=100) String query, Boolean hasQuery, Boolean zeroResults) {}

    @Schema(requiredProperties={"from","to"})
    public record AnalyticsReportFilters(OffsetDateTime from, OffsetDateTime to,
            @Schema(allowableValues={"DEFINITION_EXPORT","FIELD_MAPPING","DEFINITION_IMPORT"}) String analysisKind,
            @Schema(allowableValues={"SUCCEEDED","FAILED"}) String status,
            @Schema(description="분석 실행 UUID·엔진 버전·원본 해시에서만 찾는 ASCII 식별자. 공개 검색 원문이 아님") String query,
            Boolean hasQuery, Boolean zeroResults) {}

    @Schema(requiredProperties={"id","kind","format","status","rowCount","cutoff","createdAt","expiresAt","contentHash","fileName","filters"})
    public record AnalyticsReport(UUID id,
            @Schema(allowableValues={"SEARCH","ANALYSIS"}) String kind,
            @Schema(allowableValues={"JSON","CSV"}) String format,
            UUID organizationId, @Schema(allowableValues={"SUCCEEDED"}) String status,
            int rowCount, OffsetDateTime cutoff, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            String contentHash, String fileName, AnalyticsReportFilters filters) {}

    @Schema(requiredProperties={"runCount","succeededCount","failedCount","fieldCountSum","evidenceCount"})
    public record AnalysisStatistics(long runCount, long succeededCount, long failedCount, long fieldCountSum, long evidenceCount) {}
    public record ReportDownload(AnalyticsReport report, byte[] bytes) {
        public ReportDownload { bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof ReportDownload download && java.util.Objects.equals(report,download.report)
                    && java.util.Arrays.equals(bytes,download.bytes);
        }
        @Override public int hashCode() { return 31*java.util.Objects.hashCode(report)+java.util.Arrays.hashCode(bytes); }
        @Override public String toString() { return "ReportDownload[bytes="+bytes.length+"]"; }
    }
}

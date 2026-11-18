package kr.co.dtonic.datacatalog.delivery.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class DataAccessModels {
    private DataAccessModels() { }
    public record DataAccessInput(@NotNull UUID connectionId, boolean enabled,
            @NotBlank @Pattern(regexp="KETI_DATASET|NGSI_LD|JSON_ARRAY") String mode,
            @NotBlank @Size(max=500) String queryPath,
            @NotBlank @Size(max=200) String scopeField,
            @NotBlank @Size(max=300) String scopeValue,
            @NotBlank @Size(max=80) String scopeParameter,
            @NotNull @Size(max=10) Map<String,String> fixedQuery) { }
    @Schema(requiredProperties={"datasetId","configured","enabled","mode","queryPath","scopeField","scopeValue","scopeParameter","fixedQuery","lockVersion","ready"})
    public record DataAccessView(UUID datasetId, boolean configured, UUID connectionId, boolean enabled,
            String mode, String queryPath, String scopeField, String scopeValue, String scopeParameter,
            Map<String,String> fixedQuery, long lockVersion, boolean ready, OffsetDateTime checkedAt) { }
    @Schema(requiredProperties={"key","label","dataType","unit"})
    public record RecordColumn(String key,String label,String dataType,String unit) { }
    @Schema(requiredProperties={"datasetId","publicationNo","columns","items","offset","limit","hasMore","fetchedAt"})
    public record RecordPage(UUID datasetId,int publicationNo,List<RecordColumn> columns,
            List<Map<String,Object>> items,int offset,int limit,boolean hasMore,
            @Schema(nullable=true,description="허브가 제공한 조회 조건별 전체 행 수. 미제공 시 null") Long totalCount,
            @Schema(description="허브 데이터 조회 완료 시각") OffsetDateTime fetchedAt,
            @Schema(nullable=true,description="이번 응답 행의 modifiedAt 최댓값. 전체 데이터셋 갱신 시각이 아님") OffsetDateTime recordUpdatedAt,
            @Schema(nullable=true,description="이번 응답의 공개 필드 observedAt 최댓값. 전체 데이터셋 관측 시각이 아님") OffsetDateTime recordObservedAt) { }
    @Schema(requiredProperties={"settings","recordCount"})
    public record DataAccessCheck(DataAccessView settings,int recordCount) { }
}

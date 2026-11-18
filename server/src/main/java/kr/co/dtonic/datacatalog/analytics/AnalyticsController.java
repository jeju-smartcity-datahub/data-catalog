package kr.co.dtonic.datacatalog.analytics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.AnalysisEvidence;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1")
public class AnalyticsController {
    private final AnalyticsService service;
    private final SearchHistoryRepository searchHistory;
    private final SearchRetentionPolicy retention;
    private final kr.co.dtonic.datacatalog.identity.WorkflowAccess access;
    public AnalyticsController(AnalyticsService service,SearchHistoryRepository searchHistory,SearchRetentionPolicy retention,kr.co.dtonic.datacatalog.identity.WorkflowAccess access) { this.service=service;this.searchHistory=searchHistory;this.retention=retention;this.access=access; }
    @GetMapping("/search-history/{id}") @Operation(operationId="getSearchHistoryDetail",summary="정제 검색 표현·공개 결과 버전·선택 평가",description="플랫폼 관리자만 열람합니다. 원문 질의·이용자 ID·IP·자유서술은 없으며 만료 기록은 열람할 수 없습니다.")
    public SearchHistoryContracts.HistoryDetail searchDetail(@PathVariable UUID id,Authentication auth){
        if(!access.isPlatformAdmin(auth))throw new org.springframework.security.access.AccessDeniedException("Platform administrator required");
        return searchHistory.detail(id,retention.now());
    }
    @GetMapping("/search-history") @Operation(operationId="listSearchHistory",summary="공개 검색 이력",description="플랫폼 관리자 전용. 원문 검색어와 IP는 저장하지 않음. from 포함/to 제외, 최대 31일.")
    public List<SearchEvent> history(@RequestParam OffsetDateTime from,@RequestParam OffsetDateTime to,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="50") @Min(1) @Max(100) int size,
            @RequestParam(required=false) Boolean hasQuery,@RequestParam(required=false) Boolean zeroResults,Authentication auth) {
        return service.history(from,to,page,size,hasQuery,zeroResults,auth);
    }
    @GetMapping("/analytics/search-statistics") @Operation(operationId="getSearchStatistics",summary="저장된 공개 검색 이벤트 집계",description="from 포함/to 제외. 건수·무결과 수·총 결과 수·총 처리시간(ms). 평균=총 처리시간/검색건수, 없는 데이터는 0.")
    public SearchStatistics statistics(@RequestParam OffsetDateTime from,@RequestParam OffsetDateTime to,
            @RequestParam(required=false) Boolean hasQuery,@RequestParam(required=false) Boolean zeroResults,Authentication auth) { return service.statistics(from,to,hasQuery,zeroResults,auth); }
    @GetMapping("/analysis-runs") @Operation(operationId="listAnalysisRuns",summary="기관 필드 매핑·정의서 처리 이력",description="기관의 필드 매핑, JSON 정의서 가져오기 및 CSV·JSON 내보내기 이력을 조회한다.")
    public List<AnalysisRun> analyses(@RequestParam UUID organizationId,@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int size,@RequestParam(required=false) OffsetDateTime from,
            @RequestParam(required=false) OffsetDateTime to,@RequestParam(required=false) String kind,@RequestParam(required=false) String status,
            @RequestParam(required=false) @Size(max=100) String query,Authentication auth) { return service.analyses(organizationId,page,size,from,to,kind,status,query,auth); }
    @GetMapping("/analytics/analysis-statistics") @Operation(operationId="getAnalysisStatistics",summary="기관 분석 이력의 실제 집계",description="목록과 같은 기간·방식·상태·식별자 조건. from 포함/to 제외, 최대31일. 저장된 실행/성공/실패/필드/근거 수만 집계하며 미저장 실패를 추정하지 않음.")
    public AnalyticsReportContracts.AnalysisStatistics analysisStatistics(@RequestParam UUID organizationId,@RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,@RequestParam(required=false) String kind,@RequestParam(required=false) String status,
            @RequestParam(required=false) @Size(max=100) String query,Authentication auth) { return service.analysisStatistics(organizationId,from,to,kind,status,query,auth); }
    @GetMapping("/analysis-runs/{id}") @Operation(operationId="getAnalysisRun",summary="기관 분석 실행 상세")
    public AnalysisRun analysis(@PathVariable UUID id,Authentication auth) { return service.analysis(id,auth); }
    @GetMapping("/analysis-runs/{id}/evidence") @Operation(operationId="getAnalysisEvidence",summary="원본 해시·추출 위치·불변 필드 snapshot 근거")
    public List<AnalysisEvidence> evidence(@PathVariable UUID id,Authentication auth) { return service.evidence(id,auth); }
}

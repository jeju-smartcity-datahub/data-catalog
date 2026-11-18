package kr.co.dtonic.datacatalog.analytics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts.*;

@RestController
@RequestMapping("/api/admin/v1/analytics/reports")
public class AnalyticsReportController {
    private final AnalyticsReportService service;
    public AnalyticsReportController(AnalyticsReportService service) { this.service=service; }
    @PostMapping(produces="application/json")
    @Operation(operationId="createAnalyticsReport",summary="현재 권한과 조건을 고정한 분석·검색 보고서",description="31일/5,000행/8MiB 상한 안에서 동기 생성. 요청자 본인만 현재 권한 재검사 후 24시간 열람 가능. 원문 검색어·IP는 포함하지 않음.")
    @ApiResponse(responseCode="201",description="불변 보고서 생성 완료",useReturnTypeSchema=true)
    public ResponseEntity<AnalyticsReport> create(@Valid @RequestBody AnalyticsReportRequest input,@RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth,HttpServletRequest request) {
        var report=service.create(input,key,auth,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.created(URI.create("/api/admin/v1/analytics/reports/"+report.id())).cacheControl(CacheControl.noStore()).body(report);
    }
    @GetMapping(value="/{id}",produces="application/json") @Operation(operationId="getAnalyticsReport",summary="본인 보고서 조건·기준시점·해시 조회")
    public ResponseEntity<AnalyticsReport> get(@PathVariable UUID id,Authentication auth) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id,auth)); }
    @GetMapping(value="/{id}/file",produces={"application/json","text/csv"}) @Operation(operationId="downloadAnalyticsReport",summary="권한·기관 활성·만료를 재검사한 보고서 파일")
    public ResponseEntity<byte[]> file(@PathVariable UUID id,Authentication auth) {
        var result=service.download(id,auth);var report=result.report();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+report.fileName()+"\"")
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(report.format().equals("JSON")?"application/json;charset=UTF-8":"text/csv;charset=UTF-8"))
                .contentLength(result.bytes().length).eTag("\""+report.contentHash()+"\"").body(result.bytes());
    }
}

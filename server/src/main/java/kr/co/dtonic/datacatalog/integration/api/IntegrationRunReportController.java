package kr.co.dtonic.datacatalog.integration.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import kr.co.dtonic.datacatalog.integration.application.IntegrationRunReportService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationRunReportController {
    private final IntegrationRunReportService reports;
    public IntegrationRunReportController(IntegrationRunReportService reports) { this.reports = reports; }

    @Operation(operationId = "downloadIntegrationRunReport", summary = "수집 작업의 항목·등록후보 처리 리포트",
            description = "기존 수집 작업의 기관·역할 접근검사를 재사용합니다. json/csv, 최대10,000항목/10MiB. "
                    + "원천식별자는SHA256로 출력하고 메타데이터 원문·주소·자격증명·계정ID는 포함하지 않습니다. "
                    + "후속 수집으로 원천이 변경되면 과거 항목해시만 보존하며 최신 후보를 과거 후보로 표시하지 않습니다.")
    @GetMapping("/api/admin/v1/sync-runs/{runId}/report")
    @ApiResponse(responseCode = "200", description = "기관 접근검사를 통과한 수집 작업 리포트 다운로드",
            content = {@Content(mediaType = "application/json", schema = @Schema(type = "string", format = "binary")),
                       @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary"))})
    public ResponseEntity<byte[]> download(@PathVariable UUID runId,
            @RequestParam(defaultValue = "json") String format, Authentication auth) {
        var file = reports.download(runId, format, auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff").body(file.content());
    }
}

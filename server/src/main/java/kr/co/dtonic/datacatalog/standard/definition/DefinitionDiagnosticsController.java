package kr.co.dtonic.datacatalog.standard.definition;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.DefinitionDiagnostics;

@RestController
@RequestMapping("/api/admin/v1/analysis-runs")
public class DefinitionDiagnosticsController {
    private final DefinitionImportService service;
    public DefinitionDiagnosticsController(DefinitionImportService service) { this.service=service; }
    @GetMapping("/{id}/definition-diagnostics")
    @Operation(operationId="getDefinitionAnalysisDiagnostics",summary="정의서 규칙 점수·항목별 미생성 사유",description="기존 분석 이력과 같은 기관 감사 권한. 실패 기록도 고정 사유코드·입력 위치만 제공하며 원문 값은 반환하지 않습니다. 과거 기록은 NOT_RECORDED입니다.")
    public ResponseEntity<DefinitionDiagnostics> get(@PathVariable UUID id,Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.analysisDiagnostics(id,auth));
    }
}

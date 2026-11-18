package kr.co.dtonic.datacatalog.standard.definition;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.*;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/v1/definition-imports")
public class DefinitionImportController {
    private final DefinitionImportService service;
    private final ApiProblems problems;
    public DefinitionImportController(DefinitionImportService service,ApiProblems problems) { this.service=service;this.problems=problems; }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Operation(operationId="importMetadataDefinition",summary="제한된 JSON 정의서 업로드·필드 추출",description="UTF-8 JSON {fields:[FieldDefinition]} 형식의 정의서를 검증하고 등록한다. 크기 1 MiB, 중첩 깊이 16, 필드 500개까지 허용하며 등록 시 201을 반환한다.")
    @ApiResponse(responseCode="201",description="정의서 검증·추출 완료",useReturnTypeSchema=true)
    public ResponseEntity<DefinitionImport> create(@RequestParam UUID organizationId,@RequestParam(required=false) UUID datasetId,
            @RequestPart("file") MultipartFile file,Authentication auth,HttpServletRequest request) throws IOException {
        if(file.getSize()<1 || file.getSize()>DefinitionParser.MAX_BYTES) throw new IllegalArgumentException("Definition file size limit");
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.create(organizationId,datasetId,file.getBytes(),auth,requestId(request)));
    }
    @GetMapping("/{id}") @Operation(operationId="getDefinitionImport",summary="검증된 정의서 필드·경고·출처 조회")
    public ResponseEntity<DefinitionImport> get(@PathVariable UUID id,Authentication auth) { return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(id,auth)); }
    @GetMapping("/{id}/evidence") @Operation(operationId="getDefinitionImportEvidence",summary="원본 해시와 JSON pointer별 추출 근거")
    public List<AnalysisEvidence> evidence(@PathVariable UUID id,Authentication auth) { return service.evidence(id,auth); }
    @PostMapping("/{id}/applications") @Operation(operationId="applyDefinitionImport",summary="선택한 추출 필드로 초안 필드 전체 교체",description="현재 초안의 필드 전체를 선택한 추출 필드로 교체한다. If-Match+Idempotency-Key 필수. 검토·승인·게시를 수행하지 않는다.")
    @ApiResponse(responseCode="201",description="정의서 필드 적용 기록 생성",useReturnTypeSchema=true)
    public ResponseEntity<ApplicationResult> apply(@PathVariable UUID id,@Valid @RequestBody ApplyDefinition body,
            @RequestHeader(value="If-Match",required=false) String ifMatch,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request) {
        var result=service.apply(id,body,ifMatch,key,auth,requestId(request));
        return ResponseEntity.status(201).eTag(GovernanceSupport.etag("dataset",body.datasetId().toString(),result.fields().lockVersion())).body(result);
    }
    @ExceptionHandler(DefinitionRejectedException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> rejected(DefinitionRejectedException failure,HttpServletRequest request) {
        var body=problems.create(failure.status(),failure.code(),failure.getMessage(),request);
        body.setProperty("analysisRunId",failure.analysisRunId());body.setProperty("definitionDiagnostics",failure.diagnostics());
        return ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(body);
    }
    private String requestId(HttpServletRequest request) { return java.util.Objects.toString(request.getAttribute(RequestIdFilter.ATTRIBUTE),UUID.randomUUID().toString()); }
}

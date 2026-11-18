package kr.co.dtonic.datacatalog.standard.api;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.DraftFieldsService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/datasets/{id}")
public class DraftFieldsController {
    private final DraftFieldsService service;
    public DraftFieldsController(DraftFieldsService service) { this.service=service; }
    @GetMapping("/draft/fields") @Operation(operationId="getDraftFields",summary="초안 필드 및 승인 용어 연결 조회")
    public ResponseEntity<FieldSet> get(@PathVariable UUID id,Authentication auth) { return response(id,service.get(id,auth)); }
    @GetMapping("/draft/field-mappings") @Operation(operationId="getDraftFieldMappings",summary="초안 필드와 매핑 근거 조회")
    public ResponseEntity<FieldSet> mappings(@PathVariable UUID id,Authentication auth) { return response(id,service.get(id,auth)); }
    @PutMapping("/draft/fields") @Operation(operationId="replaceDraftFields",summary="초안 필드 전체 교체")
    public ResponseEntity<FieldSet> replace(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @Valid @RequestBody FieldsInput body,Authentication auth,HttpServletRequest request) {
        return response(id,service.replace(id,ifMatch,body,auth,requestId(request)));
    }
    @PutMapping("/draft/field-mappings") @Operation(operationId="replaceDraftFieldMappings",summary="승인 용어 매핑 전체 교체")
    public ResponseEntity<FieldSet> map(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @Valid @RequestBody MappingsInput body,Authentication auth,HttpServletRequest request) {
        return response(id,service.map(id,ifMatch,body,auth,requestId(request)));
    }
    @GetMapping("/definition") @Operation(operationId="exportMetadataDefinition",summary="현재 관리 버전의 정의서 JSON 또는 안전 CSV 추출")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,@RequestParam(defaultValue="json") String format,Authentication auth) {
        byte[] content=service.export(id,format,auth);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"definition-"+id+"."+format+"\"")
                .contentType(format.equals("csv")?MediaType.parseMediaType("text/csv;charset=UTF-8"):MediaType.APPLICATION_JSON).body(content);
    }
    private ResponseEntity<FieldSet> response(UUID id,FieldSet fields) { return ResponseEntity.ok().eTag(GovernanceSupport.etag("dataset",id.toString(),fields.lockVersion())).body(fields); }
    private String requestId(HttpServletRequest request) { return java.util.Objects.toString(request.getAttribute(RequestIdFilter.ATTRIBUTE),UUID.randomUUID().toString()); }
}

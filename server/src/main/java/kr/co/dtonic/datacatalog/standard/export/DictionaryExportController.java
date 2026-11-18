package kr.co.dtonic.datacatalog.standard.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportModels.*;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1")
public class DictionaryExportController {
    private final DictionaryExportService service;
    public DictionaryExportController(DictionaryExportService service){this.service=service;}
    @PostMapping(value="/dictionary-versions/{dictionaryId}/exports",produces="application/json")
    @Operation(operationId="createDictionaryExport",summary="선택한 사전 버전과 조건을 고정한 XLSX 추출")
    @ApiResponse(responseCode="202",description="추출 결과 생성",useReturnTypeSchema=true)
    public ResponseEntity<DictionaryExport> create(@PathVariable UUID dictionaryId,@Valid @RequestBody ExportRequest input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth,HttpServletRequest request){
        var result=service.create(dictionaryId,input,key,auth,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.accepted().location(URI.create("/api/admin/v1/dictionary-exports/"+result.id())).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping(value="/dictionary-exports/{id}",produces="application/json") @Operation(operationId="getDictionaryExport",summary="사전 추출 조건과 결과 정보")
    public DictionaryExport get(@PathVariable UUID id,Authentication auth){return service.get(id,auth);}
    @GetMapping(value="/dictionary-exports/{id}/file",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(operationId="downloadDictionaryExport",summary="사전 버전 XLSX 다운로드")
    public ResponseEntity<byte[]> file(@PathVariable UUID id,Authentication auth){
        var result=service.download(id,auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+result.export().fileName()+"\"")
                .header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(result.bytes().length).eTag("\""+result.export().contentHash()+"\"").body(result.bytes());
    }
}

package kr.co.dtonic.datacatalog.standard.search;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import kr.co.dtonic.datacatalog.standard.search.StandardLookupContracts.*;

@RestController
@RequestMapping("/api/admin/v1")
public class StandardLookupController {
    private final StandardLookupService service;
    public StandardLookupController(StandardLookupService service){this.service=service;}
    @GetMapping("/dictionary-entries/{id}/field-usages")
    @Operation(operationId="listTermFieldUsages",summary="승인 용어를 연결한 지정 기관의 최신 관리 필드 조회")
    public ResponseEntity<TermFieldUsagePage> usages(@PathVariable UUID id,@RequestParam UUID organizationId,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size,Authentication auth){return response(service.usages(id,organizationId,page,size,auth));}
    @GetMapping("/dictionary-terms")
    @Operation(operationId="searchApprovedTerms",summary="공공 및 지정 기관의 승인된 동일 자료형 용어 검색")
    public ResponseEntity<TermCandidatePage> search(@RequestParam UUID organizationId,@RequestParam @Pattern(regexp="STRING|INTEGER|NUMBER|BOOLEAN|DATE|DATETIME|OBJECT|ARRAY") String dataType,
            @RequestParam(required=false) @Size(max=200) String q,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size,Authentication auth){return response(service.search(organizationId,dataType,q,page,size,auth));}
    @GetMapping("/datasets/{id}/draft/term-suggestions")
    @Operation(operationId="suggestTermsForSavedField",summary="저장된 필드의 이름·식별자 일치에 근거한 승인 용어 후보")
    public ResponseEntity<TermCandidatePage> suggestions(@PathVariable UUID id,@RequestParam @NotBlank @Size(max=200) String fieldPath,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size,Authentication auth){return response(service.suggestions(id,fieldPath,page,size,auth));}
    private <T> ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}

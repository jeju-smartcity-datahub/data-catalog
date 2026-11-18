package kr.co.dtonic.datacatalog.standard.api;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.DictionaryService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1")
public class DictionaryController {
    private final DictionaryService service;
    public DictionaryController(DictionaryService service) { this.service=service; }
    @GetMapping("/dictionaries") @Operation(operationId="listDictionaries",summary="공공 및 지정 기관 사전 버전 조회")
    public List<Dictionary> list(@RequestParam(required=false) UUID organizationId,Authentication auth) { return service.list(organizationId,auth); }
    @PostMapping("/dictionaries") @Operation(operationId="createOrganizationDictionary",summary="기관 사전 버전 생성")
    @ApiResponse(responseCode="201",description="기관 사전 버전 생성",useReturnTypeSchema=true)
    public ResponseEntity<Dictionary> create(@Valid @RequestBody DictionaryInput body,Authentication auth,HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.create(body,auth,requestId(request)));
    }
    @GetMapping("/dictionaries/{dictionaryId}/entries") @Operation(operationId="listDictionaryEntries",summary="사전 버전의 단어·용어·도메인")
    public List<DictionaryEntry> entries(@PathVariable UUID dictionaryId,@RequestParam(required=false) String kind,
            @RequestParam(required=false) @Size(max=200) String q,@RequestParam(required=false) @Pattern(regexp="DRAFT|IN_REVIEW|APPROVED|REJECTED") String state,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="50") @Min(1) @Max(100) int size,Authentication auth) {
        return service.entries(dictionaryId,kind,q,state,page,size,auth);
    }
    @PostMapping("/dictionaries/{dictionaryId}/entries") @Operation(operationId="createDictionaryEntry",summary="기관 사전 초안 항목 생성")
    @ApiResponse(responseCode="201",description="사전 초안 항목 생성",useReturnTypeSchema=true)
    public ResponseEntity<DictionaryEntry> createEntry(@PathVariable UUID dictionaryId,@Valid @RequestBody EntryInput body,Authentication auth,HttpServletRequest request) {
        var entry=service.createEntry(dictionaryId,body,auth,requestId(request));
        return ResponseEntity.status(201).eTag(GovernanceSupport.etag("dictionary-entry",entry.id(),entry.lockVersion())).body(entry);
    }
    @GetMapping("/dictionary-entries/{id}") @Operation(operationId="getDictionaryEntry",summary="사전 항목 상세")
    public ResponseEntity<DictionaryEntry> get(@PathVariable UUID id,Authentication auth) { return response(service.get(id,auth)); }
    @PatchMapping("/dictionary-entries/{id}") @Operation(operationId="updateDictionaryEntry",summary="기관 사전 초안 수정")
    public ResponseEntity<DictionaryEntry> edit(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @Valid @RequestBody EntryInput body,Authentication auth,HttpServletRequest request) { return response(service.edit(id,ifMatch,body,auth,requestId(request))); }
    @PostMapping("/dictionary-entries/{id}/review-requests") @Operation(operationId="submitDictionaryEntry",summary="기관 사전 항목 검토 요청")
    public ResponseEntity<DictionaryEntry> submit(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            Authentication auth,HttpServletRequest request) { return response(service.submit(id,ifMatch,auth,requestId(request))); }
    @PostMapping("/dictionary-entries/{id}/decisions") @Operation(operationId="decideDictionaryEntry",summary="기관 사전 항목 승인·반려")
    public ResponseEntity<DictionaryEntry> decide(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @Valid @RequestBody Decision body,Authentication auth,HttpServletRequest request) { return response(service.decide(id,ifMatch,body,auth,requestId(request))); }
    private ResponseEntity<DictionaryEntry> response(DictionaryEntry entry) { return ResponseEntity.ok().eTag(GovernanceSupport.etag("dictionary-entry",entry.id(),entry.lockVersion())).body(entry); }
    private String requestId(HttpServletRequest request) { return java.util.Objects.toString(request.getAttribute(kr.co.dtonic.datacatalog.common.RequestIdFilter.ATTRIBUTE),UUID.randomUUID().toString()); }
}

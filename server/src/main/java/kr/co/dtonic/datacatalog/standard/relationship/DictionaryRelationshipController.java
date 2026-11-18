package kr.co.dtonic.datacatalog.standard.relationship;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipModels.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/admin/v1/dictionary-entries/{id}",produces="application/json")
public class DictionaryRelationshipController {
    private final DictionaryRelationshipService service;
    public DictionaryRelationshipController(DictionaryRelationshipService service){this.service=service;}
    @GetMapping("/relationships") @Operation(operationId="getDictionaryRelationships",summary="단어·도메인 속성과 용어 구성 참조")
    public ResponseEntity<EntryRelationships> get(@PathVariable UUID id,Authentication auth){return response(service.get(id,auth));}
    @PutMapping("/word") @Operation(operationId="replaceDictionaryWord",summary="초안 단어의 영어명·약어·동의어 설정")
    public ResponseEntity<EntryRelationships> word(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            @Valid @RequestBody WordAttributes input,Authentication auth,HttpServletRequest request){return response(service.word(id,etag,input,auth,requestId(request)));}
    @PutMapping("/domain") @Operation(operationId="replaceDictionaryDomain",summary="초안 도메인의 길이·정밀도·허용값 설정")
    public ResponseEntity<EntryRelationships> domain(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            @Valid @RequestBody DomainAttributes input,Authentication auth,HttpServletRequest request){return response(service.domain(id,etag,input,auth,requestId(request)));}
    @PutMapping("/term") @Operation(operationId="replaceDictionaryTerm",summary="초안 용어의 승인 도메인과 순서 있는 단어 설정")
    public ResponseEntity<EntryRelationships> term(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            @Valid @RequestBody TermAttributes input,Authentication auth,HttpServletRequest request){return response(service.term(id,etag,input,auth,requestId(request)));}
    private ResponseEntity<EntryRelationships> response(EntryRelationships value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(GovernanceSupport.etag("dictionary-entry",value.entryId().toString(),value.lockVersion())).body(value);}
    private String requestId(HttpServletRequest request){return (String)request.getAttribute(RequestIdFilter.ATTRIBUTE);}
}

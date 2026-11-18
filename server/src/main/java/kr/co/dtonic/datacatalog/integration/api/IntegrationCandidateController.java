package kr.co.dtonic.datacatalog.integration.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.integration.application.IntegrationCandidateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.*;

@RestController
@RequestMapping("/api/admin/v1")
public class IntegrationCandidateController {
    private final IntegrationCandidateService service;
    public IntegrationCandidateController(IntegrationCandidateService service) { this.service=service; }
    @GetMapping("/connections/{id}/candidates")
    public CandidatePage collectedCandidates(@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,Authentication auth) { return service.list(id,page,size,auth); }
    @GetMapping("/candidates/{id}")
    public ResponseEntity<CandidateDetail> collectedCandidate(@PathVariable UUID id,Authentication auth) {
        var detail=service.detail(id,auth);return ResponseEntity.ok().eTag(detail.etag()).body(detail);
    }
    @PostMapping("/candidates/{id}/applications")
    public CandidateApplication applyCollectedCandidate(@PathVariable UUID id,@Valid @RequestBody ApplyCandidate input,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request) {
        return service.apply(id,input,etag,key,auth,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}

package kr.co.dtonic.datacatalog.integration.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.integration.application.IntegrationPublicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.integration.api.IntegrationPublicationModels.*;

@RestController
@RequestMapping("/api/admin/v1/connections/{id}")
public class IntegrationPublicationController {
    private final IntegrationPublicationService service;
    public IntegrationPublicationController(IntegrationPublicationService service){this.service=service;}
    @GetMapping("/publication-policy") public ResponseEntity<PublicationPolicy> policy(@PathVariable UUID id,Authentication auth){return response(service.policy(id,auth));}
    @PutMapping("/publication-policy") public ResponseEntity<PublicationPolicy> save(@PathVariable UUID id,@Valid @RequestBody PublicationPolicyInput input,
            @RequestHeader(value="If-Match",required=false) String etag,Authentication auth,HttpServletRequest request){
        return response(service.savePolicy(id,input,etag,auth,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    @PostMapping("/publication-preview") public PublicationPreview preview(@PathVariable UUID id,@Valid @RequestBody PublicationPreviewInput input,Authentication auth){return service.preview(id,input,auth);}
    @PostMapping("/publications") public BatchPublicationResult publish(@PathVariable UUID id,@Valid @RequestBody PublishCandidatesInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth,HttpServletRequest request){
        return service.publish(id,input,key,auth,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
    @GetMapping("/publication-results") public PublicationHistory history(@PathVariable UUID id,Authentication auth){return service.history(id,auth);}
    private ResponseEntity<PublicationPolicy> response(PublicationPolicy value){return ResponseEntity.ok().eTag(IntegrationPublicationService.policyEtag(value)).body(value);}
}

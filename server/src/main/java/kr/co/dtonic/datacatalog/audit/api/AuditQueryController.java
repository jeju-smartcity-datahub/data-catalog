package kr.co.dtonic.datacatalog.audit.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.dtonic.datacatalog.audit.application.AuditQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/audit-events")
public class AuditQueryController {
    private final AuditQueryService service;
    public AuditQueryController(AuditQueryService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<AuditEventPage> listAuditEvents(Authentication auth,
            @RequestParam(required=false) UUID organizationId,
            @RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(auth,organizationId,q,page,size));
    }
    @Schema(requiredProperties={"items","number","size","totalElements","totalPages"})
    public record AuditEventPage(List<Event> items,int number,int size,long totalElements,long totalPages) {}
    @Schema(name="AuditEventSummary",requiredProperties={"id","occurredAt","actorUserId","action","targetType","targetId","requestId","result"})
    public record Event(UUID id,OffsetDateTime occurredAt,UUID actorUserId,String action,String targetType,UUID targetId,String requestId,String result) {}
}

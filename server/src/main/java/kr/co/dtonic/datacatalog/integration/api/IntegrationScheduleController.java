package kr.co.dtonic.datacatalog.integration.api;

import jakarta.validation.Valid;
import java.util.UUID;
import kr.co.dtonic.datacatalog.integration.application.IntegrationScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.integration.api.IntegrationScheduleModels.*;

@RestController
@RequestMapping("/api/admin/v1/connections/{id}/schedule")
public class IntegrationScheduleController {
    private final IntegrationScheduleService service;
    public IntegrationScheduleController(IntegrationScheduleService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ScheduleView> get(Authentication auth, @PathVariable UUID id) {
        return response(service.get(auth, id));
    }

    @PutMapping
    public ResponseEntity<ScheduleView> update(Authentication auth, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String ifMatch, @Valid @RequestBody ScheduleInput input) {
        return response(service.update(auth, id, IntegrationScheduleService.version(ifMatch, id), input));
    }

    private ResponseEntity<ScheduleView> response(ScheduleView view) {
        return ResponseEntity.ok().eTag(IntegrationScheduleService.etag(view)).body(view);
    }
}

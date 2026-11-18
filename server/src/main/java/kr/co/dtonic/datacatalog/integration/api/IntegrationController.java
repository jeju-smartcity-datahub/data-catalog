package kr.co.dtonic.datacatalog.integration.api;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.integration.api.IntegrationModels.*;

@RestController
@RequestMapping("/api/admin/v1")
public class IntegrationController {
    private final IntegrationService service;
    public IntegrationController(IntegrationService service) { this.service = service; }

    @GetMapping("/connections")
    public List<ConnectionView> listConnections(Authentication auth, @RequestParam UUID organizationId) { return service.list(auth, organizationId); }

    @PostMapping("/connections")
    @ApiResponse(responseCode="201", description="연결 설정 생성", useReturnTypeSchema=true)
    public ResponseEntity<ConnectionView> createConnection(Authentication auth, @Valid @RequestBody ConnectionInput input) {
        var view = service.create(auth, input);
        return ResponseEntity.created(URI.create("/api/admin/v1/connections/" + view.id())).eTag(IntegrationService.etag(view)).body(view);
    }

    @GetMapping("/connections/{id}")
    public ResponseEntity<ConnectionView> getConnection(Authentication auth, @PathVariable UUID id) { return connection(service.get(auth, id)); }

    @PatchMapping("/connections/{id}")
    public ResponseEntity<ConnectionView> updateConnection(Authentication auth, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String ifMatch, @Valid @RequestBody ConnectionInput input) {
        return connection(service.update(auth, id, IntegrationService.version(ifMatch, id), input));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<ConnectionView> disableConnection(Authentication auth, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String ifMatch) {
        return connection(service.disable(auth, id, IntegrationService.version(ifMatch, id)));
    }

    @PostMapping("/connections/{id}/checks")
    @ApiResponse(responseCode="202", description="연결 검사 작업 접수. 검사 성공을 뜻하지 않으며 작업 상태를 조회해야 한다.", useReturnTypeSchema=true)
    public ResponseEntity<RunView> checkConnection(Authentication auth, @PathVariable UUID id,
            @RequestHeader(value="Idempotency-Key", required=false) String key) { return accepted(service.requestRun(auth, id, "CHECK", key)); }

    @PostMapping("/connections/{id}/sync-runs")
    @ApiResponse(responseCode="202", description="수집 작업 접수. 수집 성공을 뜻하지 않으며 작업 상태를 조회해야 한다.", useReturnTypeSchema=true)
    public ResponseEntity<RunView> requestSync(Authentication auth, @PathVariable UUID id,
            @RequestHeader(value="Idempotency-Key", required=false) String key) { return accepted(service.requestRun(auth, id, "SYNC", key)); }

    @GetMapping("/connections/{id}/sync-runs")
    public List<RunView> listSyncRuns(Authentication auth, @PathVariable UUID id) { return service.runs(auth, id); }

    @GetMapping("/connections/{id}/audit-events")
    public List<ConnectionEvent> connectionEvents(Authentication auth, @PathVariable UUID id) { return service.events(auth, id); }

    @GetMapping("/sync-runs/{id}")
    public RunView getSyncRun(Authentication auth, @PathVariable UUID id) { return service.run(auth, id); }

    @GetMapping("/sync-runs/{id}/items")
    public ItemPage getSyncItems(Authentication auth, @PathVariable UUID id,
            @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="50") @Min(1) @Max(100) int size) {
        return service.items(auth, id, page, size);
    }

    private ResponseEntity<ConnectionView> connection(ConnectionView view) { return ResponseEntity.ok().eTag(IntegrationService.etag(view)).body(view); }
    private ResponseEntity<RunView> accepted(RunView view) { return ResponseEntity.accepted().location(URI.create("/api/admin/v1/sync-runs/" + view.id())).body(view); }
}

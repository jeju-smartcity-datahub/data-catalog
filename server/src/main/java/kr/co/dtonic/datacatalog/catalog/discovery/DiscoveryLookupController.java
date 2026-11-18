package kr.co.dtonic.datacatalog.catalog.discovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/discovery-lookups")
public class DiscoveryLookupController {
    private final DiscoveryLookupService service;
    public DiscoveryLookupController(DiscoveryLookupService service) { this.service=service; }
    public record CreateDiscoveryLookup(@NotNull @Pattern(regexp="REGION|HUB") String kind,
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9_-]{0,79}") String code,@NotBlank @Size(max=200) String name,@NotNull Boolean active) {}
    public record EditDiscoveryLookup(@NotBlank @Size(max=200) String name,@NotNull Boolean active) {}
    @Schema(requiredProperties={"id","kind","code","name","active","lockVersion","referenceCount"})
    public record DiscoveryLookup(UUID id,String kind,String code,String name,boolean active,long lockVersion,long referenceCount) {}
    @GetMapping @Operation(operationId="listDiscoveryLookups",summary="플랫폼 관리자의 공개 지역·허브 분류 목록")
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public List<DiscoveryLookup> list(@RequestParam @Pattern(regexp="REGION|HUB") String kind,Authentication auth) { return service.list(kind,auth); }
    @PostMapping @Operation(operationId="createDiscoveryLookup",summary="접속정보와 독립된 공개 지역·허브 분류 등록")
    @ApiResponse(responseCode="201",useReturnTypeSchema=true)
    public ResponseEntity<DiscoveryLookup> create(@Valid @RequestBody CreateDiscoveryLookup input,Authentication auth,HttpServletRequest request) {
        return response(service.create(input,auth,requestId(request)),true);
    }
    @GetMapping("/{id}") @Operation(operationId="getDiscoveryLookup",summary="공개 탐색 분류와 참조 수 조회")
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public ResponseEntity<DiscoveryLookup> get(@PathVariable UUID id,Authentication auth) { return response(service.get(id,auth),false); }
    @PatchMapping("/{id}") @Operation(operationId="editDiscoveryLookup",summary="분류 이름·활성 상태 수정, 사용 중 비활성화 금지")
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public ResponseEntity<DiscoveryLookup> edit(@PathVariable UUID id,@Valid @RequestBody EditDiscoveryLookup input,
            @RequestHeader(value="If-Match",required=false) String etag,Authentication auth,HttpServletRequest request) {
        return response(service.edit(id,input,etag,auth,requestId(request)),false);
    }
    @DeleteMapping("/{id}") @Operation(operationId="deactivateDiscoveryLookup",summary="미사용 분류를 이력 보존 비활성화")
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public ResponseEntity<DiscoveryLookup> deactivate(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            Authentication auth,HttpServletRequest request) { return response(service.deactivate(id,etag,auth,requestId(request)),false); }
    private ResponseEntity<DiscoveryLookup> response(DiscoveryLookup value,boolean created) {
        var result=created?ResponseEntity.created(URI.create("/api/admin/v1/discovery-lookups/"+value.id())):ResponseEntity.ok();
        return result.cacheControl(CacheControl.noStore()).eTag(MetadataWorkflowService.etag("discovery-lookup",value.id(),value.lockVersion())).body(value);
    }
    private static String requestId(HttpServletRequest request) { return (String)request.getAttribute(RequestIdFilter.ATTRIBUTE); }
}

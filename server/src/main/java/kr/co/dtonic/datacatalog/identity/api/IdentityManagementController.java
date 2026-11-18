package kr.co.dtonic.datacatalog.identity.api;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import kr.co.dtonic.datacatalog.identity.IdentityManagementService;
import kr.co.dtonic.datacatalog.identity.api.IdentityManagementModels.*;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1")
@PreAuthorize("hasAuthority('ROLE_PLATFORM_ADMIN')")
public class IdentityManagementController {
    private final IdentityManagementService service;
    public IdentityManagementController(IdentityManagementService service) { this.service=service; }
    @GetMapping("/accounts") @Operation(operationId="listManagedAccounts",summary="관리자 계정 목록")
    public List<ManagedAccount> accounts(Authentication auth) { return service.accounts(auth); }
    @GetMapping("/accounts/{id}") @Operation(operationId="getManagedAccount",summary="관리자 계정 상세")
    public ResponseEntity<ManagedAccount> account(@PathVariable UUID id,Authentication auth) { return accountResponse(service.getAccount(id,auth),200); }
    @PostMapping("/accounts") @Operation(operationId="createManagedAccount",summary="관리자 계정 등록")
    @ApiResponse(responseCode="201",description="계정 등록 완료",useReturnTypeSchema=true)
    public ResponseEntity<ManagedAccount> createAccount(@Valid @RequestBody CreateManagedAccount input,Authentication auth,HttpServletRequest request) {
        return accountResponse(service.createAccount(input,auth,requestId(request)),201);
    }
    @PutMapping("/accounts/{id}") @Operation(operationId="updateManagedAccount",summary="관리자 계정 상태·역할·기관 변경")
    public ResponseEntity<ManagedAccount> updateAccount(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String version,
            @Valid @RequestBody UpdateManagedAccount input,Authentication auth,HttpServletRequest request) {
        return accountResponse(service.updateAccount(id,version,input,auth,requestId(request)),200);
    }
    @PostMapping("/accounts/{id}/password") @Operation(operationId="resetManagedPassword",summary="관리자 계정 비밀번호 재설정")
    public ResponseEntity<ManagedAccount> password(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String version,
            @Valid @RequestBody ResetManagedPassword input,Authentication auth,HttpServletRequest request) {
        return accountResponse(service.resetPassword(id,version,input,auth,requestId(request)),200);
    }
    @GetMapping("/organizations") @Operation(operationId="listManagedOrganizations",summary="기관 목록")
    public List<ManagedOrganization> organizations(Authentication auth) { return service.organizations(auth); }
    @GetMapping("/organizations/{id}") @Operation(operationId="getManagedOrganization",summary="기관 상세")
    public ResponseEntity<ManagedOrganization> organization(@PathVariable UUID id,Authentication auth) { return organizationResponse(service.getOrganization(id,auth),200); }
    @PostMapping("/organizations") @Operation(operationId="createManagedOrganization",summary="기관 등록")
    @ApiResponse(responseCode="201",description="기관 등록 완료",useReturnTypeSchema=true)
    public ResponseEntity<ManagedOrganization> createOrganization(@Valid @RequestBody ManagedOrganizationInput input,Authentication auth,HttpServletRequest request) {
        return organizationResponse(service.createOrganization(input,auth,requestId(request)),201);
    }
    @PutMapping("/organizations/{id}") @Operation(operationId="updateManagedOrganization",summary="기관 명칭·상태 변경")
    public ResponseEntity<ManagedOrganization> updateOrganization(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String version,
            @Valid @RequestBody ManagedOrganizationInput input,Authentication auth,HttpServletRequest request) {
        return organizationResponse(service.updateOrganization(id,version,input,auth,requestId(request)),200);
    }
    private ResponseEntity<ManagedAccount> accountResponse(ManagedAccount account,int status) {
        return ResponseEntity.status(status).eTag(GovernanceSupport.etag("account",account.id().toString(),account.lockVersion())).body(account);
    }
    private ResponseEntity<ManagedOrganization> organizationResponse(ManagedOrganization organization,int status) {
        return ResponseEntity.status(status).eTag(GovernanceSupport.etag("organization",organization.id().toString(),organization.lockVersion())).body(organization);
    }
    private String requestId(HttpServletRequest request) {
        return java.util.Objects.toString(request.getAttribute(kr.co.dtonic.datacatalog.common.RequestIdFilter.ATTRIBUTE),UUID.randomUUID().toString());
    }
}

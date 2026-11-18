package kr.co.dtonic.datacatalog.identity.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import kr.co.dtonic.datacatalog.common.ApiProblemSchema;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizationAccessController {
    @Operation(operationId = "checkOrganizationAccess", summary = "기관별 관리자 조회 권한 확인", description = "로그인한 관리자가 지정한 기관의 정보를 조회할 수 있는지 확인한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "기관 조회 권한 있음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccessResult.class))),
            @ApiResponse(responseCode = "400", description = "기관 식별자 형식 오류", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblemSchema.class))),
            @ApiResponse(responseCode = "401", description = "인증 필요", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblemSchema.class))),
            @ApiResponse(responseCode = "403", description = "역할 또는 기관 범위 권한 없음", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblemSchema.class)))
    })
    @GetMapping(value = "/api/admin/v1/organizations/{organizationId}/access", produces = "application/json")
    @PreAuthorize("@organizationAccess.canRead(authentication, #organizationId)")
    public AccessResult checkOrganizationAccess(@PathVariable UUID organizationId) {
        return new AccessResult(organizationId, true);
    }

    public record AccessResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean readable) {}
}

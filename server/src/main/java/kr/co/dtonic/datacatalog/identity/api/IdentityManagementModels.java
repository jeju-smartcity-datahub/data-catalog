package kr.co.dtonic.datacatalog.identity.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class IdentityManagementModels {
    private IdentityManagementModels() {}
    @Schema(requiredProperties={"id","username","active","roles","organizationIds","lockVersion","createdAt","updatedAt"})
    public record ManagedAccount(UUID id, String username, boolean active, List<String> roles,
            List<UUID> organizationIds, long lockVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    @Schema(requiredProperties={"username","password","active","roles","organizationIds"})
    public record CreateManagedAccount(
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9._-]{2,79}") String username,
            @NotNull @Size(min=8,max=72) @Schema(accessMode=Schema.AccessMode.WRITE_ONLY) String password,
            boolean active, @NotNull @Size(min=1,max=5) List<@NotBlank String> roles,
            @NotNull @Size(max=100) List<@NotNull UUID> organizationIds) {
        @Override public String toString() { return "CreateManagedAccount[REDACTED]"; }
    }
    @Schema(requiredProperties={"active","roles","organizationIds"})
    public record UpdateManagedAccount(boolean active, @NotNull @Size(min=1,max=5) List<@NotBlank String> roles,
            @NotNull @Size(max=100) List<@NotNull UUID> organizationIds) {}
    @Schema(requiredProperties={"password"})
    public record ResetManagedPassword(@NotNull @Size(min=8,max=72) @Schema(accessMode=Schema.AccessMode.WRITE_ONLY) String password) {
        @Override public String toString() { return "ResetManagedPassword[REDACTED]"; }
    }
    @Schema(requiredProperties={"id","code","name","active","lockVersion","createdAt","updatedAt"})
    public record ManagedOrganization(UUID id, String code, String name, boolean active, long lockVersion,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    @Schema(requiredProperties={"code","name","active"})
    public record ManagedOrganizationInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,79}") String code,
            @NotBlank @Size(max=200) String name, boolean active) {}
}

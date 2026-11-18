package kr.co.dtonic.datacatalog.integration.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class IntegrationModels {
    private IntegrationModels() { }

    public record ConnectionInput(
            @NotNull UUID organizationId,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String adapterCode,
            @Size(max = 1000) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String endpoint,
            @Size(max = 200) @Pattern(regexp = "[a-zA-Z0-9/_-]*")
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String secretReference,
            boolean enabled,
            @Min(1) @Max(30) int timeoutSeconds,
            @Min(1) @Max(5) int maxAttempts,
            @Min(1) @Max(100) int maxPages) {
        @Override public String toString() { return "ConnectionInput[redacted]"; }
    }

    @Schema(requiredProperties={"id", "organizationId", "name", "adapterCode", "endpointConfigured", "credentialConfigured", "enabled", "timeoutSeconds", "maxAttempts", "maxPages", "lockVersion", "adapterAvailable", "capabilities", "updatedAt", "metadataEndpoint", "recordsEndpoint"})
    public record ConnectionView(UUID id, UUID organizationId, String name, String adapterCode,
            boolean endpointConfigured, boolean credentialConfigured, boolean enabled,
            int timeoutSeconds, int maxAttempts, int maxPages, long lockVersion,
            boolean adapterAvailable, Set<String> capabilities, OffsetDateTime updatedAt,
            @Schema(types={"string","null"}) String metadataEndpoint, @Schema(types={"string","null"}) String recordsEndpoint) { }

    @Schema(requiredProperties={"id", "connectionId", "kind", "status", "attempt", "configurationVersion", "mappingVersion", "errorCode", "createdAt", "startedAt", "finishedAt", "phase", "processedCount", "totalCount", "savedCount", "unchangedCount", "failedCount", "progressAt", "nextAttemptAt"})
    public record RunView(UUID id, UUID connectionId, String kind, String status, int attempt,
            long configurationVersion, String mappingVersion, String errorCode,
            OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
            String phase, int processedCount, @Schema(types={"integer","null"}) Integer totalCount,
            int savedCount, int unchangedCount, int failedCount,
            @Schema(types={"string","null"}) OffsetDateTime progressAt, @Schema(types={"string","null"}) OffsetDateTime nextAttemptAt) { }

    @Schema(requiredProperties={"externalId", "status", "sourceHash", "errorCode", "attempt", "updatedAt"})
    public record ItemView(String externalId, String status, String sourceHash, String errorCode,
            int attempt, OffsetDateTime updatedAt) { }

    @Schema(requiredProperties={"items", "page", "size", "totalElements"})
    public record ItemPage(List<ItemView> items, int page, int size, long totalElements) { }

    @Schema(requiredProperties={"id", "actorId", "action", "requestId", "configurationVersion", "occurredAt"})
    public record ConnectionEvent(UUID id, UUID actorId, String action, String requestId,
            long configurationVersion, OffsetDateTime occurredAt) { }
}

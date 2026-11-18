package kr.co.dtonic.datacatalog.integration.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class IntegrationScheduleModels {
    private IntegrationScheduleModels() { }

    public record ScheduleInput(boolean enabled, @Min(60) @Max(86400) int intervalSeconds) { }

    public record ScheduleView(UUID connectionId, boolean enabled, int intervalSeconds,
            OffsetDateTime nextRunAt, OffsetDateTime lastRequestedAt, UUID lastRunId, String lastErrorCode,
            UUID updatedBy, long lockVersion, OffsetDateTime updatedAt, boolean workerEnabled) { }
}

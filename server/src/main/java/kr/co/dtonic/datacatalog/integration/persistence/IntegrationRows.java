package kr.co.dtonic.datacatalog.integration.persistence;

import java.time.OffsetDateTime;

/** Internal rows must never be serialized to an HTTP response or logged. */
public final class IntegrationRows {
    private IntegrationRows() { }
    public record Connection(String id, String organizationId, String name, String adapterCode,
            String endpoint, String secretReference, boolean enabled, int timeoutSeconds,
            int maxAttempts, int maxPages, long lockVersion, OffsetDateTime updatedAt) {
        @Override public String toString() { return "Connection[id=" + id + "]"; }
    }
    public record Run(String id, String connectionId, String requestedBy, String kind, String status,
            String requestHash, long configurationVersion, String mappingVersion, int attempt,
            String leaseToken, String checkpointBefore, String checkpointAfter, String errorCode,
            OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
            String phase, Integer totalCount, OffsetDateTime progressAt, OffsetDateTime nextAttemptAt,
            int processedCount, int savedCount, int unchangedCount, int failedCount) { }
    public record Event(String id, String actorId, String action, String requestId, long configurationVersion, OffsetDateTime occurredAt) { }
}

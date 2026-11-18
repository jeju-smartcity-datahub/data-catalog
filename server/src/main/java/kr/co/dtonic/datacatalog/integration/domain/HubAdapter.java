package kr.co.dtonic.datacatalog.integration.domain;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Implement only after the upstream contract is verified. No production fixture adapters. */
public interface HubAdapter {
    enum Capability { TEST_CONNECTION, DISCOVER_DATASETS, FETCH_METADATA, FETCH_SCHEMA,
        FETCH_SAMPLE, FETCH_RECORDS, FETCH_FILE, SUBSCRIBE_CHANGES }

    String code();
    Set<Capability> capabilities();
    default boolean resumesFromPreviousRun() { return true; }
    void check(Context context) throws AdapterFailure;
    Page discover(Context context, String checkpoint) throws AdapterFailure;

    /** Adapter must use the server approved origin, deny redirects and enforce byte/page limits. */
    enum Phase { FETCHING_LIST, FETCHING_DETAILS }
    record Progress(Phase phase, Integer totalCount) {
        public Progress {
            java.util.Objects.requireNonNull(phase);
            if (totalCount != null && totalCount < 0) throw new IllegalArgumentException("Negative total count");
        }
    }
    record Context(URI endpoint, char[] credential, int timeoutSeconds, int maxResponseBytes,
            java.util.function.Consumer<Progress> progress) {
        public Context(URI endpoint, char[] credential, int timeoutSeconds, int maxResponseBytes) {
            this(endpoint, credential, timeoutSeconds, maxResponseBytes, ignored -> { });
        }
        public void report(Phase phase, Integer totalCount) { progress.accept(new Progress(phase, totalCount)); }
        @Override public String toString() { return "HubContext[redacted]"; }
        @Override public boolean equals(Object other) {
            return other instanceof Context context && java.util.Objects.equals(endpoint,context.endpoint)
                    && java.util.Arrays.equals(credential,context.credential)
                    && timeoutSeconds==context.timeoutSeconds && maxResponseBytes==context.maxResponseBytes;
        }
        @Override public int hashCode() {
            return 31*java.util.Objects.hash(endpoint,timeoutSeconds,maxResponseBytes)+java.util.Arrays.hashCode(credential);
        }
    }
    record Metadata(String externalId, String sourceRevision, String title, String description, Map<String,Object> details) {
        public Metadata(String externalId,String sourceRevision,String title,String description) {
            this(externalId,sourceRevision,title,description,Map.of());
        }
        public Metadata { details=details==null?Map.of():details; }
    }
    record Item(Metadata metadata, String externalId, String errorCode) {
        public static Item success(Metadata metadata) { return new Item(metadata, metadata.externalId(), null); }
        public static Item failure(String externalId) { return new Item(null, externalId, "SOURCE_ITEM_INVALID"); }
    }
    /** nextCheckpoint null ends the scan; null final cursor preserves the last durable cursor. */
    record Page(List<Item> items, String nextCheckpoint, boolean complete) { }

    /** Only bounded codes are exposed; upstream body/message/URI must never be used as an error. */
    final class AdapterFailure extends Exception {
        public enum Reason { AUTHENTICATION_FAILED, RATE_LIMITED, SOURCE_UNAVAILABLE, TIMEOUT,
            INVALID_SCHEMA, RESPONSE_TOO_LARGE, SOURCE_REJECTED }
        private final Reason reason;
        private final int retryAfterSeconds;
        public AdapterFailure(Reason reason, int retryAfterSeconds) {
            super(reason.name());
            this.reason = reason;
            this.retryAfterSeconds = Math.max(0, Math.min(3600, retryAfterSeconds));
        }
        public Reason reason() { return reason; }
        public int retryAfterSeconds() { return retryAfterSeconds; }
        public boolean retryable() { return reason == Reason.RATE_LIMITED || reason == Reason.SOURCE_UNAVAILABLE || reason == Reason.TIMEOUT; }
    }
}

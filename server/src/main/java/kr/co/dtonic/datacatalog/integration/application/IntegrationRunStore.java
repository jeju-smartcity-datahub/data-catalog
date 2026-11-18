package kr.co.dtonic.datacatalog.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper.args;

/** Short database transactions only. External network calls never hold a database transaction. */
@Service
public class IntegrationRunStore {
    private final IntegrationMapper mapper;
    private final ObjectMapper json;
    private final IntegrationPublicationQueue publicationQueue;
    public IntegrationRunStore(IntegrationMapper mapper, ObjectMapper json,IntegrationPublicationQueue publicationQueue) { this.mapper = mapper; this.json = json;this.publicationQueue=publicationQueue; }

    @Transactional
    public IntegrationRows.Run claim() { return mapper.claim(UUID.randomUUID().toString()); }

    @Transactional
    public boolean renew(IntegrationRows.Run run) {
        return mapper.fence(args("id", run.id(), "token", run.leaseToken())) == 1;
    }

    @Transactional
    public void report(IntegrationRows.Run run, String phase, Integer total) {
        if (!Set.of("FETCHING_LIST", "FETCHING_DETAILS", "SAVING_RESULTS").contains(phase))
            throw new IllegalArgumentException("Unsupported progress phase");
        if (total != null && total < 0) throw new IllegalArgumentException("Negative total count");
        if (mapper.reportProgress(args("id",run.id(),"token",run.leaseToken(),"phase",phase,"total",total)) != 1)
            throw new IllegalStateException("Lease lost");
    }

    @Transactional
    public boolean savePage(IntegrationRows.Run run, HubAdapter.Page page) {
        return savePage(run,page,false);
    }

    /** Full scans retain failed items and continue; only incremental adapters advance a cross-run checkpoint. */
    @Transactional
    public boolean savePage(IntegrationRows.Run run,HubAdapter.Page page,boolean continueOnItemFailure) {
        if (!renew(run)) return false;
        if (!mapper.executionAllowed(run.connectionId(), run.configurationVersion())) throw new IllegalStateException("Configuration no longer active");
        boolean failed = false;
        var collectedIds=new ArrayList<String>();
        for (HubAdapter.Item item : page.items()) {
            String hash = null;
            String status = "FAILED";
            String error = "SOURCE_ITEM_INVALID";
            if (item.metadata() != null && valid(item.metadata()) && item.errorCode() == null
                    && Objects.equals(item.externalId(), item.metadata().externalId())) {
                var metadata = item.metadata();
                String candidate = canonical(metadata);
                hash = IntegrationService.digest(canonical(metadata, false));
                Object sourceModifiedAt = metadata.details().get("sourceModifiedAt");
                int changed = mapper.candidate(args("candidateId", UUID.randomUUID().toString(), "connection", run.connectionId(),
                        "external", metadata.externalId(), "hash", hash, "revision", sourceModifiedAt == null ? metadata.sourceRevision() : sourceModifiedAt, "candidate", candidate,
                        "mapping", run.mappingVersion(), "id", run.id()));
                if (changed == 0) {
                    // Source observation time is audit provenance, not a change to catalogue content.
                    mapper.touchUnchangedSource(args("connection",run.connectionId(),"external",metadata.externalId(),
                            "hash",hash,"revision",sourceModifiedAt == null ? metadata.sourceRevision() : sourceModifiedAt,
                            "modifiedAt",sourceModifiedAt,"id",run.id()));
                }
                status = changed == 0 ? "UNCHANGED" : "CANDIDATE";
                error = null;
                collectedIds.add(item.externalId());
            } else failed = true;
            mapper.item(args("itemId", UUID.randomUUID().toString(), "id", run.id(), "external", item.externalId(), "hash", hash,
                    "status", status, "error", error, "attempt", run.attempt()));
        }
        publicationQueue.enqueue(run.connectionId(),collectedIds);
        if (mapper.countProgress(args("id",run.id(),"token",run.leaseToken())) != 1)
            throw new IllegalStateException("Lease lost");
        if ((!failed || continueOnItemFailure) && page.nextCheckpoint() != null) {
            var progress = args("id", run.id(), "connection", run.connectionId(), "token", run.leaseToken(), "cursor", page.nextCheckpoint());
            if (mapper.progress(progress) != 1) throw new IllegalStateException("Lease lost");
            if(!continueOnItemFailure)mapper.checkpoint(progress);
        }
        if (failed && !continueOnItemFailure) finish(run, "PARTIAL_FAILED", "SOURCE_ITEMS_FAILED");
        return !failed || continueOnItemFailure;
    }

    @Transactional(readOnly=true)
    public boolean hasFailedItems(IntegrationRows.Run run) { return mapper.hasFailedItems(run.id()); }

    @Transactional
    public void finish(IntegrationRows.Run run, String status, String error) {
        mapper.finish(args("id", run.id(), "token", run.leaseToken(), "status", status, "error", error));
    }

    @Transactional
    public void retry(IntegrationRows.Run run, String error, int seconds) {
        mapper.retry(args("id", run.id(), "token", run.leaseToken(), "error", error, "delay", seconds));
    }

    private boolean valid(HubAdapter.Metadata item) {
        try { NormalizedHubMetadata.details(item.details()); } catch(IllegalArgumentException invalid) { return false; }
        return item.title() != null && !item.title().isBlank() && item.title().length() <= 300
                && item.description() != null && item.description().length() <= 10000
                && (item.sourceRevision() == null || item.sourceRevision().length() <= 300);
    }

    private String canonical(HubAdapter.Metadata item) {
        return canonical(item,true);
    }
    private String canonical(HubAdapter.Metadata item, boolean includeSourceObservationTime) {
        try {
            // Fixed field order; upstream revision does not create a new candidate when content is unchanged.
            var content = new LinkedHashMap<String, Object>();
            content.put("externalId", item.externalId());
            content.put("title", item.title().strip());
            content.put("description", item.description());
            content.put("visibility", "PRIVATE");
            content.put("accessState", "METADATA_ONLY");
            var details = new TreeMap<String,Object>(NormalizedHubMetadata.details(item.details()));
            if (!includeSourceObservationTime && details.containsKey("sourceEntityId")) details.remove("sourceModifiedAt");
            content.put("details", details);
            return json.writeValueAsString(content);
        } catch (JsonProcessingException impossible) { throw new IllegalStateException("Cannot serialize normalized metadata"); }
    }
}

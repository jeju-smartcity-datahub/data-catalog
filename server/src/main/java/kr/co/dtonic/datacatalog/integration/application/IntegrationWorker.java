package kr.co.dtonic.datacatalog.integration.application;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import kr.co.dtonic.datacatalog.integration.domain.SecretReferenceProvider;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationRows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntegrationWorker {
    private final IntegrationMapper mapper;
    private final IntegrationRunStore store;
    private final AdapterRegistry adapters;
    private final EndpointPolicy endpoints;
    private final SecretReferenceProvider secrets;
    private final boolean scheduled;
    private final ExecutorService executor = new ThreadPoolExecutor(0, 2, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon(true).name("hub-io-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public IntegrationWorker(IntegrationMapper mapper, IntegrationRunStore store, AdapterRegistry adapters,
            EndpointPolicy endpoints, SecretReferenceProvider secrets,
            @Value("${catalog.integration.worker-enabled:false}") boolean scheduled) {
        this.mapper = mapper; this.store = store; this.adapters = adapters; this.endpoints = endpoints;
        this.secrets = secrets; this.scheduled = scheduled;
    }

    @Scheduled(fixedDelayString = "${catalog.integration.poll-delay-ms:5000}")
    public void poll() { if (scheduled) runOnce(); }

    /** Also callable by the integration test runner. No HTTP endpoint bypasses authorization. */
    public boolean runOnce() {
        var run = store.claim();
        if (run == null) return false;
        var connection = mapper.connection(run.connectionId());
        char[] credential = new char[0];
        try {
            if (connection == null || !mapper.executionAllowed(run.connectionId(), run.configurationVersion())) {
                store.finish(run, "FAILED", "CONNECTION_UNAVAILABLE"); return true;
            }
            if (run.attempt() > connection.maxAttempts()) { store.finish(run, "FAILED", "ATTEMPTS_EXHAUSTED"); return true; }
            var capability = run.kind().equals("CHECK") ? HubAdapter.Capability.TEST_CONNECTION : HubAdapter.Capability.DISCOVER_DATASETS;
            var adapter = adapters.require(connection.adapterCode(), capability);
            var endpoint = endpoints.validate(connection.endpoint());
            if (connection.secretReference() != null) credential = secrets.resolve(connection.secretReference());
            var progress = new AtomicReference<HubAdapter.Progress>();
            var context = new HubAdapter.Context(endpoint, credential, connection.timeoutSeconds(), 1_048_576, progress::set);
            if (run.kind().equals("CHECK")) {
                invoke(() -> { adapter.check(context); return null; }, connection.timeoutSeconds(),run,progress);
                store.finish(run, "SUCCEEDED", null);
            } else discover(run, connection, adapter, context,progress);
        } catch (AdapterFailure failure) {
            if (connection != null && failure.retryable() && run.attempt() < connection.maxAttempts()) {
                int backoff = Math.min(300, 1 << Math.min(run.attempt(), 8)) + ThreadLocalRandom.current().nextInt(4);
                store.retry(run, failure.reason().name(), Math.max(backoff, failure.retryAfterSeconds()));
            } else store.finish(run, "FAILED", failure.reason().name());
        } catch (DomainException unavailable) {
            // Do not copy arbitrary provider messages, cause chains, credentials or endpoint into history.
            String code = Set.of("ADAPTER_UNAVAILABLE", "CAPABILITY_UNSUPPORTED", "SOURCE_NOT_APPROVED", "INVALID_ENDPOINT", "ENDPOINT_PATH_INVALID", "SECRET_STORE_UNAVAILABLE").contains(unavailable.code())
                    ? unavailable.code() : "INTEGRATION_CONFIGURATION_UNAVAILABLE";
            store.finish(run, "FAILED", code);
        } catch (RuntimeException unexpected) {
            store.finish(run, "FAILED", "INTEGRATION_EXECUTION_FAILED");
        } finally { Arrays.fill(credential, '\0'); }
        return true;
    }

    private void discover(IntegrationRows.Run run, IntegrationRows.Connection connection, HubAdapter adapter, HubAdapter.Context context,
            AtomicReference<HubAdapter.Progress> progress) throws AdapterFailure {
        String cursor = run.checkpointAfter() != null ? run.checkpointAfter()
                : adapter.resumesFromPreviousRun() ? run.checkpointBefore() : null;
        Set<String> cursors = new HashSet<>();
        if (cursor != null) cursors.add(cursor);
        for (int pageNumber = 0; pageNumber < connection.maxPages(); pageNumber++) {
            if (!store.renew(run)) return;
            String requestedCursor = cursor;
            store.report(run,"FETCHING_LIST",null);
            var page = invoke(() -> adapter.discover(context, requestedCursor), connection.timeoutSeconds(),run,progress);
            validatePage(page);
            if (!page.complete() && page.nextCheckpoint() != null && !cursors.add(page.nextCheckpoint())) {
                store.finish(run, "FAILED", "CHECKPOINT_CYCLE"); return;
            }
            store.report(run,"SAVING_RESULTS",null);
            if (!store.savePage(run, page, !adapter.resumesFromPreviousRun())) return;
            if (page.complete()) {
                boolean failures = store.hasFailedItems(run);
                store.finish(run, failures ? "PARTIAL_FAILED" : "SUCCEEDED", failures ? "SOURCE_ITEMS_FAILED" : null);
                return;
            }
            cursor = page.nextCheckpoint();
        }
        store.finish(run, "FAILED", "PAGE_LIMIT_EXCEEDED");
    }

    private void validatePage(HubAdapter.Page page) throws AdapterFailure {
        if (page == null || page.items() == null || page.items().size() > 100
                || (!page.complete() && (page.nextCheckpoint() == null || page.nextCheckpoint().isBlank()))
                || (page.nextCheckpoint() != null && page.nextCheckpoint().length() > 2048)) throw invalidSchema();
        Set<String> ids = new HashSet<>();
        long size = 0;
        for (var item : page.items()) {
            if (item == null || item.externalId() == null || !item.externalId().matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,299}")
                    || !ids.add(item.externalId())) throw invalidSchema();
            size += item.externalId().length();
            if (item.metadata() != null) {
                var metadata = item.metadata();
                size += metadata.title() == null ? 0 : metadata.title().length();
                size += metadata.description() == null ? 0 : metadata.description().length();
                try { size += NormalizedHubMetadata.encode(metadata.details()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
                catch(IllegalArgumentException invalid) { throw invalidSchema(); }
                size += metadata.sourceRevision() == null ? 0 : metadata.sourceRevision().length();
            }
            if (size > 262_144) throw new AdapterFailure(AdapterFailure.Reason.RESPONSE_TOO_LARGE, 0);
        }
    }

    private <T> T invoke(Callable<T> operation, int timeout,IntegrationRows.Run run,
            AtomicReference<HubAdapter.Progress> progress) throws AdapterFailure {
        Future<T> task;
        try { task = executor.submit(operation); }
        catch (RejectedExecutionException busy) { throw new AdapterFailure(AdapterFailure.Reason.SOURCE_UNAVAILABLE, 1); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        try {
            while (true) {
                persistProgress(run,progress);
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new TimeoutException();
                try {
                    T result=task.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(250)),TimeUnit.NANOSECONDS);
                    persistProgress(run,progress);
                    return result;
                } catch(TimeoutException waiting) {
                    if(System.nanoTime()>=deadline)throw waiting;
                }
            }
        }
        catch (TimeoutException expired) { task.cancel(true); throw new AdapterFailure(AdapterFailure.Reason.TIMEOUT, 0); }
        catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); throw new AdapterFailure(AdapterFailure.Reason.SOURCE_UNAVAILABLE, 0);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof AdapterFailure adapterFailure) throw adapterFailure;
            throw new AdapterFailure(AdapterFailure.Reason.SOURCE_REJECTED, 0);
        } catch(RuntimeException failure) { task.cancel(true); throw failure; }
    }
    /** Adapter I/O only reports values; the worker owns the fenced database writes. */
    private void persistProgress(IntegrationRows.Run run,AtomicReference<HubAdapter.Progress> progress) {
        var current=progress.getAndSet(null);
        if(current!=null)store.report(run,current.phase().name(),current.totalCount());
    }
    private static AdapterFailure invalidSchema() { return new AdapterFailure(AdapterFailure.Reason.INVALID_SCHEMA, 0); }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}

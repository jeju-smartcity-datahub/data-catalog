package kr.co.dtonic.datacatalog.integration.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IntegrationScheduleDispatcher {
    /** Internal service identity; it never has platform-admin or publish authority and is not a login account. */
    public static final UUID SCHEDULER_ACTOR = UUID.nameUUIDFromBytes("data-catalog:metadata-scheduler".getBytes(StandardCharsets.UTF_8));
    private final IntegrationScheduleRepository repository;
    private final IntegrationService integrations;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public IntegrationScheduleDispatcher(IntegrationScheduleRepository repository, IntegrationService integrations,
            PlatformTransactionManager transactionManager,
            @Value("${catalog.integration.worker-enabled:false}") boolean enabled) {
        this.repository = repository; this.integrations = integrations;
        this.transactions = new TransactionTemplate(transactionManager); this.enabled = enabled;
    }

    @Scheduled(fixedDelayString="${catalog.integration.poll-delay-ms:5000}")
    public void poll() { if (enabled) enqueueDue(); }

    /** Explicit invocation is also used by isolated integration tests; normal runtime enters through poll(). */
    public int enqueueDue() {
        int requested = 0;
        for (UUID id : repository.dueIds()) {
            AtomicReference<IntegrationScheduleRepository.Schedule> attempt = new AtomicReference<>();
            try {
                Boolean queued = transactions.execute(status -> {
                    var connection = repository.lockConnection(id, true);
                    if (connection == null) return false;
                    var schedule = repository.lockDue(id);
                    if (schedule == null) return false;
                    attempt.set(schedule);
                    var auth = UsernamePasswordAuthenticationToken.authenticated(
                            new CatalogPrincipal(SCHEDULER_ACTOR, Set.of(connection.organizationId())), null,
                            List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
                    String key = "schedule:" + id + ":" + schedule.lockVersion() + ":" + schedule.nextRunAt().toInstant().toEpochMilli();
                    var run = integrations.requestRun(auth, id, "SYNC", key);
                    repository.requested(id, run.id());
                    repository.audit(id, SCHEDULER_ACTOR, "SCHEDULE_RUN_REQUESTED", connection.configurationVersion());
                    return true;
                });
                if (Boolean.TRUE.equals(queued)) requested++;
            } catch (RuntimeException failure) {
                if (attempt.get() == null) throw failure;
                // The failed enqueue transaction has rolled back; persist only an allowlisted code, never upstream bodies.
                String code = failure instanceof DomainException domain ? domain.code() : "SCHEDULE_ENQUEUE_FAILED";
                if (!code.matches("[A-Z_]{1,80}")) code = "SCHEDULE_ENQUEUE_FAILED";
                String safeCode = code;
                transactions.executeWithoutResult(status -> repository.failed(attempt.get(), safeCode));
            }
        }
        return requested;
    }
}

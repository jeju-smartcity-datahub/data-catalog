package kr.co.dtonic.datacatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.api.IntegrationScheduleModels.ScheduleInput;
import kr.co.dtonic.datacatalog.integration.application.IntegrationScheduleDispatcher;
import kr.co.dtonic.datacatalog.integration.application.IntegrationScheduleService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.allowed-origins=https://hub-schedule.invalid", "catalog.integration.worker-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationSchedulePostgresIT.ScheduleFixture.class)
class IntegrationSchedulePostgresIT {
    @Autowired IntegrationScheduleService schedules;
    @Autowired IntegrationScheduleDispatcher dispatcher;
    @Autowired IntegrationScheduleRepository repository;
    @Autowired IntegrationService integrations;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    UUID organization;
    Authentication administrator;
    Authentication editor;
    final List<UUID> connections = new ArrayList<>();

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @BeforeEach void setup() {
        organization = UUID.randomUUID();
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", organization, organization.toString(), "Schedule integration test");
        administrator = auth(organization, "PLATFORM_ADMIN");
        editor = auth(organization, "ORG_EDITOR");
    }

    // Committed fixtures are necessary for real concurrent connections. Retain append-only audit events,
    // and deactivate every fixture so no later worker test can claim its work.
    @AfterEach void deactivateFixtures() {
        for (UUID id : connections) {
            jdbc.update("UPDATE integration.sync_schedule SET use_yn=false,next_excn_dt=NULL WHERE sou_lnkg_id=?", id);
            jdbc.update("""
                    UPDATE integration.sync_run SET stts_cd='FAILED',joblse_tkn_id=NULL,joblse_expry_dt=NULL,end_dt=clock_timestamp()
                    WHERE sou_lnkg_id=? AND stts_cd IN ('QUEUED','RUNNING','RETRY_WAIT')
                    """, id);
            jdbc.update("UPDATE integration.source_connection SET use_yn=false WHERE id=?", id);
        }
    }

    @Test void storesScheduleAndEnqueuesAtMostOncePerInterval() {
        UUID id = connection();
        var enabled = schedules.update(administrator, id, 0, new ScheduleInput(true, 300));
        assertThat(enabled.nextRunAt()).isNotNull();
        assertThat(enabled.workerEnabled()).isFalse();
        assertThat(dispatcher.enqueueDue()).isEqualTo(1);
        var view = schedules.get(editor, id);
        assertThat(view.lastRunId()).isNotNull();
        assertThat(view.lastRequestedAt()).isNotNull();
        assertThat(view.nextRunAt()).isAfter(view.lastRequestedAt().plusSeconds(299));
        assertThat(view.lockVersion()).isEqualTo(enabled.lockVersion());
        assertThat(dispatcher.enqueueDue()).isZero();
        assertThat(runCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rqstr_id FROM integration.sync_run WHERE id=?", UUID.class, view.lastRunId()))
                .isEqualTo(IntegrationScheduleDispatcher.SCHEDULER_ACTOR);
        assertThat(integrations.events(editor, id)).extracting(event -> event.action()).contains("SCHEDULE_UPDATED", "SCHEDULE_RUN_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.source_metadata WHERE sou_lnkg_id=?", Integer.class, id)).isZero();
        finishRuns(id);
        dueNow(id);
        assertThat(dispatcher.enqueueDue()).isEqualTo(1);
        assertThat(runCount(id)).isEqualTo(2);
    }

    @Test void twoDispatchersCannotCreateDuplicateRuns() throws Exception {
        UUID id = enabledConnection();
        var second = new IntegrationScheduleDispatcher(repository, integrations, transactionManager, false);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(() -> { start.await(); return dispatcher.enqueueDue(); });
            var secondResult = pool.submit(() -> { start.await(); return second.enqueueDue(); });
            start.countDown();
            assertThat(firstResult.get(10, TimeUnit.SECONDS) + secondResult.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(runCount(id)).isEqualTo(1);
        assertThat(repository.get(id).lastRunId()).isNotNull();
    }

    @Test void skipsLockedConnectionAndResumesFromDatabaseAfterRestart() throws Exception {
        UUID id = enabledConnection();
        try (var pool = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lockConnection(id, false);
                try { assertThat(pool.submit(dispatcher::enqueueDue).get(5, TimeUnit.SECONDS)).isZero(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
        }
        var restarted = new IntegrationScheduleDispatcher(repository, integrations, transactionManager, false);
        assertThat(restarted.enqueueDue()).isEqualTo(1);
        assertThat(runCount(id)).isEqualTo(1);
    }

    @Test void disabledWorkerScheduleConnectionAndOrganizationDoNotEnqueue() {
        UUID id = enabledConnection();
        dispatcher.poll();
        assertThat(runCount(id)).isZero();
        schedules.update(administrator, id, 1, new ScheduleInput(false, 60));
        assertThat(dispatcher.enqueueDue()).isZero();
        assertThat(repository.get(id).nextRunAt()).isNull();
        schedules.update(administrator, id, 2, new ScheduleInput(true, 60));
        jdbc.update("UPDATE integration.source_connection SET use_yn=false WHERE id=?", id);
        assertThat(dispatcher.enqueueDue()).isZero();
        jdbc.update("UPDATE integration.source_connection SET use_yn=true WHERE id=?", id);
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", organization);
        assertThat(dispatcher.enqueueDue()).isZero();
        jdbc.update("UPDATE identity.organization SET stts_cd='ACTIVE' WHERE id=?", organization);
        assertThat(dispatcher.enqueueDue()).isEqualTo(1);
    }

    @Test void manualRunBlocksScheduleAndFailureRetriesWithoutLosingConfiguration() {
        UUID id = enabledConnection();
        var manual = integrations.requestRun(editor, id, "SYNC", "manual-before-periodic-001");
        assertThat(dispatcher.enqueueDue()).isZero();
        assertThat(runCount(id)).isEqualTo(1);
        assertThat(repository.get(id).lastErrorCode()).isEqualTo("SYNC_ALREADY_ACTIVE");
        assertThat(repository.get(id).lastRunId()).isNull();
        assertThat(integrations.run(editor, manual.id()).status()).isEqualTo("QUEUED");
        finishRuns(id);
        dueNow(id);
        assertThat(dispatcher.enqueueDue()).isEqualTo(1);
        assertThat(repository.get(id).lastErrorCode()).isNull();
    }

    @Test void adapterFailureIsRecordedAfterRollbackAndOtherConnectionsStillRun() {
        UUID unavailable = enabledConnection();
        UUID healthy = enabledConnection();
        jdbc.update("UPDATE integration.source_connection SET adptr_cd='UNAVAILABLE_SCHEDULE_TEST' WHERE id=?", unavailable);
        assertThat(dispatcher.enqueueDue()).isEqualTo(1);
        assertThat(runCount(unavailable)).isZero();
        assertThat(repository.get(unavailable).enabled()).isTrue();
        assertThat(repository.get(unavailable).lastErrorCode()).isEqualTo("ADAPTER_UNAVAILABLE");
        assertThat(repository.get(unavailable).nextRunAt()).isNotNull();
        assertThat(runCount(healthy)).isEqualTo(1);
        assertThat(dispatcher.enqueueDue()).isZero();
    }

    @Test void apiRequiresTrustedScopeAdministratorCsrfAndCurrentEtag() throws Exception {
        UUID id = connection();
        String path = "/api/admin/v1/connections/" + id + "/schedule";
        String body = "{\"enabled\":true,\"intervalSeconds\":60}";
        String etag = "\"schedule:" + id + ":0\"";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Role", "PLATFORM_ADMIN").header("X-Organization-Id", organization.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(auth(UUID.randomUUID(), "ORG_EDITOR")))).andExpect(status().isNotFound());
        mvc.perform(get(path).with(authentication(auth(organization, "ORG_REVIEWER"))))
                .andExpect(status().isOk()).andExpect(header().string("ETag", etag))
                .andExpect(jsonPath("$.lockVersion").value(0)).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put(path).with(authentication(editor)).with(csrf()).header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(administrator)).header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.lockVersion").value(1));
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).header("If-Match", "\"schedule:" + id + ":1\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalSeconds\":59}"))
                .andExpect(status().isBadRequest());
    }

    @Test void cannotEnableScheduleForInactiveOrganizationOrDisabledConnection() throws Exception {
        UUID id = connection();
        String path = "/api/admin/v1/connections/" + id + "/schedule";
        String etag = "\"schedule:" + id + ":0\"";
        jdbc.update("UPDATE integration.source_connection SET use_yn=false WHERE id=?", id);
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalSeconds\":60}"))
                .andExpect(status().isUnprocessableEntity());
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", organization);
        mvc.perform(put(path).with(authentication(administrator)).with(csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalSeconds\":60}"))
                .andExpect(status().isForbidden());
    }

    private UUID connection() {
        UUID id = integrations.create(editor, new ConnectionInput(organization, "Schedule test", "TEST_SCHEDULE",
                "https://hub-schedule.invalid/metadata", null, true, 3, 2, 10)).id();
        connections.add(id);
        return id;
    }
    private UUID enabledConnection() {
        UUID id = connection();
        schedules.update(administrator, id, 0, new ScheduleInput(true, 60));
        return id;
    }
    private int runCount(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM integration.sync_run WHERE sou_lnkg_id=?", Integer.class, id); }
    private void dueNow(UUID id) { jdbc.update("UPDATE integration.sync_schedule SET next_excn_dt=clock_timestamp()-interval '1 second' WHERE sou_lnkg_id=?", id); }
    private void finishRuns(UUID id) {
        jdbc.update("UPDATE integration.sync_run SET stts_cd='SUCCEEDED',end_dt=clock_timestamp() WHERE sou_lnkg_id=? AND stts_cd='QUEUED'", id);
    }
    private static Authentication auth(UUID org, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(), Set.of(org)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    @TestConfiguration
    static class ScheduleFixture {
        @Bean HubAdapter scheduleTestAdapter() {
            return new HubAdapter() {
                public String code() { return "TEST_SCHEDULE"; }
                public Set<Capability> capabilities() { return Set.of(Capability.DISCOVER_DATASETS); }
                public void check(Context context) { }
                public Page discover(Context context, String checkpoint) { return new Page(List.of(), null, true); }
            };
        }
    }
}

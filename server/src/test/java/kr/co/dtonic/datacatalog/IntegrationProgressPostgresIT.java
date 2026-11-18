package kr.co.dtonic.datacatalog;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ConnectionInput;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.application.IntegrationWorker;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Committed worker progress must be visible to a second request while hub I/O is still running. */
@Tag("postgres")
@SpringBootTest(properties={"catalog.integration.allowed-origins=https://progress.invalid","catalog.integration.worker-enabled=false"})
@ActiveProfiles("test")
@Import(IntegrationProgressPostgresIT.Fixture.class)
class IntegrationProgressPostgresIT {
    @Autowired IntegrationService service;
    @Autowired IntegrationWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired BlockingAdapter adapter;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { PostgresDatabase.configure(properties); }

    @Test void exposesActualStageAndCommittedCountsBeforeCompletion() throws Exception {
        UUID organization=UUID.randomUUID();
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",organization,organization.toString(),"Progress verification");
        var auth=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of(organization)),null,
                List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        var connection=service.create(auth,new ConnectionInput(organization,"Progress verification","PROGRESS_TEST",
                "https://progress.invalid",null,true,15,1,10));
        var run=service.requestRun(auth,connection.id(),"SYNC","progress-visibility-001");
        var executor=Executors.newSingleThreadExecutor();
        var execution=executor.submit(worker::runOnce);
        try {
            await(()->"FETCHING_DETAILS".equals(service.run(auth,run.id()).phase()));
            var current=service.run(auth,run.id());
            assertThat(current.status()).isEqualTo("RUNNING");
            assertThat(current.totalCount()).isEqualTo(2);
            assertThat(current.processedCount()).isZero();
            assertThat(current.progressAt()).isNotNull();
            adapter.firstRelease.countDown();
            await(()->service.run(auth,run.id()).processedCount()==1);
            assertThat(service.run(auth,run.id()).savedCount()).isEqualTo(1);
            adapter.secondRelease.countDown();
            assertThat(execution.get(5,TimeUnit.SECONDS)).isTrue();
            current=service.run(auth,run.id());
            assertThat(current.status()).isEqualTo("SUCCEEDED");
            assertThat(current.phase()).isEqualTo("COMPLETED");
            assertThat(current.processedCount()).isEqualTo(2);
            assertThat(current.savedCount()).isEqualTo(2);
        } finally {
            adapter.firstRelease.countDown();adapter.secondRelease.countDown();
            execution.get(20,TimeUnit.SECONDS);executor.shutdownNow();
            // The isolated Testcontainers database owns these UUID-scoped rows, including append-only audit events.
        }
    }
    private static void await(BooleanSupplier condition)throws InterruptedException {
        long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @TestConfiguration static class Fixture { @Bean BlockingAdapter progressAdapter(){return new BlockingAdapter();} }
    static class BlockingAdapter implements HubAdapter {
        final CountDownLatch firstRelease=new CountDownLatch(1),secondRelease=new CountDownLatch(1);
        public String code(){return "PROGRESS_TEST";}
        public Set<Capability> capabilities(){return Set.of(Capability.DISCOVER_DATASETS);}
        public void check(Context context){}
        public Page discover(Context context,String checkpoint)throws AdapterFailure {
            context.report(Phase.FETCHING_LIST,null);
            context.report(Phase.FETCHING_DETAILS,2);
            try {
                if(!(checkpoint==null?firstRelease:secondRelease).await(10,TimeUnit.SECONDS))
                    throw new AdapterFailure(AdapterFailure.Reason.TIMEOUT,0);
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AdapterFailure(AdapterFailure.Reason.TIMEOUT,0);}
            String id=checkpoint==null?"first":"second";
            return new Page(List.of(Item.success(new Metadata(id,"1",id,"Progress verification"))),checkpoint==null?"next":null,checkpoint!=null);
        }
    }
}

package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class MetadataConcurrencyPostgresIT {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){PostgresDatabase.configure(registry);}
    @Test void simultaneousCreateReplaysOnceAndSimultaneousEditsHaveOneWinner() throws Exception {
        // Independent request transactions must commit to contend on real PostgreSQL locks.
        // These labelled PRIVATE fixtures remain in the isolated _test DB; no trigger is bypassed for cleanup.
        UUID org=UUID.randomUUID(),actor=UUID.randomUUID();
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",org,"concurrency-"+org,"[시험] 동시성 검증 기관");
        String key=UUID.randomUUID().toString();
        String payload=json.writeValueAsString(Map.of("ownerOrganizationId",org,"slug","concurrency-"+UUID.randomUUID(),"title","[시험] 비공개 동시성 검증",
                "description","동시 요청에서 한 번만 생성","providerOrganizationId",org,"visibility","PRIVATE"));
        var results=concurrent(
                request(post("/api/admin/v1/datasets"),org,actor).header("Idempotency-Key",key).content(payload),
                request(post("/api/admin/v1/datasets"),org,actor).header("Idempotency-Key",key).content(payload));
        assertThat(results).extracting(r->r.getResponse().getStatus()).containsExactly(201,201);
        var first=json.readTree(results.get(0).getResponse().getContentAsByteArray());
        var second=json.readTree(results.get(1).getResponse().getContentAsByteArray());
        assertThat(first).isEqualTo(second);UUID id=UUID.fromString(first.path("id").asText());
        assertThat(db.queryForObject("SELECT count(*) FROM catalog.dataset WHERE psn_inst_id=?",Integer.class,org)).isEqualTo(1);
        String etag=results.getFirst().getResponse().getHeader("ETag"),path="/api/admin/v1/datasets/"+id+"/draft";
        var edits=concurrent(request(patch(path),org,actor).header("If-Match",etag).content(edit(org,"첫 편집자")),
                request(patch(path),org,actor).header("If-Match",etag).content(edit(org,"둘째 편집자")));
        assertThat(edits).extracting(r->r.getResponse().getStatus()).containsExactlyInAnyOrder(200,412);
        assertThat(db.queryForObject("SELECT lck_ver_no AS lock_version FROM catalog.dataset WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE dtset_id=? AND prcs_cd='DRAFT_UPDATED'",Integer.class,id)).isEqualTo(1);
    }
    private List<MvcResult> concurrent(MockHttpServletRequestBuilder first,MockHttpServletRequestBuilder second)throws Exception{
        var start=new CountDownLatch(1);try(var workers=Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Request barrier timeout");return mvc.perform(first).andReturn();});
            var b=workers.submit(()->{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Request barrier timeout");return mvc.perform(second).andReturn();});
            start.countDown();return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    private String edit(UUID org,String title)throws Exception{return json.writeValueAsString(Map.of("title",title,"description","동시 수정","providerOrganizationId",org,"visibility","PRIVATE"));}
    private MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder request,UUID org,UUID actor){
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,Set.of(org)),null,
                List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR"))))).with(csrf()).contentType("application/json");
    }
}

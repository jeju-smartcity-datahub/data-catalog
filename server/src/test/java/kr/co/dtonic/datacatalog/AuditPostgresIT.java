package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
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
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class AuditPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }
    @Test @Transactional
    void scopesAuditAndNeverReturnsChangePayloadToAggregateList() throws Exception {
        UUID own=UUID.randomUUID(),other=UUID.randomUUID(),actor=UUID.randomUUID();
        for(UUID organization:List.of(own,other)) {
            db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",organization,organization.toString(),"audit test");
            db.update("INSERT INTO audit.audit_event(id,prcs_user_id,inst_id,prcs_cd,trgt_type_cd,trgt_id,dmnd_id,chg_smry_cn) VALUES (?,?,?,'DRAFT_CREATED','DATASET',?,'audit-test',?::jsonb)",UUID.randomUUID(),actor,organization,UUID.randomUUID(),"{\"private\":\"sensitive audit detail\"}");
        }
        var auditor=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,Set.of(own)),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_AUDITOR")));
        mvc.perform(get("/api/admin/v1/audit-events").with(authentication(auditor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].changeSummary").doesNotExist());
        mvc.perform(get("/api/admin/v1/audit-events").param("organizationId",other.toString()).with(authentication(auditor))).andExpect(status().isForbidden());
        var editor=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,Set.of(own)),null,List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        mvc.perform(get("/api/admin/v1/audit-events").with(authentication(editor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/v1/audit-events")).andExpect(status().isUnauthorized());
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",own);
        mvc.perform(get("/api/admin/v1/audit-events").with(authentication(auditor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/v1/audit-events").param("organizationId",own.toString()).with(authentication(auditor))).andExpect(status().isForbidden());
    }
}

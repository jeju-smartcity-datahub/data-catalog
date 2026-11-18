package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.LocalAuthenticationToken;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"test","local-auth"}) @Transactional
class IdentityManagementPostgresIT {
    private static final String ROOT="/api/admin/v1";
    private static final String PASSWORD="Isolated-test-2026!";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }
    private Authentication platform() { return auth(UUID.randomUUID(),"PLATFORM_ADMIN"); }
    private Authentication auth(UUID id,String role) { return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(id,Set.of()),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
    private record Created(UUID id,String etag) {}

    @Test void everyIdentityEndpointRequiresPlatformRoleAndMutationsRequireCsrf() throws Exception {
        mvc.perform(get(ROOT+"/accounts")).andExpect(status().isUnauthorized());
        for(String path:List.of("/accounts","/organizations","/accounts/"+UUID.randomUUID(),"/organizations/"+UUID.randomUUID()))
            mvc.perform(get(ROOT+path).with(authentication(auth(UUID.randomUUID(),"ORG_EDITOR")))).andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/accounts").with(authentication(platform())).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/organizations").with(authentication(auth(UUID.randomUUID(),"ORG_AUDITOR"))).with(csrf())
                .contentType("application/json").content("{\"code\":\"JEJU\",\"name\":\"제주\",\"active\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test void accountCreationRetainsExistingRowsAndNeverReturnsOrAuditsCredentials() throws Exception {
        var admin=platform();var organization=createOrganization(admin);
        var account=createAccount(admin,organization.id());
        var response=mvc.perform(get(ROOT+"/accounts").with(authentication(admin))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains(account.id().toString()).doesNotContain(PASSWORD,"pwd_hash","passwordHash","$2a$");
        String hash=jdbc.queryForObject("SELECT pwd_hash FROM identity.local_account WHERE id=?",String.class,account.id());
        assertThat(hash).startsWith("$2a$12$");assertThat(encoder.matches(PASSWORD,hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT chg_smry_cn::text FROM audit.audit_event WHERE trgt_id=? AND prcs_cd='ACCOUNT_CREATED'",String.class,account.id()))
                .doesNotContain(PASSWORD,hash);
        assertThat(jdbc.queryForObject("SELECT stts_cd FROM identity.organization WHERE id=?",String.class,organization.id())).isEqualTo("ACTIVE");
    }

    @Test void accountUpdateUsesVersionAndRevokesOldSessionWithoutChangingPassword() throws Exception {
        var admin=platform();var organization=createOrganization(admin);var account=createAccount(admin,organization.id());
        long version=jdbc.queryForObject("SELECT auth_ver_no FROM identity.local_account WHERE id=?",Long.class,account.id());
        var oldSession=new LocalAuthenticationToken(new CatalogPrincipal(account.id(),Set.of(organization.id())),version,List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        String beforeHash=jdbc.queryForObject("SELECT pwd_hash FROM identity.local_account WHERE id=?",String.class,account.id());
        String update=json.writeValueAsString(Map.of("active",true,"roles",List.of("ORG_AUDITOR"),"organizationIds",List.of(organization.id())));
        mvc.perform(put(ROOT+"/accounts/"+account.id()).with(authentication(admin)).with(csrf()).contentType("application/json").content(update))
                .andExpect(status().isPreconditionRequired());
        var changed=mvc.perform(put(ROOT+"/accounts/"+account.id()).with(authentication(admin)).with(csrf()).header("If-Match",account.etag()).contentType("application/json").content(update))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("ORG_AUDITOR")).andReturn().getResponse();
        assertThat(changed.getHeader("ETag")).isNotEqualTo(account.etag());
        mvc.perform(put(ROOT+"/accounts/"+account.id()).with(authentication(admin)).with(csrf()).header("If-Match",account.etag()).contentType("application/json").content(update))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get(ROOT+"/session").with(authentication(oldSession))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT pwd_hash FROM identity.local_account WHERE id=?",String.class,account.id())).isEqualTo(beforeHash);
    }

    @Test void passwordResetRevokesSessionsAndRejectsStaleRequest() throws Exception {
        var admin=platform();var organization=createOrganization(admin);var account=createAccount(admin,organization.id());
        String password="Changed-test-2026!";
        long version=jdbc.queryForObject("SELECT auth_ver_no FROM identity.local_account WHERE id=?",Long.class,account.id());
        var oldSession=new LocalAuthenticationToken(new CatalogPrincipal(account.id(),Set.of(organization.id())),version,List.of(new SimpleGrantedAuthority("ROLE_ORG_EDITOR")));
        mvc.perform(post(ROOT+"/accounts/"+account.id()+"/password").with(authentication(admin)).with(csrf()).header("If-Match",account.etag())
                .contentType("application/json").content(json.writeValueAsString(Map.of("password",password))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        assertThat(encoder.matches(password,jdbc.queryForObject("SELECT pwd_hash FROM identity.local_account WHERE id=?",String.class,account.id()))).isTrue();
        mvc.perform(get(ROOT+"/session").with(authentication(oldSession))).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT+"/accounts/"+account.id()+"/password").with(authentication(admin)).with(csrf()).header("If-Match",account.etag())
                .contentType("application/json").content(json.writeValueAsString(Map.of("password",PASSWORD)))).andExpect(status().isPreconditionFailed());
    }

    @Test void selfAndLastPlatformAdministratorRemovalAreBlocked() throws Exception {
        var admin=platform();var account=createPlatform(admin);
        String body=json.writeValueAsString(Map.of("active",false,"roles",List.of("PLATFORM_ADMIN"),"organizationIds",List.of()));
        mvc.perform(put(ROOT+"/accounts/"+account.id()).with(authentication(auth(account.id(),"PLATFORM_ADMIN"))).with(csrf()).header("If-Match",account.etag())
                .contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_ADMIN_CHANGE_FORBIDDEN"));
        mvc.perform(put(ROOT+"/accounts/"+account.id()).with(authentication(admin)).with(csrf()).header("If-Match",account.etag())
                .contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        assertThat(jdbc.queryForObject("SELECT stts_cd FROM identity.local_account WHERE id=?",String.class,account.id())).isEqualTo("ACTIVE");
    }

    @Test void organizationRenameRetainsIdentityAndProtectsReferencesAndVersion() throws Exception {
        var admin=platform();var organization=createOrganization(admin);var account=createAccount(admin,organization.id());
        String code=jdbc.queryForObject("SELECT cd FROM identity.organization WHERE id=?",String.class,organization.id());
        String body=json.writeValueAsString(Map.of("code",code,"name","변경된 기관명","active",true));
        var updated=mvc.perform(put(ROOT+"/organizations/"+organization.id()).with(authentication(admin)).with(csrf()).header("If-Match",organization.etag())
                .contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("변경된 기관명")).andReturn().getResponse();
        mvc.perform(put(ROOT+"/organizations/"+organization.id()).with(authentication(admin)).with(csrf()).header("If-Match",organization.etag())
                .contentType("application/json").content(body)).andExpect(status().isPreconditionFailed());
        mvc.perform(put(ROOT+"/organizations/"+organization.id()).with(authentication(admin)).with(csrf()).header("If-Match",updated.getHeader("ETag"))
                .contentType("application/json").content(json.writeValueAsString(Map.of("code",code,"name","변경된 기관명","active",false))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORGANIZATION_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT inst_id FROM identity.local_account_organization WHERE acnt_id=?",UUID.class,account.id())).isEqualTo(organization.id());
    }

    @Test void invalidRolesMissingScopeAndInactiveOrganizationsCannotCreateUsableAccounts() throws Exception {
        var admin=platform();var organization=createOrganization(admin);
        for(var roles:List.of(List.of("SUPERUSER"),List.of("ORG_EDITOR"))) {
            mvc.perform(post(ROOT+"/accounts").with(authentication(admin)).with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("username","bad-"+UUID.randomUUID(),"password",PASSWORD,"active",true,"roles",roles,"organizationIds",List.of()))))
                    .andExpect(result->assertThat(result.getResponse().getStatus()).isIn(400,409));
        }
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",organization.id());
        mvc.perform(post(ROOT+"/accounts").with(authentication(admin)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","bad-"+UUID.randomUUID(),"password",PASSWORD,"active",true,"roles",List.of("ORG_EDITOR"),"organizationIds",List.of(organization.id())))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORGANIZATION_INACTIVE"));
    }
    private Created createOrganization(Authentication admin) throws Exception {
        var response=mvc.perform(post(ROOT+"/organizations").with(authentication(admin)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("code","IT-"+UUID.randomUUID(),"name","시험 기관","active",true))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return new Created(UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText()),response.getHeader("ETag"));
    }
    private Created createAccount(Authentication admin,UUID organization) throws Exception { return create(admin,List.of("ORG_EDITOR"),List.of(organization)); }
    private Created createPlatform(Authentication admin) throws Exception { return create(admin,List.of("PLATFORM_ADMIN"),List.of()); }
    private Created create(Authentication admin,List<String> roles,List<UUID> organizations) throws Exception {
        var response=mvc.perform(post(ROOT+"/accounts").with(authentication(admin)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","it-"+UUID.randomUUID(),"password",PASSWORD,"active",true,"roles",roles,"organizationIds",organizations))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return new Created(UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText()),response.getHeader("ETag"));
    }
}

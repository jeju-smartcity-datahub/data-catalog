package kr.co.dtonic.datacatalog;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, only PostgresDatabase's isolated _test database; each fixture is rolled back. */
@Tag("postgres")
@SpringBootTest(properties = {"catalog.local-auth.username-attempts=100", "catalog.local-auth.address-attempts=1000"})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local-auth"})
@Transactional
class LocalAuthenticationPostgresIT {
    private static final String ROOT = "/api/admin/v1/auth";
    private static final String PASSWORD = "Public-test-only-2026!";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test void realLoginRestoresPrincipalAndEnforcesInstitutionScope() throws Exception {
        var fixture = fixture("ORG_EDITOR", true, true);
        var session = login(fixture);
        mvc.perform(get("/api/admin/v1/session").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixture.id().toString()))
                .andExpect(jsonPath("$.organizationIds[0]").value(fixture.organization().toString()))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ORG_EDITOR"));
        mvc.perform(get(access(fixture.organization())).session(session)).andExpect(status().isOk());
        mvc.perform(get(access(UUID.randomUUID())).session(session)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT pwd_hash FROM identity.local_account WHERE id=?", String.class, fixture.id()))
                .startsWith("$2a$12$").hasSize(60).doesNotContain(PASSWORD);
    }

    @Test void missingInactiveAndWrongPasswordHaveSameFailureContract() throws Exception {
        var inactive = fixture("PLATFORM_ADMIN", false, false);
        var active = fixture("PLATFORM_ADMIN", true, false);
        for (var attempt : java.util.List.of(Map.of("username", inactive.login(), "password", PASSWORD),
                Map.of("username", active.login(), "password", "wrong-password"),
                Map.of("username", "missing-" + UUID.randomUUID(), "password", PASSWORD))) {
            var bootstrap = bootstrap(null);
            mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                    .contentType("application/json").content(json.writeValueAsString(attempt)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOCAL_LOGIN_FAILED"))
                    .andExpect(jsonPath("$.detail").value("로그인 정보를 확인해 주세요."));
        }
    }

    @Test void accountPasswordRoleAndMembershipChangesRevokePersistedSessions() throws Exception {
        for (String change : java.util.List.of("status", "password", "role", "membership")) {
            var fixture = fixture("ORG_EDITOR", true, true);
            var session = login(fixture);
            long before = jdbc.queryForObject("SELECT auth_ver_no FROM identity.local_account WHERE id=?", Long.class, fixture.id());
            switch (change) {
                case "status" -> jdbc.update("UPDATE identity.local_account SET stts_cd='INACTIVE' WHERE id=?", fixture.id());
                case "password" -> jdbc.update("UPDATE identity.local_account SET pwd_hash=? WHERE id=?", encoder.encode("Public-changed-test-only!"), fixture.id());
                case "role" -> jdbc.update("UPDATE identity.local_account_role SET role_cd='ORG_AUDITOR' WHERE acnt_id=?", fixture.id());
                case "membership" -> jdbc.update("DELETE FROM identity.local_account_organization WHERE acnt_id=?", fixture.id());
                default -> throw new IllegalStateException(change);
            }
            assertThat(jdbc.queryForObject("SELECT auth_ver_no FROM identity.local_account WHERE id=?", Long.class, fixture.id())).isGreaterThan(before);
            mvc.perform(get("/api/admin/v1/session").session(session)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Test void institutionDeactivationAndReactivationCannotRestoreOldSession() throws Exception {
        var fixture = fixture("ORG_EDITOR", true, true);
        var session = login(fixture);
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", fixture.organization());
        jdbc.update("UPDATE identity.organization SET stts_cd='ACTIVE' WHERE id=?", fixture.organization());
        mvc.perform(get("/api/admin/v1/session").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/admin/v1/session").session(login(fixture))).andExpect(status().isOk());
    }

    @Test void nonPlatformAccountWithoutActiveScopeCannotLogIn() throws Exception {
        var fixture = fixture("ORG_EDITOR", true, false);
        var bootstrap = bootstrap(null);
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                .contentType("application/json").content(json.writeValueAsString(Map.of("username", fixture.login(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test void logoutNeedsCurrentCsrfAndInvalidatesPersistedSession() throws Exception {
        var fixture = fixture("PLATFORM_ADMIN", true, false);
        var session = login(fixture);
        mvc.perform(post(ROOT + "/logout").session(session)).andExpect(status().isForbidden());
        var fresh = bootstrap(session);
        mvc.perform(post(ROOT + "/logout").session(session).header("X-CSRF-TOKEN", fresh.csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/admin/v1/session")).andExpect(status().isUnauthorized());
    }

    @Test void databaseRejectsUnknownRole() {
        var fixture = fixture("PLATFORM_ADMIN", true, false);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,?)", fixture.id(), "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsNonNormalizedLoginAndWrongPasswordEncoding() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash) VALUES (?,?,?)",
                UUID.randomUUID(), "UPPERCASE", encoder.encode(PASSWORD))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsPlaintextPassword() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash) VALUES (?,?,?)",
                UUID.randomUUID(), "plain-" + UUID.randomUUID(), PASSWORD)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Fixture fixture(String role, boolean active, boolean organization) {
        UUID id = UUID.randomUUID(), org = organization ? UUID.randomUUID() : null;
        String username = "it-" + id;
        if (org != null) jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", org, "AUTH-" + org, "Local auth test institution");
        jdbc.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash) VALUES (?,?,?)", id, username, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,?)", id, role);
        if (org != null) jdbc.update("INSERT INTO identity.local_account_organization(acnt_id,inst_id) VALUES (?,?)", id, org);
        if (active) jdbc.update("UPDATE identity.local_account SET stts_cd='ACTIVE' WHERE id=?", id);
        return new Fixture(id, username, org);
    }
    private MockHttpSession login(Fixture fixture) throws Exception {
        var bootstrap = bootstrap(null); String previousId = bootstrap.session().getId();
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                .contentType("application/json").content(json.writeValueAsString(Map.of("username", fixture.login(), "password", PASSWORD))))
                .andExpect(status().isNoContent());
        assertThat(bootstrap.session().getId()).isNotEqualTo(previousId);
        return bootstrap.session();
    }
    private Bootstrap bootstrap(MockHttpSession session) throws Exception {
        var request = get(ROOT); if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Bootstrap((MockHttpSession) result.getRequest().getSession(false), json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    private String access(UUID institution) { return "/api/admin/v1/organizations/" + institution + "/access"; }
    private record Fixture(UUID id, String login, UUID organization) {}
    private record Bootstrap(MockHttpSession session, String csrf) {}
}

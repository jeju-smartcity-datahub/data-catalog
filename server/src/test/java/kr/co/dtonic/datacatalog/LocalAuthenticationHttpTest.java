package kr.co.dtonic.datacatalog;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.config.LocalAuthenticationConfiguration;
import kr.co.dtonic.datacatalog.config.SecurityConfiguration;
import kr.co.dtonic.datacatalog.identity.LocalAccountRepository;
import kr.co.dtonic.datacatalog.identity.OrganizationAccess;
import kr.co.dtonic.datacatalog.identity.api.LocalAuthenticationController;
import kr.co.dtonic.datacatalog.identity.api.OrganizationAccessController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {LocalAuthenticationController.class, OrganizationAccessController.class},
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class, LocalAuthenticationConfiguration.class, ApiProblems.class, OrganizationAccess.class})
@ActiveProfiles({"test", "local-auth"})
@TestPropertySource(properties = {"catalog.local-auth.username-attempts=3", "catalog.local-auth.address-attempts=100"})
class LocalAuthenticationHttpTest {
    private static final String ROOT = "/api/admin/v1/auth";
    private static final String PASSWORD = "Public-test-only-2026!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @MockitoBean LocalAccountRepository accounts;
    String username;
    LocalAccountRepository.Account account;

    @BeforeEach void fixture() {
        username = "fixture-" + UUID.randomUUID();
        account = new LocalAccountRepository.Account(UUID.randomUUID(), encoder.encode(PASSWORD), true, 7,
                Set.of("PLATFORM_ADMIN"), Set.of(), true);
        when(accounts.byLogin(username)).thenReturn(Optional.of(account));
        when(accounts.byId(account.id())).thenReturn(Optional.of(account));
    }

    @Test void loginRequiresCsrfThenRotatesSessionAndExplicitlyPersistsIdentity() throws Exception {
        mvc.perform(post(ROOT + "/login").contentType("application/json").content(body(PASSWORD)))
                .andExpect(status().isForbidden());
        var bootstrap = bootstrap(null);
        String previousId = bootstrap.session().getId();
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                .contentType("application/json").content(body(PASSWORD))).andExpect(status().isNoContent());
        assertThat(bootstrap.session().getId()).isNotEqualTo(previousId);
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access").session(bootstrap.session()))
                .andExpect(status().isOk());
        mvc.perform(post(ROOT + "/logout").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf()))
                .andExpect(status().isForbidden());
        var refreshed = bootstrap(bootstrap.session());
        mvc.perform(post(ROOT + "/logout").session(refreshed.session()).header("X-CSRF-TOKEN", refreshed.csrf()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(refreshed.session().isInvalid()).isTrue();
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access"))
                .andExpect(status().isUnauthorized());
    }

    @Test void genericFailureAndUsernameRateLimitDoNotRevealCredentials() throws Exception {
        var bootstrap = bootstrap(null);
        for (int i = 0; i < 3; i++) mvc.perform(post(ROOT + "/login").session(bootstrap.session())
                        .header("X-CSRF-TOKEN", bootstrap.csrf()).header("X-Forwarded-For", "192.0.2." + i)
                        .contentType("application/json").content(body("wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOCAL_LOGIN_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(username))));
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                        .contentType("application/json").content(body(PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test void accountChangeRevokesExistingSessionBeforeAuthorization() throws Exception {
        var bootstrap = bootstrap(null);
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                .contentType("application/json").content(body(PASSWORD))).andExpect(status().isNoContent());
        when(accounts.byId(account.id())).thenReturn(Optional.of(new LocalAccountRepository.Account(account.id(),
                account.passwordHash(), false, 8, account.roles(), Set.of(), true)));
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access").session(bootstrap.session()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        assertThat(bootstrap.session().isInvalid()).isTrue();
    }

    @Test void loginStoreFailureReturnsNoIdentityOrDatabaseDetails() throws Exception {
        when(accounts.byLogin(username)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "jdbc:postgresql://private.invalid/catalog password=not-a-real-secret"));
        var bootstrap = bootstrap(null);
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                        .contentType("application/json").content(body(PASSWORD)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private.invalid"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not-a-real-secret"))));
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access").session(bootstrap.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test void identityStoreFailureFailsClosedAndDoesNotCacheError() throws Exception {
        var bootstrap = bootstrap(null);
        mvc.perform(post(ROOT + "/login").session(bootstrap.session()).header("X-CSRF-TOKEN", bootstrap.csrf())
                .contentType("application/json").content(body(PASSWORD))).andExpect(status().isNoContent());
        when(accounts.byId(account.id())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test-only unavailable"));
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access").session(bootstrap.session()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-only unavailable"))));
        assertThat(bootstrap.session().isInvalid()).isTrue();
    }

    private String body(String password) throws Exception { return json.writeValueAsString(java.util.Map.of("username", username, "password", password)); }
    private Bootstrap bootstrap(MockHttpSession session) throws Exception {
        var request = get(ROOT); if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.localLoginEnabled").value(true))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return new Bootstrap((MockHttpSession) result.getRequest().getSession(false), json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    private record Bootstrap(MockHttpSession session, String csrf) {}
}

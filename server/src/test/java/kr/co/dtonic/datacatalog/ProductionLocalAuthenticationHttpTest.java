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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {LocalAuthenticationController.class, OrganizationAccessController.class},
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class, LocalAuthenticationConfiguration.class, ApiProblems.class, OrganizationAccess.class})
@ActiveProfiles({"prod", "local-auth"})
@TestPropertySource(properties = "catalog.local-auth.enabled=true")
class ProductionLocalAuthenticationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @MockitoBean LocalAccountRepository accounts;

    @Test void explicitProductionLoginKeepsCsrfSessionAndPermissionBoundaries() throws Exception {
        String password = "Public-test-only-2026!";
        var account = new LocalAccountRepository.Account(UUID.randomUUID(), encoder.encode(password), true, 1,
                Set.of("PLATFORM_ADMIN"), Set.of(), true);
        when(accounts.byLogin("production-fixture")).thenReturn(Optional.of(account));
        when(accounts.byId(account.id())).thenReturn(Optional.of(account));
        String body = json.writeValueAsString(java.util.Map.of("username", "production-fixture", "password", password));
        String access = "/api/admin/v1/organizations/" + UUID.randomUUID() + "/access";
        mvc.perform(get(access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/v1/auth/login").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        var bootstrap = mvc.perform(get("/api/admin/v1/auth")).andExpect(status().isOk())
                .andExpect(jsonPath("$.localLoginEnabled").value(true)).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var session = (MockHttpSession) bootstrap.getRequest().getSession(false);
        String originalId = session.getId();
        String csrf = json.readTree(bootstrap.getResponse().getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/admin/v1/auth/login").session(session).header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json").content(body)).andExpect(status().isNoContent());
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get(access).session(session)).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").session(session)).andExpect(status().isForbidden());
    }

    @Test void enablingLocalLoginDoesNotEnableInternalDocumentsOrBasicAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs").with(user("document-reader").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("INTERNAL_API_DOCS"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/v1/organizations/" + UUID.randomUUID() + "/access")
                        .header("Authorization", "Basic cHVibGljLXRlc3Q6bm90LWEtY3JlZGVudGlhbA=="))
                .andExpect(status().isUnauthorized());
    }
}

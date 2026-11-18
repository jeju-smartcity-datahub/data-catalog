package kr.co.dtonic.datacatalog;

import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.config.SecurityConfiguration;
import kr.co.dtonic.datacatalog.config.LocalAuthenticationConfiguration;
import kr.co.dtonic.datacatalog.identity.api.LocalAuthenticationController;
import kr.co.dtonic.datacatalog.identity.OrganizationAccess;
import kr.co.dtonic.datacatalog.identity.api.OrganizationAccessController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {OrganizationAccessController.class, LocalAuthenticationController.class},
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class, LocalAuthenticationConfiguration.class, ApiProblems.class, OrganizationAccess.class})
@ActiveProfiles({"dev", "prod", "local-auth"})
class ProductionBoundaryTest {
    @Autowired MockMvc mvc;

    @Test
    void productionProfileBlocksInternalDocsEvenWhenDevAndDocumentAuthorityArePresent() throws Exception {
        mvc.perform(get("/v3/api-docs").with(user("document-reader")
                        .authorities(new SimpleGrantedAuthority("INTERNAL_API_DOCS"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void productionRequiresExplicitLocalAuthSettingAndDoesNotExposeLoginByDefault() throws Exception {
        mvc.perform(get("/api/admin/v1/auth")).andExpect(status().isOk())
                .andExpect(jsonPath("$.localLoginEnabled").value(false)).andExpect(jsonPath("$.csrfToken").isEmpty());
        mvc.perform(post("/api/admin/v1/auth/login").with(user("untrusted").roles("PLATFORM_ADMIN")).with(csrf())
                        .contentType("application/json").content("{\"username\":\"not-created\",\"password\":\"Public-test-only-2026!\"}"))
                .andExpect(status().isForbidden());
    }
}

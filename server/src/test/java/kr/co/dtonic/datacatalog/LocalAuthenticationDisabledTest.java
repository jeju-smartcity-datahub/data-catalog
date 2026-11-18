package kr.co.dtonic.datacatalog;

import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.config.SecurityConfiguration;
import kr.co.dtonic.datacatalog.identity.api.LocalAuthenticationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LocalAuthenticationController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class, ApiProblems.class})
@ActiveProfiles("test")
class LocalAuthenticationDisabledTest {
    @Autowired MockMvc mvc;
    @Test void explicitLocalAuthProfileIsRequiredAndDisabledBootstrapCreatesNoSession() throws Exception {
        var result = mvc.perform(get("/api/admin/v1/auth")).andExpect(status().isOk())
                .andExpect(jsonPath("$.localLoginEnabled").value(false))
                .andExpect(jsonPath("$.csrfToken").isEmpty()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        mvc.perform(post("/api/admin/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"username\":\"not-created\",\"password\":\"Public-test-only-2026!\"}"))
                .andExpect(status().isUnauthorized());
    }
}

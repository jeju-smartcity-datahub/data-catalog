package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.api.CatalogController;
import kr.co.dtonic.datacatalog.catalog.api.DatasetPage;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.config.SecurityConfiguration;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.OrganizationAccess;
import kr.co.dtonic.datacatalog.identity.api.OrganizationAccessController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CatalogController.class, OrganizationAccessController.class},
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class, ApiProblems.class, OrganizationAccess.class})
@ActiveProfiles("test")
class SecurityBoundaryTest {
    private static final UUID ORGANIZATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String ADMIN_PATH = "/api/admin/v1/organizations/" + ORGANIZATION + "/access";
    @Autowired MockMvc mvc;
    @MockitoBean CatalogQueryService catalog;

    @Test
    void anonymousCanReadCatalogWithRequestId() throws Exception {
        when(catalog.listPublished(0, 10, CatalogFilter.empty())).thenReturn(new DatasetPage(List.of(), new DatasetPage.PageMetadata(0, 10, 0, 0)));
        mvc.perform(get("/api/catalog/v1/datasets").header("X-Request-Id", "request-test-123"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id",org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.page.size").value(10));
    }

    @Test
    void anonymousAdminRequestIs401WithProblemDetails() throws Exception {
        mvc.perform(get(ADMIN_PATH)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void regularUserCannotUseAdminEndpoint() throws Exception {
        mvc.perform(get(ADMIN_PATH).with(principal("USER", ORGANIZATION)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void organizationRoleCannotReadAnotherOrganization() throws Exception {
        mvc.perform(get(ADMIN_PATH).with(principal("ORG_EDITOR", UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationEditorAndReviewerCanReadOwnScope() throws Exception {
        for (String role : List.of("ORG_EDITOR", "ORG_REVIEWER")) {
            mvc.perform(get(ADMIN_PATH).with(principal(role, ORGANIZATION)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.readable").value(true));
        }
    }

    @Test
    void platformAdministratorCanReadOtherScope() throws Exception {
        mvc.perform(get(ADMIN_PATH).with(principal("PLATFORM_ADMIN", UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void roleWithoutTrustedPrincipalCannotGrantAccess() throws Exception {
        mvc.perform(get(ADMIN_PATH).with(user("not-an-identity-adapter").roles("PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownEndpointsAreDeniedEvenForPlatformAdministrator() throws Exception {
        mvc.perform(get("/api/unknown").with(principal("PLATFORM_ADMIN", ORGANIZATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfRemainsEnabledForMutation() throws Exception {
        mvc.perform(post(ADMIN_PATH).with(principal("PLATFORM_ADMIN", ORGANIZATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPaginationReturns400WithoutEchoingInput() throws Exception {
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=secret-invalid")) {
            mvc.perform(get("/api/catalog/v1/datasets" + query)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
    }

    @Test
    void internalApiDocsRequireAuthenticationAndDedicatedAuthority() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(principal("PLATFORM_ADMIN", ORGANIZATION)))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor principal(String role, UUID organizationId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new CatalogPrincipal(UUID.randomUUID(), Set.of(organizationId)), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}

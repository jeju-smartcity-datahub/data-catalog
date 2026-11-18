package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.analytics.*;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.config.SecurityConfiguration;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={SearchFeedbackController.class,AnalyticsController.class},excludeAutoConfiguration=UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfiguration.class,ApiProblems.class,WorkflowAccess.class})
@ActiveProfiles("test")
class SearchFeedbackSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SearchHistoryRepository repository;
    @MockitoBean SearchRetentionPolicy retention;
    @MockitoBean AnalyticsService analytics;
    private final UUID id=UUID.randomUUID();
    private String body(){return "{\"eventId\":\""+id+"\",\"feedbackToken\":\""+"a".repeat(43)+"\",\"rating\":\"HELPFUL\"}";}
    @Test void anonymousFormIsNoStoreAndMutationStillRequiresCsrf()throws Exception{
        mvc.perform(get("/api/catalog/v1/search-feedback-form")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN")).andExpect(jsonPath("$.csrfToken").isString());
        mvc.perform(post("/api/catalog/v1/search-feedback").header("Origin","http://localhost").contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }
    @Test void exactSameOriginSelectedRatingCanSubmitWithCsrf()throws Exception{
        mvc.perform(post("/api/catalog/v1/search-feedback").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(body())).andExpect(status().isNoContent());
        verify(repository).feedback(any(),any());
    }
    @Test void foreignOriginAndLargeOrNonJsonRequestsNeverReachStorage()throws Exception{
        mvc.perform(post("/api/catalog/v1/search-feedback").with(csrf()).header("Origin","http://other.invalid").contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post("/api/catalog/v1/search-feedback").with(csrf()).header("Origin","http://localhost").contentType("text/plain").content(body())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/catalog/v1/search-feedback").with(csrf()).header("Origin","http://localhost").contentType("application/json").content("x".repeat(2049))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(repository);
    }
    @Test void unknownRatingOrInvalidCapabilityDoesNotEchoRejectedValues()throws Exception{
        mvc.perform(post("/api/catalog/v1/search-feedback").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(body().replace("HELPFUL","person@example.invalid")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("person@example.invalid"))));
        verifyNoInteractions(repository);
    }
    @Test void onlyPlatformAdministratorCanReadDetails()throws Exception{
        mvc.perform(get("/api/admin/v1/search-history/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/search-history/"+id).with(actor("ORG_AUDITOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/v1/search-history/"+id).with(actor("PLATFORM_ADMIN"))).andExpect(status().isOk());
        verify(repository,times(1)).detail(eq(id),any());
    }
    @Test void noOtherPublicMethodsOrSubpathsAreOpened()throws Exception{
        mvc.perform(get("/api/catalog/v1/search-feedback")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/catalog/v1/search-feedback/other").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(body())).andExpect(status().isUnauthorized());
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(String role){return authentication(UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),Set.of()),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
}

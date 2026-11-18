package kr.co.dtonic.datacatalog.config;

import java.util.Arrays;
import java.util.List;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.identity.LocalAuthenticationService;
import kr.co.dtonic.datacatalog.identity.LocalSessionValidationFilter;
import kr.co.dtonic.datacatalog.analytics.SearchFeedbackRequestFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiProblems problems, Environment environment,
            ObjectProvider<LocalAuthenticationService> localAuthentication,
            SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) throws Exception {
        boolean developmentDocs = environment.acceptsProfiles(Profiles.of("(dev | test) & !prod & !production"));
        boolean localLogin = localAuthentication.getIfAvailable() != null;
        http.cors(cors -> {}).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.requireExplicitSave(true).securityContextRepository(contexts))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .addFilterBefore(new LocalSessionValidationFilter(localAuthentication, problems), CsrfFilter.class)
                .addFilterBefore(new SearchFeedbackRequestFilter(problems), CsrfFilter.class)
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problems.write(request, response,
                                HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> problems.write(request, response,
                                HttpStatus.FORBIDDEN, "ACCESS_DENIED", "요청한 작업을 수행할 권한이 없습니다.")))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, "/api/admin/v1/auth").permitAll();
                    requests.requestMatchers(HttpMethod.GET,"/api/catalog/v1/search-feedback-form").permitAll();
                    requests.requestMatchers(HttpMethod.POST,"/api/catalog/v1/search-feedback").permitAll();
                    if (localLogin) {
                        requests.requestMatchers(HttpMethod.POST, "/api/admin/v1/auth/login").permitAll();
                    } else {
                        requests.requestMatchers("/api/admin/v1/auth/login", "/api/admin/v1/auth/logout").denyAll();
                    }
                    requests.requestMatchers(HttpMethod.GET, "/actuator/health", "/api/catalog/v1/datasets",
                            "/api/catalog/v1/datasets/facets", "/api/catalog/v1/datasets/*", "/api/catalog/v1/datasets/*/fields",
                            "/api/catalog/v1/datasets/*/access", "/api/catalog/v1/datasets/*/versions", "/api/catalog/v1/datasets/*/versions/*",
                            "/api/catalog/v1/datasets/*/records", "/api/catalog/v1/datasets/*/records/download",
                            "/api/catalog/v1/regions", "/api/catalog/v1/hubs",
                            "/api/catalog/v1/search", "/api/catalog/v1/search/facets",
                            "/api/catalog/v1/dcat", "/api/catalog/v1/dcat/download", "/api/docs/v1/datasets/*").permitAll();
                    if (developmentDocs) {
                        requests.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").hasAuthority("INTERNAL_API_DOCS");
                    }
                    requests.requestMatchers("/api/admin/**").authenticated().anyRequest().denyAll();
                });
        // CSRF remains enabled for login and logout as well as all mutations.
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${catalog.cors-origins:}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-Request-Id", "If-Match", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "ETag", "Location", "X-Total-Count", "Link", "Content-Disposition", "Retry-After"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

package kr.co.dtonic.datacatalog.identity;

import kr.co.dtonic.datacatalog.config.LocalAuthenticationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LocalAuthenticationProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalAuthenticationConfiguration.class, LocalAccountRepository.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test void productionAndDefaultProfilesDoNotEnableLocalAuthenticationByDefault() {
        for (String[] profiles : new String[][]{{}, {"dev"}, {"test"}, {"local-auth"}, {"prod", "local-auth"},
                {"production", "local-auth"}, {"prod", "dev", "local-auth"}, {"production", "test", "local-auth"}}) {
            var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
            assertThat(LocalAuthProfiles.enabled(environment)).as(String.join(",", profiles)).isFalse();
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .run(context -> assertThat(context).hasNotFailed()
                            .doesNotHaveBean(LocalAuthenticationService.class).doesNotHaveBean(LocalAccountRepository.class));
        }
    }

    @Test void productionRequiresBothLocalAuthProfileAndExplicitSetting() {
        for (String production : new String[]{"prod", "production"}) {
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(production, "local-auth"))
                    .withPropertyValues("catalog.local-auth.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LocalAuthenticationService.class)
                            .hasSingleBean(LocalAccountRepository.class));
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(production, "local-auth"))
                    .withPropertyValues("catalog.local-auth.enabled=false")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalAuthenticationService.class)
                            .doesNotHaveBean(LocalAccountRepository.class));
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(production))
                    .withPropertyValues("catalog.local-auth.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalAuthenticationService.class));
        }
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local-auth"))
                .withPropertyValues("catalog.local-auth.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalAuthenticationService.class));
    }

    @Test void developmentAndTestProfileOptInStillEnableLocalAuthentication() {
        for (String profile : new String[]{"dev", "test"}) {
            var environment = new MockEnvironment(); environment.setActiveProfiles(profile, "local-auth");
            assertThat(LocalAuthProfiles.enabled(environment)).isTrue();
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile, "local-auth"))
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LocalAuthenticationService.class));
        }
    }

    @Test void insecureProductionCookiesRequireExplicitHttpOptionEvenOnLoopbackServer() {
        for (String production : new String[]{"prod", "production"}) {
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(production, "local-auth"))
                    .withPropertyValues("catalog.local-auth.enabled=true", "server.servlet.session.cookie.secure=false", "server.address=127.0.0.1")
                    .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Production local-auth requires secure session cookies"));
        }
    }

    @Test void explicitHttpOptionAllowsProductionLocalAuthenticationOnContainerInterface() {
        for (String production : new String[]{"prod", "production"}) {
            for (String option : new String[]{"catalog.local-auth.allow-insecure-http=true",
                    "CATALOG_LOCAL_AUTH_ALLOW_INSECURE_HTTP=true"}) {
                runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(production, "local-auth"))
                        .withPropertyValues("catalog.local-auth.enabled=true", option,
                                "server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                        .run(context -> assertThat(context).hasNotFailed()
                                .hasSingleBean(LocalAuthenticationService.class).hasSingleBean(LocalAccountRepository.class));
            }
        }
    }

    @Test void disabledHttpOptionKeepsProductionSecureCookieRequirement() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local-auth"))
                .withPropertyValues("catalog.local-auth.enabled=true", "catalog.local-auth.allow-insecure-http=false",
                        "server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Production local-auth requires secure session cookies"));
    }

    @Test void httpOptionDoesNotEnableLocalAuthenticationWithoutItsExistingOptIns() {
        for (String[] profiles : new String[][]{{"prod"}, {"production"}, {"local-auth"}}) {
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                    .withPropertyValues("catalog.local-auth.enabled=true", "catalog.local-auth.allow-insecure-http=true",
                            "server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalAuthenticationService.class));
        }
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local-auth"))
                .withPropertyValues("catalog.local-auth.allow-insecure-http=true",
                        "server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(LocalAuthenticationService.class));
    }

    @Test void productionHttpOptionDoesNotRelaxDevelopmentLoopbackRequirement() {
        for (String profile : new String[]{"dev", "test"}) {
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile, "local-auth"))
                    .withPropertyValues("catalog.local-auth.allow-insecure-http=true",
                            "server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                    .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Insecure local-auth cookies require an explicit loopback server.address"));
        }
    }

    @Test void insecureDevelopmentCookiesStillRequireLoopbackServer() {
        runner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", "local-auth"))
                .withPropertyValues("server.servlet.session.cookie.secure=false", "server.address=0.0.0.0")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Insecure local-auth cookies require an explicit loopback server.address"));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev", "local-auth"))
                .withPropertyValues("server.servlet.session.cookie.secure=false", "server.address=127.0.0.1")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LocalAuthenticationService.class));
    }
}

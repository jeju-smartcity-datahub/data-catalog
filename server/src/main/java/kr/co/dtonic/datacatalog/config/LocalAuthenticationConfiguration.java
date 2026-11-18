package kr.co.dtonic.datacatalog.config;

import java.time.Clock;
import java.util.Set;
import kr.co.dtonic.datacatalog.identity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Conditional(LocalAuthenticationCondition.class)
public class LocalAuthenticationConfiguration {
    @Bean
    PasswordEncoder localPasswordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    LocalAuthenticationService localAuthenticationService(LocalAccountRepository accounts, PasswordEncoder encoder,
            Environment environment,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${catalog.local-auth.allow-insecure-http:${CATALOG_LOCAL_AUTH_ALLOW_INSECURE_HTTP:false}}") boolean allowInsecureHttp) {
        // Only the explicitly selected production HTTP installation may use a non-Secure session cookie.
        // Local-auth profile and catalog.local-auth.enabled remain required by LocalAuthenticationCondition.
        boolean directHttp = LocalAuthProfiles.production(environment) && allowInsecureHttp;
        if (!secure && LocalAuthProfiles.production(environment) && !directHttp) {
            throw new IllegalStateException("Production local-auth requires secure session cookies");
        }
        if (!secure && !directHttp && !Set.of("127.0.0.1", "::1", "[::1]").contains(address)) {
            throw new IllegalStateException("Insecure local-auth cookies require an explicit loopback server.address");
        }
        return new LocalAuthenticationService(accounts, encoder);
    }

    @Bean
    LocalLoginRateLimiter localLoginRateLimiter(
            @Value("${catalog.local-auth.username-attempts:5}") int usernameAttempts,
            @Value("${catalog.local-auth.address-attempts:30}") int addressAttempts,
            @Value("${catalog.local-auth.window-seconds:60}") int windowSeconds) {
        return new LocalLoginRateLimiter(Clock.systemUTC(), usernameAttempts, addressAttempts, windowSeconds);
    }
}

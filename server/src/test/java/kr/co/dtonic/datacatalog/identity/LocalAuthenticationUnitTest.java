package kr.co.dtonic.datacatalog.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalAuthenticationUnitTest {
    @Test
    void rolesAndInstitutionMembershipMustBeUsable() {
        var id = UUID.randomUUID();
        assertThat(account(id, "hash", true, 1, Set.of("PLATFORM_ADMIN"), Set.of(), true).usable()).isTrue();
        assertThat(account(id, "hash", true, 1, Set.of("ORG_EDITOR"), Set.of(), true).usable()).isFalse();
        assertThat(account(id, "hash", true, 1, Set.of("USER"), Set.of(UUID.randomUUID()), true).usable()).isFalse();
        assertThat(account(id, "hash", false, 1, Set.of("PLATFORM_ADMIN"), Set.of(), true).usable()).isFalse();
        assertThat(account(id, "hash", true, 1, Set.of("PLATFORM_ADMIN"), Set.of(UUID.randomUUID()), false).usable()).isFalse();
    }

    @Test
    void realPasswordIsRequiredAndSessionCarriesNoCredential() {
        var repository = mock(LocalAccountRepository.class);
        var encoder = new BCryptPasswordEncoder(4); // Fast isolated test; runtime and V17 require cost 12.
        var value = account(UUID.randomUUID(), encoder.encode("Public-test-only-2026!"), true, 7, Set.of("PLATFORM_ADMIN"), Set.of(), true);
        when(repository.byLogin("fixture-admin")).thenReturn(Optional.of(value));
        var service = new LocalAuthenticationService(repository, encoder);
        var auth = service.authenticate("  FIXTURE-ADMIN  ", "Public-test-only-2026!");
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.getPrincipal().userId()).isEqualTo(value.id());
        assertThat(auth.version()).isEqualTo(7);
        assertThatThrownBy(() -> service.authenticate("fixture-admin", "wrong")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate("missing", "wrong")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.authenticate("fixture-admin", "가".repeat(25))).isInstanceOf(BadCredentialsException.class);
        assertThat(value.toString()).doesNotContain(value.passwordHash());
    }

    @Test
    void currentSessionRequiresExactVersionRolesAndActiveInstitutionScope() {
        var repository = mock(LocalAccountRepository.class);
        var encoder = new BCryptPasswordEncoder(4);
        var id = UUID.randomUUID(); var org = UUID.randomUUID();
        var value = account(id, encoder.encode("Public-test-only-2026!"), true, 7, Set.of("ORG_EDITOR"), Set.of(org), true);
        when(repository.byLogin("fixture-admin")).thenReturn(Optional.of(value));
        when(repository.byId(id)).thenReturn(Optional.of(value));
        var service = new LocalAuthenticationService(repository, encoder);
        var auth = service.authenticate("fixture-admin", "Public-test-only-2026!");
        assertThat(service.current(auth)).isTrue();
        for (var changed : java.util.List.of(
                account(id, "hash", true, 8, value.roles(), value.organizationIds(), true),
                account(id, "hash", true, 7, Set.of("ORG_AUDITOR"), value.organizationIds(), true),
                account(id, "hash", true, 7, value.roles(), Set.of(UUID.randomUUID()), true),
                account(id, "hash", true, 7, value.roles(), value.organizationIds(), false),
                account(id, "hash", false, 7, value.roles(), value.organizationIds(), true))) {
            when(repository.byId(id)).thenReturn(Optional.of(changed));
            assertThat(service.current(auth)).isFalse();
        }
    }

    @Test
    void rateLimitCombinesUsernameAndSocketAddressWithoutResetOnSuccess() {
        var clock = mock(Clock.class); when(clock.millis()).thenReturn(1000L);
        var limiter = new LocalLoginRateLimiter(clock, 2, 3, 60);
        assertThat(limiter.reserve("admin", "127.0.0.1")).isZero();
        assertThat(limiter.reserve("admin", "127.0.0.2")).isZero();
        assertThat(limiter.reserve("admin", "127.0.0.3")).isEqualTo(60);
        assertThat(limiter.reserve("other", "127.0.0.1")).isZero();
        assertThat(limiter.reserve("third", "127.0.0.1")).isZero();
        assertThat(limiter.reserve("fourth", "127.0.0.1")).isEqualTo(60);
        when(clock.millis()).thenReturn(61_001L);
        assertThat(limiter.reserve("admin", "127.0.0.1")).isZero();
    }

    @Test
    void capacityIsBoundedAndRefusesFurtherNewKeys() {
        var limiter = new LocalLoginRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2, 2, 60);
        for (int i = 0; i < 5000; i++) assertThat(limiter.reserve("user-" + i, "peer-" + i)).isZero();
        assertThat(limiter.reserve("extra", "extra")).isEqualTo(60);
        assertThat(limiter.reserve("user-0", "peer-0")).isZero();
    }

    private static LocalAccountRepository.Account account(UUID id, String hash, boolean active, long version,
            Set<String> roles, Set<UUID> orgs, boolean organizationsActive) {
        return new LocalAccountRepository.Account(id, hash, active, version, roles, orgs, organizationsActive);
    }
}

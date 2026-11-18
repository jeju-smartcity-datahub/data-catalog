package kr.co.dtonic.datacatalog.identity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Conditional(LocalAuthenticationCondition.class)
public class LocalAccountRepository {
    private final JdbcTemplate jdbc;
    public LocalAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Account> byLogin(String normalizedLogin) { return load("lgn_id", normalizedLogin); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Account> byId(UUID id) { return load("id", id); }

    private Optional<Account> load(String key, Object value) {
        var rows = jdbc.query("SELECT id, pwd_hash, stts_cd, auth_ver_no FROM identity.local_account WHERE " + key + " = ?",
                (rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("pwd_hash"),
                        rs.getString("stts_cd").equals("ACTIVE"), rs.getLong("auth_ver_no"), Set.of(), Set.of(), true), value);
        if (rows.isEmpty()) return Optional.empty();
        var account = rows.getFirst();
        Set<String> roles = Set.copyOf(jdbc.query("SELECT role_cd FROM identity.local_account_role WHERE acnt_id = ?",
                (rs, n) -> rs.getString(1), account.id()));
        var organizations = jdbc.query("SELECT o.id, o.stts_cd FROM identity.local_account_organization m "
                        + "JOIN identity.organization o ON o.id = m.inst_id WHERE m.acnt_id = ?",
                (rs, n) -> new Organization(rs.getObject(1, UUID.class), rs.getString(2).equals("ACTIVE")), account.id());
        return Optional.of(new Account(account.id(), account.passwordHash(), account.active(), account.version(), roles,
                organizations.stream().map(Organization::id).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                organizations.stream().allMatch(Organization::active)));
    }

    private record Organization(UUID id, boolean active) {}
    public record Account(UUID id, String passwordHash, boolean active, long version, Set<String> roles,
                          Set<UUID> organizationIds, boolean organizationsActive) {
        private static final Set<String> ALLOWED = Set.of("PLATFORM_ADMIN", "ORG_EDITOR", "ORG_REVIEWER", "ORG_PUBLISHER", "ORG_AUDITOR");
        public boolean usable() {
            return active && organizationsActive && !roles.isEmpty() && ALLOWED.containsAll(roles)
                    && (roles.contains("PLATFORM_ADMIN") || !organizationIds.isEmpty());
        }
        @Override public String toString() { return "LocalAccount[REDACTED]"; }
    }
}

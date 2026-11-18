package kr.co.dtonic.datacatalog.bootstrap;

import java.io.Console;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Manual console utility. Never discovered or run by the web application. */
public final class LocalAccountProvisioner {
    private static final Set<String> ROLES = Set.of("PLATFORM_ADMIN", "ORG_EDITOR", "ORG_REVIEWER", "ORG_PUBLISHER", "ORG_AUDITOR");
    private LocalAccountProvisioner() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Manual local account: --username <id> --roles <comma-separated roles> [--organizations <comma-separated UUIDs>]");
            System.out.println("Requires exactly dev,local-auth with a loopback PostgreSQL database, or prod,local-auth with CATALOG_LOCAL_AUTH_ENABLED=true and jdbc:postgresql://db:5432/datacatalog.");
            System.out.println("An interactive console is required. Password is read without echo; no account is seeded or replaced.");
            return;
        }
        char[] password = null;
        char[] confirmation = null;
        int exitCode = 0;
        try {
            Settings settings = settings(args, System.getenv());
            Console console = System.console();
            if (console == null) throw new IllegalArgumentException("An interactive console is required. Password arguments, environment values and redirected input are unsupported.");
            console.printf("Local account target: %s%nUsername: %s%nRoles: %s%nOrganizations: %s%n", settings.url(), settings.username(), settings.roles(), settings.organizations());
            console.printf("This creates one account in the displayed database. Existing accounts are never replaced.%n");
            password = console.readPassword("New password (hidden): ");
            confirmation = console.readPassword("Repeat password (hidden): ");
            validatePassword(password, confirmation);
            String hash = new BCryptPasswordEncoder(12).encode(CharBuffer.wrap(password));
            try (Connection connection = DriverManager.getConnection(settings.url(), settings.databaseUser(), settings.databasePassword())) {
                createAccount(connection, settings, hash);
            }
            console.printf("Created the requested local account. No credential was printed. Start the explicitly enabled local-auth server to sign in.%n");
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            exitCode = 1;
        } catch (SQLException error) {
            // Never print JDBC messages, SQL parameters or a password/hash on failure.
            System.err.println("Account creation was not confirmed. Do not retry automatically: inspect the requested login ID first, then check V17 and the active organization IDs. A lost commit response may mean the account already exists.");
            exitCode = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static Settings settings(String[] args, Map<String, String> environment) {
        Set<String> profiles = new LinkedHashSet<>(Arrays.asList(environment.getOrDefault("SPRING_PROFILES_ACTIVE", "").split(",")));
        boolean development = profiles.equals(Set.of("dev", "local-auth"));
        boolean deployed = profiles.equals(Set.of("prod", "local-auth")) && "true".equals(environment.get("CATALOG_LOCAL_AUTH_ENABLED"));
        if (!development && !deployed) throw new IllegalArgumentException("Provisioning requires exactly dev,local-auth, or prod,local-auth with CATALOG_LOCAL_AUTH_ENABLED=true.");
        String jdbcUrl = environment.getOrDefault("DB_URL", "");
        if (deployed) {
            if (!jdbcUrl.equals("jdbc:postgresql://db:5432/datacatalog")) throw new IllegalArgumentException("Deployed provisioning requires the exact internal database URL jdbc:postgresql://db:5432/datacatalog.");
        } else {
            URI uri;
            try { uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", "")); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("A plain loopback PostgreSQL JDBC URL is required."); }
            if (!jdbcUrl.startsWith("jdbc:postgresql://") || !Set.of("127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || !uri.getPath().matches("/[a-zA-Z0-9_]+")) {
                throw new IllegalArgumentException("A plain loopback PostgreSQL JDBC URL is required; remote hosts, URL credentials and query options are rejected.");
            }
        }
        String username = null;
        Set<String> roles = null;
        Set<UUID> organizations = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !seen.add(args[index])) throw new IllegalArgumentException("Invalid or repeated account option. Use --help.");
            switch (args[index]) {
                case "--username" -> username = args[index + 1];
                case "--roles" -> roles = new LinkedHashSet<>(Arrays.asList(args[index + 1].split(",", -1)));
                case "--organizations" -> {
                    try { for (String value : args[index + 1].split(",", -1)) organizations.add(UUID.fromString(value)); }
                    catch (IllegalArgumentException error) { throw new IllegalArgumentException("Organizations must be UUIDs."); }
                }
                default -> throw new IllegalArgumentException("Unsupported account option. Passwords must only be entered in the hidden console prompt.");
            }
        }
        if (username == null || !username.matches("[a-z0-9][a-z0-9._-]{2,79}")) throw new IllegalArgumentException("Username must be 3–80 lowercase ASCII letters, digits, dots, underscores or hyphens.");
        if (roles == null || roles.isEmpty() || !ROLES.containsAll(roles)) throw new IllegalArgumentException("Explicit supported roles are required; use --help.");
        if (!roles.contains("PLATFORM_ADMIN") && organizations.isEmpty()) throw new IllegalArgumentException("Organization roles require at least one active organization.");
        String dbUser = environment.get("DB_USERNAME"), dbPassword = environment.get("DB_PASSWORD");
        if (dbUser == null || dbUser.isBlank() || dbPassword == null || dbPassword.isBlank()) throw new IllegalArgumentException("Existing database credentials are required.");
        return new Settings(jdbcUrl, dbUser, dbPassword, username, Set.copyOf(roles), Set.copyOf(organizations));
    }

    static void validatePassword(char[] password, char[] confirmation) {
        if (password == null || confirmation == null || password.length == 0 || !Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords must be nonempty and match.");
        var bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        try {
            if (bytes.remaining() > 72) throw new IllegalArgumentException("Password exceeds the BCrypt limit of 72 UTF-8 bytes.");
        } finally { if (bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0); }
    }

    static void createAccount(Connection connection, Settings settings, String passwordHash) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (var lock = connection.prepareStatement("SELECT stts_cd FROM identity.organization WHERE id = ? FOR SHARE")) {
                for (UUID organization : settings.organizations()) {
                    lock.setObject(1, organization);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next() || !rows.getString(1).equals("ACTIVE")) throw new IllegalArgumentException("Every assigned organization must exist and be active.");
                    }
                }
            }
            UUID account = UUID.randomUUID();
            try (var insert = connection.prepareStatement("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,?,?,'INACTIVE')")) {
                insert.setObject(1, account); insert.setString(2, settings.username()); insert.setString(3, passwordHash); insert.executeUpdate();
            }
            try (var role = connection.prepareStatement("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,?)")) {
                for (String value : settings.roles()) { role.setObject(1, account); role.setString(2, value); role.addBatch(); }
                role.executeBatch();
            }
            try (var organization = connection.prepareStatement("INSERT INTO identity.local_account_organization(acnt_id,inst_id) VALUES (?,?)")) {
                for (UUID value : settings.organizations()) { organization.setObject(1, account); organization.setObject(2, value); organization.addBatch(); }
                organization.executeBatch();
            }
            try (var activate = connection.prepareStatement("UPDATE identity.local_account SET stts_cd='ACTIVE' WHERE id=?")) {
                activate.setObject(1, account); activate.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
    }

    // Deliberately not a record: its default toString must never expose database credentials.
    static final class Settings {
        private final String url, databaseUser, databasePassword, username;
        private final Set<String> roles;
        private final Set<UUID> organizations;
        Settings(String url, String databaseUser, String databasePassword, String username, Set<String> roles, Set<UUID> organizations) {
            this.url=url; this.databaseUser=databaseUser; this.databasePassword=databasePassword; this.username=username; this.roles=roles; this.organizations=organizations;
        }
        String url() { return url; }
        String databaseUser() { return databaseUser; }
        String databasePassword() { return databasePassword; }
        String username() { return username; }
        Set<String> roles() { return roles; }
        Set<UUID> organizations() { return organizations; }
    }
}

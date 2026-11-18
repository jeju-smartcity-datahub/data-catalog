package kr.co.dtonic.datacatalog.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Console;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit console-only first administrator. Never invoked by application startup. */
public final class EmptyDatabaseAdminBootstrap {
    static final String ACTION = "INITIAL_ADMIN_BOOTSTRAPPED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SCHEMAS = Set.of("identity", "classification", "catalog", "metadata", "audit", "standard", "analytics", "integration", "delivery", "apidocs");
    private static final Set<String> REQUIRED_TABLES = Set.of("identity.local_account", "identity.local_account_role", "identity.local_account_organization", "identity.organization", "catalog.dataset", "integration.source_connection", "audit.audit_event");
    private static final Set<String> FILTERS = Set.of("organizationId", "categoryId", "regionId", "hubId", "topicId", "format", "accessPolicy", "sort");
    private static final Set<String> SORTS = Set.of("publishedAt_desc", "title_asc", "relevance");
    private static final Set<Integer> REVIEWED_MIGRATIONS = Set.of(19, 20, 21, 22, 23, 24, 25, 26, 27);
    // Exact V23 seed only. A populated or edited classification is never an empty installation.
    private static final List<String> V23_CATEGORY_NAMES = List.of("교육", "국토관리", "공공행정", "재정금융", "산업고용", "사회복지",
            "식품건강", "문화관광", "보건의료", "재난안전", "교통물류", "환경기상", "과학기술", "농축수산", "통일외교안보", "법률");
    private EmptyDatabaseAdminBootstrap() { }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Owned empty-database bootstrap: --installation-id UUID --migration-version N [--approval-reference ID] [--check]");
            System.out.println("Creates only admin / PLATFORM_ADMIN after an interactive confirmation and hidden password entry. Existing accounts/data are never overwritten.");
            System.out.println("The offline bootstrap wrapper must validate the owned installation first. --check is read-only; passwords in arguments/environment/pipes are unsupported.");
            return;
        }
        char[] password = null, confirmation = null;
        int code = 0;
        try {
            Settings settings = settings(args, System.getenv());
            Outcome before;
            try (Connection connection = connect(settings)) { connection.setReadOnly(true); before = inspect(connection, settings); }
            if (settings.checkOnly() || before == Outcome.ALREADY_INITIALIZED) { print(before, settings); return; }
            Console console = System.console();
            if (console == null) throw invalid("An interactive terminal is required; no password argument, environment value or redirected input is accepted.");
            console.printf("Target: owned installation %s, internal database datacatalog.%n", settings.installationId());
            console.printf("This creates admin with PLATFORM_ADMIN in a new empty database. Existing accounts are never changed.%n");
            if (!"INIT admin".equals(console.readLine("Type INIT admin to continue: "))) throw invalid("Initialization cancelled; no account was created.");
            password = console.readPassword("New admin password (hidden, at least 12 characters): ");
            confirmation = console.readPassword("Repeat password (hidden): ");
            validatePassword(password, confirmation);
            String hash = new BCryptPasswordEncoder(12).encode(CharBuffer.wrap(password));
            try (Connection connection = connect(settings)) { print(bootstrap(connection, settings, hash), settings); }
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage()); code = 2;
        } catch (SQLException failure) {
            System.err.println("Administrator initialization was not confirmed. No automatic retry: run the read-only check for this installation; a lost commit response may mean it already completed."); code = 2;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
        if (code != 0) System.exit(code);
    }

    private static Connection connect(Settings settings) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://db:5432/datacatalog", settings.databaseUser(), settings.databasePassword());
    }
    private static void print(Outcome outcome, Settings settings) {
        System.out.println("{\"status\":\"" + outcome + "\",\"username\":\"admin\",\"role\":\"PLATFORM_ADMIN\",\"installationId\":\"" + settings.installationId() + "\"}");
    }

    static Settings settings(String[] args, Map<String, String> environment) {
        if (!new LinkedHashSet<>(Arrays.asList(environment.getOrDefault("SPRING_PROFILES_ACTIVE", "").split(","))).equals(Set.of("prod", "local-auth"))
                || !"true".equals(environment.get("CATALOG_LOCAL_AUTH_ENABLED"))
                || !"jdbc:postgresql://db:5432/datacatalog".equals(environment.get("DB_URL")))
            throw invalid("Bootstrap requires the explicitly enabled owned prod,local-auth internal database; remote/development databases are not accepted.");
        for (String key : List.of("ADMIN_PASSWORD", "BOOTSTRAP_PASSWORD", "CATALOG_BOOTSTRAP_PASSWORD"))
            if (environment.containsKey(key)) throw invalid("Password environment input is unsupported; use the hidden console prompt.");
        String installation = null, approval = null; int migration = 0; boolean check = false;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!seen.add(option)) throw invalid("Repeated bootstrap option; use --help.");
            if (option.equals("--check")) { check = true; continue; }
            if (i + 1 >= args.length) throw invalid("Missing bootstrap option value; use --help.");
            String value = args[++i];
            switch (option) {
                case "--installation-id" -> installation = value;
                case "--approval-reference" -> approval = value;
                case "--migration-version" -> { try { migration = Integer.parseInt(value); } catch (NumberFormatException invalid) { throw invalid("Invalid reviewed migration version."); } }
                default -> throw invalid("Unsupported bootstrap option; passwords and roles cannot be passed as arguments.");
            }
        }
        UUID id;
        try { id = UUID.fromString(installation); if (!id.toString().equals(installation)) throw new IllegalArgumentException(); }
        catch (RuntimeException invalid) { throw invalid("A canonical installation UUID is required."); }
        requireReviewedMigration(migration);
        if ((!check && approval == null) || (approval != null && !approval.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,159}")))
            throw invalid("Provide the actual non-secret initialization approval reference.");
        String user = environment.get("DB_USERNAME"), secret = environment.get("DB_PASSWORD");
        if (user == null || user.isBlank() || secret == null || secret.isBlank()) throw invalid("Existing internal database credentials are required.");
        return new Settings(id, migration, approval, check, user, secret);
    }

    static void validatePassword(char[] password, char[] confirmation) {
        LocalAccountProvisioner.validatePassword(password, confirmation);
        if (CharBuffer.wrap(password).codePoints().count() < 12 || CharBuffer.wrap(password).codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
            throw invalid("Choose at least 12 nonempty characters within the BCrypt 72 UTF-8 byte limit.");
    }

    static Outcome inspect(Connection connection, Settings settings) throws SQLException {
        verifyMigration(connection, settings.migrationVersion());
        Outcome existing = marker(connection, settings.installationId());
        if (existing != null) return existing;
        List<String> tables = tables(connection);
        requireEmpty(connection, tables, settings.migrationVersion());
        return Outcome.EMPTY;
    }

    static Outcome bootstrap(Connection connection, Settings settings, String passwordHash) throws SQLException {
        if (settings.checkOnly()) throw invalid("Read-only check cannot initialize an account.");
        if (passwordHash == null || !passwordHash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")) throw invalid("A BCrypt cost12 password hash is required.");
        connection.setAutoCommit(false);
        try {
            try (var command = connection.createStatement()) {
                command.execute("SET LOCAL lock_timeout='5s'"); command.execute("SET LOCAL statement_timeout='15s'");
                command.execute("SELECT pg_advisory_xact_lock(638726194250)");
            }
            verifyMigration(connection, settings.migrationVersion());
            List<String> tables = tables(connection);
            try (var command = connection.createStatement()) { command.execute("LOCK TABLE " + String.join(",", tables) + " IN SHARE ROW EXCLUSIVE MODE"); }
            Outcome existing = marker(connection, settings.installationId());
            if (existing != null) { connection.rollback(); return existing; }
            requireEmpty(connection, tables, settings.migrationVersion());
            UUID account = UUID.randomUUID();
            try (var insert = connection.prepareStatement("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,'admin',?,'INACTIVE')")) {
                insert.setObject(1, account); insert.setString(2, passwordHash); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,'PLATFORM_ADMIN')")) { insert.setObject(1, account); insert.executeUpdate(); }
            try (var activate = connection.prepareStatement("UPDATE identity.local_account SET stts_cd='ACTIVE' WHERE id=?")) { activate.setObject(1, account); activate.executeUpdate(); }
            Map<String, String> details = new LinkedHashMap<>(); details.put("installationId", settings.installationId().toString()); details.put("username", "admin"); details.put("bootstrapVersion", "empty-admin-v1"); details.put("approvalReference", settings.approvalReference());
            try (var insert = connection.prepareStatement("INSERT INTO audit.audit_event(id,prcs_user_id,prcs_cd,trgt_type_cd,trgt_id,dmnd_id,rslt_cd,chg_smry_cn) VALUES (?,?,'" + ACTION + "','LOCAL_ACCOUNT',?,?,'SUCCESS',?::jsonb)")) {
                insert.setObject(1, UUID.randomUUID()); insert.setObject(2, account); insert.setObject(3, account);
                insert.setString(4, "bootstrap:" + settings.installationId());
                try { insert.setString(5, JSON.writeValueAsString(details)); } catch (Exception invalid) { throw invalid("Cannot encode the initialization record."); }
                insert.executeUpdate();
            }
            connection.commit(); return Outcome.CREATED;
        } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
    }

    private static void verifyMigration(Connection connection, int expected) throws SQLException {
        requireReviewedMigration(expected);
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT COALESCE(MAX(version::int),0) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")) {
            if (!rows.next() || rows.getInt(1) != expected) throw invalid("Database migration does not match the owned reviewed release.");
        }
    }
    private static Outcome marker(Connection connection, UUID installation) throws SQLException {
        try (var query = connection.prepareStatement("SELECT trgt_id,chg_smry_cn->>'installationId',chg_smry_cn->>'bootstrapVersion',chg_smry_cn->>'username' FROM audit.audit_event WHERE prcs_cd=? AND trgt_type_cd='LOCAL_ACCOUNT'")) {
            query.setString(1, ACTION);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                UUID account = rows.getObject(1, UUID.class);
                if (!installation.toString().equals(rows.getString(2)) || !"empty-admin-v1".equals(rows.getString(3)) || !"admin".equals(rows.getString(4)) || rows.next()) throw invalid("Existing initialization belongs to a different or unconfirmed installation; no account changed.");
                try (var identity = connection.prepareStatement("SELECT lgn_id FROM identity.local_account WHERE id=?")) {
                    identity.setObject(1, account);
                    try (var found = identity.executeQuery()) { if (!found.next() || !"admin".equals(found.getString(1))) throw invalid("Existing initialization marker cannot be confirmed; no account changed."); }
                }
                return Outcome.ALREADY_INITIALIZED;
            }
        }
    }
    private static List<String> tables(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>(); Set<String> names = new LinkedHashSet<>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT n.nspname,c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\' AND n.nspname<>'information_schema' AND c.relkind IN ('r','p','v','m','f') ORDER BY n.nspname,c.relname")) {
            while (rows.next()) {
                String schema = rows.getString(1), table = rows.getString(2), kind = rows.getString(3);
                if (schema.equals("public") && table.equals("flyway_schema_history") && kind.equals("r")) continue;
                if (!SCHEMAS.contains(schema) || !kind.equals("r")) throw invalid("Unsupported database objects are present; bootstrap refuses an existing or unknown database.");
                names.add(schema + "." + table); result.add(quoted(schema) + "." + quoted(table));
            }
        }
        if (!names.containsAll(REQUIRED_TABLES)) throw invalid("Required application schema is unavailable.");
        return result;
    }
    private static void requireEmpty(Connection connection, List<String> tables, int migration) throws SQLException {
        for (String table : tables) {
            if (migration >= 23 && table.equals("\"classification\".\"category\"")) {
                requireUnchangedV23Categories(connection);
                continue;
            }
            if (table.equals("\"analytics\".\"search_event\"")) {
                String columns = migration >= 27 ? ",query_repr,feedback_tkn_hash_vl,returned_page,returned_page_sz,expry_dt,ocrn_dt"
                    : migration >= 20 ? ",query_repr,feedback_token_hash,returned_page,returned_size,expires_at,ocrn_dt" : "";
                try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,srch_rng_cd" + columns + " FROM analytics.search_event")) {
                    while (rows.next()) {
                        if (rows.getBoolean(1) || rows.getLong(3) != 0 || !"PUBLIC".equals(rows.getString(4)) || !emptyBrowseFilters(rows.getString(2))) throw notEmpty();
                        if (migration >= 20) {
                            String representation = rows.getString(5), tokenHash = rows.getString(6);
                            Integer page = (Integer) rows.getObject(7), size = (Integer) rows.getObject(8);
                            var expires = rows.getTimestamp(9); var occurred = rows.getTimestamp(10);
                            if (representation == null) {
                                // Only an unchanged pre-V20 empty health browse is eligible for the legacy exception.
                                if (tokenHash != null || page != null || size != null || expires != null) throw notEmpty();
                            } else {
                                if (!emptyBrowseRepresentation(representation) || tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")
                                        || (page == null) != (size == null) || page != null && (page < 0 || size < 1 || size > 100)
                                        || expires == null || occurred == null
                                        || !expires.toInstant().equals(occurred.toInstant().atOffset(ZoneOffset.UTC).plusYears(5).toInstant())) throw notEmpty();
                            }
                        }
                    }
                }
                continue;
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT EXISTS(SELECT 1 FROM " + table + ")")) {
                if (!rows.next() || rows.getBoolean(1)) throw notEmpty();
            }
        }
    }
    private static void requireReviewedMigration(int version) {
        if (!REVIEWED_MIGRATIONS.contains(version))
            throw invalid("Only reviewed migration versions 19 through 27 are supported; unknown future schemas are refused.");
    }

    private static void requireUnchangedV23Categories(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("""
                SELECT id,up_id,cd,nm,actvtn_yn,sort_seq,lck_ver_no,expln,crt_dt,mdfcn_dt
                FROM classification.category ORDER BY sort_seq
                """)) {
            int index = 0;
            java.sql.Timestamp seedTime = null;
            while (rows.next()) {
                if (index >= V23_CATEGORY_NAMES.size()) throw notEmpty();
                int ordinal = index + 1;
                String suffix = String.format(java.util.Locale.ROOT, "%012d", ordinal);
                String code = String.format(java.util.Locale.ROOT, "PUBLIC-%02d", ordinal);
                var created = rows.getTimestamp(9);
                if (!UUID.fromString("c2300000-0000-4000-8000-" + suffix).equals(rows.getObject(1, UUID.class))
                        || rows.getObject(2) != null || !code.equals(rows.getString(3))
                        || !V23_CATEGORY_NAMES.get(index).equals(rows.getString(4)) || !rows.getBoolean(5)
                        || rows.getInt(6) != ordinal || rows.getLong(7) != 0
                        || !"공공데이터포털 분야 분류".equals(rows.getString(8))
                        || created == null || !created.equals(rows.getTimestamp(10))
                        || seedTime != null && !seedTime.equals(created)) throw notEmpty();
                seedTime = created;
                index++;
            }
            if (index != V23_CATEGORY_NAMES.size()) throw notEmpty();
        }
    }
    static boolean emptyBrowseFilters(String encoded) {
        try {
            JsonNode value = JSON.readTree(encoded); if (value == null || !value.isObject()) return false;
            var entries = value.fields();
            while (entries.hasNext()) {
                var entry = entries.next(); JsonNode values = entry.getValue();
                if (!FILTERS.contains(entry.getKey()) || !values.isArray()) return false;
                if (entry.getKey().equals("sort")) { if (values.size() > 1 || values.size() == 1 && (!values.get(0).isTextual() || !SORTS.contains(values.get(0).textValue()))) return false; }
                else if (!values.isEmpty()) return false;
            }
            return true;
        } catch (Exception malformed) { return false; }
    }
    static boolean emptyBrowseRepresentation(String encoded) {
        try {
            JsonNode value = JSON.readTree(encoded);
            return value != null && value.isObject() && value.size() == 4
                    && value.path("policyVersion").isTextual() && value.path("policyVersion").textValue().equals("catalog-search-privacy-v1")
                    && value.path("intent").isTextual() && value.path("intent").textValue().equals("BROWSE")
                    && value.path("controlledTerms").isArray() && value.path("controlledTerms").isEmpty()
                    && value.path("hasUnclassifiedTerms").isBoolean() && !value.path("hasUnclassifiedTerms").booleanValue();
        } catch (Exception malformed) { return false; }
    }
    private static String quoted(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    private static IllegalArgumentException notEmpty() { return invalid("Existing application data or accounts are present; bootstrap never overwrites or adopts an existing database."); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    enum Outcome { EMPTY, CREATED, ALREADY_INITIALIZED }
    static final class Settings {
        private final UUID installationId; private final int migrationVersion; private final String approvalReference; private final boolean checkOnly; private final String databaseUser, databasePassword;
        Settings(UUID installation, int version, String approval, boolean check, String user, String secret) { installationId=installation; migrationVersion=version; approvalReference=approval; checkOnly=check; databaseUser=user; databasePassword=secret; }
        UUID installationId() { return installationId; } int migrationVersion() { return migrationVersion; } String approvalReference() { return approvalReference; } boolean checkOnly() { return checkOnly; } String databaseUser() { return databaseUser; } String databasePassword() { return databasePassword; }
        @Override public String toString() { return "BootstrapSettings[redacted]"; }
    }
}

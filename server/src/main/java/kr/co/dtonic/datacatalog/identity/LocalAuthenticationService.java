package kr.co.dtonic.datacatalog.identity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

public class LocalAuthenticationService {
    private final LocalAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final String dummyHash;
    public LocalAuthenticationService(LocalAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts; this.encoder = encoder;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }
    public static String normalize(String username) { return username == null ? "" : username.strip().toLowerCase(Locale.ROOT); }

    public LocalAuthenticationToken authenticate(String username, String password) {
        String login = normalize(username);
        boolean validInput = login.matches("[a-z0-9][a-z0-9._-]{2,79}") && password != null && !password.isEmpty()
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
        var account = validInput ? accounts.byLogin(login).orElse(null) : null;
        // The same BCrypt work runs for missing, inactive, unusable and incorrect credentials.
        boolean matches = encoder.matches(validInput ? password : "invalid-input", account == null ? dummyHash : account.passwordHash());
        if (!matches || account == null || !account.usable()) throw new BadCredentialsException("Local login failed");
        return new LocalAuthenticationToken(new CatalogPrincipal(account.id(), account.organizationIds()), account.version(),
                account.roles().stream().sorted().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }

    public boolean current(LocalAuthenticationToken authentication) {
        var account = accounts.byId(authentication.getPrincipal().userId()).orElse(null);
        return account != null && account.usable() && account.version() == authentication.version()
                && account.organizationIds().equals(authentication.getPrincipal().organizationIds())
                && account.roles().stream().map(role -> "ROLE_" + role).collect(java.util.stream.Collectors.toSet())
                .equals(authentication.getAuthorities().stream().map(a -> a.getAuthority()).collect(java.util.stream.Collectors.toSet()));
    }
}

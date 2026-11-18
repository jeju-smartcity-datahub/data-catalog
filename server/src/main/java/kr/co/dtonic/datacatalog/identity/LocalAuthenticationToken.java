package kr.co.dtonic.datacatalog.identity;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Session contains identity and revocation version, never password or hash. */
public final class LocalAuthenticationToken extends AbstractAuthenticationToken {
    private static final long serialVersionUID = 1L;
    private final CatalogPrincipal principal;
    private final long version;
    public LocalAuthenticationToken(CatalogPrincipal principal, long version, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.version = version;
        super.setAuthenticated(true);
    }
    public long version() { return version; }
    @Override public CatalogPrincipal getPrincipal() { return principal; }
    @Override public Object getCredentials() { return null; }
    @Override public void setAuthenticated(boolean authenticated) {
        if (authenticated) throw new IllegalArgumentException("Use trusted authentication service");
        super.setAuthenticated(false);
    }
}

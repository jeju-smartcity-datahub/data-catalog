package kr.co.dtonic.datacatalog.identity;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("organizationAccess")
public class OrganizationAccess {
    private static final Set<String> READ_ROLES = Set.of("ROLE_ORG_EDITOR", "ROLE_ORG_REVIEWER");

    public boolean canRead(Authentication authentication, UUID organizationId) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof CatalogPrincipal principal)) {
            return false;
        }
        if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"))) {
            return true;
        }
        return principal.organizationIds().contains(organizationId)
                && authentication.getAuthorities().stream().anyMatch(a -> READ_ROLES.contains(a.getAuthority()));
    }
}

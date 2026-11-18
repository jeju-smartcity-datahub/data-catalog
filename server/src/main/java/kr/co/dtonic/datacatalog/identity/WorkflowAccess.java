package kr.co.dtonic.datacatalog.identity;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Authorization adapter boundary. Role headers and generic Spring users cannot enter workflows. */
@Component
public class WorkflowAccess {
    public CatalogPrincipal principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof CatalogPrincipal principal)) {
            throw new AccessDeniedException("Trusted identity required");
        }
        return principal;
    }

    public boolean isPlatformAdmin(Authentication authentication) {
        principal(authentication);
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
    }

    public CatalogPrincipal require(Authentication authentication, UUID organizationId, String... allowedRoles) {
        CatalogPrincipal principal = principal(authentication);
        if (isPlatformAdmin(authentication)) return principal;
        boolean role = authentication.getAuthorities().stream().anyMatch(a -> Arrays.stream(allowedRoles)
                .anyMatch(r -> a.getAuthority().equals("ROLE_" + r)));
        if (!principal.organizationIds().contains(organizationId) || !role) {
            throw new AccessDeniedException("Organization or action denied");
        }
        return principal;
    }
}

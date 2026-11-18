package kr.co.dtonic.datacatalog.identity;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

/** Trusted identity adapter output. Never constructed from browser-supplied role/organization headers. */
public record CatalogPrincipal(UUID userId, Set<UUID> organizationIds) implements Principal, java.io.Serializable {
    public CatalogPrincipal {
        organizationIds = Set.copyOf(organizationIds);
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}

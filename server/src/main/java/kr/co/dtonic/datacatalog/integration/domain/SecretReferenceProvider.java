package kr.co.dtonic.datacatalog.integration.domain;

/** A deployment-owned implementation resolves opaque references; callers erase returned material. */
public interface SecretReferenceProvider {
    default boolean available() { return true; }
    char[] resolve(String reference);
}

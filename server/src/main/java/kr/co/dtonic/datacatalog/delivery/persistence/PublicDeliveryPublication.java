package kr.co.dtonic.datacatalog.delivery.persistence;

import java.time.OffsetDateTime;

/** Public snapshot projection only; publicMetadata contains explicitly selected descriptive properties. */
public record PublicDeliveryPublication(String datasetId, String publicationId, int publicationVersion,
        String title, String description, String organizationId, String organizationName,
        OffsetDateTime publishedAt, String accessState, OffsetDateTime modifiedAt,
        String categoryId, String categoryName, String publicMetadata) {}

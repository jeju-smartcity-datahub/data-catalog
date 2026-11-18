package kr.co.dtonic.datacatalog.catalog.persistence;

import java.time.OffsetDateTime;

public record CatalogRow(String id, String title, String description,
        String organizationId, String organizationName, String categoryId, String categoryName,
        OffsetDateTime publishedAt, String accessState,String metadata,int publicationNo,String freshness) {}

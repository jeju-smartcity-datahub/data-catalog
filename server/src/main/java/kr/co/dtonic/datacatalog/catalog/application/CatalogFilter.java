package kr.co.dtonic.datacatalog.catalog.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CatalogFilter(String q, List<UUID> organizationIds, List<UUID> categoryIds, String sort,
        List<UUID> regionIds,List<UUID> hubIds,List<UUID> topicIds,List<String> naturalTerms) {
    private static final Set<String> SORTS = Set.of("publishedAt,desc", "publishedAt,asc", "title,asc", "title,desc", "relevance");

    public CatalogFilter {
        q = q == null ? "" : q.strip();
        organizationIds = organizationIds == null ? List.of() : organizationIds.stream().distinct().toList();
        categoryIds = categoryIds == null ? List.of() : categoryIds.stream().distinct().toList();
        regionIds=ids(regionIds);hubIds=ids(hubIds);topicIds=ids(topicIds);
        naturalTerms=naturalTerms==null?List.of():List.copyOf(naturalTerms);
        sort = sort == null ? "publishedAt,desc" : sort;
        if (q.length() > 200 || organizationIds.size() > 20 || categoryIds.size() > 20 || !SORTS.contains(sort)
                || naturalTerms.size()>8 || naturalTerms.stream().anyMatch(term -> term.isBlank() || term.length()>200)) {
            throw new IllegalArgumentException("Unsupported search criteria");
        }
    }
    public CatalogFilter(String q,List<UUID> organizationIds,List<UUID> categoryIds,String sort,
            List<UUID> regionIds,List<UUID> hubIds,List<UUID> topicIds) {
        this(q,organizationIds,categoryIds,sort,regionIds,hubIds,topicIds,List.of());
    }
    public CatalogFilter(String q,List<UUID> organizationIds,List<UUID> categoryIds,String sort) {
        this(q,organizationIds,categoryIds,sort,null,null,null);
    }
    private static List<UUID> ids(List<UUID> values) {
        if(values==null)return List.of();
        if(values.size()>20||values.stream().anyMatch(java.util.Objects::isNull))throw new IllegalArgumentException("Unsupported search criteria");
        return values.stream().distinct().toList();
    }

    public String pattern() {
        return literalPattern(q);
    }
    public List<String> termPatterns() { return naturalTerms.stream().map(CatalogFilter::literalPattern).toList(); }
    private static String literalPattern(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }

    public static CatalogFilter empty() { return new CatalogFilter("", List.of(), List.of(), null); }
}

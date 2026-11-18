package kr.co.dtonic.datacatalog.catalog.persistence;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.api.DatasetVersion;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogMapper extends EgovAbstractMapper {
    private Map<String,Object> parameters(CatalogFilter filter) {
        var parameters = new HashMap<String,Object>();
        parameters.put("q", filter.q()); parameters.put("pattern", filter.pattern());
        parameters.put("termPatterns", filter.termPatterns());
        parameters.put("organizationIds", filter.organizationIds()); parameters.put("categoryIds", filter.categoryIds());
        parameters.put("sort", filter.sort());
        parameters.put("regionIds",filter.regionIds());parameters.put("hubIds",filter.hubIds());parameters.put("topicIds",filter.topicIds());
        return parameters;
    }
    public List<CatalogRow> findPublished(int page, int size, CatalogFilter filter) {
        var parameters = parameters(filter);
        parameters.put("offset", (long) page * size); parameters.put("size", size);
        return selectList("Catalog.findPublished", parameters);
    }
    public long countPublished(CatalogFilter filter) { return selectOne("Catalog.countPublished", parameters(filter)); }
    public List<FacetRow> organizations(CatalogFilter filter) { return selectList("Catalog.organizationFacets", parameters(filter)); }
    public List<FacetRow> categories(CatalogFilter filter) { return selectList("Catalog.categoryFacets", parameters(filter)); }
    public List<FacetRow> regions(CatalogFilter filter) { return selectList("Catalog.regionFacets",parameters(filter)); }
    public List<FacetRow> hubs(CatalogFilter filter) { return selectList("Catalog.hubFacets",parameters(filter)); }
    public List<FacetRow> topics(CatalogFilter filter) { return selectList("Catalog.topicFacets",parameters(filter)); }
    public PublicationRow detail(UUID id, Integer publicationNo) {
        var parameters = new HashMap<String,Object>();
        parameters.put("id", id); parameters.put("publicationNo", publicationNo);
        return selectOne("Catalog.detail", parameters);
    }
    public List<DatasetVersion> versions(UUID id) { return selectList("Catalog.versions", Map.of("id", id)); }
}

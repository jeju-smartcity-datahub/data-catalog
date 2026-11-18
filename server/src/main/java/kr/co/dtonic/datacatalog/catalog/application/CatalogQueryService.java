package kr.co.dtonic.datacatalog.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.api.*;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary.NamedReference;
import kr.co.dtonic.datacatalog.catalog.api.DatasetDetail.PublishedField;
import kr.co.dtonic.datacatalog.catalog.api.DatasetDetail.PublishedHubMetadata;
import kr.co.dtonic.datacatalog.catalog.persistence.CatalogMapper;
import kr.co.dtonic.datacatalog.catalog.persistence.FacetRow;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogQueryService extends EgovAbstractServiceImpl {
    private final CatalogMapper mapper;
    private final ObjectMapper json;

    public CatalogQueryService(CatalogMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    public DatasetPage listPublished(int page, int size) { return listPublished(page, size, CatalogFilter.empty()); }
    public DatasetPage listPublished(int page, int size, CatalogFilter filter) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Pagination is outside the permitted range");
        long total = mapper.countPublished(filter);
        var items = mapper.findPublished(page, size, filter).stream().map(row -> new DatasetSummary(
                row.id(), row.title(), row.description(), new NamedReference(row.organizationId(), row.organizationName()),
                row.categoryId() == null ? null : new NamedReference(row.categoryId(), row.categoryName()),
                row.publishedAt(), row.accessState(),references(row.metadata(),"regions"),references(row.metadata(),"hubs"),references(row.metadata(),"topics"),details(row.metadata()),row.publicationNo(),freshness(row.freshness(),row.publishedAt()))).toList();
        return new DatasetPage(items, new DatasetPage.PageMetadata(page, size, total, (total + size - 1) / size));
    }
    public CatalogFacets facets(CatalogFilter filter) {
        return new CatalogFacets("EXCLUDE_OWN_GROUP", facetValues(mapper.organizations(filter), filter.organizationIds()),
                facetValues(mapper.categories(filter), filter.categoryIds()),facetValues(mapper.regions(filter),filter.regionIds()),
                facetValues(mapper.hubs(filter),filter.hubIds()),facetValues(mapper.topics(filter),filter.topicIds()));
    }
    public List<NamedReference> discoveryOptions(String kind) {
        var rows="REGION".equals(kind)?mapper.regions(CatalogFilter.empty()):mapper.hubs(CatalogFilter.empty());
        return rows.stream().map(r->new NamedReference(r.id(),r.name())).toList();
    }
    private List<CatalogFacets.FacetValue> facetValues(List<FacetRow> rows, List<UUID> selected) {
        var values = new ArrayList<>(rows.stream().map(row -> new CatalogFacets.FacetValue(row.id(), row.name(), row.count(),
                selected.contains(UUID.fromString(row.id())))).toList());
        selected.stream().filter(id -> rows.stream().noneMatch(row -> row.id().equals(id.toString())))
                .forEach(id -> values.add(new CatalogFacets.FacetValue(id.toString(), "선택한 항목", 0, true)));
        return List.copyOf(values);
    }
    public DatasetDetail detail(UUID id, Integer publicationNo) {
        if (publicationNo != null && publicationNo < 1) throw new IllegalArgumentException("Invalid publication number");
        var row = mapper.detail(id, publicationNo);
        if (row == null) throw new CatalogNotFound();
        var access = new DatasetAccess(row.publicationNo(), row.accessState(),
                "게시된 메타정보와 필드정보를 JSON API로 조회할 수 있습니다.", List.of("METADATA_JSON", "DCAT_JSONLD"));
        return new DatasetDetail(row.id(), row.title(), row.description(), new NamedReference(row.organizationId(), row.organizationName()),
                row.categoryId() == null ? null : new NamedReference(row.categoryId(), row.categoryName()), row.publishedAt(),
                row.publicationNo(), row.accessState(), publicFields(row.metadata()), access,references(row.metadata(),"regions"),
                references(row.metadata(),"hubs"),references(row.metadata(),"topics"),details(row.metadata()),hubMetadata(row.metadata()),freshness(row.freshness(),row.publishedAt()));
    }
    private CatalogFreshness freshness(String value,OffsetDateTime publishedAt) {
        if(value==null)return new CatalogFreshness(publishedAt,null,"NOT_LINKED");
        try {
            var data=json.readTree(value);
            var first=publicTimestamp(data,"firstPublishedAt");
            return new CatalogFreshness(first==null?publishedAt:first,publicTimestamp(data,"metadataCollectedAt"),
                    data.path("collectionStatus").asText("NOT_LINKED"));
        } catch(JsonProcessingException exception) { throw new IllegalStateException("Invalid catalog freshness",exception); }
    }
    private List<NamedReference> references(String metadata,String group) {
        try {
            var values=json.readTree(metadata).path("discovery").path(group);var result=new ArrayList<NamedReference>();
            if(values.isArray())for(var value:values)result.add(new NamedReference(value.path("id").asText(),value.path("name").asText()));
            return List.copyOf(result);
        } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid public metadata snapshot",e); }
    }
    private MetadataDetails details(String metadata) {
        try { return MetadataDetails.read(json.readTree(metadata).path("details")); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid public metadata snapshot",e); }
    }
    private PublishedHubMetadata hubMetadata(String metadata) {
        try {
            var values = json.readTree(metadata).path("hubMetadata");
            return new PublishedHubMetadata(publicText(values, "providerOrganization", 4000),
                    publicText(values, "providerSystem", 4000), publicText(values, "ownership", 4000),
                    publicText(values, "datamodelId", 300), publicTimestamp(values, "sourceModifiedAt"), publicApiUri(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid public metadata snapshot", exception);
        }
    }
    private String publicText(JsonNode values, String key, int maxLength) {
        var value = values.path(key);
        if (!value.isTextual()) return null;
        var text = value.textValue().strip();
        return text.isEmpty() || text.length() > maxLength || text.codePoints().anyMatch(Character::isISOControl) ? null : text;
    }
    private OffsetDateTime publicTimestamp(JsonNode values, String key) {
        var value = publicText(values, key, 100);
        if (value == null) return null;
        try { return OffsetDateTime.parse(value); }
        catch (DateTimeParseException exception) { return null; }
    }
    private String publicApiUri(JsonNode values) {
        var value = publicText(values, "providingApiUri", 2000);
        if (value == null) return null;
        try {
            var uri = java.net.URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null ? value : null;
        } catch (IllegalArgumentException exception) { return null; }
    }
    private List<PublishedField> publicFields(String metadata) {
        try {
            var fields = json.readTree(metadata).path("fields");
            var result = new ArrayList<PublishedField>();
            if (fields.isArray()) for (var field : fields) {
                if (field.path("publicVisible").isBoolean() && field.path("publicVisible").asBoolean()) {
                    result.add(new PublishedField(field.path("fieldPath").asText(), field.path("physicalName").asText(),
                            field.path("displayName").asText(), field.path("description").asText(), field.path("dataType").asText(),
                            field.path("nullable").asBoolean(), field.path("unit").asText("")));
                }
            }
            return List.copyOf(result);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid stored metadata", exception); }
    }
    public List<DatasetVersion> versions(UUID id) {
        detail(id, null);
        return mapper.versions(id);
    }
}

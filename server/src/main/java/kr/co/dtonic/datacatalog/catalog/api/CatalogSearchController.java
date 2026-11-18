package kr.co.dtonic.datacatalog.catalog.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.analytics.SearchEventRecorder;
import kr.co.dtonic.datacatalog.analytics.SearchQueryRepresentation;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.application.CatalogSearchQuery;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogSearchController {
    private final CatalogQueryService service;
    private final ObjectProvider<SearchEventRecorder> recorder;
    public CatalogSearchController(CatalogQueryService service,ObjectProvider<SearchEventRecorder> recorder) {
        this.service=service;this.recorder=recorder;
    }
    @Operation(operationId="searchPublishedDatasets",summary="규칙 기반 자연어 카탈로그 검색",
            description="요청 표현과 조사를 해석한 주제어를 모두 포함하는 공개 메타데이터를 검색합니다. 목록·요약·실제 일치 근거·상세 바로가기를 제공합니다. 점수는 공개된 규칙의 합계이며 정확도나 확률이 아닙니다.")
    @GetMapping(value="/api/catalog/v1/search",produces="application/json")
    public CatalogSearchPage search(@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(required=false) @Size(max=20) List<UUID> organizationId,
            @RequestParam(required=false) @Size(max=20) List<UUID> categoryId,
            @RequestParam(required=false) @Size(max=20) List<UUID> regionId,
            @RequestParam(required=false) @Size(max=20) List<UUID> hubId,
            @RequestParam(required=false) @Size(max=20) List<UUID> topicId,
            @RequestParam(defaultValue="relevance") String sort,HttpServletRequest request) {
        var query=CatalogSearchQuery.parse(q);
        var filter=filter(query,organizationId,categoryId,sort,regionId,hubId,topicId);
        long started=System.nanoTime();
        var result=query.needsSubject()?new DatasetPage(List.of(),new DatasetPage.PageMetadata(page,size,0,0)):service.listPublished(page,size,filter);
        var events=recorder.getIfAvailable();
        var feedback=events==null?null:events.capture(!q.isBlank(),SearchQueryRepresentation.from(query),
                Map.of("organizationId",filter.organizationIds().stream().map(UUID::toString).toList(),
                       "categoryId",filter.categoryIds().stream().map(UUID::toString).toList(),
                       "regionId",filter.regionIds().stream().map(UUID::toString).toList(),
                       "hubId",filter.hubIds().stream().map(UUID::toString).toList(),
                       "topicId",filter.topicIds().stream().map(UUID::toString).toList(),
                       "sort",List.of(filter.sort().replace(',','_'))),result.items(),result.page().totalElements(),(System.nanoTime()-started)/1_000_000,page,size);
        return new CatalogSearchPage(result.items(),result.page(),query.interpretation(),result.items().stream().map(query::explain).toList(),feedback);
    }
    @Operation(operationId="getNaturalSearchFacets",summary="규칙 기반 질의와 같은 조건의 카탈로그 필터 건수")
    @GetMapping(value="/api/catalog/v1/search/facets",produces="application/json")
    public CatalogFacets facets(@RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(required=false) @Size(max=20) List<UUID> organizationId,
            @RequestParam(required=false) @Size(max=20) List<UUID> categoryId,
            @RequestParam(required=false) @Size(max=20) List<UUID> regionId,
            @RequestParam(required=false) @Size(max=20) List<UUID> hubId,
            @RequestParam(required=false) @Size(max=20) List<UUID> topicId) {
        var query=CatalogSearchQuery.parse(q);
        var filter=filter(query,organizationId,categoryId,null,regionId,hubId,topicId);
        if(query.needsSubject())return new CatalogFacets("EXCLUDE_OWN_GROUP",List.of(),List.of(),List.of(),List.of(),List.of());
        return service.facets(filter);
    }
    private static CatalogFilter filter(CatalogSearchQuery query,List<UUID> organizations,List<UUID> categories,String sort,
            List<UUID> regions,List<UUID> hubs,List<UUID> topics) {
        return new CatalogFilter(query.original(),organizations,categories,sort,regions,hubs,topics,query.terms());
    }
}

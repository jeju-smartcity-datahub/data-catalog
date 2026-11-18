package kr.co.dtonic.datacatalog.catalog.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.dtonic.datacatalog.analytics.SearchEventRecorder;
import kr.co.dtonic.datacatalog.analytics.SearchQueryRepresentation;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import kr.co.dtonic.datacatalog.common.ApiProblemSchema;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiResponses({
    @ApiResponse(responseCode="200", description="성공", useReturnTypeSchema=true),
    @ApiResponse(responseCode="400", description="요청 조건 오류", content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="404", description="공개 게시본 없음", content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="503", description="카탈로그 DB 일시 불가", content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class)))
})
public class CatalogController {
    private final CatalogQueryService service;
    private final ObjectProvider<SearchEventRecorder> recorder;
    public CatalogController(CatalogQueryService service, ObjectProvider<SearchEventRecorder> recorder) { this.service = service; this.recorder = recorder; }

    @Operation(operationId="listPublishedDatasets", summary="공개 게시 데이터셋 검색", description="기관·분야/기존분류·지역·허브·주제는 그룹 내 OR, 그룹 간 AND. 검색어는 제목·설명에 literal 부분 일치. relevance는 제목 일치 우선 후 게시일 내림차순.")
    @GetMapping(value="/api/catalog/v1/datasets", produces="application/json")
    public DatasetPage listPublishedDatasets(
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(required=false) @Size(max=20) List<UUID> organizationId,
            @RequestParam(required=false) @Size(max=20) List<UUID> categoryId,
            @RequestParam(required=false) @Size(max=20) List<UUID> regionId,
            @RequestParam(required=false) @Size(max=20) List<UUID> hubId,
            @RequestParam(required=false) @Size(max=20) List<UUID> topicId,
            @RequestParam(defaultValue="publishedAt,desc") String sort, HttpServletRequest request) {
        var filter = new CatalogFilter(q,organizationId,categoryId,sort,regionId,hubId,topicId);
        long started = System.nanoTime();
        var result = service.listPublished(page, size, filter);
        var events=recorder.getIfAvailable();
        var feedback=events==null?null:events.capture(!filter.q().isBlank(),SearchQueryRepresentation.fromLegacyQuery(filter.q()),
                Map.of("organizationId",filter.organizationIds().stream().map(UUID::toString).toList(),
                       "categoryId",filter.categoryIds().stream().map(UUID::toString).toList(),
                       "regionId",filter.regionIds().stream().map(UUID::toString).toList(),
                       "hubId",filter.hubIds().stream().map(UUID::toString).toList(),
                       "topicId",filter.topicIds().stream().map(UUID::toString).toList(),
                       "sort",List.of(filter.sort().replace(',', '_'))),
                result.items(),result.page().totalElements(), (System.nanoTime()-started)/1_000_000,page,size);
        return new DatasetPage(result.items(),result.page(),feedback);
    }

    @Operation(operationId="getCatalogFacets", summary="공개 검색의 기관·분류·지역·허브·주제별 건수")
    @GetMapping(value="/api/catalog/v1/datasets/facets", produces="application/json")
    public CatalogFacets facets(@RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(required=false) @Size(max=20) List<UUID> organizationId,
            @RequestParam(required=false) @Size(max=20) List<UUID> categoryId,
            @RequestParam(required=false) @Size(max=20) List<UUID> regionId,
            @RequestParam(required=false) @Size(max=20) List<UUID> hubId,
            @RequestParam(required=false) @Size(max=20) List<UUID> topicId) {
        return service.facets(new CatalogFilter(q,organizationId,categoryId,null,regionId,hubId,topicId));
    }
    @GetMapping("/api/catalog/v1/regions") @Operation(operationId="listPublicRegions",summary="현재 공개 게시본에 연결된 지역 선택지")
    public List<DatasetSummary.NamedReference> regions() { return service.discoveryOptions("REGION"); }
    @GetMapping("/api/catalog/v1/hubs") @Operation(operationId="listPublicHubs",summary="현재 공개 게시본에 연결된 허브 분류 선택지, 접속정보 없음")
    public List<DatasetSummary.NamedReference> hubs() { return service.discoveryOptions("HUB"); }

    @Operation(operationId="getPublishedDataset", summary="현재 공개 게시본 상세")
    @GetMapping(value="/api/catalog/v1/datasets/{datasetId}", produces="application/json")
    public ResponseEntity<DatasetDetail> detail(@PathVariable UUID datasetId) {
        return response(service.detail(datasetId,null));
    }
    @Operation(operationId="getPublishedDatasetVersion", summary="지정 공개 게시 버전 상세")
    @GetMapping(value="/api/catalog/v1/datasets/{datasetId}/versions/{publicationNo}", produces="application/json")
    public ResponseEntity<DatasetDetail> version(@PathVariable UUID datasetId,@PathVariable @Min(1) int publicationNo) {
        return response(service.detail(datasetId,publicationNo));
    }
    @Operation(operationId="getPublishedDatasetFields", summary="게시 버전의 공개 필드")
    @GetMapping(value="/api/catalog/v1/datasets/{datasetId}/fields", produces="application/json")
    public List<DatasetDetail.PublishedField> fields(@PathVariable UUID datasetId,@RequestParam(required=false) @Min(1) Integer publicationNo) {
        return service.detail(datasetId,publicationNo).fields();
    }
    @Operation(operationId="getPublishedDatasetAccess", summary="게시 버전의 데이터 이용 안내")
    @GetMapping(value="/api/catalog/v1/datasets/{datasetId}/access", produces="application/json")
    public DatasetAccess access(@PathVariable UUID datasetId,@RequestParam(required=false) @Min(1) Integer publicationNo) {
        return service.detail(datasetId,publicationNo).access();
    }
    @Operation(operationId="listPublishedDatasetVersions", summary="조회 가능한 공개 게시 버전 목록")
    @GetMapping(value="/api/catalog/v1/datasets/{datasetId}/versions", produces="application/json")
    public List<DatasetVersion> versions(@PathVariable UUID datasetId) { return service.versions(datasetId); }

    private ResponseEntity<DatasetDetail> response(DatasetDetail detail) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag("\"publication:" + detail.id() + ":" + detail.publicationNo() + "\"").body(detail);
    }
}

package kr.co.dtonic.datacatalog.classification;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import kr.co.dtonic.datacatalog.classification.CategoryContracts.*;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/categories")
public class CategoryController {
    private final CategoryService service;
    public CategoryController(CategoryService service) { this.service=service; }
    @GetMapping @Operation(operationId="listAdminCategories",summary="분류와 참조 건수 조회")
    public List<CategoryDetails> list(Authentication auth) { return service.list(auth); }
    @GetMapping("/{id}/usage") @Operation(operationId="getCategoryUsage",summary="분류 사용 현황")
    public ResponseEntity<CategoryDetails> usage(@PathVariable UUID id,Authentication auth) { return response(service.get(id,auth)); }
    @GetMapping("/{id}/datasets")
    @Operation(operationId="listCategoryDatasets",summary="분류를 참조한 데이터셋 목록",
            description="권한 있는 활성 소유기관의 데이터셋만 조회합니다. 분야는 하위 주제를 포함하며, 데이터셋마다 가장 최근에 이 분류에 연결된 버전 1건을 반환합니다. 현재 메타데이터 버전과 다를 수 있으며 currentRevision으로 구분합니다. totalElements는 접근 가능한 서로 다른 데이터셋 수이고 usageCount 참조 합계와 다릅니다. page 0~100000, size 1~100.")
    public CategoryDatasetPage datasets(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,Authentication auth) { return service.datasets(id,page,size,auth); }
    @PostMapping @Operation(operationId="createCategory",summary="분야 또는 주제 생성")
    @ApiResponse(responseCode="201",description="분류 생성",useReturnTypeSchema=true)
    public ResponseEntity<CategoryDetails> create(@Valid @RequestBody CategoryDetailsInput body,Authentication auth,HttpServletRequest request) {
        var value=service.create(body,auth,requestId(request));
        return ResponseEntity.status(201).eTag(GovernanceSupport.etag("category",value.id(),value.lockVersion())).body(value);
    }
    @PatchMapping("/{id}") @Operation(operationId="updateCategory",summary="분류 수정 (참조 중 비활성화 금지)")
    public ResponseEntity<CategoryDetails> update(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,
            @Valid @RequestBody CategoryDetailsInput body,Authentication auth,HttpServletRequest request) {
        return response(service.update(id,ifMatch,body,auth,requestId(request)));
    }
    @DeleteMapping("/{id}") @Operation(operationId="deleteCategory",summary="미사용 분류 삭제")
    @ApiResponse(responseCode="204",description="삭제 완료. 응답 본문 없음.",content=@Content)
    public ResponseEntity<Void> delete(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String ifMatch,Authentication auth,HttpServletRequest request) {
        service.delete(id,ifMatch,auth,requestId(request)); return ResponseEntity.noContent().build();
    }
    private ResponseEntity<CategoryDetails> response(CategoryDetails value) { return ResponseEntity.ok().eTag(GovernanceSupport.etag("category",value.id(),value.lockVersion())).body(value); }
    private String requestId(HttpServletRequest request) { return java.util.Objects.toString(request.getAttribute(kr.co.dtonic.datacatalog.common.RequestIdFilter.ATTRIBUTE),UUID.randomUUID().toString()); }
}

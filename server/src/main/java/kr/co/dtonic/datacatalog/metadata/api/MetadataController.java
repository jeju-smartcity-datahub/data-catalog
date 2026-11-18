package kr.co.dtonic.datacatalog.metadata.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.ApiProblemSchema;
import kr.co.dtonic.datacatalog.common.RequestIdFilter;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/admin/v1",produces="application/json")
@ApiResponses({
    @ApiResponse(responseCode="400",description="잘못된 입력",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="401",description="인증 필요",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="403",description="기관 또는 작업 권한 없음",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="404",description="대상 없음",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="409",description="상태 또는 멱등 키 충돌",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="412",description="버전 충돌",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="422",description="등록 필수 메타데이터 누락. 초안 보관은 가능하나 검토 요청·승인·게시 전 보완 필요",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class))),
    @ApiResponse(responseCode="428",description="If-Match 필요",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ApiProblemSchema.class)))
})
public class MetadataController {
    private final MetadataWorkflowService service;
    private final WorkflowAccess access;
    public MetadataController(MetadataWorkflowService service,WorkflowAccess access){this.service=service;this.access=access;}

    @GetMapping("/session")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="getAdminSession",summary="신뢰된 관리자 세션과 CSRF 정보")
    public ResponseEntity<SessionResponse> session(Authentication auth,@Parameter(hidden=true) CsrfToken csrf){
        var principal=access.principal(auth);
        // A read through the authorized option scope also checks the administrative roles.
        service.options(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(principal.userId(),principal.organizationIds(),
                auth.getAuthorities().stream().map(a->a.getAuthority()).sorted().toList(),csrf.getHeaderName(),csrf.getToken()));
    }
    @GetMapping("/metadata-options")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="getMetadataOptions",summary="현재 기관 범위와 활성 분류 선택지")
    public MetadataOptions options(Authentication auth){return service.options(auth);}
    @GetMapping("/datasets")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="listAdminDatasets",summary="기관 범위의 최신 메타데이터 목록")
    public MetadataPage list(Authentication auth,@RequestParam(required=false) UUID organizationId,
            @RequestParam(required=false) @Size(max=200) String q,@RequestParam(required=false) String state,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="10") @Min(1) @Max(100) int size){
        return service.list(auth,organizationId,q,state,page,size);
    }
    @PostMapping("/datasets")
    @Operation(operationId="createMetadataDataset",summary="초안 데이터셋 생성")
    @ApiResponse(responseCode="201",description="생성 완료",useReturnTypeSchema=true)
    public ResponseEntity<MetadataDraft> create(@Valid @RequestBody CreateDataset input,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request){
        var result=service.create(input,key,auth,requestId(request));
        return draftResponse(result,true);
    }
    @GetMapping("/datasets/{id}/draft")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="getMetadataDraft",summary="최신 초안 또는 동결된 검토본 조회")
    public ResponseEntity<MetadataDraft> draft(@PathVariable UUID id,Authentication auth){return draftResponse(service.getDraft(id,auth),false);}
    @PatchMapping("/datasets/{id}/draft")
    @ApiResponse(responseCode="200",description="변경 완료",useReturnTypeSchema=true)
    @Operation(operationId="updateMetadataDraft",summary="초안 기본정보 교체",description="기본정보 5개 필드를 모두 전송한다. schema/필드 확장 metadata는 보존한다.")
    public ResponseEntity<MetadataDraft> edit(@PathVariable UUID id,@Valid @RequestBody EditDraft input,
            @RequestHeader(value="If-Match",required=false) String etag,Authentication auth,HttpServletRequest request){
        return draftResponse(service.edit(id,input,etag,auth,requestId(request)),false);
    }
    @PostMapping("/datasets/{id}/drafts")
    @ApiResponse(responseCode="200",description="처리 완료",useReturnTypeSchema=true)
    @Operation(operationId="copyMetadataDraft",summary="결정된 검토본을 새 초안으로 복사")
    public ResponseEntity<MetadataDraft> copy(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth,HttpServletRequest request){
        return draftResponse(service.newDraft(id,etag,key,auth,requestId(request)),false);
    }
    @PostMapping("/datasets/{id}/review-requests")
    @Operation(operationId="submitMetadataReview",summary="초안 내용을 동결하여 검토 요청",description="설명·키워드·제공기관·담당부서·라이선스·공개등급 등 등록 필수항목을 확인한다. 불완전한 초안은 보관할 수 있지만 검토 요청은 차단한다.")
    @ApiResponse(responseCode="201",description="검토 요청 생성",useReturnTypeSchema=true)
    public ResponseEntity<ReviewRequest> submit(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth,HttpServletRequest request){
        return reviewResponse(service.submit(id,etag,key,auth,requestId(request)),true);
    }
    @GetMapping("/review-requests")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="listMetadataReviews",summary="기관 범위 검토 요청 목록")
    public ReviewPage reviews(Authentication auth,@RequestParam(required=false) UUID organizationId,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="10") @Min(1) @Max(100) int size){
        return service.reviews(auth,organizationId,status,page,size);
    }
    @GetMapping("/review-requests/{id}")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="getMetadataReview",summary="검토 요청과 결정 조회")
    public ResponseEntity<ReviewRequest> review(@PathVariable UUID id,Authentication auth){return reviewResponse(service.getReview(id,auth),false);}
    @PostMapping("/review-requests/{id}/decisions")
    @ApiResponse(responseCode="200",description="처리 완료",useReturnTypeSchema=true)
    @Operation(operationId="decideMetadataReview",summary="동결된 동일 내용에 승인 또는 반려 결정")
    public ResponseEntity<ReviewRequest> decide(@PathVariable UUID id,@Valid @RequestBody DecideReview input,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request){return reviewResponse(service.decide(id,input,etag,key,auth,requestId(request)),false);}
    @PostMapping("/review-requests/{id}/withdrawal")
    @ApiResponse(responseCode="200",description="처리 완료",useReturnTypeSchema=true)
    @Operation(operationId="withdrawMetadataReview",summary="진행 중 검토 요청 철회")
    public ResponseEntity<ReviewRequest> withdrawReview(@PathVariable UUID id,@Valid @RequestBody Withdrawal input,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request){return reviewResponse(service.withdrawReview(id,input,etag,key,auth,requestId(request)),false);}
    @PostMapping("/datasets/{id}/publications")
    @Operation(operationId="publishMetadataRevision",summary="승인된 최신 revision을 METADATA_ONLY로 원자적 게시")
    @ApiResponse(responseCode="201",description="게시 완료",useReturnTypeSchema=true)
    public ResponseEntity<Publication> publish(@PathVariable UUID id,@Valid @RequestBody PublishRevision input,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request){return publicationResponse(service.publish(id,input,etag,key,auth,requestId(request)),true);}
    @GetMapping("/publications/{id}")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="getAdminPublication",summary="관리자 게시 상태 조회")
    public ResponseEntity<Publication> publication(@PathVariable UUID id,Authentication auth){return publicationResponse(service.getPublication(id,auth),false);}
    @PostMapping("/publications/{id}/withdrawal")
    @ApiResponse(responseCode="200",description="처리 완료",useReturnTypeSchema=true)
    @Operation(operationId="withdrawMetadataPublication",summary="게시 철회와 현재 공개 포인터 원자적 제거")
    public ResponseEntity<Publication> withdraw(@PathVariable UUID id,@Valid @RequestBody Withdrawal input,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestHeader(value="Idempotency-Key",required=false) String key,
            Authentication auth,HttpServletRequest request){return publicationResponse(service.withdraw(id,input,etag,key,auth,requestId(request)),false);}
    @GetMapping("/datasets/{id}/audit-events")
    @ApiResponse(responseCode="200",description="조회 완료",useReturnTypeSchema=true)
    @Operation(operationId="listDatasetAuditEvents",summary="감사 담당자의 기관 범위 변경 이력 조회")
    public AuditPage audit(@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,Authentication auth){return service.auditEvents(id,page,size,auth);}
    private ResponseEntity<MetadataDraft> draftResponse(MetadataDraft value,boolean created){
        var builder=created?ResponseEntity.created(URI.create("/api/admin/v1/datasets/"+value.id()+"/draft")):ResponseEntity.ok();
        return builder.cacheControl(CacheControl.noStore()).eTag(MetadataWorkflowService.etag("dataset",value.id(),value.lockVersion())).body(value);
    }
    private ResponseEntity<ReviewRequest> reviewResponse(ReviewRequest value,boolean created){
        var builder=created?ResponseEntity.created(URI.create("/api/admin/v1/review-requests/"+value.id())):ResponseEntity.ok();
        return builder.cacheControl(CacheControl.noStore()).eTag(MetadataWorkflowService.etag("review",value.id(),value.lockVersion())).body(value);
    }
    private ResponseEntity<Publication> publicationResponse(Publication value,boolean created){
        var builder=created?ResponseEntity.created(URI.create("/api/admin/v1/publications/"+value.id())):ResponseEntity.ok();
        return builder.cacheControl(CacheControl.noStore()).eTag(MetadataWorkflowService.etag("publication",value.id(),value.lockVersion())).body(value);
    }
    private static String requestId(HttpServletRequest request){return (String)request.getAttribute(RequestIdFilter.ATTRIBUTE);}
}

package kr.co.dtonic.datacatalog.analytics;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.analytics.SearchHistoryContracts.*;

@RestController
public class SearchFeedbackController {
    private final SearchHistoryRepository repository;
    private final SearchRetentionPolicy retention;
    public SearchFeedbackController(SearchHistoryRepository repository,SearchRetentionPolicy retention){this.repository=repository;this.retention=retention;}
    @GetMapping("/api/catalog/v1/search-feedback-form")
    @Operation(operationId="getSearchFeedbackForm",summary="검색 평가용 CSRF 토큰",description="평가 보안 세션만 사용하며 검색 이력에 사용자/세션 식별자를 저장하지 않습니다.")
    public ResponseEntity<FeedbackForm> form(CsrfToken csrf){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new FeedbackForm(csrf.getHeaderName(),csrf.getToken()));}
    @PostMapping(value="/api/catalog/v1/search-feedback",consumes="application/json")
    @Operation(operationId="recordSearchFeedback",summary="선택형 검색 평가",description="동일 origin·CSRF·단일 검색 토큰이 필요합니다. 같은 평가는 멱등, 다른 평가는409. 원문·사용자 식별·IP·자유서술은 저장하지 않습니다.")
    public ResponseEntity<Void> feedback(@Valid @RequestBody FeedbackInput input){repository.feedback(input,retention.now());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
}

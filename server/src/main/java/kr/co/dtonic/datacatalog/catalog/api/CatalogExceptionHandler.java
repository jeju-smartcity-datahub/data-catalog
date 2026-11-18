package kr.co.dtonic.datacatalog.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CatalogExceptionHandler {
    private final ApiProblems problems;
    public CatalogExceptionHandler(ApiProblems problems) { this.problems = problems; }
    @ExceptionHandler(CatalogNotFound.class)
    ProblemDetail notFound(CatalogNotFound exception, HttpServletRequest request) {
        return problems.create(HttpStatus.NOT_FOUND, "DATASET_NOT_FOUND", "공개된 데이터셋을 찾을 수 없습니다.", request);
    }
}

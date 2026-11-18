package kr.co.dtonic.datacatalog.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblems problems;

    public ApiExceptionHandler(ApiProblems problems) {
        this.problems = problems;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class,
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalidArgument(Exception exception, HttpServletRequest request) {
        return problems.create(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "요청 매개변수를 확인하세요.", request);
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail businessFailure(DomainException exception, HttpServletRequest request) {
        return problems.create(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "요청한 작업을 수행할 권한이 없습니다.", request);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail dataUnavailable(DataAccessException exception, HttpServletRequest request) {
        // Do not log SQL, connection URLs, credentials, or response bodies.
        LOGGER.error("Database request failed: type={}", exception.getClass().getSimpleName());
        return problems.create(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "카탈로그를 일시적으로 조회할 수 없습니다.", request);
    }

    @ExceptionHandler(RuntimeException.class)
    ProblemDetail unexpectedFailure(RuntimeException exception, HttpServletRequest request) {
        // Exception messages and causes may contain remote responses or connection details.
        LOGGER.error("Request failed: type={}", exception.getClass().getSimpleName());
        return problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", request);
    }
}

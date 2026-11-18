package kr.co.dtonic.datacatalog.common;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "catalog.requestId";
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader("X-Request-Id");
        boolean searchPrivacy = request.getRequestURI().equals("/api/catalog/v1/search")
                || request.getRequestURI().equals("/api/catalog/v1/datasets")
                || request.getRequestURI().equals("/api/catalog/v1/search/facets")
                || request.getRequestURI().equals("/api/catalog/v1/search-feedback-form")
                || request.getRequestURI().equals("/api/catalog/v1/search-feedback");
        String requestId = !searchPrivacy && candidate != null && candidate.matches("[a-zA-Z0-9_-]{8,64}")
                ? candidate : UUID.randomUUID().toString();
        if(searchPrivacy){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            try {
                chain.doFilter(request, response);
            } finally {
                // Only the framework's route template is logged: no raw URL, query, body, cookie or principal.
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String method = switch (request.getMethod()) {
                    case "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD" -> request.getMethod();
                    default -> "OTHER";
                };
                LOG.info("http_request requestId={} method={} route={} status={} durationMs={}",
                        requestId, method, pattern == null ? "UNMAPPED" : pattern,
                        response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            }
        }
    }
}

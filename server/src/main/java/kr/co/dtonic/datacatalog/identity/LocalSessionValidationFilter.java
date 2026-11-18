package kr.co.dtonic.datacatalog.identity;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates local sessions before CSRF and authorization on every request. */
public final class LocalSessionValidationFilter extends OncePerRequestFilter {
    private final ObjectProvider<LocalAuthenticationService> services;
    private final ApiProblems problems;
    public LocalSessionValidationFilter(ObjectProvider<LocalAuthenticationService> services, ApiProblems problems) {
        this.services = services; this.problems = problems;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof LocalAuthenticationToken local) {
            boolean current;
            try {
                var service = services.getIfAvailable();
                current = service != null && service.current(local);
            } catch (DataAccessException unavailable) {
                new SecurityContextLogoutHandler().logout(request, response, auth);
                problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE", "인증 상태를 확인할 수 없습니다.");
                return;
            }
            if (!current) {
                new SecurityContextLogoutHandler().logout(request, response, auth);
                problems.write(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

package kr.co.dtonic.datacatalog.identity.api;

import java.io.IOException;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.dtonic.datacatalog.common.ApiProblemSchema;
import kr.co.dtonic.datacatalog.common.ApiProblems;
import kr.co.dtonic.datacatalog.identity.LocalAuthenticationService;
import kr.co.dtonic.datacatalog.identity.LocalLoginRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/auth")
public class LocalAuthenticationController {
    private final ObjectProvider<LocalAuthenticationService> services;
    private final ObjectProvider<LocalLoginRateLimiter> limiters;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrf;
    private final ApiProblems problems;
    private final String cookieName;
    private final boolean secureCookie;

    public LocalAuthenticationController(ObjectProvider<LocalAuthenticationService> services,
            ObjectProvider<LocalLoginRateLimiter> limiters, SecurityContextRepository contexts, CsrfTokenRepository csrf,
            ApiProblems problems, @Value("${server.servlet.session.cookie.name:JSESSIONID}") String cookieName,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie) {
        this.services = services; this.limiters = limiters; this.contexts = contexts; this.csrf = csrf;
        this.problems = problems; this.cookieName = cookieName; this.secureCookie = secureCookie;
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "getLocalAuthentication", summary = "로컬 로그인 사용 여부와 로그인 CSRF 토큰", description = "local-auth 프로필을 명시해야 하며 prod/production에서는 catalog.local-auth.enabled=true도 필요하다. 비활성 응답은 세션을 생성하지 않는다.")
    @ApiResponse(responseCode = "200", description = "로그인 설정", useReturnTypeSchema = true)
    public ResponseEntity<AuthBootstrapResponse> bootstrap(HttpServletRequest request) {
        if (services.getIfAvailable() == null) return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthBootstrapResponse(false, null, null));
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthBootstrapResponse(true, token.getHeaderName(), token.getToken()));
    }

    @PostMapping(value = "/login", consumes = "application/json")
    @Operation(operationId = "loginLocalAdministrator", summary = "개발용 로컬 관리자 로그인", description = "익명 bootstrap에서 받은 CSRF 토큰이 필요하다. 성공 후 세션 ID와 CSRF를 회전한다. 기존 세션 조회 API에서 새 토큰을 받는다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "서버 세션 생성"),
        @ApiResponse(responseCode = "400", description = "잘못된 입력", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class))),
        @ApiResponse(responseCode = "401", description = "로그인 실패", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class))),
        @ApiResponse(responseCode = "403", description = "CSRF 검증 실패 또는 로그인 비활성", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class))),
        @ApiResponse(responseCode = "429", description = "요청 제한; Retry-After 초 후 재시도", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class))),
        @ApiResponse(responseCode = "503", description = "인증 저장소 사용 불가", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class)))
    })
    public void login(@Valid @RequestBody LocalLoginRequest body, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        var service = services.getIfAvailable();
        if (service == null) { problems.write(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "로그인을 사용할 수 없습니다."); return; }
        // Only the socket peer is used; browser-provided forwarding headers are not identity or limiter keys.
        long retry = limiters.getObject().reserve(LocalAuthenticationService.normalize(body.username()), request.getRemoteAddr());
        if (retry > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retry));
            problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "잠시 후 다시 시도해 주세요."); return;
        }
        try {
            var auth = service.authenticate(body.username(), body.password());
            new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),
                    new CsrfAuthenticationStrategy(csrf))).onAuthentication(auth, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (BadCredentialsException denied) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "LOCAL_LOGIN_FAILED", "로그인 정보를 확인해 주세요.");
        } catch (DataAccessException unavailable) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE", "인증 상태를 확인할 수 없습니다.");
        }
    }

    @PostMapping("/logout")
    @Operation(operationId = "logoutLocalAdministrator", summary = "로컬 관리자 로그아웃", description = "현재 CSRF 토큰이 필요하며 서버 세션과 CSRF 토큰을 무효화한다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "세션 무효화"),
        @ApiResponse(responseCode = "403", description = "CSRF 검증 실패", content = @Content(schema = @Schema(implementation = ApiProblemSchema.class)))})
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
        var logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contexts);
        logout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(cookieName, "").path("/").httpOnly(true).secure(secureCookie)
                        .sameSite("Lax").maxAge(0).build().toString()).build();
    }

    @Schema(requiredProperties = {"localLoginEnabled", "csrfHeaderName", "csrfToken"})
    public record AuthBootstrapResponse(boolean localLoginEnabled,
            @Schema(nullable = true) String csrfHeaderName, @Schema(nullable = true) String csrfToken) {}

    public record LocalLoginRequest(@NotNull @Size(min = 3, max = 80) String username,
            @NotNull @Size(min = 1, max = 128) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, format = "password",
                    description = "최대 UTF-8 72바이트. 비밀번호는 응답·로그에 포함하지 않는다.") String password) {
        @Override public String toString() { return "LocalLoginRequest[REDACTED]"; }
    }
}

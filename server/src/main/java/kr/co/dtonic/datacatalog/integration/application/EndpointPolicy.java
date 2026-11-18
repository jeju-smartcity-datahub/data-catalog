package kr.co.dtonic.datacatalog.integration.application;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** No arbitrary URL tests. Empty deployment allowlist denies every configured endpoint. */
@Component
public class EndpointPolicy {
    private final Set<String> allowed;
    private final Set<String> allowedHttp;
    public EndpointPolicy(String origins) { this(origins, ""); }
    @Autowired
    public EndpointPolicy(@Value("${catalog.integration.allowed-origins:}") String origins,
                          @Value("${catalog.integration.allowed-http-origins:}") String httpOrigins) {
        allowed = parseOrigins(origins, "https");
        allowedHttp = parseOrigins(httpOrigins, "http");
    }
    public URI validate(String value) {
        URI uri = parseEndpoint(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) && allowed.contains(origin(uri))
                || "http".equalsIgnoreCase(uri.getScheme()) && allowedHttp.contains(origin(uri)))) throw rejected();
        return uri;
    }
    /** Syntax validation is separate from the server's connection allowlist. */
    public static URI parseEndpoint(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException invalid) { throw invalidEndpoint(); }
        if (uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getRawPath().contains("%")
                || !uri.normalize().equals(uri) || uri.getRawPath().contains("//")
                || uri.getPort()==0 || uri.getPort()>65535 || uri.getRawAuthority().endsWith(":")
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw invalidEndpoint();
        return uri;
    }
    private static Set<String> parseOrigins(String value, String scheme) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(URI::create)
                .peek(uri -> { if (!scheme.equalsIgnoreCase(uri.getScheme())
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
                    throw new IllegalArgumentException("Invalid approved origin"); })
                .map(EndpointPolicy::origin).collect(Collectors.toUnmodifiableSet());
    }
    private static String origin(URI uri) {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Invalid approved origin");
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":"
                + (uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort());
    }
    private static DomainException rejected() {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_APPROVED", "서버의 연결 허용 목록에 없는 주소입니다. 설치 담당자에게 주소·포트 등록을 요청하세요.");
    }
    private static DomainException invalidEndpoint() {
        return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_ENDPOINT","IP 또는 도메인과 포트를 확인하세요.");
    }
}

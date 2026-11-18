package kr.co.dtonic.datacatalog.integration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.*;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.Capability;
import kr.co.dtonic.datacatalog.integration.domain.SecretReferenceProvider;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationRows;
import org.springframework.http.HttpStatus;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper.args;

@Service
public class IntegrationService {
    public static final String MAPPING_VERSION = "normalized-metadata-v1";
    private final IntegrationMapper mapper;
    private final WorkflowAccess access;
    private final AdapterRegistry adapters;
    private final EndpointPolicy endpoints;
    private final SecretReferenceProvider secrets;
    private final KetiRecordsEndpointResolver recordsEndpoints;

    public IntegrationService(IntegrationMapper mapper, WorkflowAccess access, AdapterRegistry adapters, EndpointPolicy endpoints, SecretReferenceProvider secrets,
            KetiRecordsEndpointResolver recordsEndpoints) {
        this.mapper = mapper; this.access = access; this.adapters = adapters; this.endpoints = endpoints; this.secrets = secrets;
        this.recordsEndpoints=recordsEndpoints;
    }

    public List<ConnectionView> list(Authentication auth, UUID organizationId) {
        access.require(auth, organizationId, "ORG_EDITOR", "ORG_REVIEWER");
        requireActive(organizationId);
        return mapper.connections(organizationId.toString()).stream().map(this::view).toList();
    }

    public ConnectionView get(Authentication auth, UUID id) { return view(scoped(auth, id, false, false)); }

    @Transactional
    public ConnectionView create(Authentication auth, ConnectionInput input) {
        var actor = access.require(auth, input.organizationId(), "ORG_EDITOR");
        requireActive(input.organizationId());
        validateEndpoint(input.endpoint(), input.enabled(),input.adapterCode());
        String id = UUID.randomUUID().toString();
        mapper.createConnection(connectionArgs(id, input, input.endpoint(), blankToNull(input.secretReference())));
        audit(id, actor.userId(), "CONNECTION_CREATED", 0);
        return view(mapper.connection(id));
    }

    @Transactional
    public ConnectionView update(Authentication auth, UUID id, long version, ConnectionInput input) {
        var current = scoped(auth, id, true, true);
        if (!current.organizationId().equals(input.organizationId().toString())) throw problem(HttpStatus.CONFLICT,
                "ORGANIZATION_IMMUTABLE", "연결의 소유 기관을 변경할 수 없습니다.");
        ensureEditable(current, version);
        String endpoint = input.endpoint() == null ? current.endpoint() : blankToNull(input.endpoint());
        if (!current.adapterCode().equals(input.adapterCode())) throw problem(HttpStatus.CONFLICT,
                "ADAPTER_IMMUTABLE", "수집 방식은 기존 연결에서 변경할 수 없습니다. 다른 방식은 새 연결로 등록하세요.");
        String secret = input.secretReference() == null ? current.secretReference() : blankToNull(input.secretReference());
        validateEndpoint(endpoint, input.enabled(),input.adapterCode());
        var values = connectionArgs(id.toString(), input, endpoint, secret);
        values.put("version", version);
        if (mapper.updateConnection(values) != 1) throw stale();
        audit(id.toString(), access.principal(auth).userId(), "CONNECTION_UPDATED", version + 1);
        return view(mapper.connection(id.toString()));
    }

    /** DELETE deactivates and retains history; the request cannot race an active worker. */
    @Transactional
    public ConnectionView disable(Authentication auth, UUID id, long version) {
        var current = scoped(auth, id, true, true);
        ensureEditable(current, version);
        var input = new ConnectionInput(UUID.fromString(current.organizationId()), current.name(), current.adapterCode(),
                current.endpoint(), null, false, current.timeoutSeconds(), current.maxAttempts(), current.maxPages());
        var values = connectionArgs(id.toString(), input, current.endpoint(), current.secretReference());
        values.put("version", version);
        if (mapper.updateConnection(values) != 1) throw stale();
        audit(id.toString(), access.principal(auth).userId(), "CONNECTION_DISABLED", version + 1);
        return view(mapper.connection(id.toString()));
    }

    @Transactional
    public RunView requestRun(Authentication auth, UUID id, String kind, String key) {
        if (key == null || !key.matches("[a-zA-Z0-9._:-]{8,128}")) throw problem(HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED", "8~128자의 멱등 키가 필요합니다.");
        var connection = scoped(auth, id, true, true);
        String actor = access.principal(auth).userId().toString();
        String hash = digest(kind + ":" + connection.lockVersion() + ":" + MAPPING_VERSION);
        var values = args("id", UUID.randomUUID().toString(), "connection", id.toString(), "actor", actor,
                "kind", kind, "digest", digest(key), "hash", hash, "version", connection.lockVersion(),
                "mapping", MAPPING_VERSION, "checkpoint", mapper.checkpoint(id.toString()));
        var previous = mapper.idempotentRun(values);
        if (previous != null) {
            if (!previous.requestHash().equals(hash)) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 멱등 키로 다른 연결 설정을 실행할 수 없습니다.");
            return runView(previous);
        }
        adapters.require(connection.adapterCode(), kind.equals("CHECK") ? Capability.TEST_CONNECTION : Capability.DISCOVER_DATASETS);
        if (!connection.enabled()) throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_DISABLED", "연결 설정을 활성화해야 실행할 수 있습니다.");
        validateEndpoint(connection.endpoint(), true,connection.adapterCode());
        if (connection.secretReference() != null && !secrets.available()) throw problem(HttpStatus.SERVICE_UNAVAILABLE,
                "SECRET_STORE_UNAVAILABLE", "서버 비밀 저장소가 연결되지 않았습니다.");
        if (mapper.createRun(values) != 1) throw problem(HttpStatus.CONFLICT, "SYNC_ALREADY_ACTIVE", "이 연결의 수집 또는 연결 검사가 진행 중입니다.");
        audit(id.toString(), UUID.fromString(actor), kind.equals("CHECK") ? "CHECK_REQUESTED" : "SYNC_REQUESTED", connection.lockVersion());
        return runView(mapper.run((String) values.get("id")));
    }

    public List<RunView> runs(Authentication auth, UUID connectionId) {
        scoped(auth, connectionId, false, false);
        return mapper.runs(connectionId.toString()).stream().map(IntegrationService::runView).toList();
    }

    public RunView run(Authentication auth, UUID id) { return runView(scopedRun(auth, id)); }

    public ItemPage items(Authentication auth, UUID id, int page, int size) {
        scopedRun(auth, id);
        return new ItemPage(mapper.items(args("id", id.toString(), "size", size, "offset", (long) page * size)),
                page, size, mapper.itemCount(id.toString()));
    }

    public List<ConnectionEvent> events(Authentication auth, UUID id) {
        scoped(auth, id, false, false);
        return mapper.events(id.toString()).stream().map(row -> new ConnectionEvent(UUID.fromString(row.id()),
                UUID.fromString(row.actorId()), row.action(), row.requestId(), row.configurationVersion(), row.occurredAt())).toList();
    }

    private IntegrationRows.Run scopedRun(Authentication auth, UUID id) {
        access.principal(auth);
        var run = mapper.run(id.toString());
        if (run == null) throw notFound();
        scoped(auth, UUID.fromString(run.connectionId()), false, false);
        return run;
    }

    private IntegrationRows.Connection scoped(Authentication auth, UUID id, boolean edit, boolean lock) {
        var principal = access.principal(auth);
        var row = lock ? mapper.lockConnection(id.toString()) : mapper.connection(id.toString());
        if (row == null || (!access.isPlatformAdmin(auth) && !principal.organizationIds().contains(UUID.fromString(row.organizationId())))) throw notFound();
        access.require(auth, UUID.fromString(row.organizationId()), edit ? new String[]{"ORG_EDITOR"} : new String[]{"ORG_EDITOR", "ORG_REVIEWER"});
        requireActive(UUID.fromString(row.organizationId()));
        return row;
    }

    private void ensureEditable(IntegrationRows.Connection row, long version) {
        if (row.lockVersion() != version) throw stale();
        if (mapper.activeRuns(row.id()) != 0) throw problem(HttpStatus.CONFLICT, "SYNC_ALREADY_ACTIVE", "실행 중인 작업이 있어 연결 설정을 변경할 수 없습니다.");
    }

    private void validateEndpoint(String endpoint, boolean enabled,String adapter) {
        if (endpoint == null || endpoint.isBlank()) {
            if (enabled) throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "ENDPOINT_REQUIRED", "메타데이터 API 주소를 입력하세요.");
        } else {
            var uri=EndpointPolicy.parseEndpoint(endpoint);
            String resource="KETI_DATACORE".equals(adapter)?"datasets":"NGSI_LD".equals(adapter)?"entities":null;
            if(resource!=null && uri.getPath().matches(".*/"+resource+"(?:/.*)?"))
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY,"ENDPOINT_PATH_INVALID","/"+resource+" 앞까지의 기본 주소를 입력하세요.");
            endpoints.validate(endpoint);
        }
    }

    private Map<String, Object> connectionArgs(String id, ConnectionInput input, String endpoint, String secret) {
        return args("id", id, "org", input.organizationId().toString(), "name", input.name().trim(), "adapter", input.adapterCode(),
                "endpoint", blankToNull(endpoint), "secret", secret, "enabled", input.enabled(), "timeout", input.timeoutSeconds(),
                "attempts", input.maxAttempts(), "pages", input.maxPages());
    }
    private void audit(String id, UUID actor, String action, long version) {
        String requestId = MDC.get("requestId");
        if (requestId == null || !requestId.matches("[a-zA-Z0-9_-]{8,64}")) requestId = UUID.randomUUID().toString();
        mapper.audit(args("event", UUID.randomUUID().toString(), "id", id, "actor", actor.toString(), "action", action, "requestId", requestId, "version", version));
    }
    private ConnectionView view(IntegrationRows.Connection row) {
        var adapter = adapters.find(row.adapterCode());
        String metadataEndpoint=null,recordsEndpoint=null;
        if(row.endpoint()!=null) {
            try { metadataEndpoint=EndpointPolicy.parseEndpoint(row.endpoint()).toString(); }
            catch(DomainException invalid) { /* Invalid stored addresses are not echoed. */ }
            if(metadataEndpoint!=null)try { recordsEndpoint=recordsEndpoints.resolve(row.adapterCode(),metadataEndpoint).toString(); }
            catch(DomainException unavailable) { /* Keep the settings page usable after an allowlist change. */ }
        }
        return new ConnectionView(UUID.fromString(row.id()), UUID.fromString(row.organizationId()), row.name(), row.adapterCode(),
                row.endpoint() != null, row.secretReference() != null, row.enabled(), row.timeoutSeconds(), row.maxAttempts(), row.maxPages(),
                row.lockVersion(), adapter.isPresent(), adapter.map(a -> a.capabilities().stream().map(Enum::name).collect(Collectors.toSet())).orElse(Set.of()), row.updatedAt(),metadataEndpoint,recordsEndpoint);
    }
    public static RunView runView(IntegrationRows.Run row) {
        return new RunView(UUID.fromString(row.id()), UUID.fromString(row.connectionId()), row.kind(), row.status(), row.attempt(),
                row.configurationVersion(), row.mappingVersion(), row.errorCode(), row.createdAt(), row.startedAt(), row.finishedAt(),
                row.phase(),row.processedCount(),row.totalCount(),row.savedCount(),row.unchangedCount(),row.failedCount(),row.progressAt(),row.nextAttemptAt());
    }
    public static String etag(ConnectionView view) { return "connection:" + view.id() + ":" + view.lockVersion(); }
    public static long version(String ifMatch, UUID id) {
        if (ifMatch == null) throw problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "현재 ETag를 If-Match로 전달하세요.");
        String prefix = "\"connection:" + id + ":";
        if (!ifMatch.startsWith(prefix) || !ifMatch.endsWith("\"") || !ifMatch.substring(prefix.length(), ifMatch.length() - 1).matches("[0-9]{1,18}")) throw stale();
        return Long.parseLong(ifMatch.substring(prefix.length(), ifMatch.length() - 1));
    }
    private void requireActive(UUID organizationId) {
        if (!mapper.organizationActive(organizationId.toString())) throw problem(HttpStatus.FORBIDDEN, "ORGANIZATION_INACTIVE", "활성 기관의 권한이 필요합니다.");
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static DomainException notFound() { return problem(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "연결 또는 수집 작업을 찾을 수 없습니다."); }
    private static DomainException stale() { return problem(HttpStatus.PRECONDITION_FAILED, "STALE_CONNECTION", "연결이 변경되었습니다. 다시 조회하세요."); }
    private static DomainException problem(HttpStatus status, String code, String message) { return new DomainException(status, code, message); }
}

package kr.co.dtonic.datacatalog.integration.application;

import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.integration.api.IntegrationScheduleModels.ScheduleInput;
import kr.co.dtonic.datacatalog.integration.api.IntegrationScheduleModels.ScheduleView;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationScheduleService {
    private final IntegrationService integrations;
    private final IntegrationScheduleRepository repository;
    private final WorkflowAccess access;
    private final boolean workerEnabled;

    public IntegrationScheduleService(IntegrationService integrations, IntegrationScheduleRepository repository,
            WorkflowAccess access, @Value("${catalog.integration.worker-enabled:false}") boolean workerEnabled) {
        this.integrations = integrations; this.repository = repository; this.access = access;
        this.workerEnabled = workerEnabled;
    }

    public ScheduleView get(Authentication auth, UUID id) {
        integrations.get(auth, id);
        return view(id, repository.get(id));
    }

    @Transactional
    public ScheduleView update(Authentication auth, UUID id, long version, ScheduleInput input) {
        // The persistent system operation is configured by a platform administrator, never by stored browser roles.
        if (!access.isPlatformAdmin(auth)) throw new AccessDeniedException("Platform administrator required");
        var connection = repository.lockConnection(id, false);
        var visible = integrations.get(auth, id);
        if (input.intervalSeconds() < 60 || input.intervalSeconds() > 86400) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "SCHEDULE_INTERVAL_INVALID", "수집 주기는 60~86400초로 입력하세요.");
        }
        var current = repository.get(id);
        if (version != (current == null ? 0 : current.lockVersion())) throw stale();
        if (input.enabled()) {
            if (!visible.enabled()) throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_DISABLED", "연결 설정을 활성화해야 정기 수집을 켤 수 있습니다.");
            if (!visible.adapterAvailable() || !visible.capabilities().contains("DISCOVER_DATASETS")) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPABILITY_UNSUPPORTED", "데이터셋 수집이 가능한 연결이 필요합니다.");
            }
        }
        var actor = access.principal(auth).userId();
        repository.save(id, input.enabled(), input.intervalSeconds(), actor);
        repository.audit(id, actor, "SCHEDULE_UPDATED", connection.configurationVersion());
        return view(id, repository.get(id));
    }

    private ScheduleView view(UUID id, IntegrationScheduleRepository.Schedule row) {
        if (row == null) return new ScheduleView(id, false, 3600, null, null, null, null, null, 0, null, workerEnabled);
        return new ScheduleView(id, row.enabled(), row.intervalSeconds(), row.nextRunAt(), row.lastRequestedAt(),
                row.lastRunId(), row.lastErrorCode(), row.updatedBy(), row.lockVersion(), row.updatedAt(), workerEnabled);
    }

    public static String etag(ScheduleView view) { return "schedule:" + view.connectionId() + ":" + view.lockVersion(); }

    public static long version(String ifMatch, UUID id) {
        if (ifMatch == null) throw new DomainException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "현재 ETag를 If-Match로 전달하세요.");
        String prefix = "\"schedule:" + id + ":";
        if (!ifMatch.startsWith(prefix) || !ifMatch.endsWith("\"")
                || !ifMatch.substring(prefix.length(), ifMatch.length() - 1).matches("[0-9]{1,18}")) throw stale();
        return Long.parseLong(ifMatch.substring(prefix.length(), ifMatch.length() - 1));
    }

    private static DomainException stale() {
        return new DomainException(HttpStatus.PRECONDITION_FAILED, "STALE_SCHEDULE", "수집 일정이 변경되었습니다. 다시 조회하세요.");
    }
}

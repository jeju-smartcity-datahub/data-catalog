package kr.co.dtonic.datacatalog.standard.definition;

import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.http.HttpStatus;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.DefinitionDiagnostics;

/** Safe codes and generated input positions only; never retains source text or a cause. */
public final class DefinitionRejectedException extends DomainException {
    private final UUID analysisRunId;
    private final DefinitionDiagnostics diagnostics;
    public DefinitionRejectedException(UUID analysisRunId,DefinitionDiagnostics diagnostics) {
        super(HttpStatus.BAD_REQUEST,DefinitionFailureRecorder.ERROR_CODE,"정의서를 생성하지 않았습니다. 항목별 사유를 확인하고 원본 정의서를 수정하세요.");
        this.analysisRunId=analysisRunId;this.diagnostics=diagnostics;
    }
    public UUID analysisRunId() { return analysisRunId; }
    public DefinitionDiagnostics diagnostics() { return diagnostics; }
}

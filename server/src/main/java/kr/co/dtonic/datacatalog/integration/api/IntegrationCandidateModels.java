package kr.co.dtonic.datacatalog.integration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IntegrationCandidateModels {
    private IntegrationCandidateModels() { }
    @Schema(requiredProperties={"id","connectionId","externalId","title","revisionNo","state","updatedAt"})
    public record CandidateSummary(UUID id,UUID connectionId,String externalId,String title,int revisionNo,
            String state,UUID datasetId,OffsetDateTime updatedAt) { }
    @Schema(requiredProperties={"items","page","size","totalElements"})
    public record CandidatePage(List<CandidateSummary> items,int page,int size,long totalElements) { }
    @Schema(requiredProperties={"field","sourceValue","sourceChanged","manuallyChanged","conflict","action"})
    public record SourceFieldChange(String field,String previousSourceValue,String currentValue,String sourceValue,
            boolean sourceChanged,boolean manuallyChanged,boolean conflict,String action) { }
    @Schema(requiredProperties={"candidate","description","sourceHash","etag","changes","conflict","metadata"})
    public record CandidateDetail(CandidateSummary candidate,String description,String sourceHash,String etag,
            String targetEtag,String targetState,List<SourceFieldChange> changes,boolean conflict,Map<String,Object> metadata) { }
    public record ApplyCandidate(@Size(max=160) String targetEtag) { }
    @Schema(requiredProperties={"id","candidateId","sourceHash","datasetId","revisionId","lockVersion","appliedAt"})
    public record CandidateApplication(UUID id,UUID candidateId,String sourceHash,UUID datasetId,
            UUID revisionId,long lockVersion,OffsetDateTime appliedAt) { }
}

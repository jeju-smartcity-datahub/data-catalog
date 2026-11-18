package kr.co.dtonic.datacatalog.integration.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class IntegrationPublicationModels {
    private IntegrationPublicationModels() {}
    @Schema(requiredProperties={"connectionId","enabled","department","license","keywords","defaultCategoryId","lockVersion"})
    public record PublicationPolicy(UUID connectionId,boolean enabled,@Schema(types={"string","null"}) String department,@Schema(types={"string","null"}) String license,List<String> keywords,@Schema(types={"string","null"},format="uuid") UUID defaultCategoryId,long lockVersion) {}
    public record PublicationPolicyInput(boolean enabled,@Schema(types={"string","null"}) @Size(max=200) String department,@Schema(types={"string","null"}) @Size(max=500) String license,
            @Size(max=20) List<@NotBlank @Size(max=100) String> keywords,@Schema(types={"string","null"},format="uuid") UUID defaultCategoryId) {}
    public record PublicationPreviewInput(@NotEmpty @Size(max=100) List<@NotNull UUID> candidateIds) {}
    @Schema(requiredProperties={"candidateId","title","etag","targetEtag","publishable","missingFields","categoryId","categoryName","alreadyPublished"})
    public record PublicationPreviewItem(UUID candidateId,String title,String etag,@Schema(types={"string","null"}) String targetEtag,boolean publishable,
            List<String> missingFields,@Schema(types={"string","null"},format="uuid") UUID categoryId,@Schema(types={"string","null"}) String categoryName,boolean alreadyPublished) {}
    @Schema(requiredProperties={"items","policy"})
    public record PublicationPreview(List<PublicationPreviewItem> items,PublicationPolicy policy) {}
    public record PublishCandidateInput(@NotNull UUID candidateId,@NotBlank @Size(max=160) String etag,@Schema(types={"string","null"}) @Size(max=160) String targetEtag) {}
    public record PublishCandidatesInput(@NotEmpty @Size(max=100) List<@NotNull @Valid PublishCandidateInput> items,@NotNull @Min(0) Long policyVersion) {}
    @Schema(requiredProperties={"candidateId","datasetId","publicationId","status","code","message"})
    public record PublicationResult(UUID candidateId,@Schema(types={"string","null"},format="uuid") UUID datasetId,@Schema(types={"string","null"},format="uuid") UUID publicationId,String status,@Schema(types={"string","null"}) String code,String message) {}
    @Schema(requiredProperties={"items","published","skipped","failed"})
    public record BatchPublicationResult(List<PublicationResult> items,int published,int skipped,int failed) {}
    @Schema(requiredProperties={"candidateId","title","status","code","message","mode","occurredAt"})
    public record PublicationHistoryItem(UUID candidateId,String title,String status,@Schema(types={"string","null"}) String code,String message,String mode,OffsetDateTime occurredAt) {}
    @Schema(requiredProperties={"items","pendingCount","runningCount"})
    public record PublicationHistory(List<PublicationHistoryItem> items,long pendingCount,long runningCount) {}
}

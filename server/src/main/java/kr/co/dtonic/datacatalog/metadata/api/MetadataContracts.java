package kr.co.dtonic.datacatalog.metadata.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;

public final class MetadataContracts {
    private MetadataContracts() {}
    public record CreateDataset(@NotNull UUID ownerOrganizationId,
            @NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{0,159}") String slug,
            @NotBlank @Size(max=300) String title, @NotNull @Size(max=20000) String description,
            @NotNull UUID providerOrganizationId, UUID primaryCategoryId,
            @NotNull @Pattern(regexp="PUBLIC|ORGANIZATION|PRIVATE") String visibility,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> regionIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> hubIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> topicIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid MetadataDetails details) {
        public CreateDataset(UUID ownerOrganizationId,String slug,String title,String description,UUID providerOrganizationId,UUID primaryCategoryId,String visibility) {
            this(ownerOrganizationId,slug,title,description,providerOrganizationId,primaryCategoryId,visibility,null,null,null,null);
        }
    }
    public record EditDraft(@NotBlank @Size(max=300) String title,
            @NotNull @Size(max=20000) String description, @NotNull UUID providerOrganizationId,
            UUID primaryCategoryId, @NotNull @Pattern(regexp="PUBLIC|ORGANIZATION|PRIVATE") String visibility,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> regionIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> hubIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Size(max=20) List<@NotNull UUID> topicIds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Valid MetadataDetails details) {
        public EditDraft(String title,String description,UUID providerOrganizationId,UUID primaryCategoryId,String visibility) {
            this(title,description,providerOrganizationId,primaryCategoryId,visibility,null,null,null,null);
        }
    }
    public record DecideReview(@NotNull @Pattern(regexp="APPROVE|REJECT") String decision, @Size(max=2000) String reason) {}
    public record PublishRevision(@NotNull UUID revisionId, @NotNull UUID reviewDecisionId) {}
    public record Withdrawal(@NotBlank @Size(max=2000) String reason) {}
    @Schema(requiredProperties={"id","slug","ownerOrganizationId","revisionId","revisionNo","state","title","description","providerOrganizationId","visibility","lockVersion","regionIds","hubIds","topicIds","details","updatedAt"})
    public record MetadataDraft(UUID id, String slug, UUID ownerOrganizationId, UUID revisionId,
            int revisionNo, String state, String title, String description, UUID providerOrganizationId,
            UUID primaryCategoryId, String visibility, long lockVersion, UUID currentPublicationId,
            UUID reviewRequestId, UUID reviewDecisionId, List<UUID> regionIds,List<UUID> hubIds,List<UUID> topicIds,
            MetadataDetails details,OffsetDateTime updatedAt) {
        public MetadataDraft {
            regionIds=regionIds==null?List.of():List.copyOf(regionIds);
            hubIds=hubIds==null?List.of():List.copyOf(hubIds);
            topicIds=topicIds==null?List.of():List.copyOf(topicIds);
            details=details==null?MetadataDetails.empty():details;
        }
    }
    @Schema(requiredProperties={"number","size","totalElements","totalPages"})
    public record Page(int number,int size,long totalElements,long totalPages) {
        public static Page of(int number,int size,long total) { return new Page(number,size,total,(total+size-1)/size); }
    }
    @Schema(requiredProperties={"items","page"})
    public record MetadataPage(List<MetadataDraft> items,Page page) {}
    @Schema(requiredProperties={"id","datasetId","revisionId","contentHash","status","submittedBy","submittedAt","lockVersion"})
    public record ReviewRequest(UUID id,UUID datasetId,UUID revisionId,String contentHash,String status,
            UUID submittedBy,OffsetDateTime submittedAt,long lockVersion,UUID decisionId,String decision,String reason) {}
    @Schema(requiredProperties={"items","page"})
    public record ReviewPage(List<ReviewRequest> items,Page page) {}
    @Schema(requiredProperties={"id","datasetId","revisionId","publicationNo","state","accessState","publishedAt","lockVersion"})
    public record Publication(UUID id,UUID datasetId,UUID revisionId,int publicationNo,String state,String accessState,
            OffsetDateTime publishedAt,OffsetDateTime withdrawnAt,long lockVersion,String withdrawalReason) {}
    @Schema(requiredProperties={"id","occurredAt","actorUserId","action","targetType","targetId","requestId","result","changeSummary"})
    public record AuditEvent(UUID id,OffsetDateTime occurredAt,UUID actorUserId,String action,String targetType,
            UUID targetId,String requestId,String result,Map<String,Object> changeSummary) {}
    @Schema(requiredProperties={"items","page"})
    public record AuditPage(List<AuditEvent> items,Page page) {}
    @Schema(requiredProperties={"id","name"})
    public record Option(UUID id,String name) {}
    @Schema(requiredProperties={"id","name","parentId"})
    public record TopicOption(UUID id,String name,UUID parentId) {}
    @Schema(requiredProperties={"organizations","categories","regions","hubs","topics"})
    public record MetadataOptions(List<Option> organizations,List<Option> categories,List<Option> regions,List<Option> hubs,List<TopicOption> topics) {}
    @Schema(requiredProperties={"userId","organizationIds","authorities","csrfHeaderName","csrfToken"})
    public record SessionResponse(UUID userId,Set<UUID> organizationIds,List<String> authorities,String csrfHeaderName,String csrfToken) {}
}

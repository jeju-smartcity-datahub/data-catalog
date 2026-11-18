package kr.co.dtonic.datacatalog.classification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class CategoryContracts {
    private CategoryContracts() {}
    @Schema(requiredProperties={"id","parentId","code","name","description","active","ordinal","lockVersion","usageCount"})
    public record CategoryDetails(String id,@Schema(types={"string","null"}) String parentId,String code,String name,
            String description,boolean active,int ordinal,long lockVersion,long usageCount) {}
    public record CategoryDetailsInput(@Schema(types={"string","null"},format="uuid") UUID parentId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String code,
            @NotBlank @Size(max=200) String name,@NotNull @Size(max=4000) String description,boolean active,@Min(0) int ordinal) {}
    @Schema(requiredProperties={"datasetId","revisionId","title","fieldName","topicName","providerOrganizationName","visibility","revisionState","publicationState","revisionNo","updatedAt","currentRevision"})
    public record CategoryDatasetUsage(String datasetId,String revisionId,String title,String fieldName,String topicName,
            String providerOrganizationName,String visibility,String revisionState,
            @Schema(types={"string","null"}) String publicationState,int revisionNo,OffsetDateTime updatedAt,boolean currentRevision) {}
    @Schema(requiredProperties={"items","page","size","totalElements","totalPages"})
    public record CategoryDatasetPage(List<CategoryDatasetUsage> items,int page,int size,long totalElements,long totalPages) {}
}

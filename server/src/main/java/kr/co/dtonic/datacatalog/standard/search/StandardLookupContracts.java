package kr.co.dtonic.datacatalog.standard.search;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

public final class StandardLookupContracts {
    private StandardLookupContracts(){}
    @Schema(requiredProperties={"id","dictionaryId","dictionaryName","versionLabel","scope","code","name","definition","dataType","physicalName","evidenceCode","evidence"})
    public record TermCandidate(UUID id,UUID dictionaryId,String dictionaryName,String versionLabel,String scope,String code,String name,String definition,String dataType,String physicalName,String evidenceCode,String evidence,
            @Schema(types={"integer","null"},description="Versioned exact-match rule points, not probability or measured accuracy") Integer recommendationScore,
            @Schema(types={"integer","null"}) Integer recommendationRank,
            @Schema(types={"string","null"}) String scoreRule,
            @Schema(types={"string","null"}) String confidenceLabel){}
    @Schema(requiredProperties={"revisionId","lockVersion","fieldPath","displayName","physicalName","dataType"})
    public record SuggestionBasis(UUID revisionId,long lockVersion,String fieldPath,String displayName,String physicalName,String dataType){}
    @Schema(requiredProperties={"items","page","size","hasNext"})
    public record TermCandidatePage(List<TermCandidate> items,int page,int size,boolean hasNext,SuggestionBasis basis){}
    @Schema(requiredProperties={"id","publicationNo","state","revisionId","current","matchesManagementRevision"})
    public record UsagePublication(UUID id,int publicationNo,String state,UUID revisionId,boolean current,boolean matchesManagementRevision){}
    @Schema(requiredProperties={"datasetId","revisionId","revisionNo","title","state","visibility","fieldPath","physicalName","displayName","dataType","providerOrganizationName","revisionUpdatedAt"})
    public record TermFieldUsage(UUID datasetId,UUID revisionId,int revisionNo,String title,String state,String visibility,String fieldPath,String physicalName,String displayName,String dataType,
            String providerOrganizationName,@Schema(types={"string","null"}) String primaryCategoryName,OffsetDateTime revisionUpdatedAt,
            @Schema(types={"object","null"}) UsagePublication latestPublication){}
    @Schema(requiredProperties={"items","page","size","hasNext","revisionScope"})
    public record TermFieldUsagePage(List<TermFieldUsage> items,int page,int size,boolean hasNext,String revisionScope){}
}

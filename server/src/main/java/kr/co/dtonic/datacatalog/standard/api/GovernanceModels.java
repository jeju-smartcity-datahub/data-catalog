package kr.co.dtonic.datacatalog.standard.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

/** Explicit response contracts; database rows and arbitrary metadata are never returned. */
public final class GovernanceModels {
    private GovernanceModels() {}
    @Schema(requiredProperties={"id","parentId","code","name","active","ordinal","lockVersion","usageCount"})
    public record Category(String id, @Schema(types={"string","null"}) String parentId, String code, String name, boolean active, int ordinal, long lockVersion, long usageCount) {}
    public record CategoryInput(UUID parentId, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String code,
            @NotBlank @Size(max=200) String name, boolean active, @Min(0) int ordinal) {}
    @Schema(requiredProperties={"id","scope","organizationId","code","name","sourceUri","versionLabel","sourceChecksum"})
    public record Dictionary(String id, String scope, @Schema(types={"string","null"}) String organizationId, String code, String name,
            @Schema(types={"string","null"}) String sourceUri, String versionLabel, @Schema(types={"string","null"}) String sourceChecksum) {}
    public record DictionaryInput(@NotNull UUID organizationId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String code,
            @NotBlank @Size(max=200) String name, @NotBlank @Size(max=100) String versionLabel) {}
    @Schema(requiredProperties={"id","dictionaryId","kind","code","name","definition","dataType","state","lockVersion","createdBy","updatedBy","submittedBy","decidedBy","decisionReason"})
    public record DictionaryEntry(String id, String dictionaryId, String kind, String code, String name,
            String definition, String dataType, String state, long lockVersion,
            String createdBy, String updatedBy, @Schema(types={"string","null"}) String submittedBy, @Schema(types={"string","null"}) String decidedBy, @Schema(types={"string","null"}) String decisionReason) {}
    public record EntryInput(@NotBlank @Pattern(regexp="WORD|TERM|DOMAIN") String kind,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,100}") String code,
            @NotBlank @Size(max=200) String name, @NotBlank @Size(max=4000) String definition,
            @NotBlank @Pattern(regexp="STRING|INTEGER|NUMBER|BOOLEAN|DATE|DATETIME|OBJECT|ARRAY") String dataType) {}
    public record Decision(@NotBlank @Pattern(regexp="APPROVE|REJECT") String decision, @Size(max=2000) String reason) {}
    @Schema(requiredProperties={"fieldPath","physicalName","displayName","description","dataType","nullable","maxLength","unit","ordinal","publicVisible","approvedTermId","mappingEvidence"})
    public record FieldDefinition(@NotBlank @Size(max=200) String fieldPath,
            @NotBlank @Size(max=200) String physicalName, @NotBlank @Size(max=200) String displayName,
            @NotNull @Size(max=4000) String description,
            @NotBlank @Pattern(regexp="STRING|INTEGER|NUMBER|BOOLEAN|DATE|DATETIME|OBJECT|ARRAY") String dataType,
            boolean nullable, @Schema(types={"integer","null"}) @Min(1) @Max(1000000) Integer maxLength, @Schema(types={"string","null"}) @Size(max=100) String unit,
            @Min(0) int ordinal, boolean publicVisible, @Schema(types={"string","null"},format="uuid") UUID approvedTermId, @Schema(types={"string","null"}) @Size(max=2000) String mappingEvidence) {}
    public record FieldsInput(@NotNull @Size(max=500) List<@NotNull @Valid FieldDefinition> fields) {}
    @Schema(requiredProperties={"revisionId","lockVersion","fields"})
    public record FieldSet(UUID revisionId, long lockVersion, List<FieldDefinition> fields) {}
    public record FieldMapping(@NotBlank @Size(max=200) String fieldPath, @NotNull UUID approvedTermId,
            @NotBlank @Size(max=2000) String mappingEvidence) {}
    public record MappingsInput(@NotNull @Size(max=500) List<@NotNull @Valid FieldMapping> mappings) {}
    @Schema(requiredProperties={"id","requestId","occurredAt","hasQuery","normalizedFilters","resultCount","durationMs","searchScope"})
    public record SearchEvent(String id, String requestId, OffsetDateTime occurredAt, boolean hasQuery,
            java.util.Map<String,List<String>> normalizedFilters, long resultCount, long durationMs, String searchScope) {}
    @Schema(requiredProperties={"searchCount","zeroResultCount","resultCountSum","durationMsSum"})
    public record SearchStatistics(long searchCount, long zeroResultCount, long resultCountSum, long durationMsSum) {}
    @Schema(requiredProperties={"id","organizationId","datasetRevisionId","kind","engineVersion","requestedBy","startedAt","finishedAt","status","fieldCount","sourceHash","errorCode"})
    public record AnalysisRun(String id, String organizationId, @Schema(types={"string","null"}) String datasetRevisionId, String kind, String engineVersion,
            String requestedBy, OffsetDateTime startedAt, OffsetDateTime finishedAt, @Schema(allowableValues={"SUCCEEDED","FAILED"}) String status,
            int fieldCount, String sourceHash, @Schema(types={"string","null"}) String errorCode) {}
}

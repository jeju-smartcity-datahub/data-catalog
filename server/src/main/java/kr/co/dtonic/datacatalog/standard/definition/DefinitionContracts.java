package kr.co.dtonic.datacatalog.standard.definition;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldSet;

public final class DefinitionContracts {
    private DefinitionContracts() {}
    @Schema(requiredProperties={"id","organizationId","datasetId","sourceHash","sourceSize","parserVersion","status","fieldCount","warnings","createdAt","fields","analysisRunId","diagnostics"})
    public record DefinitionImport(UUID id,UUID organizationId,@Schema(types={"string","null"},format="uuid") UUID datasetId,
            String sourceHash,int sourceSize,String parserVersion,String status,int fieldCount,List<String> warnings,
            OffsetDateTime createdAt,List<ExtractedField> fields,UUID analysisRunId,DefinitionDiagnostics diagnostics) {}
    @Schema(requiredProperties={"id","sourcePointer","field","evidenceKind","confidence"})
    public record ExtractedField(UUID id,String sourcePointer,FieldDefinition field,String evidenceKind,
            @Schema(implementation=RuleConfidence.class,nullable=true) RuleConfidence confidence) {
        public ExtractedField(UUID id,String sourcePointer,FieldDefinition field,String evidenceKind) { this(id,sourcePointer,field,evidenceKind,null); }
    }
    public record ApplyDefinition(@NotNull UUID datasetId,@NotNull @Size(min=1,max=500) List<@NotNull UUID> selectedFieldIds) {}
    @Schema(requiredProperties={"applicationId","importId","datasetId","fields"})
    public record ApplicationResult(UUID applicationId,UUID importId,UUID datasetId,FieldSet fields) {}
    @Schema(requiredProperties={"fieldPath","sourcePointer","evidenceKind","explanation","sourceHash","field","confidence"})
    public record AnalysisEvidence(String fieldPath,String sourcePointer,String evidenceKind,String explanation,String sourceHash,FieldDefinition field,
            @Schema(implementation=RuleConfidence.class,nullable=true) RuleConfidence confidence) {
        public AnalysisEvidence(String fieldPath,String sourcePointer,String evidenceKind,String explanation,String sourceHash,FieldDefinition field) {
            this(fieldPath,sourcePointer,evidenceKind,explanation,sourceHash,field,null);
        }
    }
    @Schema(requiredProperties={"code","passed","points"})
    public record RuleCheck(String code,boolean passed,int points) {}
    @Schema(requiredProperties={"ruleVersion","score","maxScore","level","formula","checks","limitation"},
            description="Versioned evidence-rule points. Not probability, measured accuracy, or semantic approval.")
    public record RuleConfidence(String ruleVersion,int score,int maxScore,
            @Schema(allowableValues={"DIRECT_EVIDENCE_COMPLETE","DESCRIPTION_REVIEW_REQUIRED"}) String level,
            String formula,List<RuleCheck> checks,String limitation) {}
    @Schema(requiredProperties={"sourcePointer","reasonCode","message","properties"},
            description="Only generated JSON positions, fixed reason codes/messages and allowlisted property names. No source values.")
    public record UnextractedField(String sourcePointer,String reasonCode,String message,List<String> properties) {}
    @Schema(requiredProperties={"sourcePointer","confidence"})
    public record FieldAssessment(String sourcePointer,RuleConfidence confidence) {}
    @Schema(requiredProperties={"ruleVersion","status","inputFieldCount","generatedFieldCount","unextractedFields","assessments"})
    public record DefinitionDiagnostics(String ruleVersion,
            @Schema(allowableValues={"COMPLETE","FAILED","NOT_RECORDED"}) String status,
            int inputFieldCount,int generatedFieldCount,List<UnextractedField> unextractedFields,List<FieldAssessment> assessments) {
        public static DefinitionDiagnostics notRecorded() { return new DefinitionDiagnostics("NOT_RECORDED","NOT_RECORDED",0,0,List.of(),List.of()); }
    }
}

package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefinitionParserTest {
    private final ObjectMapper json=new ObjectMapper();
    private final DefinitionParser parser=new DefinitionParser(json,Validation.buildDefaultValidatorFactory().getValidator());
    @Test
    void readsExplicitFieldsWithPointersButDoesNotImportClaimedStandardApproval() throws Exception {
        var field=new FieldDefinition("temperature","temperature","온도","설명","NUMBER",true,null,"C",0,true,UUID.randomUUID(),"승인 주장");
        var result=parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(field))));
        assertThat(result.fields()).hasSize(1);assertThat(result.fields().getFirst().sourcePointer()).isEqualTo("/fields/0");
        assertThat(result.fields().getFirst().field().approvedTermId()).isNull();assertThat(result.fields().getFirst().field().mappingEvidence()).isNull();
        assertThat(result.warnings()).containsExactly("IMPORTED_STANDARD_MAPPING_REQUIRES_REVIEW");
        var confidence=result.fields().getFirst().confidence();
        assertThat(confidence.ruleVersion()).isEqualTo(DefinitionParser.RULE_VERSION);
        assertThat(confidence.score()).isEqualTo(100);assertThat(confidence.maxScore()).isEqualTo(100);
        assertThat(confidence.checks()).hasSize(5);assertThat(confidence.checks()).allMatch(c->c.passed()&&c.points()==20);
        assertThat(confidence.level()).isEqualTo("DIRECT_EVIDENCE_COMPLETE");
        assertThat(confidence.limitation()).contains("확률","측정 정확도","사용자 검토");
        assertThat(result.diagnostics().status()).isEqualTo("COMPLETE");
        assertThat(result.diagnostics().assessments().getFirst().confidence()).isEqualTo(confidence);
    }
    @Test
    void rejectsSizeDepthUnknownPropertiesAndDuplicateJsonKeys() {
        assertThatThrownBy(()->parser.parse(new byte[DefinitionParser.MAX_BYTES+1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(("[".repeat(17)+"0"+"]".repeat(17)).getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse("{\"fields\":[],\"secret\":\"value\"}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse("{\"fields\":[],\"fields\":[]}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse("{\"fields\":[]} {}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rejectsNonUtf8AndAcceptsUtf8Bom() throws Exception {
        var field=new FieldDefinition("a","a","표시명","설명","STRING",true,null,null,0,true,null,null);
        String document=json.writeValueAsString(Map.of("fields",List.of(field)));
        assertThatThrownBy(()->parser.parse(document.getBytes(StandardCharsets.UTF_16))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(new byte[]{(byte)0xc3,(byte)0x28})).isInstanceOf(IllegalArgumentException.class);
        assertThat(parser.parse(("\uFEFF"+document).getBytes(StandardCharsets.UTF_8)).fields()).hasSize(1);
    }
    @Test
    void rejectsMissingVisibilityAmbiguousNamesAndMoreThanFiveHundredFields() throws Exception {
        var field=new FieldDefinition("a","a","표시명","설명","STRING",true,null,null,0,true,null,null);
        assertThatThrownBy(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(field,field))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(json.writeValueAsBytes(Map.of("fields",Collections.nCopies(501,field))))).isInstanceOf(IllegalArgumentException.class);
        var tree=json.valueToTree(field);((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("publicVisible");
        assertThatThrownBy(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(tree))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void emptyDescriptionReducesEvidencePointsWithoutInventingConfidenceOrDescription() throws Exception {
        var field=new FieldDefinition("a","a","표시명"," ","STRING",true,null,null,0,true,null,null);
        var result=parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(field))));
        var confidence=result.fields().getFirst().confidence();
        assertThat(confidence.score()).isEqualTo(80);assertThat(confidence.level()).isEqualTo("DESCRIPTION_REVIEW_REQUIRED");
        assertThat(confidence.checks().getLast().code()).isEqualTo("DESCRIPTION_NONEMPTY");
        assertThat(confidence.checks().getLast().passed()).isFalse();assertThat(confidence.checks().getLast().points()).isZero();
        assertThat(confidence.checks().stream().mapToInt(c->c.points()).sum()).isEqualTo(confidence.score());
        assertThat(result.fields().getFirst().field().description()).isEqualTo(" ");
        assertThat(result.warnings()).contains("FIELD_DESCRIPTION_REQUIRES_REVIEW");
    }
    @Test
    void oneInvalidItemKeepsAtomicRejectionAndExplainsEveryUnextractedItemWithoutSourceValues() throws Exception {
        var field=new FieldDefinition("private-identifier","private-physical","표시명","private-description","STRING",true,null,null,0,true,null,null);
        var invalid=json.valueToTree(field).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid).remove("physicalName");
        var failure=catchThrowableOfType(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(field,invalid)))),DefinitionParser.ParseFailure.class);
        assertThat(failure).isNotNull();var diagnostics=failure.diagnostics();
        assertThat(diagnostics.status()).isEqualTo("FAILED");assertThat(diagnostics.inputFieldCount()).isEqualTo(2);
        assertThat(diagnostics.generatedFieldCount()).isZero();assertThat(diagnostics.assessments()).isEmpty();
        assertThat(diagnostics.unextractedFields()).hasSize(2);
        assertThat(diagnostics.unextractedFields().getFirst().sourcePointer()).isEqualTo("/fields/0");
        assertThat(diagnostics.unextractedFields().getFirst().reasonCode()).isEqualTo("DOCUMENT_REJECTED");
        assertThat(diagnostics.unextractedFields().getLast().sourcePointer()).isEqualTo("/fields/1");
        assertThat(diagnostics.unextractedFields().getLast().reasonCode()).isEqualTo("REQUIRED_PROPERTY_MISSING");
        assertThat(diagnostics.unextractedFields().getLast().properties()).containsExactly("physicalName");
        assertThat(json.writeValueAsString(diagnostics)).doesNotContain("private-identifier","private-physical","private-description");
    }
    @Test
    void classifiesTypeConstraintAndDuplicateFailuresUsingOnlyAllowlistedPropertyNames() throws Exception {
        var field=new FieldDefinition("a","a","표시명","설명","STRING",true,null,null,0,true,null,null);
        var type=json.valueToTree(field).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)type).put("nullable","secret-source-value");
        var invalidType=catchThrowableOfType(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(type)))),DefinitionParser.ParseFailure.class);
        assertThat(invalidType.diagnostics().unextractedFields().getFirst().reasonCode()).isEqualTo("INVALID_PROPERTY_TYPE");
        assertThat(invalidType.diagnostics().unextractedFields().getFirst().properties()).containsExactly("nullable");
        var constraint=json.valueToTree(field).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)constraint).put("dataType","secret-invalid-type");
        var invalidConstraint=catchThrowableOfType(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(constraint)))),DefinitionParser.ParseFailure.class);
        assertThat(invalidConstraint.diagnostics().unextractedFields().getFirst().reasonCode()).isEqualTo("INVALID_FIELD_CONSTRAINT");
        assertThat(invalidConstraint.diagnostics().unextractedFields().getFirst().properties()).containsExactly("dataType");
        var duplicate=catchThrowableOfType(()->parser.parse(json.writeValueAsBytes(Map.of("fields",List.of(field,field)))),DefinitionParser.ParseFailure.class);
        assertThat(duplicate.diagnostics().unextractedFields().getLast().reasonCode()).isEqualTo("DUPLICATE_FIELD_IDENTITY");
        assertThat(json.writeValueAsString(invalidType.diagnostics())).doesNotContain("secret-source-value");
        assertThat(json.writeValueAsString(invalidConstraint.diagnostics())).doesNotContain("secret-invalid-type");
    }
    @Test
    void boundsDiagnosticPayloadAndNeverEchoesUnknownNamesOrParserMessages() throws Exception {
        var many=catchThrowableOfType(()->parser.parse(json.writeValueAsBytes(Map.of("fields",Collections.nCopies(500,null)))),DefinitionParser.ParseFailure.class);
        assertThat(many.diagnostics().unextractedFields()).hasSize(500);
        assertThat(json.writeValueAsBytes(many.diagnostics()).length).isLessThan(512*1024);
        var unknown=catchThrowableOfType(()->parser.parse("{\"sensitive-key\":\"sensitive-value\",\"fields\":[]}".getBytes(StandardCharsets.UTF_8)),DefinitionParser.ParseFailure.class);
        assertThat(unknown.diagnostics().unextractedFields().getFirst().reasonCode()).isEqualTo("UNSUPPORTED_PROPERTY");
        assertThat(json.writeValueAsString(unknown.diagnostics())).doesNotContain("sensitive-key","sensitive-value");
        var syntax=catchThrowableOfType(()->parser.parse("{\"fields\": [secret-value]}".getBytes(StandardCharsets.UTF_8)),DefinitionParser.ParseFailure.class);
        assertThat(syntax.diagnostics().unextractedFields().getFirst().reasonCode()).isEqualTo("INVALID_JSON");
        var encoding=catchThrowableOfType(()->parser.parse(new byte[]{(byte)0xc3,(byte)0x28}),DefinitionParser.ParseFailure.class);
        assertThat(encoding.diagnostics().unextractedFields().getFirst().reasonCode()).isEqualTo("INVALID_UTF8");
    }
}

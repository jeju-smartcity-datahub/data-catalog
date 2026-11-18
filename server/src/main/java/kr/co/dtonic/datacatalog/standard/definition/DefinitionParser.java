package kr.co.dtonic.datacatalog.standard.definition;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.*;
import org.springframework.stereotype.Component;

/** Parses and validates the fields declared in a JSON metadata definition. */
@Component
public class DefinitionParser {
    public static final int MAX_BYTES=1_048_576;
    public static final String VERSION="explicit-fields-json-v2";
    public static final String RULE_VERSION="explicit-field-evidence-v1";
    private static final Set<String> ROOT_KEYS=Set.of("fields","revisionId","lockVersion");
    private static final Set<String> FIELD_KEYS=Set.of("fieldPath","physicalName","displayName","description","dataType","nullable","maxLength","unit","ordinal","publicVisible","approvedTermId","mappingEvidence");
    private final ObjectMapper parser;
    private final Validator validator;
    public record ParsedDefinition(List<ExtractedField> fields,List<String> warnings,DefinitionDiagnostics diagnostics) {}
    public static final class ParseFailure extends IllegalArgumentException {
        private final DefinitionDiagnostics diagnostics;
        private ParseFailure(DefinitionDiagnostics diagnostics) { super("Definition input rejected");this.diagnostics=diagnostics; }
        public DefinitionDiagnostics diagnostics() { return diagnostics; }
    }
    private static final class FieldRejected extends RuntimeException {
        final String code;final List<String> properties;
        FieldRejected(String code,List<String> properties) { this.code=code;this.properties=List.copyOf(properties); }
    }
    public DefinitionParser(ObjectMapper json,Validator validator) {
        this.parser=json.copy();this.validator=validator;
        parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(8000).maxNumberLength(12).build());
        parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        parser.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public ParsedDefinition parse(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Definition size limit");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\uFEFF")) text=text.substring(1);
            JsonNode root=parser.readTree(text);
            if(root==null || !root.isObject()) throw documentFailure("INVALID_DOCUMENT","/");
            for(var keys=root.fieldNames();keys.hasNext();) if(!ROOT_KEYS.contains(keys.next())) throw documentFailure("UNSUPPORTED_PROPERTY","/");
            JsonNode fields=root.get("fields");
            if(fields==null || !fields.isArray()) throw documentFailure("INVALID_DOCUMENT","/fields");
            if(fields.isEmpty()) throw documentFailure("EMPTY_FIELDS","/fields");
            if(fields.size()>500) throw documentFailure("FIELD_COUNT_LIMIT","/fields");
            List<ExtractedField> result=new ArrayList<>(); var warnings=new LinkedHashSet<String>();
            var paths=new HashSet<String>();var names=new HashSet<String>();var ordinals=new HashSet<Integer>();
            Map<Integer,UnextractedField> rejected=new HashMap<>();
            for(int index=0;index<fields.size();index++) {
                try {
                    JsonNode value=fields.get(index);
                    if(!value.isObject()) throw new FieldRejected("FIELD_NOT_OBJECT",List.of());
                    for(var keys=value.fieldNames();keys.hasNext();) if(!FIELD_KEYS.contains(keys.next())) throw new FieldRejected("UNSUPPORTED_PROPERTY",List.of());
                    var missing=new ArrayList<String>();var invalidTypes=new ArrayList<String>();
                    for(String required:List.of("fieldPath","physicalName","displayName","description","dataType")) {
                        if(!value.has(required)) missing.add(required);else if(!value.path(required).isTextual()) invalidTypes.add(required);
                    }
                    for(String required:List.of("nullable","publicVisible")) {
                        if(!value.has(required)) missing.add(required);else if(!value.path(required).isBoolean()) invalidTypes.add(required);
                    }
                    if(!value.has("ordinal")) missing.add("ordinal");else if(!value.path("ordinal").isInt()) invalidTypes.add("ordinal");
                    if(!missing.isEmpty()) throw new FieldRejected("REQUIRED_PROPERTY_MISSING",missing);
                    if(value.hasNonNull("maxLength")&&!value.get("maxLength").isInt()) invalidTypes.add("maxLength");
                    if(value.hasNonNull("unit")&&!value.get("unit").isTextual()) invalidTypes.add("unit");
                    if(!invalidTypes.isEmpty()) throw new FieldRejected("INVALID_PROPERTY_TYPE",invalidTypes);
                    if(value.hasNonNull("approvedTermId") || value.hasNonNull("mappingEvidence")) warnings.add("IMPORTED_STANDARD_MAPPING_REQUIRES_REVIEW");
                    // Source files cannot assert an approved mapping. Preserve only reviewed field definitions.
                    var field=new FieldDefinition(value.get("fieldPath").textValue(),value.get("physicalName").textValue(),value.get("displayName").textValue(),
                            value.get("description").textValue(),value.get("dataType").textValue(),value.get("nullable").booleanValue(),
                            value.hasNonNull("maxLength")?value.get("maxLength").intValue():null,value.hasNonNull("unit")?value.get("unit").textValue():null,
                            value.get("ordinal").intValue(),value.get("publicVisible").booleanValue(),null,null);
                    var violations=validator.validate(field);
                    if(!violations.isEmpty()) throw new FieldRejected("INVALID_FIELD_CONSTRAINT",violations.stream()
                            .map(v->v.getPropertyPath().toString()).filter(FIELD_KEYS::contains).distinct().sorted().toList());
                    if(paths.contains(field.fieldPath()) || names.contains(field.physicalName()) || ordinals.contains(field.ordinal()))
                        throw new FieldRejected("DUPLICATE_FIELD_IDENTITY",List.of("fieldPath","physicalName","ordinal"));
                    paths.add(field.fieldPath());names.add(field.physicalName());ordinals.add(field.ordinal());
                    result.add(new ExtractedField(UUID.randomUUID(),"/fields/"+index,field,"EXPLICIT_JSON_FIELD",confidence(field)));
                    if(field.description().isBlank()) warnings.add("FIELD_DESCRIPTION_REQUIRES_REVIEW");
                } catch(FieldRejected invalid) {
                    rejected.put(index,issue("/fields/"+index,invalid.code,invalid.properties));
                }
            }
            if(!rejected.isEmpty()) {
                var unextracted=new ArrayList<UnextractedField>();
                for(int index=0;index<fields.size();index++) unextracted.add(rejected.getOrDefault(index,issue("/fields/"+index,"DOCUMENT_REJECTED",List.of())));
                // Input remains atomic: no subset or unvalidated field becomes an applicable import.
                throw new ParseFailure(new DefinitionDiagnostics(RULE_VERSION,"FAILED",fields.size(),0,List.copyOf(unextracted),List.of()));
            }
            var assessments=result.stream().map(f->new FieldAssessment(f.sourcePointer(),f.confidence())).toList();
            return new ParsedDefinition(List.copyOf(result),List.copyOf(warnings),new DefinitionDiagnostics(RULE_VERSION,"COMPLETE",fields.size(),result.size(),List.of(),assessments));
        } catch(java.nio.charset.CharacterCodingException invalid) { throw documentFailure("INVALID_UTF8","/"); }
        catch(java.io.IOException invalid) { throw documentFailure("INVALID_JSON","/"); }
    }
    private static RuleConfidence confidence(FieldDefinition field) {
        boolean description=!field.description().isBlank();
        var checks=List.of(new RuleCheck("SOURCE_POINTER_VALIDATED",true,20),new RuleCheck("IDENTITY_EXPLICIT_VALIDATED",true,20),
                new RuleCheck("TYPE_EXPLICIT_VALIDATED",true,20),new RuleCheck("CONSTRAINTS_VALIDATED",true,20),new RuleCheck("DESCRIPTION_NONEMPTY",description,description?20:0));
        return new RuleConfidence(RULE_VERSION,checks.stream().mapToInt(RuleCheck::points).sum(),100,
                description?"DIRECT_EVIDENCE_COMPLETE":"DESCRIPTION_REVIEW_REQUIRED","각 확인 규칙 충족 시 20점, 미충족 시 0점을 합산합니다.",checks,
                "명시적 원문 위치·형식 검증의 근거 점수입니다. 확률·측정 정확도나 의미 적합성을 뜻하지 않으며 사용자 검토가 필요합니다.");
    }
    private static ParseFailure documentFailure(String code,String pointer) {
        return new ParseFailure(new DefinitionDiagnostics(RULE_VERSION,"FAILED",0,0,List.of(issue(pointer,code,List.of())),List.of()));
    }
    private static UnextractedField issue(String pointer,String code,List<String> properties) {
        String message=switch(code) {
            case "DOCUMENT_REJECTED" -> "다른 항목의 오류로 정의서 전체가 보류되어 이 항목도 생성하지 않았습니다.";
            case "FIELD_NOT_OBJECT" -> "항목 정의는 JSON 객체여야 합니다.";
            case "UNSUPPORTED_PROPERTY" -> "지원하지 않는 속성이 있어 생성하지 않았습니다. 지원 정의서 형식을 확인하세요.";
            case "REQUIRED_PROPERTY_MISSING" -> "필수 속성이 없어 생성하지 않았습니다. 표시된 속성을 입력하세요.";
            case "INVALID_PROPERTY_TYPE" -> "속성의 JSON 자료형이 맞지 않아 생성하지 않았습니다.";
            case "INVALID_FIELD_CONSTRAINT" -> "항목 값이 길이·허용 자료형·범위 검증을 통과하지 못했습니다.";
            case "DUPLICATE_FIELD_IDENTITY" -> "필드 경로·물리명 또는 순서가 중복되어 생성하지 않았습니다.";
            case "INVALID_UTF8" -> "UTF-8로 읽을 수 없어 생성하지 않았습니다.";
            case "INVALID_JSON" -> "JSON 문법·중복 키 또는 중첩 제한을 확인하세요. 정의서를 생성하지 않았습니다.";
            case "EMPTY_FIELDS" -> "명시적 fields 항목이 비어 있어 생성하지 않았습니다.";
            case "FIELD_COUNT_LIMIT" -> "한 번에 500개 이하의 항목만 분석할 수 있습니다.";
            default -> "fields 배열을 포함한 지원 JSON 정의서가 아니어서 생성하지 않았습니다.";
        };
        return new UnextractedField(pointer,code,message,List.copyOf(properties));
    }
}

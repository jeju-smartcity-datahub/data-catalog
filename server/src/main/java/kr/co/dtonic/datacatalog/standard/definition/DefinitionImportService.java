package kr.co.dtonic.datacatalog.standard.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldsInput;
import kr.co.dtonic.datacatalog.standard.application.DraftFieldsService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.*;

@Service
public class DefinitionImportService {
    private final DefinitionImportMapper mapper;
    private final DefinitionParser parser;
    private final GovernanceSupport support;
    private final MetadataWorkflowService metadata;
    private final DraftFieldsService fields;
    private final ObjectMapper json;
    private final DefinitionFailureRecorder failures;
    public DefinitionImportService(DefinitionImportMapper mapper,DefinitionParser parser,GovernanceSupport support,MetadataWorkflowService metadata,DraftFieldsService fields,ObjectMapper json,DefinitionFailureRecorder failures) {
        this.mapper=mapper;this.parser=parser;this.support=support;this.metadata=metadata;this.fields=fields;this.json=json;this.failures=failures;
    }
    @Transactional
    public DefinitionImport create(UUID organization,UUID dataset,byte[] bytes,Authentication auth,String requestId) {
        var actor=support.scoped(auth,organization,"ORG_EDITOR");
        UUID revision=null;
        if(dataset!=null) {
            var draft=metadata.getDraft(dataset,auth);
            if(!draft.ownerOrganizationId().equals(organization)) throw missing();
            revision=draft.revisionId();
        }
        // Oversized/empty input is rejected before accepting an analysis attempt, including direct service callers.
        if(bytes==null||bytes.length==0||bytes.length>DefinitionParser.MAX_BYTES)throw new IllegalArgumentException("Definition file size limit");
        String sourceHash=hash(bytes);var startedAt=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        DefinitionParser.ParsedDefinition parsed;
        try { parsed=parser.parse(bytes); }
        catch(DefinitionParser.ParseFailure invalidDefinition) {
            UUID failed=failures.record(organization,revision,sourceHash,startedAt,auth,requestId,invalidDefinition.diagnostics());
            throw new DefinitionRejectedException(failed,invalidDefinition.diagnostics());
        }
        UUID id=UUID.randomUUID();UUID analysis=UUID.randomUUID();
        mapper.insertImport(params("id",id.toString(),"organization",organization.toString(),"dataset",dataset==null?null:dataset.toString(),"actor",actor.userId().toString(),
                "hash",sourceHash,"size",bytes.length,"parser",DefinitionParser.VERSION,"count",parsed.fields().size(),"warnings",encode(parsed.warnings())));
        for(int index=0;index<parsed.fields().size();index++) {
            var field=parsed.fields().get(index);
            mapper.insertField(params("id",field.id().toString(),"importId",id.toString(),"ordinal",index,"field",encode(field.field()),"pointer",field.sourcePointer()));
        }
        var evidence=parsed.fields().stream().map(f->new AnalysisEvidence(f.field().fieldPath(),f.sourcePointer(),f.evidenceKind(),"명시적 JSON 필드 정의를 검증해 읽었습니다.",sourceHash,f.field(),f.confidence())).toList();
        mapper.insertAnalysis(params("id",analysis.toString(),"organization",organization.toString(),"revision",revision==null?null:revision.toString(),"importId",id.toString(),
                "parser",DefinitionParser.VERSION,"actor",actor.userId().toString(),"count",parsed.fields().size(),"hash",sourceHash,"evidence",encode(evidence),"diagnostics",encode(parsed.diagnostics())));
        support.audit(actor.userId(),organization,"DEFINITION_IMPORTED","DEFINITION_IMPORT",id.toString(),requestId);
        return get(id,auth);
    }
    @Transactional(readOnly=true)
    public DefinitionImport get(UUID id,Authentication auth) {
        var row=mapper.find(id.toString()); if(row==null) throw missing();
        support.scoped(auth,UUID.fromString(row.organizationId()),"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");
        var diagnostics=decodeDiagnostics(row.diagnostics());
        var scores=new HashMap<String,RuleConfidence>();for(var assessment:diagnostics.assessments())scores.put(assessment.sourcePointer(),assessment.confidence());
        var extracted=mapper.fields(id.toString()).stream().map(f->new ExtractedField(UUID.fromString(f.id()),f.sourcePointer(),decode(f.fieldDefinition(),FieldDefinition.class),"EXPLICIT_JSON_FIELD",scores.get(f.sourcePointer()))).toList();
        return new DefinitionImport(id,UUID.fromString(row.organizationId()),row.datasetId()==null?null:UUID.fromString(row.datasetId()),row.sourceHash(),row.sourceSize(),row.parserVersion(),row.status(),row.fieldCount(),
                decodeStrings(row.warnings()),row.createdAt(),extracted,UUID.fromString(row.analysisRunId()),diagnostics);
    }
    @Transactional(readOnly=true)
    public List<AnalysisEvidence> evidence(UUID id,Authentication auth) {
        var source=get(id,auth);
        return source.fields().stream().map(f->new AnalysisEvidence(f.field().fieldPath(),f.sourcePointer(),f.evidenceKind(),"명시적 JSON 필드 정의를 검증해 읽었습니다.",source.sourceHash(),f.field(),f.confidence())).toList();
    }
    @Transactional(readOnly=true)
    public DefinitionDiagnostics analysisDiagnostics(UUID id,Authentication auth) {
        var row=mapper.analysisDiagnostics(id.toString());if(row==null)throw missing();
        support.scoped(auth,UUID.fromString(row.organizationId()),"ORG_AUDITOR");
        return decodeDiagnostics(row.diagnostics());
    }
    @Transactional
    public ApplicationResult apply(UUID id,ApplyDefinition body,String ifMatch,String key,Authentication auth,String requestId) {
        var source=get(id,auth);var actor=support.scoped(auth,source.organizationId(),"ORG_EDITOR");
        var target=metadata.getDraft(body.datasetId(),auth);
        if(!target.ownerOrganizationId().equals(source.organizationId()) || source.datasetId()!=null&&!source.datasetId().equals(body.datasetId())) throw missing();
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException("Idempotency-Key required (8..128 safe characters)");
        if(ifMatch==null || ifMatch.isBlank()) GovernanceSupport.match(ifMatch,"dataset",body.datasetId().toString(),target.lockVersion());
        if(body.selectedFieldIds()==null || body.selectedFieldIds().isEmpty() || body.selectedFieldIds().size()>500) throw new IllegalArgumentException("Field selection required");
        var selected=new HashSet<>(body.selectedFieldIds());
        if(selected.contains(null)||selected.size()!=body.selectedFieldIds().size()) throw new IllegalArgumentException("Duplicate or null field selection");
        String keyDigest=hash(key.getBytes(StandardCharsets.UTF_8));
        String requestHash=hash(encode(List.of(id,body.datasetId(),selected.stream().map(UUID::toString).sorted().toList(),ifMatch)).getBytes(StandardCharsets.UTF_8));
        mapper.lockApplication(actor.userId()+":"+keyDigest);
        var previous=mapper.application(params("actor",actor.userId().toString(),"key",keyDigest));
        if(previous!=null) {
            if(!previous.requestHash().equals(requestHash)) throw conflict("IDEMPOTENCY_CONFLICT","같은 멱등 키가 다른 요청에 사용되었습니다.");
            return decode(previous.result(),ApplicationResult.class);
        }
        var chosen=source.fields().stream().filter(f->selected.contains(f.id())).map(ExtractedField::field).toList();
        if(chosen.size()!=selected.size()) throw new IllegalArgumentException("Unknown imported field");
        var saved=fields.replace(body.datasetId(),ifMatch,new FieldsInput(chosen),auth,requestId);
        var result=new ApplicationResult(UUID.randomUUID(),id,body.datasetId(),saved);
        mapper.insertApplication(params("id",result.applicationId().toString(),"importId",id.toString(),"dataset",body.datasetId().toString(),"revision",saved.revisionId().toString(),
                "actor",actor.userId().toString(),"key",keyDigest,"hash",requestHash,"selected",encode(body.selectedFieldIds()),"result",encode(result)));
        support.audit(actor.userId(),source.organizationId(),"DEFINITION_APPLIED","DEFINITION_IMPORT",id.toString(),requestId);
        return result;
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalArgumentException("Invalid definition"); } }
    private <T>T decode(String value,Class<T> type) { try { return json.readValue(value,type); } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid persisted definition",e); } }
    private List<String> decodeStrings(String value) { try { return json.readValue(value,new TypeReference<>() {}); } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid persisted warnings",e); } }
    private DefinitionDiagnostics decodeDiagnostics(String value) {
        try {
            var tree=json.readTree(value);
            if(tree.isObject()&&tree.isEmpty())return DefinitionDiagnostics.notRecorded();
            return json.treeToValue(tree,DefinitionDiagnostics.class);
        } catch(JsonProcessingException invalid) { throw new IllegalStateException("Invalid persisted definition diagnostics"); }
    }
    private String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}

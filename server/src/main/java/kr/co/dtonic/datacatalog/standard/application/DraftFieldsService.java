package kr.co.dtonic.datacatalog.standard.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.MetadataDraft;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.persistence.GovernanceMapper;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.AnalysisEvidence;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.*;

@Service
public class DraftFieldsService {
    private final MetadataWorkflowService metadata;
    private final DictionaryService dictionaries;
    private final GovernanceSupport support;
    private final GovernanceMapper mapper;
    private final ObjectMapper json;
    public DraftFieldsService(MetadataWorkflowService metadata,DictionaryService dictionaries,GovernanceSupport support,GovernanceMapper mapper,ObjectMapper json) {
        this.metadata=metadata;this.dictionaries=dictionaries;this.support=support;this.mapper=mapper;this.json=json;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public FieldSet get(UUID id,Authentication auth) {
        var draft=metadata.getDraft(id,auth); return new FieldSet(draft.revisionId(),draft.lockVersion(),read(id,auth));
    }
    @Transactional
    public FieldSet replace(UUID id,String ifMatch,FieldsInput body,Authentication auth,String requestId) {
        var draft=metadata.getDraft(id,auth); validate(body.fields(),draft.ownerOrganizationId(),auth);
        List<Map<String,Object>> fields=json.convertValue(body.fields(),new TypeReference<>() {});
        var updated=metadata.replaceFields(id,ifMatch,fields,auth,requestId);
        if(body.fields().stream().anyMatch(f->f.approvedTermId()!=null)) record(updated,body.fields(),"FIELD_MAPPING",auth);
        return new FieldSet(updated.revisionId(),updated.lockVersion(),List.copyOf(body.fields()));
    }
    @Transactional
    public FieldSet map(UUID id,String ifMatch,MappingsInput body,Authentication auth,String requestId) {
        var existing=read(id,auth); var mappings=new HashMap<String,FieldMapping>();
        for(var mapping:body.mappings()) if(mappings.put(mapping.fieldPath(),mapping)!=null) throw new IllegalArgumentException("Duplicate field mapping");
        if(!existing.stream().map(FieldDefinition::fieldPath).collect(java.util.stream.Collectors.toSet()).containsAll(mappings.keySet()))
            throw new IllegalArgumentException("Unknown field path");
        var fields=existing.stream().map(f->{
            var m=mappings.get(f.fieldPath());
            return new FieldDefinition(f.fieldPath(),f.physicalName(),f.displayName(),f.description(),f.dataType(),f.nullable(),f.maxLength(),f.unit(),f.ordinal(),f.publicVisible(),
                    m==null?null:m.approvedTermId(),m==null?null:m.mappingEvidence());
        }).toList();
        return replace(id,ifMatch,new FieldsInput(fields),auth,requestId);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] export(UUID id,String format,Authentication auth) {
        var draft=metadata.getDraft(id,auth); var fields=read(id,auth);
        String document;
        if(format.equals("csv")) document=DefinitionExporter.csv(fields);
        else if(format.equals("json")) document=encode(new FieldSet(draft.revisionId(),draft.lockVersion(),fields));
        else throw new IllegalArgumentException("Unsupported export format");
        record(draft,fields,"DEFINITION_EXPORT",auth);
        return document.getBytes(StandardCharsets.UTF_8);
    }
    private List<FieldDefinition> read(UUID id,Authentication auth) {
        Object fields=metadata.getDraftMetadata(id,auth).get("fields");
        return fields==null?List.of():json.convertValue(fields,new TypeReference<>() {});
    }
    private void validate(List<FieldDefinition> fields,UUID organization,Authentication auth) {
        if(fields==null || fields.size()>500) throw new IllegalArgumentException("Field count");
        var paths=new HashSet<String>(); var names=new HashSet<String>(); var ordinals=new HashSet<Integer>();
        for(var field:fields) {
            if(field==null) throw new IllegalArgumentException("Null field");
            if(!paths.add(field.fieldPath()) || !names.add(field.physicalName()) || !ordinals.add(field.ordinal())) throw new IllegalArgumentException("Duplicate field");
            if(field.approvedTermId()!=null) {
                if(field.mappingEvidence()==null || field.mappingEvidence().isBlank()) throw new IllegalArgumentException("Mapping evidence required");
                var term=dictionaries.approvedTerm(field.approvedTermId(),organization,auth);
                if(!term.dataType().equals(field.dataType())) throw conflict("TERM_TYPE_MISMATCH","필드와 표준 용어의 타입이 다릅니다.");
            } else if(field.mappingEvidence()!=null && !field.mappingEvidence().isBlank()) throw new IllegalArgumentException("Term required for evidence");
        }
    }
    private void record(MetadataDraft draft,List<FieldDefinition> fields,String kind,Authentication auth) {
        String sourceHash=hash(encode(fields));
        var evidence=new ArrayList<AnalysisEvidence>();
        for(int index=0;index<fields.size();index++) {
            var field=fields.get(index);
            evidence.add(new AnalysisEvidence(field.fieldPath(),"/metadata/fields/"+index,kind,
                    field.mappingEvidence()==null?"현재 관리 버전의 명시적 필드 snapshot":field.mappingEvidence(),sourceHash,field));
        }
        mapper.analysis(params("id",UUID.randomUUID().toString(),"organization",draft.ownerOrganizationId().toString(),"revision",draft.revisionId().toString(),
                "kind",kind,"actor",support.reader(auth).userId().toString(),"count",fields.size(),"hash",sourceHash,"evidence",encode(evidence)));
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalArgumentException("Invalid fields",e); } }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

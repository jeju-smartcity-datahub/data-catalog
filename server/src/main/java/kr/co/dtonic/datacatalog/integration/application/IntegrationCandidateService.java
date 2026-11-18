package kr.co.dtonic.datacatalog.integration.application;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.*;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationCandidateRepository;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationCandidateRepository.Candidate;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper.args;

@Service
public class IntegrationCandidateService {
    private final IntegrationCandidateRepository repository;
    private final IntegrationService connections;
    private final IntegrationMapper mapper;
    private final MetadataWorkflowService metadata;
    private final WorkflowAccess access;
    private final ObjectMapper json;
    public IntegrationCandidateService(IntegrationCandidateRepository repository,IntegrationService connections,
            IntegrationMapper mapper,MetadataWorkflowService metadata,WorkflowAccess access,ObjectMapper json) {
        this.repository=repository;this.connections=connections;this.mapper=mapper;this.metadata=metadata;this.access=access;this.json=json;
    }
    @Transactional(readOnly=true)
    public CandidatePage list(UUID connection,int page,int size,Authentication auth) {
        connections.get(auth,connection);
        return new CandidatePage(repository.list(connection,page,size).stream().map(this::summary).toList(),page,size,repository.count(connection));
    }
    @Transactional(readOnly=true)
    public CandidateDetail detail(UUID id,Authentication auth) {
        var candidate=authorized(id,auth,false);
        MetadataDraft draft=candidate.datasetId()==null?null:metadata.getDraft(candidate.datasetId(),auth);
        var changes=plan(candidate,draft,draft==null?Map.of():metadata.getDraftMetadata(draft.id(),auth)).changes();
        return new CandidateDetail(summary(candidate),candidate.description(),candidate.sourceHash(),etag(candidate),draft==null?null:targetEtag(draft),
                draft==null?null:draft.state(),changes,changes.stream().anyMatch(SourceFieldChange::conflict),read(candidate.sourceDetails()));
    }
    record PublicationContent(String title,String description,MetadataDetails details) {}
    PublicationContent publicationContent(UUID id,Authentication auth) {
        var candidate=authorized(id,auth,false);
        var draft=candidate.datasetId()==null?null:metadata.getDraft(candidate.datasetId(),auth);
        var planned=plan(candidate,draft,draft==null?Map.of():metadata.getDraftMetadata(draft.id(),auth));
        return new PublicationContent(planned.title(),planned.description(),planned.details());
    }

    @Transactional
    public CandidateApplication apply(UUID id,ApplyCandidate input,String ifMatch,String key,Authentication auth,String requestId) {
        var candidate=authorized(id,auth,true);
        var connection=connections.get(auth,candidate.connectionId());
        var principal=access.require(auth,connection.organizationId(),"ORG_EDITOR");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw fail(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","멱등 키가 필요합니다.");
        String digest=IntegrationService.digest(key),requestHash=IntegrationService.digest(Objects.toString(ifMatch,"")+"|"+Objects.toString(input.targetEtag(),""));
        var previous=repository.application(id,principal.userId(),digest);
        if(previous!=null) {
            if(!previous.requestHash().equals(requestHash))throw fail(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","동일 멱등 키의 요청 내용이 다릅니다.");
            return previous.application();
        }
        if(ifMatch==null)throw fail(HttpStatus.PRECONDITION_REQUIRED,"IF_MATCH_REQUIRED","수집 후보의 현재 ETag가 필요합니다.");
        if(!etag(candidate).equals(ifMatch))throw fail(HttpStatus.PRECONDITION_FAILED,"STALE_CANDIDATE","수집 후보가 변경되었습니다. 비교 내용을 다시 확인하세요.");
        MetadataDraft draft;
        if(candidate.datasetId()==null) {
            if(input.targetEtag()!=null)throw fail(HttpStatus.BAD_REQUEST,"TARGET_NOT_LINKED","아직 메타데이터와 연결되지 않은 후보입니다.");
            var plan=plan(candidate,null,Map.of());
            draft=metadata.create(new CreateDataset(connection.organizationId(),"source-"+candidate.id(),candidate.title(),candidate.description(),
                    connection.organizationId(),null,"PRIVATE",null,null,null,plan.details()),"source-create:"+candidate.id(),auth,requestId);
            if(!plan.hubMetadata().isEmpty())draft=metadata.replaceImportedMetadata(draft.id(),targetEtag(draft),plan.fields(),plan.hubMetadata(),auth,requestId);
        } else {
            draft=metadata.getDraft(candidate.datasetId(),auth);
            if(input.targetEtag()==null)throw fail(HttpStatus.PRECONDITION_REQUIRED,"TARGET_ETAG_REQUIRED","대상 메타데이터의 현재 ETag가 필요합니다.");
            if(!targetEtag(draft).equals(input.targetEtag()))throw fail(HttpStatus.PRECONDITION_FAILED,"STALE_DATASET","대상 메타데이터가 변경되었습니다. 비교 내용을 다시 확인하세요.");
            draft=metadata.lockForImport(draft.id(),input.targetEtag(),auth);
            var plan=plan(candidate,draft,metadata.getDraftMetadata(draft.id(),auth));
            if(plan.changes().stream().anyMatch(SourceFieldChange::conflict))throw fail(HttpStatus.CONFLICT,"SOURCE_MANUAL_CONFLICT","허브의 변경과 수동 편집이 충돌합니다. 메타데이터에서 해당 필드를 확인하세요.");
            if(plan.changed()) {
                if(draft.state().equals("IN_REVIEW"))throw fail(HttpStatus.CONFLICT,"TARGET_IN_REVIEW","검토 중인 메타데이터에는 적용할 수 없습니다. 검토를 철회한 뒤 다시 확인하세요.");
                if(!draft.state().equals("DRAFT"))draft=metadata.newDraft(draft.id(),targetEtag(draft),"source-copy:"+candidate.id()+":"+candidate.revisionNo(),auth,requestId);
                draft=metadata.edit(draft.id(),new EditDraft(plan.title(),plan.description(),draft.providerOrganizationId(),draft.primaryCategoryId(),draft.visibility(),
                        null,null,null,plan.details()),targetEtag(draft),auth,requestId);
                if(!plan.hubMetadata().isEmpty())draft=metadata.replaceImportedMetadata(draft.id(),targetEtag(draft),plan.fields(),plan.hubMetadata(),auth,requestId);
            }
        }
        repository.link(candidate,draft.id());
        mapper.audit(args("event",UUID.randomUUID().toString(),"id",candidate.connectionId().toString(),"actor",principal.userId().toString(),
                "action","CANDIDATE_APPLIED","requestId",requestId,"version",connection.lockVersion()));
        return repository.record(candidate,draft.id(),draft.revisionId(),draft.lockVersion(),principal.userId(),requestId,digest,requestHash);
    }
    private Candidate authorized(UUID id,Authentication auth,boolean lock) {
        access.principal(auth);var candidate=repository.get(id,lock);
        if(candidate==null)throw fail(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND","수집 후보를 찾을 수 없습니다.");
        connections.get(auth,candidate.connectionId());return candidate;
    }
    private CandidateSummary summary(Candidate candidate) {
        return new CandidateSummary(candidate.id(),candidate.connectionId(),candidate.externalId(),candidate.title(),candidate.revisionNo(),
                candidate.datasetId()==null?"NEW":candidate.sourceHash().equals(candidate.appliedHash())?"APPLIED":"UPDATED",candidate.datasetId(),candidate.updatedAt());
    }
    private record ImportPlan(List<SourceFieldChange> changes,String title,String description,MetadataDetails details,
            List<Map<String,Object>> fields,Map<String,Object> hubMetadata,boolean changed) {}
    private ImportPlan plan(Candidate candidate,MetadataDraft draft,Map<String,Object> stored) {
        var source=importContent(read(candidate.sourceDetails()));var previous=importContent(read(candidate.appliedDetails()));
        var merged=new TreeMap<String,Object>(previous);merged.putAll(source);
        var values=new LinkedHashMap<String,Object>();values.put("title",candidate.title());values.put("description",candidate.description());values.putAll(mapped(source));
        var baseline=new LinkedHashMap<String,Object>();baseline.put("title",candidate.appliedTitle());baseline.put("description",candidate.appliedDescription());baseline.putAll(mapped(previous));
        var current=new LinkedHashMap<String,Object>();current.put("title",draft==null?null:draft.title());current.put("description",draft==null?null:draft.description());
        var details=draft==null?MetadataDetails.empty():draft.details();
        current.put("details.department",details.department());current.put("details.license",details.license());current.put("details.updateFrequency",details.updateFrequency());
        current.put("details.spatialCoverage",details.spatialCoverage());current.put("details.keywords",details.keywords());
        if(source.containsKey("fields")) {
            values.put("fields",NormalizedHubMetadata.catalogFields(source.get("fields")));
            baseline.put("fields",previous.containsKey("fields")?NormalizedHubMetadata.catalogFields(previous.get("fields")):List.of());
            current.put("fields",stored.getOrDefault("fields",List.of()));
        }
        if(!merged.isEmpty()) {
            values.put("hubMetadata",merged);baseline.put("hubMetadata",previous);current.put("hubMetadata",stored.getOrDefault("hubMetadata",Map.of()));
        }
        baseline.putIfAbsent("details.keywords",List.of());
        var changes=new ArrayList<SourceFieldChange>();var selected=new HashMap<String,Object>(current);boolean changed=false;
        for(var entry:values.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            var change=change(key,display(baseline.get(key)),display(current.get(key)),display(value),draft==null);changes.add(change);
            if(change.sourceChanged()) { selected.put(key,value);if(!Objects.equals(display(current.get(key)),display(value)))changed=true; }
        }
        var resultDetails=new MetadataDetails((String)selected.get("details.department"),(String)selected.get("details.license"),(String)selected.get("details.updateFrequency"),
                (String)selected.get("details.spatialCoverage"),details.temporalStart(),details.temporalEnd(),strings(selected.get("details.keywords")));
        List<Map<String,Object>> fields=null;
        if(values.containsKey("fields")&&!Objects.equals(display(current.get("fields")),display(selected.get("fields"))))
            fields=json.convertValue(selected.get("fields"),new TypeReference<>() {});
        Map<String,Object> hub=json.convertValue(selected.getOrDefault("hubMetadata",Map.of()),new TypeReference<>() {});
        return new ImportPlan(List.copyOf(changes),(String)selected.get("title"),(String)selected.get("description"),resultDetails,fields,hub,changed);
    }
    private Map<String,Object> mapped(Map<String,Object> source) {
        var result=new LinkedHashMap<String,Object>();
        for(var mapping:Map.of("license","details.license","department","details.department","updateInterval","details.updateFrequency").entrySet()) {
            Object value=source.get(mapping.getKey());int max=mapping.getKey().equals("license")?500:mapping.getKey().equals("department")?200:100;
            if(value instanceof String text&&text.length()<=max&&!text.codePoints().anyMatch(Character::isISOControl))result.put(mapping.getValue(),text.isBlank()?null:text);
        }
        if(source.get("targetRegions") instanceof List<?> regions) {
            String text=String.join(", ",strings(regions));if(text.length()<=500&&!text.codePoints().anyMatch(Character::isISOControl))result.put("details.spatialCoverage",text.isBlank()?null:text);
        }
        if(source.get("keywords") instanceof List<?> words&&words.size()<=20&&words.stream().allMatch(w->w instanceof String s&&!s.isBlank()&&s.length()<=100&&!s.codePoints().anyMatch(Character::isISOControl)))
            result.put("details.keywords",words);
        return result;
    }
    private Map<String,Object> importContent(Map<String,Object> source) {
        var result=new TreeMap<String,Object>(source);
        // Retained on the collection candidate for source audit, not imported as editable catalogue content.
        if(result.containsKey("sourceEntityId"))result.remove("sourceModifiedAt");
        return result;
    }
    private List<String> strings(Object value) { return value==null?List.of():json.convertValue(value,new TypeReference<>() {}); }
    private Map<String,Object> read(String value) {
        if(value==null)return Map.of();try { return json.readValue(value,new TypeReference<>() {}); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored candidate metadata"); }
    }
    private String display(Object value) { return value==null?null:value instanceof String text?text:NormalizedHubMetadata.encode(value); }
    private SourceFieldChange change(String field,String previous,String current,String source,boolean first) {
        boolean sourceChanged=first||!Objects.equals(previous,source),manual=!first&&!Objects.equals(previous,current);
        boolean conflict=sourceChanged&&manual&&!Objects.equals(current,source);
        return new SourceFieldChange(field,previous,current,source,sourceChanged,manual,conflict,conflict?"CONFLICT":sourceChanged?"APPLY_SOURCE":"PRESERVE_CURRENT");
    }
    private String etag(Candidate candidate) { return "\"candidate:"+candidate.id()+":"+candidate.revisionNo()+":"+candidate.applicationVersion()+"\""; }
    private String targetEtag(MetadataDraft draft) { return "\"dataset:"+draft.id()+":"+draft.lockVersion()+"\""; }
    private static DomainException fail(HttpStatus status,String code,String message) { return new DomainException(status,code,message); }
}

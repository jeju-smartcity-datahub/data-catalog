package kr.co.dtonic.datacatalog.standard.application;

import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.persistence.GovernanceMapper;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipService;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.*;

@Service
public class DictionaryService extends EgovAbstractServiceImpl {
    private final GovernanceMapper mapper;
    private final GovernanceSupport support;
    private final DictionaryRelationshipService relationships;
    private final kr.co.dtonic.datacatalog.standard.search.DictionaryEntrySearch search;
    public DictionaryService(GovernanceMapper mapper,GovernanceSupport support,DictionaryRelationshipService relationships,kr.co.dtonic.datacatalog.standard.search.DictionaryEntrySearch search) {
        this.mapper=mapper; this.support=support; this.relationships=relationships;this.search=search;
    }
    @Transactional(readOnly=true)
    public List<Dictionary> list(UUID organization,Authentication auth) {
        support.reader(auth);
        if(organization!=null) support.scoped(auth,organization,"ORG_EDITOR","ORG_REVIEWER","ORG_AUDITOR","ORG_PUBLISHER");
        return mapper.dictionaries(params("organization",organization==null?null:organization.toString()));
    }
    @Transactional
    public Dictionary create(DictionaryInput body,Authentication auth,String requestId) {
        var actor=support.scoped(auth,body.organizationId(),"ORG_EDITOR"); String id=UUID.randomUUID().toString();
        try { mapper.insertDictionary(params("id",id,"organization",body.organizationId().toString(),"code",body.code(),"name",body.name().strip(),"version",body.versionLabel())); }
        catch(DataIntegrityViolationException e) { throw conflict("DICTIONARY_CONFLICT","기관과 사전 코드·버전을 확인하세요."); }
        support.audit(actor.userId(),body.organizationId(),"DICTIONARY_CREATED","DICTIONARY",id,requestId);
        return mapper.dictionary(id);
    }
    @Transactional(readOnly=true)
    public List<DictionaryEntry> entries(UUID dictionary,String kind,int page,int size,Authentication auth) {
        return entries(dictionary,kind,null,null,page,size,auth);
    }
    @Transactional(readOnly=true)
    public List<DictionaryEntry> entries(UUID dictionary,String kind,String query,String state,int page,int size,Authentication auth){return search.search(dictionary,kind,query,state,page,size,auth);}
    @Transactional(readOnly=true)
    public DictionaryEntry get(UUID id,Authentication auth) {
        var entry=required(id);var dictionary=readDictionary(UUID.fromString(entry.dictionaryId()),auth);
        if(dictionary.scope().equals("PUBLIC")&&!entry.state().equals("APPROVED")) throw missing();
        return entry;
    }
    @Transactional
    public DictionaryEntry createEntry(UUID dictionaryId,EntryInput body,Authentication auth,String requestId) {
        var dictionary=editableDictionary(dictionaryId,auth,"ORG_EDITOR"); var actor=support.reader(auth); String id=UUID.randomUUID().toString();
        var p=params("id",id,"dictionary",dictionaryId.toString(),"actor",actor.userId().toString(),"kind",body.kind(),"code",body.code(),"name",body.name().strip(),"definition",body.definition(),"dataType",body.dataType());
        try { mapper.insertEntry(p); } catch(DataIntegrityViolationException e) { throw conflict("ENTRY_CONFLICT","사전 항목 코드를 확인하세요."); }
        support.audit(actor.userId(),UUID.fromString(dictionary.organizationId()),"DICTIONARY_ENTRY_CREATED","DICTIONARY_ENTRY",id,requestId);
        return required(UUID.fromString(id));
    }
    @Transactional
    public DictionaryEntry edit(UUID id,String ifMatch,EntryInput body,Authentication auth,String requestId) {
        var old=required(id); var dictionary=editableDictionary(UUID.fromString(old.dictionaryId()),auth,"ORG_EDITOR");
        match(ifMatch,"dictionary-entry",id.toString(),old.lockVersion());
        if(!old.state().equals("DRAFT")) throw conflict("ENTRY_IMMUTABLE","초안 항목만 수정할 수 있습니다. 새 버전을 생성하세요.");
        if(!old.kind().equals(body.kind()) || !old.code().equals(body.code())) throw conflict("ENTRY_IDENTITY_IMMUTABLE","항목 종류와 코드는 변경할 수 없습니다.");
        if(mapper.updateEntry(params("id",id.toString(),"version",old.lockVersion(),"name",body.name().strip(),"definition",body.definition(),"dataType",body.dataType(),"actor",support.reader(auth).userId().toString()))!=1)
            throw versionConflict();
        support.audit(support.reader(auth).userId(),UUID.fromString(dictionary.organizationId()),"DICTIONARY_ENTRY_UPDATED","DICTIONARY_ENTRY",id.toString(),requestId);
        return required(id);
    }
    @Transactional
    public DictionaryEntry submit(UUID id,String ifMatch,Authentication auth,String requestId) {
        var old=required(id); var dictionary=editableDictionary(UUID.fromString(old.dictionaryId()),auth,"ORG_EDITOR");
        match(ifMatch,"dictionary-entry",id.toString(),old.lockVersion());
        if(!old.state().equals("DRAFT")) throw conflict("ENTRY_STATE_INVALID","초안만 검토 요청할 수 있습니다.");
        relationships.validateForReview(id);
        return transition(old,dictionary,"IN_REVIEW",null,auth,requestId);
    }
    @Transactional
    public DictionaryEntry decide(UUID id,String ifMatch,Decision body,Authentication auth,String requestId) {
        var old=required(id); var dictionary=editableDictionary(UUID.fromString(old.dictionaryId()),auth,"ORG_REVIEWER");
        match(ifMatch,"dictionary-entry",id.toString(),old.lockVersion());
        if(!old.state().equals("IN_REVIEW")) throw conflict("ENTRY_STATE_INVALID","검토 중인 항목만 결정할 수 있습니다.");
        if(body.decision().equals("REJECT") && (body.reason()==null || body.reason().isBlank())) throw new IllegalArgumentException("reason required");
        if(body.decision().equals("APPROVE")) relationships.validateForReview(id);
        return transition(old,dictionary,body.decision().equals("APPROVE")?"APPROVED":"REJECTED",body.reason(),auth,requestId);
    }
    /** Validation used by the draft field module. Approved immutable entries bind version and source. */
    @Transactional(readOnly=true)
    public DictionaryEntry approvedTerm(UUID id,UUID organization,Authentication auth) {
        var entry=get(id,auth); var dictionary=mapper.dictionary(entry.dictionaryId());
        if(!entry.state().equals("APPROVED") || !entry.kind().equals("TERM")) throw conflict("TERM_NOT_APPROVED","승인된 표준 용어만 연결할 수 있습니다.");
        if(!dictionary.scope().equals("PUBLIC") && !organization.toString().equals(dictionary.organizationId())) throw missing();
        relationships.validateForReview(id);
        return entry;
    }
    private DictionaryEntry transition(DictionaryEntry old,Dictionary dictionary,String state,String reason,Authentication auth,String requestId) {
        var actor=support.reader(auth);
        if(mapper.transitionEntry(params("id",old.id(),"version",old.lockVersion(),"oldState",old.state(),"state",state,"actor",actor.userId().toString(),"reason",reason))!=1)
            throw versionConflict();
        support.audit(actor.userId(),UUID.fromString(dictionary.organizationId()),"DICTIONARY_ENTRY_"+state,"DICTIONARY_ENTRY",old.id(),requestId);
        return required(UUID.fromString(old.id()));
    }
    private Dictionary readDictionary(UUID id,Authentication auth) {
        support.reader(auth); var dictionary=mapper.dictionary(id.toString()); if(dictionary==null) throw missing();
        if(dictionary.organizationId()!=null) support.scoped(auth,UUID.fromString(dictionary.organizationId()),"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");
        return dictionary;
    }
    private Dictionary editableDictionary(UUID id,Authentication auth,String role) {
        var dictionary=readDictionary(id,auth);
        if(dictionary.scope().equals("PUBLIC")) throw conflict("PUBLIC_DICTIONARY_IMMUTABLE","공공 사전 원본은 직접 수정할 수 없습니다.");
        support.scoped(auth,UUID.fromString(dictionary.organizationId()),role); return dictionary;
    }
    private DictionaryEntry required(UUID id) { var entry=mapper.entry(id.toString()); if(entry==null) throw missing(); return entry; }
}

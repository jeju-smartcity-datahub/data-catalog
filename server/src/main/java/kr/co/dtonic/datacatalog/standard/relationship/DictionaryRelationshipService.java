package kr.co.dtonic.datacatalog.standard.relationship;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.relationship.DictionaryRelationshipModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DictionaryRelationshipService {
    private final JdbcTemplate db;
    private final GovernanceSupport support;
    private final ObjectMapper json;
    public DictionaryRelationshipService(JdbcTemplate db,GovernanceSupport support,ObjectMapper json){this.db=db;this.support=support;this.json=json;}
    private record Entry(UUID id,UUID dictionaryId,UUID organizationId,String scope,String kind,String dataType,String state,long version) {}
    @Transactional(readOnly=true)
    public EntryRelationships get(UUID id,Authentication auth){var entry=read(id,auth);return relationships(entry);}
    public EntryRelationships word(UUID id,String etag,WordAttributes input,Authentication auth,String requestId){
        var entry=editable(id,"WORD",etag,auth);
        db.update("""
            INSERT INTO standard.word_definition(artcl_id,eng_nm,abbr_nm,synm_cn) VALUES (?,?,?,?::jsonb)
            ON CONFLICT(artcl_id) DO UPDATE SET eng_nm=excluded.eng_nm,abbr_nm=excluded.abbr_nm,synm_cn=excluded.synm_cn
            """,id,input.englishName(),input.abbreviation(),encode(input.synonyms()));
        changed(entry,auth,requestId);return relationships(required(id));
    }
    public EntryRelationships domain(UUID id,String etag,DomainAttributes input,Authentication auth,String requestId){
        var entry=editable(id,"DOMAIN",etag,auth);validateDomain(entry,input);
        db.update("""
            INSERT INTO standard.domain_definition(artcl_id,max_len,prcsn,dcpt_len,frm_nm,unit_nm,prm_vl_cn) VALUES (?,?,?,?,?,?,?::jsonb)
            ON CONFLICT(artcl_id) DO UPDATE SET max_len=excluded.max_len,prcsn=excluded.prcsn,
              dcpt_len=excluded.dcpt_len,frm_nm=excluded.frm_nm,unit_nm=excluded.unit_nm,prm_vl_cn=excluded.prm_vl_cn
            """,id,input.maxLength(),input.precision(),input.scale(),input.format(),input.unit(),encode(input.allowedValues()));
        changed(entry,auth,requestId);return relationships(required(id));
    }
    public EntryRelationships term(UUID id,String etag,TermAttributes input,Authentication auth,String requestId){
        var entry=editable(id,"TERM",etag,auth);validateTerm(entry,input);
        db.update("""
            INSERT INTO standard.term_definition(artcl_id,trm_eng_abbr_nm,stddmn_artcl_id) VALUES (?,?,?)
            ON CONFLICT(artcl_id) DO UPDATE SET trm_eng_abbr_nm=excluded.trm_eng_abbr_nm,stddmn_artcl_id=excluded.stddmn_artcl_id
            """,id,input.physicalName().strip(),input.domainEntryId());
        db.update("DELETE FROM standard.term_word WHERE trm_artcl_id=?",id);
        for(int i=0;i<input.wordEntryIds().size();i++)db.update("INSERT INTO standard.term_word(trm_artcl_id,word_artcl_id,sort_seq) VALUES (?,?,?)",id,input.wordEntryIds().get(i),i);
        changed(entry,auth,requestId);return relationships(required(id));
    }
    /** Caller checks action permissions; this port validates the complete frozen relation before review/approval. */
    public void validateForReview(UUID id){
        var entry=required(id);var details=relationships(entry);
        if(entry.kind().equals("TERM")){
            if(details.term()==null)throw invalid("용어의 승인 도메인과 순서 있는 단어를 등록하세요.");
            validateTerm(entry,details.term());
        }
        if(details.domain()!=null)validateDomain(entry,details.domain());
    }
    private void validateTerm(Entry entry,TermAttributes input){
        if(input.wordEntryIds()==null||input.wordEntryIds().isEmpty()||input.wordEntryIds().size()>32)throw invalid("용어는 1~32개의 순서 있는 단어가 필요합니다.");
        var domain=reference(entry,input.domainEntryId(),"DOMAIN");
        if(!entry.dataType().equals(domain.dataType()))throw invalid("용어의 데이터 타입이 도메인과 일치해야 합니다.");
        for(UUID id:input.wordEntryIds())reference(entry,id,"WORD");
    }
    private Entry reference(Entry owner,UUID id,String kind){
        if(id==null)throw invalid("필수 표준 참조를 선택하세요.");var reference=required(id);
        if(!reference.kind().equals(kind)||!reference.state().equals("APPROVED"))throw invalid("승인된 "+kind+" 항목을 선택하세요.");
        if(!reference.scope().equals("PUBLIC")&&!java.util.Objects.equals(reference.organizationId(),owner.organizationId()))
            throw invalid("다른 기관의 표준 항목은 참조할 수 없습니다.");
        return reference;
    }
    private void validateDomain(Entry entry,DomainAttributes input){
        if(input.maxLength()!=null&&!entry.dataType().equals("STRING"))throw invalid("문자열 도메인만 최대 길이를 설정할 수 있습니다.");
        if((input.precision()!=null||input.scale()!=null)&&!List.of("INTEGER","NUMBER").contains(entry.dataType()))throw invalid("숫자 도메인만 정밀도를 설정할 수 있습니다.");
        if(input.scale()!=null&&(input.precision()==null||input.scale()>input.precision()||entry.dataType().equals("INTEGER")&&input.scale()!=0))
            throw invalid("도메인의 소수 자릿수와 정밀도를 확인하세요.");
    }
    private EntryRelationships relationships(Entry entry){
        var words=db.query("SELECT artcl_id AS entry_id,eng_nm AS english_name,abbr_nm AS abbreviation,synm_cn AS synonyms FROM standard.word_definition WHERE artcl_id=?",(r,n)->new WordAttributes(r.getString("english_name"),r.getString("abbreviation"),strings(r.getString("synonyms"))),entry.id());
        var domains=db.query("SELECT artcl_id AS entry_id,max_len AS max_length,prcsn AS numeric_precision,dcpt_len AS numeric_scale,frm_nm AS format,unit_nm AS unit,prm_vl_cn AS allowed_values FROM standard.domain_definition WHERE artcl_id=?",(r,n)->new DomainAttributes((Integer)r.getObject("max_length"),(Integer)r.getObject("numeric_precision"),
                (Integer)r.getObject("numeric_scale"),r.getString("format"),r.getString("unit"),strings(r.getString("allowed_values"))),entry.id());
        var ordered=db.query("SELECT word_artcl_id AS word_entry_id FROM standard.term_word WHERE trm_artcl_id=? ORDER BY sort_seq",(r,n)->r.getObject(1,UUID.class),entry.id());
        var terms=db.query("SELECT trm_eng_abbr_nm AS physical_name,stddmn_artcl_id AS domain_entry_id FROM standard.term_definition WHERE artcl_id=?",(r,n)->new TermAttributes(r.getString(1),r.getObject(2,UUID.class),ordered),entry.id());
        return new EntryRelationships(entry.id(),entry.kind(),entry.version(),words.isEmpty()?null:words.getFirst(),domains.isEmpty()?null:domains.getFirst(),terms.isEmpty()?null:terms.getFirst());
    }
    private Entry read(UUID id,Authentication auth){
        support.reader(auth);var entry=required(id);
        if(entry.organizationId()!=null)support.scoped(auth,entry.organizationId(),"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");
        if(entry.scope().equals("PUBLIC")&&!entry.state().equals("APPROVED"))throw GovernanceSupport.missing();
        return entry;
    }
    private Entry editable(UUID id,String kind,String etag,Authentication auth){
        var entry=read(id,auth);if(entry.scope().equals("PUBLIC"))throw invalid("공공 사전 원본은 수정할 수 없습니다.");support.scoped(auth,entry.organizationId(),"ORG_EDITOR");
        db.queryForObject("SELECT id FROM standard.dictionary_entry WHERE id=? FOR UPDATE",UUID.class,id);entry=required(id);
        GovernanceSupport.match(etag,"dictionary-entry",id.toString(),entry.version());
        if(!entry.state().equals("DRAFT")||!entry.kind().equals(kind))throw invalid("동일 종류의 초안 항목만 수정할 수 있습니다.");return entry;
    }
    private Entry required(UUID id){
        var rows=db.query("""
            SELECT e.id,e.stddict_id AS dictionary_id,d.inst_id AS organization_id,d.rng_cd AS scope,e.type_cd AS kind,e.data_type_cd AS data_type,e.stts_cd AS state,e.lck_ver_no AS lock_version FROM standard.dictionary_entry e
            JOIN standard.dictionary d ON d.id=e.stddict_id WHERE e.id=?
            """,(r,n)->new Entry(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8)),id);
        if(rows.isEmpty())throw GovernanceSupport.missing();return rows.getFirst();
    }
    private void changed(Entry entry,Authentication auth,String requestId){
        UUID actor=support.reader(auth).userId();
        if(db.update("UPDATE standard.dictionary_entry SET lck_ver_no=lck_ver_no+1,mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=? AND lck_ver_no=? AND stts_cd='DRAFT'",actor,entry.id(),entry.version())!=1)throw GovernanceSupport.versionConflict();
        support.audit(actor,entry.organizationId(),"DICTIONARY_RELATION_UPDATED","DICTIONARY_ENTRY",entry.id().toString(),requestId);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid relation serialization");}}
    private List<String> strings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored relation");}}
    private static DomainException invalid(String message){return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"STANDARD_RELATION_INVALID",message);}
}

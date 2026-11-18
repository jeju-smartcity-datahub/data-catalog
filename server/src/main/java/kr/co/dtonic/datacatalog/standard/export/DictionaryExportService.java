package kr.co.dtonic.datacatalog.standard.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportModels.*;
import kr.co.dtonic.datacatalog.standard.search.DictionaryEntrySearch;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DictionaryExportService {
    private final JdbcTemplate db;private final GovernanceSupport support;private final ObjectMapper json;
    private static final int ROW_LIMIT=10000;
    public DictionaryExportService(JdbcTemplate db,GovernanceSupport support,ObjectMapper json){this.db=db;this.support=support;this.json=json;}
    private record Dictionary(UUID id,UUID organizationId,String scope,String name,String version,String sourceUri,String checksum) {}
    @Transactional
    public DictionaryExport create(UUID dictionaryId,ExportRequest input,String key,Authentication auth,String requestId){
        var dictionary=authorized(dictionaryId,auth);UUID actor=support.reader(auth).userId();
        if(input.entryIds()!=null&&(input.entryIds().isEmpty()||input.entryIds().size()>100||input.entryIds().stream().anyMatch(java.util.Objects::isNull)||new java.util.HashSet<>(input.entryIds()).size()!=input.entryIds().size()))throw invalid("선택 항목은 중복 없이 1~100개여야 합니다.");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw invalid("Idempotency-Key를 입력하세요.");
        String keyHash=hash(key.getBytes(StandardCharsets.UTF_8)),requestHash=hash(encode(input).getBytes(StandardCharsets.UTF_8));
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor+":"+dictionaryId+":"+keyHash);
        var existing=db.queryForList("SELECT id,dmnd_hash_vl AS request_hash FROM standard.dictionary_export WHERE stddict_id=? AND rqstr_id=? AND key_hash_vl=?",dictionaryId,actor,keyHash);
        if(!existing.isEmpty()){
            if(!existing.getFirst().get("request_hash").equals(requestHash))throw new DomainException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","같은 키가 다른 추출 조건에 사용되었습니다.");
            return get((UUID)existing.getFirst().get("id"),auth);
        }
        var args=new ArrayList<Object>();args.add(dictionaryId);args.add(input.kind());String where=" WHERE e.stddict_id=? AND e.type_cd=?";
        where+=DictionaryEntrySearch.queryCondition(input.query(),args);
        if(input.state()!=null){where+=" AND e.stts_cd=?";args.add(input.state());}
        if(dictionary.scope().equals("PUBLIC"))where+=" AND e.stts_cd='APPROVED'";
        if(input.entryIds()!=null){where+=" AND e.id IN ("+String.join(",",java.util.Collections.nCopies(input.entryIds().size(),"?"))+")";args.addAll(input.entryIds());}
        args.add(ROW_LIMIT+1);
        var data=db.query("""
            SELECT e.cd AS code,e.nm AS name,e.dfn_cn AS definition,e.data_type_cd AS data_type,e.stts_cd AS state,e.id::text,w.eng_nm AS english_name,w.abbr_nm AS abbreviation,w.synm_cn::text AS synonyms,
              d.max_len::text AS max_length,d.prcsn::text AS numeric_precision,d.dcpt_len::text AS numeric_scale,d.frm_nm AS format,d.unit_nm AS unit,d.prm_vl_cn::text AS allowed_values,
              t.trm_eng_abbr_nm AS physical_name,t.stddmn_artcl_id::text AS domain_entry_id,
              (SELECT string_agg(tw.word_artcl_id::text,',' ORDER BY tw.sort_seq) FROM standard.term_word tw WHERE tw.trm_artcl_id=e.id) AS ordered_words
            FROM standard.dictionary_entry e LEFT JOIN standard.word_definition w ON w.artcl_id=e.id
            LEFT JOIN standard.domain_definition d ON d.artcl_id=e.id LEFT JOIN standard.term_definition t ON t.artcl_id=e.id
            """+where+" ORDER BY e.cd,e.id LIMIT ?",(r,n)->{
                var values=new ArrayList<String>();for(int c=1;c<=18;c++)values.add(r.getString(c));return values;
            },args.toArray());
        long count=data.size();
        if(input.entryIds()!=null&&count!=input.entryIds().size())throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"EXPORT_SELECTION_MISMATCH","선택 항목이 사전·종류·검색·상태 조건과 일치하지 않습니다. 목록을 새로 조회하세요.");
        if(count>ROW_LIMIT)throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"EXPORT_LIMIT_EXCEEDED","한 번에 10,000개까지 다운로드할 수 있습니다. 검색 조건을 좁혀주세요.");
        var rows=new ArrayList<List<String>>();rows.add(List.of("코드","이름","정의","데이터 타입","검토 상태","항목 ID","영어명","약어","동의어","최대 길이","정밀도","소수 자릿수","형식","단위","허용값","물리명","도메인 ID","순서 있는 단어 ID"));rows.addAll(data);
        OffsetDateTime now=db.queryForObject("SELECT CURRENT_TIMESTAMP",OffsetDateTime.class);
        List<List<String>> provenance=List.of(List.of("항목","값"),List.of("사전 ID",dictionaryId.toString()),List.of("사전 이름",dictionary.name()),
                List.of("사전 버전",dictionary.version()),List.of("범위",dictionary.scope()),List.of("원본 출처",orEmpty(dictionary.sourceUri())),
                List.of("원본 체크섬",orEmpty(dictionary.checksum())),List.of("종류",input.kind()),List.of("검색 조건",orEmpty(input.query())),
                List.of("상태 조건",orEmpty(input.state())),List.of("선택 항목",input.entryIds()==null?"조건 전체":input.entryIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))),List.of("추출 기준시점",now.toString()),List.of("항목 수",Long.toString(count)));
        byte[] file=XlsxWriter.write(provenance,rows);
        if(file.length>8*1024*1024)throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"EXPORT_LIMIT_EXCEEDED","추출 파일 크기 제한을 초과했습니다.");
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO standard.dictionary_export(id,stddict_id,rqstr_id,type_cd,srch_cn,stts_cnd_cd,key_hash_vl,dmnd_hash_vl,artcl_cnt,file_cn,cn_hash_vl)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,dictionaryId,actor,input.kind(),input.query(),input.state(),keyHash,requestHash,count,file,hash(file));
        support.audit(actor,dictionary.organizationId(),"DICTIONARY_EXPORTED","DICTIONARY_EXPORT",id.toString(),requestId);
        return get(id,auth);
    }
    @Transactional(readOnly=true)
    public DictionaryExport get(UUID id,Authentication auth){
        var rows=db.query("SELECT id,stddict_id AS dictionary_id,rqstr_id AS requested_by,type_cd AS kind,srch_cn AS query,stts_cnd_cd AS state_filter,key_hash_vl AS key_digest,dmnd_hash_vl AS request_hash,artcl_cnt AS entry_count,file_cn AS file_content,cn_hash_vl AS content_hash,crt_dt AS created_at,expry_dt AS expires_at FROM standard.dictionary_export WHERE id=?",(r,n)->new DictionaryExport(r.getObject("id",UUID.class),r.getObject("dictionary_id",UUID.class),
                r.getString("kind"),r.getString("query"),r.getString("state_filter"),"SUCCEEDED",r.getInt("entry_count"),r.getObject("created_at",OffsetDateTime.class),
                r.getObject("expires_at",OffsetDateTime.class),r.getString("content_hash"),"dictionary-"+r.getString("id")+".xlsx"),id);
        if(rows.isEmpty())throw GovernanceSupport.missing();var result=rows.getFirst();authorized(result.dictionaryId(),auth);return result;
    }
    @Transactional(readOnly=true)
    public Download download(UUID id,Authentication auth){
        var result=get(id,auth);
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT expry_dt>CURRENT_TIMESTAMP FROM standard.dictionary_export WHERE id=?",Boolean.class,id)))
            throw new DomainException(HttpStatus.GONE,"EXPORT_EXPIRED","추출 파일이 만료되었습니다. 새 추출을 요청하세요.");
        return new Download(result,db.queryForObject("SELECT file_cn AS file_content FROM standard.dictionary_export WHERE id=?",byte[].class,id));
    }
    private Dictionary authorized(UUID id,Authentication auth){
        support.reader(auth);var rows=db.query("SELECT id,rng_cd AS scope,inst_id AS organization_id,cd AS code,nm AS name,sou_rfrnc_addr AS source_uri,ver_nm AS version_label,sou_hash_vl AS source_checksum,crt_dt AS created_at FROM standard.dictionary WHERE id=?",(r,n)->new Dictionary(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),
                r.getString("scope"),r.getString("name"),r.getString("version_label"),r.getString("source_uri"),r.getString("source_checksum")),id);
        if(rows.isEmpty())throw GovernanceSupport.missing();var value=rows.getFirst();
        if(value.organizationId()!=null)support.scoped(auth,value.organizationId(),"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");return value;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid export request");}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String orEmpty(String value){return value==null?"":value;}
    private static DomainException invalid(String message){return new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",message);}
}

package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.analytics.SearchHistoryContracts.*;

@Repository
public class SearchHistoryRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public SearchHistoryRepository(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    List<String> approvedFilterIds(String kind,List<String> values){
        if(values.isEmpty())return List.of();
        String join="",column;
        switch(kind){
            case "organizationId"->column="r.pvsn_inst_id";
            case "categoryId"->column="r.main_clsf_id";
            case "regionId"->{join=" JOIN catalog.revision_discovery x ON x.rvsn_id=r.id AND x.type_cd='REGION'";column="x.clsf_id";}
            case "hubId"->{join=" JOIN catalog.revision_discovery x ON x.rvsn_id=r.id AND x.type_cd='HUB'";column="x.clsf_id";}
            case "topicId"->{join=" JOIN catalog.revision_topic x ON x.rvsn_id=r.id";column="x.tpc_id";}
            default->throw new IllegalArgumentException("Unknown controlled filter");
        }
        String parameters=String.join(",",java.util.Collections.nCopies(values.size(),"?::uuid"));
        return db.queryForList("SELECT DISTINCT "+column+"::text FROM catalog.dataset d JOIN catalog.publication p ON p.id=d.now_pstg_id AND p.dtset_id=d.id JOIN catalog.dataset_revision r ON r.id=p.rvsn_id AND r.dtset_id=d.id JOIN identity.organization provider ON provider.id=r.pvsn_inst_id JOIN identity.organization owner_org ON owner_org.id=d.psn_inst_id"+join+" WHERE d.mng_stts_cd='ACTIVE' AND p.stts_cd='PUBLISHED' AND r.stts_cd='APPROVED' AND r.rls_rng_cd='PUBLIC' AND provider.stts_cd='ACTIVE' AND owner_org.stts_cd='ACTIVE' AND "+column+" IN ("+parameters+") ORDER BY 1",String.class,values.toArray());
    }
    void insert(UUID id,boolean hasQuery,String filters,long total,long duration,SearchQueryRepresentation representation,
            List<ResultReference> results,Integer page,Integer size,String tokenHash,OffsetDateTime at) throws JsonProcessingException {
        db.update("INSERT INTO analytics.search_event(id,dmnd_id,srch_cn_exst_yn,nrmlz_srch_cnd_cn,rslt_cnt,req_ms_cnt,ocrn_dt,query_repr,expry_dt,feedback_tkn_hash_vl,returned_page,returned_page_sz) VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb,?,?,?,?)",
                id,id.toString(),hasQuery,filters,total,duration,at,json.writeValueAsString(representation),SearchRetentionPolicy.expiresAt(at),tokenHash,page,size);
        for(ResultReference result:results)db.update("INSERT INTO analytics.search_result(srch_event_id,seq_no,dtset_id,pstg_no) VALUES (?,?,?,?)",id,result.ordinal(),result.datasetId(),result.publicationNo());
    }
    @Transactional
    public void feedback(FeedbackInput input,OffsetDateTime now){
        if(input==null||input.eventId()==null||input.feedbackToken()==null||!input.feedbackToken().matches("[A-Za-z0-9_-]{43}")||input.rating()==null)throw rejected();
        var rows=db.query("SELECT feedback_tkn_hash_vl FROM analytics.search_event WHERE id=? AND expry_dt>? FOR UPDATE",(rs,n)->rs.getString(1),input.eventId(),now);
        String expected=rows.isEmpty()?null:rows.get(0);
        if(expected==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),tokenHash(input.feedbackToken()).getBytes(StandardCharsets.US_ASCII)))throw rejected();
        var prior=db.query("SELECT srch_feedback_cd FROM analytics.search_feedback WHERE srch_event_id=?",(rs,n)->rs.getString(1),input.eventId());
        if(!prior.isEmpty()){
            if(!prior.get(0).equals(input.rating().name()))throw new DomainException(HttpStatus.CONFLICT,"SEARCH_FEEDBACK_ALREADY_RECORDED","이 검색의 평가는 이미 저장되었습니다.");
            return;
        }
        db.update("INSERT INTO analytics.search_feedback(srch_event_id,srch_feedback_cd,feedback_dt) VALUES (?,?,?)",input.eventId(),input.rating().name(),now);
    }
    public HistoryDetail detail(UUID id,OffsetDateTime now){
        var rows=db.query("SELECT ocrn_dt,expry_dt,query_repr::text,returned_page,returned_page_sz FROM analytics.search_event WHERE id=? AND COALESCE(expry_dt,((ocrn_dt AT TIME ZONE 'UTC')+interval '5 years') AT TIME ZONE 'UTC')>?",(rs,n)->{
            SearchQueryRepresentation representation=null;
            if(rs.getString(3)!=null)try{representation=json.readValue(rs.getString(3),SearchQueryRepresentation.class);}catch(JsonProcessingException invalid){throw new IllegalStateException("Invalid controlled search representation");}
            OffsetDateTime occurred=rs.getObject(1,OffsetDateTime.class),expires=rs.getObject(2,OffsetDateTime.class);
            Integer page=(Integer)rs.getObject(4),size=(Integer)rs.getObject(5);
            return new HistoryDetail(id,occurred,expires==null?SearchRetentionPolicy.expiresAt(occurred):expires,representation,List.of(),null,null,page,size,page==null?"COUNTS_ONLY":"RETURNED_PUBLIC_PAGE",representation==null||page==null);
        },id,now);
        if(rows.isEmpty())throw rejected();
        var row=rows.get(0);
        var results=db.query("SELECT dtset_id,pstg_no,seq_no FROM analytics.search_result WHERE srch_event_id=? ORDER BY seq_no",(rs,n)->new ResultReference(rs.getObject(1,UUID.class),rs.getInt(2),rs.getInt(3)),id);
        var feedback=db.query("SELECT srch_feedback_cd,feedback_dt FROM analytics.search_feedback WHERE srch_event_id=?",(rs,n)->new Object[]{Rating.valueOf(rs.getString(1)),rs.getObject(2,OffsetDateTime.class)},id);
        return new HistoryDetail(id,row.occurredAt(),row.expiresAt(),row.queryRepresentation(),List.copyOf(results),feedback.isEmpty()?null:(Rating)feedback.get(0)[0],feedback.isEmpty()?null:(OffsetDateTime)feedback.get(0)[1],row.returnedPage(),row.returnedSize(),row.resultScope(),row.legacyIncomplete());
    }
    @Transactional
    public int purgeExpired(OffsetDateTime now,int limit){
        if(limit<1||limit>10000)throw new IllegalArgumentException("Invalid retention batch size");
        return db.update("DELETE FROM analytics.search_event WHERE id IN (SELECT id FROM analytics.search_event WHERE COALESCE(expry_dt,((ocrn_dt AT TIME ZONE 'UTC')+interval '5 years') AT TIME ZONE 'UTC')<=? ORDER BY ocrn_dt,id LIMIT ? FOR UPDATE SKIP LOCKED)",now,limit);
    }
    static String tokenHash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static DomainException rejected(){return new DomainException(HttpStatus.NOT_FOUND,"SEARCH_EVENT_UNAVAILABLE","검색 기록을 확인할 수 없거나 보유기간이 지났습니다.");}
}

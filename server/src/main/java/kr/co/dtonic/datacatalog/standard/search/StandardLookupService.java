package kr.co.dtonic.datacatalog.standard.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.FieldDefinition;
import kr.co.dtonic.datacatalog.standard.application.DictionaryService;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.search.StandardLookupContracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StandardLookupService {
    private final JdbcTemplate db;private final GovernanceSupport support;private final MetadataWorkflowService metadata;private final DictionaryService dictionaries;private final ObjectMapper json;
    private static final Set<String> TYPES=Set.of("STRING","INTEGER","NUMBER","BOOLEAN","DATE","DATETIME","OBJECT","ARRAY");
    public StandardLookupService(JdbcTemplate db,GovernanceSupport support,MetadataWorkflowService metadata,DictionaryService dictionaries,ObjectMapper json){this.db=db;this.support=support;this.metadata=metadata;this.dictionaries=dictionaries;this.json=json;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TermFieldUsagePage usages(UUID termId,UUID organization,int page,int size,Authentication auth){
        bounds(page,size);scope(organization,auth);dictionaries.approvedTerm(termId,organization,auth);
        var data=db.query("""
            SELECT d.id,r.id,r.rvsn_no AS revision_no,r.ttl AS title,r.stts_cd AS state,r.rls_rng_cd AS visibility,f.value->>'fieldPath',f.value->>'physicalName',f.value->>'displayName',f.value->>'dataType',
              o.inst_nm AS name,c.nm AS name,r.mdfcn_dt AS updated_at,p.id,p.pstg_no AS publication_no,p.stts_cd AS state,p.rvsn_id AS revision_id,
              coalesce(d.now_pstg_id=p.id AND p.stts_cd='PUBLISHED',false),coalesce(p.rvsn_id=r.id,false)
            FROM catalog.dataset d
            JOIN LATERAL (SELECT * FROM catalog.dataset_revision r0 WHERE r0.dtset_id=d.id ORDER BY r0.rvsn_no DESC LIMIT 1) r ON true
            JOIN identity.organization o ON o.id=r.pvsn_inst_id
            LEFT JOIN classification.category c ON c.id=r.main_clsf_id
            LEFT JOIN LATERAL (SELECT * FROM catalog.publication p0 WHERE p0.dtset_id=d.id ORDER BY p0.pstg_no DESC LIMIT 1) p ON true
            CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(r.mtdt_cn->'fields')='array' THEN r.mtdt_cn->'fields' ELSE '[]'::jsonb END) f(value)
            WHERE d.psn_inst_id=? AND d.mng_stts_cd='ACTIVE' AND f.value->>'approvedTermId'=?
            ORDER BY r.ttl,d.id,f.value->>'fieldPath' LIMIT ? OFFSET ?
            """,(r,n)->new TermFieldUsage(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),
                r.getString(11),r.getString(12),r.getObject(13,java.time.OffsetDateTime.class),r.getObject(14)==null?null:new UsagePublication(r.getObject(14,UUID.class),r.getInt(15),r.getString(16),r.getObject(17,UUID.class),r.getBoolean(18),r.getBoolean(19))),organization,termId.toString(),size+1,(long)page*size);
        return new TermFieldUsagePage(trim(data,size),page,size,data.size()>size,"LATEST_MANAGEMENT_REVISION");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TermCandidatePage search(UUID organization,String dataType,String query,int page,int size,Authentication auth){
        scope(organization,auth);return candidates(organization,dataType,query,page,size,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TermCandidatePage suggestions(UUID datasetId,String fieldPath,int page,int size,Authentication auth){
        var draft=metadata.getDraft(datasetId,auth);scope(draft.ownerOrganizationId(),auth);
        Object stored=metadata.getDraftMetadata(datasetId,auth).get("fields");
        List<FieldDefinition> fields=stored==null?List.of():json.convertValue(stored,new TypeReference<>(){});
        var field=fields.stream().filter(f->f.fieldPath().equals(fieldPath)).findFirst().orElseThrow(GovernanceSupport::missing);
        var basis=new SuggestionBasis(draft.revisionId(),draft.lockVersion(),field.fieldPath(),field.displayName(),field.physicalName(),field.dataType());
        return candidates(draft.ownerOrganizationId(),field.dataType(),null,page,size,basis);
    }
    private TermCandidatePage candidates(UUID organization,String dataType,String query,int page,int size,SuggestionBasis basis){
        bounds(page,size);if(!TYPES.contains(dataType)||query!=null&&query.length()>200)throw new IllegalArgumentException("Invalid term filter");
        var args=new ArrayList<Object>();args.add(organization);args.add(dataType);
        String where="""
            WHERE e.type_cd='TERM' AND e.stts_cd='APPROVED' AND (d.rng_cd='PUBLIC' OR d.inst_id=?) AND e.data_type_cd=?
            AND de.type_cd='DOMAIN' AND de.stts_cd='APPROVED' AND de.data_type_cd=e.data_type_cd
            AND (dd.rng_cd='PUBLIC' OR dd.inst_id=d.inst_id)
            AND (SELECT count(*) FROM standard.term_word tw WHERE tw.trm_artcl_id=e.id) BETWEEN 1 AND 32
            AND NOT EXISTS(SELECT 1 FROM standard.term_word tw JOIN standard.dictionary_entry we ON we.id=tw.word_artcl_id
              JOIN standard.dictionary wd ON wd.id=we.stddict_id WHERE tw.trm_artcl_id=e.id
              AND (we.type_cd<>'WORD' OR we.stts_cd<>'APPROVED' OR NOT (wd.rng_cd='PUBLIC' OR wd.inst_id IS NOT DISTINCT FROM d.inst_id)))
            """;
        if(basis!=null){where+=" AND (lower(btrim(e.nm))=lower(btrim(?)) OR lower(btrim(e.cd))=lower(btrim(?)) OR lower(btrim(t.trm_eng_abbr_nm))=lower(btrim(?)))";args.add(basis.displayName());args.add(basis.physicalName());args.add(basis.physicalName());}
        else if(query!=null&&!query.isBlank()){where+=" AND (e.nm ILIKE ? ESCAPE '!' OR e.cd ILIKE ? ESCAPE '!' OR t.trm_eng_abbr_nm ILIKE ? ESCAPE '!')";for(int i=0;i<3;i++)args.add(DictionaryEntrySearch.literalPattern(query));}
        String order="e.nm,e.cd,e.id";
        if(basis!=null){order="CASE WHEN lower(btrim(e.nm))=lower(btrim(?)) THEN 100 ELSE 90 END DESC,"+order;args.add(basis.displayName());}
        args.add(size+1);args.add((long)page*size);
        var result=db.query("""
            SELECT e.id,e.stddict_id AS dictionary_id,d.nm AS name,d.ver_nm AS version_label,d.rng_cd AS scope,e.cd AS code,e.nm AS name,e.dfn_cn AS definition,e.data_type_cd AS data_type,t.trm_eng_abbr_nm AS physical_name
            FROM standard.dictionary_entry e JOIN standard.dictionary d ON d.id=e.stddict_id
            JOIN standard.term_definition t ON t.artcl_id=e.id JOIN standard.dictionary_entry de ON de.id=t.stddmn_artcl_id
            JOIN standard.dictionary dd ON dd.id=de.stddict_id
            """+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",(r,n)->{
                String evidenceCode=basis==null?"SEARCH_SELECTED":r.getString(7).strip().equalsIgnoreCase(basis.displayName().strip())?"DISPLAY_NAME_EXACT":"IDENTIFIER_EXACT";
                String evidence=basis==null?"승인 용어 검색에서 사용자가 의미와 자료형을 확인하여 선택합니다.":evidenceCode.equals("DISPLAY_NAME_EXACT")?"저장된 필드 표시명 '"+basis.displayName()+"'과 승인 용어명 '"+r.getString(7)+"'이 앞뒤 공백과 대소문자를 제외하고 일치합니다.":"저장된 필드 물리명 '"+basis.physicalName()+"'과 승인 용어의 코드 또는 물리명이 앞뒤 공백과 대소문자를 제외하고 일치합니다.";
                Integer score=basis==null?null:evidenceCode.equals("DISPLAY_NAME_EXACT")?100:90;
                return new TermCandidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),evidenceCode,evidence,
                        score,basis==null?null:page*size+n+1,basis==null?null:"term-exact-match-v1: 표시명 일치 100점, 식별자 일치 90점. 동점은 용어명·코드·ID 순.",basis==null?null:"이름 또는 식별자 직접 일치 확인");
            },args.toArray());
        return new TermCandidatePage(trim(result,size),page,size,result.size()>size,basis);
    }
    private void scope(UUID organization,Authentication auth){support.scoped(auth,organization,"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");}
    private static void bounds(int page,int size){if(page<0||page>100000||size<1||size>50)throw new IllegalArgumentException("Invalid pagination");}
    private static <T> List<T> trim(List<T> data,int size){return List.copyOf(data.subList(0,Math.min(data.size(),size)));}
}

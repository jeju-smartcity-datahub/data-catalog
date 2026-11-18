package kr.co.dtonic.datacatalog.standard.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.DictionaryEntry;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DictionaryEntrySearch {
    private final JdbcTemplate db;
    private final GovernanceSupport support;
    public DictionaryEntrySearch(JdbcTemplate db,GovernanceSupport support){this.db=db;this.support=support;}
    @Transactional(readOnly=true)
    public List<DictionaryEntry> search(UUID dictionary,String kind,String query,String state,int page,int size,Authentication auth){
        support.reader(auth);
        var rows=db.queryForList("SELECT rng_cd AS scope,inst_id AS organization_id FROM standard.dictionary WHERE id=?",dictionary);
        if(rows.isEmpty())throw GovernanceSupport.missing();
        var organization=(UUID)rows.getFirst().get("organization_id");
        if(organization!=null)support.scoped(auth,organization,"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");
        if(kind!=null&&!Set.of("WORD","TERM","DOMAIN").contains(kind)||state!=null&&!Set.of("DRAFT","IN_REVIEW","APPROVED","REJECTED").contains(state))throw new IllegalArgumentException("Invalid filter");
        if(page<0||page>100000||size<1||size>100||query!=null&&query.length()>200)throw new IllegalArgumentException("Invalid search bounds");
        var args=new ArrayList<Object>();args.add(dictionary);
        String where=" WHERE e.stddict_id=?";
        if(kind!=null){where+=" AND e.type_cd=?";args.add(kind);}
        if(state!=null){where+=" AND e.stts_cd=?";args.add(state);}
        if("PUBLIC".equals(rows.getFirst().get("scope")))where+=" AND e.stts_cd='APPROVED'";
        where+=queryCondition(query,args);
        args.add(size);args.add((long)page*size);
        return db.query("""
            SELECT e.id,e.stddict_id AS dictionary_id,e.type_cd AS kind,e.cd AS code,e.nm AS name,e.dfn_cn AS definition,e.data_type_cd AS data_type,e.stts_cd AS state,e.lck_ver_no AS lock_version,e.creatr_id AS created_by,e.sbmsn_user_id AS submitted_by,e.dcsn_user_id AS decided_by,e.dcsn_rsn AS decision_reason,e.crt_dt AS created_at,e.mdfcn_dt AS updated_at,e.mdfr_id AS updated_by FROM standard.dictionary_entry e
            LEFT JOIN standard.word_definition w ON w.artcl_id=e.id
            LEFT JOIN standard.term_definition t ON t.artcl_id=e.id
            """+where+" ORDER BY e.type_cd,e.cd,e.id LIMIT ? OFFSET ?",(r,n)->new DictionaryEntry(r.getString("id"),r.getString("dictionary_id"),r.getString("kind"),r.getString("code"),r.getString("name"),r.getString("definition"),r.getString("data_type"),r.getString("state"),r.getLong("lock_version"),r.getString("created_by"),r.getString("updated_by"),r.getString("submitted_by"),r.getString("decided_by"),r.getString("decision_reason")),args.toArray());
    }
    /** Shared literal substring search for both the screen and filtered XLSX. */
    public static String queryCondition(String query,List<Object> args){
        if(query==null||query.isBlank())return "";
        String q=literalPattern(query);
        for(int i=0;i<6;i++)args.add(q);
        return " AND (e.nm ILIKE ? ESCAPE '!' OR e.cd ILIKE ? ESCAPE '!' OR e.dfn_cn ILIKE ? ESCAPE '!' OR w.eng_nm ILIKE ? ESCAPE '!' OR w.abbr_nm ILIKE ? ESCAPE '!' OR t.trm_eng_abbr_nm ILIKE ? ESCAPE '!')";
    }
    public static String literalPattern(String value){return "%"+value.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}

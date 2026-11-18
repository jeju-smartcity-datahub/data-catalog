package kr.co.dtonic.datacatalog.standard.definition;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.DefinitionDiagnostics;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate bean/proxy: the immutable failure survives rollback of the rejected import. No raw input enters this API. */
@Service
public class DefinitionFailureRecorder {
    public static final String ERROR_CODE="DEFINITION_PARSE_FAILED";
    private final JdbcTemplate db;
    private final GovernanceSupport support;
    private final ObjectMapper json;
    public DefinitionFailureRecorder(JdbcTemplate db,GovernanceSupport support,ObjectMapper json){this.db=db;this.support=support;this.json=json;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=5)
    public UUID record(UUID organization,UUID revision,String sourceHash,OffsetDateTime startedAt,Authentication auth,String requestId,DefinitionDiagnostics diagnostics){
        var actor=support.scoped(auth,organization,"ORG_EDITOR");
        if(sourceHash==null||!sourceHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source hash");
        if(diagnostics==null||!DefinitionParser.RULE_VERSION.equals(diagnostics.ruleVersion())||!"FAILED".equals(diagnostics.status())
                ||diagnostics.generatedFieldCount()!=0||!diagnostics.assessments().isEmpty())throw new IllegalArgumentException("Failed definition diagnostics required");
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO analytics.analysis_run(id,inst_id,dtset_rvsn_id,type_cd,engn_ver_nm,rqstr_id,
                bgng_dt,end_dt,stts_cd,data_artcl_cnt,sou_hash_vl,bss_cn,err_cd,dfn_dgns_cn)
            VALUES (?,?,?,'DEFINITION_IMPORT',?,?,?,CURRENT_TIMESTAMP,'FAILED',0,?,'[]'::jsonb,?,?::jsonb)
            """,id,organization,revision,DefinitionParser.VERSION,actor.userId(),startedAt,sourceHash,ERROR_CODE,encode(diagnostics));
        String trace=requestId!=null&&requestId.matches("[A-Za-z0-9_-]{8,64}")?requestId:UUID.randomUUID().toString();
        support.audit(actor.userId(),organization,"DEFINITION_ANALYSIS_FAILED","ANALYSIS_RUN",id.toString(),trace);
        return id;
    }
    private String encode(DefinitionDiagnostics diagnostics) {
        try { return json.writeValueAsString(diagnostics); }
        catch(JsonProcessingException invalid) { throw new IllegalStateException("Invalid safe definition diagnostics"); }
    }
}

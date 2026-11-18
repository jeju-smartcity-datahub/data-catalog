package kr.co.dtonic.datacatalog.audit.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.audit.api.AuditQueryController.*;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model of the append-only audit ledger. Detail payloads are intentionally not aggregated here. */
@Service
public class AuditQueryService {
    private final JdbcTemplate db;
    private final WorkflowAccess access;
    public AuditQueryService(JdbcTemplate db,WorkflowAccess access) { this.db=db; this.access=access; }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AuditEventPage list(Authentication auth,UUID organization,String q,int page,int size) {
        var principal=access.principal(auth);
        boolean platform=access.isPlatformAdmin(auth);
        if(!platform && auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ORG_AUDITOR")))
            throw new AccessDeniedException("Audit role required");
        List<Object> args=new ArrayList<>();
        String where=" WHERE true";
        if(organization!=null) {
            access.require(auth,organization,"ORG_AUDITOR");
            if(!platform && !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.organization WHERE id=? AND stts_cd='ACTIVE')",Boolean.class,organization)))
                throw new AccessDeniedException("Active organization required");
            where+=" AND inst_id=?"; args.add(organization);
        } else if(!platform) {
            if(principal.organizationIds().isEmpty()) return new AuditEventPage(List.of(),page,size,0,0);
            where+=" AND inst_id IN ("+String.join(",",principal.organizationIds().stream().map(id->"?").toList())+")";
            args.addAll(principal.organizationIds());
        }
        if(!platform) where+=" AND inst_id IN (SELECT id FROM identity.organization WHERE stts_cd='ACTIVE')";
        if(!q.isBlank()) {
            String pattern="%"+q.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where+=" AND (prcs_cd ILIKE ? ESCAPE '!' OR trgt_type_cd ILIKE ? ESCAPE '!' OR trgt_id::text ILIKE ? ESCAPE '!')";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        long total=db.queryForObject("SELECT count(*) FROM audit.audit_event"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var items=db.query("SELECT id,ocrn_dt AS occurred_at,prcs_user_id AS actor_user_id,prcs_cd AS action,trgt_type_cd AS target_type,trgt_id AS target_id,dmnd_id AS request_id,rslt_cd AS result FROM audit.audit_event"+where+
                " ORDER BY ocrn_dt DESC,id DESC LIMIT ? OFFSET ?", (row,index)->new Event(row.getObject("id",UUID.class),
                row.getObject("occurred_at",OffsetDateTime.class),row.getObject("actor_user_id",UUID.class),row.getString("action"),
                row.getString("target_type"),row.getObject("target_id",UUID.class),row.getString("request_id"),row.getString("result")),args.toArray());
        return new AuditEventPage(items,page,size,total,(total+size-1)/size);
    }
}

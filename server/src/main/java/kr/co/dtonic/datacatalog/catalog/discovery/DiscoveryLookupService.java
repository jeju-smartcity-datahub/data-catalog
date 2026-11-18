package kr.co.dtonic.datacatalog.catalog.discovery;

import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.discovery.DiscoveryLookupController.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class DiscoveryLookupService {
    private final JdbcTemplate db;private final WorkflowAccess access;
    public DiscoveryLookupService(JdbcTemplate db,WorkflowAccess access) { this.db=db;this.access=access; }
    private static final String SELECT="SELECT l.id,l.type_cd AS kind,l.cd AS code,l.nm AS name,l.actvtn_yn AS active,l.lck_ver_no AS lock_version,l.crt_dt AS created_at,l.mdfcn_dt AS updated_at,(SELECT count(*) FROM catalog.revision_discovery r WHERE r.clsf_id=l.id) AS reference_count FROM catalog.discovery_lookup l ";
    private static final RowMapper<DiscoveryLookup> ROW=(r,n)->new DiscoveryLookup(r.getObject("id",UUID.class),r.getString("kind"),r.getString("code"),r.getString("name"),r.getBoolean("active"),r.getLong("lock_version"),r.getLong("reference_count"));
    @Transactional(readOnly=true)
    public List<DiscoveryLookup> list(String kind,Authentication auth) {
        platform(auth);kind(kind);return db.query(SELECT+"WHERE l.type_cd=? ORDER BY l.nm,l.id LIMIT 500",ROW,kind);
    }
    @Transactional(readOnly=true)
    public DiscoveryLookup get(UUID id,Authentication auth) { platform(auth);return read(id,false); }
    public DiscoveryLookup create(CreateDiscoveryLookup input,Authentication auth,String requestId) {
        var actor=platform(auth);kind(input.kind());String name=name(input.name());
        if(input.code()==null||!input.code().matches("[a-z0-9][a-z0-9_-]{0,79}")||input.active()==null)throw invalid();
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","discovery-lookup:"+input.kind());
        if(db.queryForObject("SELECT count(*) FROM catalog.discovery_lookup WHERE type_cd=?",Long.class,input.kind())>=500)
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"LOOKUP_LIMIT_EXCEEDED","종류별 공개 분류는 최대 500개입니다.");
        UUID id=UUID.randomUUID();
        if(db.update("INSERT INTO catalog.discovery_lookup(id,type_cd,cd,nm,actvtn_yn) VALUES (?,?,?,?,?) ON CONFLICT(type_cd,cd) DO NOTHING",id,input.kind(),input.code(),name,input.active())!=1)
            throw new DomainException(HttpStatus.CONFLICT,"DUPLICATE_LOOKUP_CODE","같은 종류에서 이미 사용한 코드입니다.");
        audit(actor.userId(),id,"DISCOVERY_LOOKUP_CREATED",0,requestId);return read(id,false);
    }
    public DiscoveryLookup edit(UUID id,EditDiscoveryLookup input,String etag,Authentication auth,String requestId) {
        var actor=platform(auth);var current=read(id,true);MetadataWorkflowService.match(etag,"discovery-lookup",id,current.lockVersion());
        if(input.active()==null)throw invalid();
        if(!input.active()&&current.referenceCount()>0)throw inUse();
        db.update("UPDATE catalog.discovery_lookup SET nm=?,actvtn_yn=?,lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=?",name(input.name()),input.active(),id);
        audit(actor.userId(),id,"DISCOVERY_LOOKUP_UPDATED",current.lockVersion()+1,requestId);return read(id,false);
    }
    public DiscoveryLookup deactivate(UUID id,String etag,Authentication auth,String requestId) {
        var actor=platform(auth);var current=read(id,true);MetadataWorkflowService.match(etag,"discovery-lookup",id,current.lockVersion());
        if(current.referenceCount()>0)throw inUse();
        db.update("UPDATE catalog.discovery_lookup SET actvtn_yn=false,lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=?",id);
        audit(actor.userId(),id,"DISCOVERY_LOOKUP_DEACTIVATED",current.lockVersion()+1,requestId);return read(id,false);
    }
    private DiscoveryLookup read(UUID id,boolean lock) {
        var rows=db.query(SELECT+"WHERE l.id=?"+(lock?" FOR UPDATE OF l":""),ROW,id);
        if(rows.isEmpty())throw new DomainException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","분류를 찾을 수 없습니다.");return rows.getFirst();
    }
    private CatalogPrincipal platform(Authentication auth) {
        var principal=access.principal(auth);if(!access.isPlatformAdmin(auth))throw new AccessDeniedException("Platform role required");return principal;
    }
    private void audit(UUID actor,UUID target,String action,long version,String requestId) {
        db.update("INSERT INTO audit.audit_event(id,prcs_user_id,prcs_cd,trgt_type_cd,trgt_id,dmnd_id,chg_smry_cn) VALUES (?,?,?,'DISCOVERY_LOOKUP',?,?,jsonb_build_object('version',CAST(? AS bigint)))",
                UUID.randomUUID(),actor,action,target,requestId,version);
    }
    private static void kind(String value) { if(!List.of("REGION","HUB").contains(value==null?"":value))throw invalid(); }
    private static String name(String value) {
        if(value==null||value.isBlank()||value.length()>200||value.codePoints().anyMatch(Character::isISOControl))throw invalid();return value.strip();
    }
    private static DomainException invalid() { return new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","공개 분류 입력값을 확인하세요."); }
    private static DomainException inUse() { return new DomainException(HttpStatus.CONFLICT,"LOOKUP_IN_USE","초안 또는 과거 게시본이 참조하는 분류는 비활성화할 수 없습니다."); }
}

package kr.co.dtonic.datacatalog.standard.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.standard.persistence.GovernanceMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class GovernanceSupport {
    private final WorkflowAccess access;
    private final GovernanceMapper mapper;
    public GovernanceSupport(WorkflowAccess access, GovernanceMapper mapper) { this.access=access; this.mapper=mapper; }
    public CatalogPrincipal scoped(Authentication auth, UUID organization, String... roles) {
        var principal=access.require(auth,organization,roles);
        if(organization==null || !mapper.activeOrganization(organization.toString())) throw new AccessDeniedException("Active organization required");
        return principal;
    }
    public CatalogPrincipal platform(Authentication auth) {
        var p=access.principal(auth);
        if (!access.isPlatformAdmin(auth)) throw new AccessDeniedException("Platform role required");
        return p;
    }
    public CatalogPrincipal reader(Authentication auth) {
        var p=access.principal(auth);
        if (!access.isPlatformAdmin(auth) && auth.getAuthorities().stream().noneMatch(a ->
                java.util.Set.of("ROLE_ORG_EDITOR","ROLE_ORG_REVIEWER","ROLE_ORG_PUBLISHER","ROLE_ORG_AUDITOR").contains(a.getAuthority())))
            throw new AccessDeniedException("Governance read role required");
        return p;
    }
    public boolean isPlatform(Authentication auth) { return access.isPlatformAdmin(auth); }
    public static String etag(String type, String id, long version) { return "\""+type+":"+id+":"+version+"\""; }
    public static void match(String incoming,String type,String id,long version) {
        if (incoming==null || incoming.isBlank()) throw new DomainException(HttpStatus.PRECONDITION_REQUIRED,"IF_MATCH_REQUIRED","If-Match가 필요합니다.");
        if (!etag(type,id,version).equals(incoming)) throw new DomainException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","다른 사용자가 변경했습니다. 다시 조회하세요.");
    }
    public static DomainException missing() { return new DomainException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","대상을 찾을 수 없습니다."); }
    public static DomainException conflict(String code,String detail) { return new DomainException(HttpStatus.CONFLICT,code,detail); }
    public static DomainException versionConflict() { return new DomainException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","다른 사용자가 변경했습니다. 다시 조회하세요."); }
    public static Map<String,Object> params(Object... values) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
    public void audit(UUID actor,UUID organization,String action,String targetType,String target,String requestId) {
        mapper.audit(params("id",UUID.randomUUID().toString(),"actor",actor.toString(),"organization",organization==null?null:organization.toString(),
                "action",action,"targetType",targetType,"target",target,"requestId",requestId));
    }
}

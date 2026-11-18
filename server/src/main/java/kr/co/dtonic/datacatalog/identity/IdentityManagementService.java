package kr.co.dtonic.datacatalog.identity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.identity.api.IdentityManagementModels.*;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.*;

@Service
public class IdentityManagementService {
    private static final Set<String> ROLES=Set.of("PLATFORM_ADMIN","ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR");
    private final JdbcTemplate jdbc;
    private final GovernanceSupport support;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    public IdentityManagementService(JdbcTemplate jdbc,GovernanceSupport support) { this.jdbc=jdbc;this.support=support; }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<ManagedAccount> accounts(Authentication auth) {
        support.platform(auth);
        return jdbc.query("SELECT id FROM identity.local_account ORDER BY lgn_id",(rs,n)->rs.getObject(1,UUID.class)).stream().map(this::account).toList();
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ManagedAccount getAccount(UUID id,Authentication auth) { support.platform(auth);return account(id); }
    @Transactional
    public ManagedAccount createAccount(CreateManagedAccount input,Authentication auth,String requestId) {
        var actor=lock(auth);validateAssignments(input.roles(),input.organizationIds());validatePassword(input.password());
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO identity.local_account(id,lgn_id,pwd_hash,stts_cd) VALUES (?,?,?,'INACTIVE')",id,input.username(),encoder.encode(input.password())); }
        catch(DataIntegrityViolationException duplicate) { throw conflict("ACCOUNT_LOGIN_CONFLICT","이미 등록된 로그인 아이디입니다."); }
        replaceAssignments(id,input.roles(),input.organizationIds());
        jdbc.update("UPDATE identity.local_account SET stts_cd=? WHERE id=?",input.active()?"ACTIVE":"INACTIVE",id);
        support.audit(actor.userId(),null,"ACCOUNT_CREATED","LOCAL_ACCOUNT",id.toString(),requestId);
        return account(id);
    }
    @Transactional
    public ManagedAccount updateAccount(UUID id,String ifMatch,UpdateManagedAccount input,Authentication auth,String requestId) {
        var actor=lock(auth);var old=lockAccount(id);match(ifMatch,"account",id.toString(),old.lockVersion());
        validateAssignments(input.roles(),input.organizationIds());
        boolean removesAdmin=!input.active() || !input.roles().contains("PLATFORM_ADMIN");
        if(actor.userId().equals(id) && removesAdmin) throw conflict("SELF_ADMIN_CHANGE_FORBIDDEN","본인 계정의 비활성화 또는 전체 관리자 권한 해제는 할 수 없습니다.");
        if(old.active() && old.roles().contains("PLATFORM_ADMIN") && removesAdmin && usableAdminCount()<=1)
            throw conflict("LAST_PLATFORM_ADMIN","마지막 전체 관리자 계정은 비활성화하거나 권한을 해제할 수 없습니다.");
        if(jdbc.update("UPDATE identity.local_account SET stts_cd=? WHERE id=? AND auth_ver_no=?",input.active()?"ACTIVE":"INACTIVE",id,old.lockVersion())!=1) throw versionConflict();
        replaceAssignments(id,input.roles(),input.organizationIds());
        support.audit(actor.userId(),null,"ACCOUNT_UPDATED","LOCAL_ACCOUNT",id.toString(),requestId);
        return account(id);
    }
    @Transactional
    public ManagedAccount resetPassword(UUID id,String ifMatch,ResetManagedPassword input,Authentication auth,String requestId) {
        var actor=lock(auth);var old=lockAccount(id);match(ifMatch,"account",id.toString(),old.lockVersion());validatePassword(input.password());
        if(jdbc.update("UPDATE identity.local_account SET pwd_hash=? WHERE id=? AND auth_ver_no=?",encoder.encode(input.password()),id,old.lockVersion())!=1) throw versionConflict();
        support.audit(actor.userId(),null,"ACCOUNT_PASSWORD_RESET","LOCAL_ACCOUNT",id.toString(),requestId);
        return account(id);
    }
    @Transactional(readOnly=true)
    public List<ManagedOrganization> organizations(Authentication auth) {
        support.platform(auth);return jdbc.query("SELECT id,cd,inst_nm,stts_cd,lck_ver_no,crt_dt,mdfcn_dt FROM identity.organization ORDER BY inst_nm,id",this::mapOrganization);
    }
    @Transactional(readOnly=true)
    public ManagedOrganization getOrganization(UUID id,Authentication auth) { support.platform(auth);return organization(id); }
    @Transactional
    public ManagedOrganization createOrganization(ManagedOrganizationInput input,Authentication auth,String requestId) {
        var actor=lock(auth);UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm,stts_cd) VALUES (?,?,?,?)",id,input.code(),input.name().strip(),input.active()?"ACTIVE":"INACTIVE"); }
        catch(DataIntegrityViolationException duplicate) { throw conflict("ORGANIZATION_CODE_CONFLICT","이미 등록된 기관 코드입니다."); }
        support.audit(actor.userId(),id,"ORGANIZATION_CREATED","ORGANIZATION",id.toString(),requestId);return organization(id);
    }
    @Transactional
    public ManagedOrganization updateOrganization(UUID id,String ifMatch,ManagedOrganizationInput input,Authentication auth,String requestId) {
        var actor=lock(auth);
        if(jdbc.query("SELECT id FROM identity.organization WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),id).isEmpty())throw missing();
        var old=organization(id);match(ifMatch,"organization",id.toString(),old.lockVersion());
        if(!old.code().equals(input.code())) throw conflict("ORGANIZATION_CODE_IMMUTABLE","기관 코드는 변경할 수 없습니다.");
        if(old.active() && !input.active() && organizationInUse(id))throw conflict("ORGANIZATION_IN_USE","계정·허브·데이터에서 사용 중인 기관은 비활성화할 수 없습니다.");
        if(jdbc.update("UPDATE identity.organization SET inst_nm=?,stts_cd=?,lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=? AND lck_ver_no=?",
                input.name().strip(),input.active()?"ACTIVE":"INACTIVE",id,old.lockVersion())!=1)throw versionConflict();
        support.audit(actor.userId(),id,"ORGANIZATION_UPDATED","ORGANIZATION",id.toString(),requestId);return organization(id);
    }
    private CatalogPrincipal lock(Authentication auth) {
        var actor=support.platform(auth);
        // Serialize administrator-removal checks and identity mutations without schema changes.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(1749026112)");
        if(auth instanceof LocalAuthenticationToken local) {
            var current=lockAccount(actor.userId());
            if(!current.active() || current.lockVersion()!=local.version() || !current.roles().contains("PLATFORM_ADMIN"))throw new AccessDeniedException("Current platform account required");
        }
        return actor;
    }
    private ManagedAccount lockAccount(UUID id) {
        if(jdbc.query("SELECT id FROM identity.local_account WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),id).isEmpty())throw missing();
        return account(id);
    }
    private ManagedAccount account(UUID id) {
        var roles=jdbc.query("SELECT role_cd FROM identity.local_account_role WHERE acnt_id=? ORDER BY role_cd",(rs,n)->rs.getString(1),id);
        var orgs=jdbc.query("SELECT inst_id FROM identity.local_account_organization WHERE acnt_id=? ORDER BY inst_id",(rs,n)->rs.getObject(1,UUID.class),id);
        var rows=jdbc.query("SELECT id,lgn_id,stts_cd,auth_ver_no,crt_dt,mdfcn_dt FROM identity.local_account WHERE id=?",
            (rs,n)->new ManagedAccount(rs.getObject(1,UUID.class),rs.getString(2),"ACTIVE".equals(rs.getString(3)),roles,orgs,rs.getLong(4),rs.getObject(5,java.time.OffsetDateTime.class),rs.getObject(6,java.time.OffsetDateTime.class)),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private void validateAssignments(List<String> roles,List<UUID> organizations) {
        if(roles==null || roles.isEmpty() || roles.size()>5 || !ROLES.containsAll(roles) || Set.copyOf(roles).size()!=roles.size()
                || organizations==null || organizations.size()>100 || Set.copyOf(organizations).size()!=organizations.size())throw new IllegalArgumentException("Invalid account scope");
        if(!roles.contains("PLATFORM_ADMIN") && organizations.isEmpty())throw conflict("ACCOUNT_ORGANIZATION_REQUIRED","기관 담당 역할에는 담당 기관을 지정하세요.");
        for(UUID id:organizations.stream().sorted().toList()) {
            var status=jdbc.query("SELECT stts_cd FROM identity.organization WHERE id=? FOR SHARE",(rs,n)->rs.getString(1),id);
            if(status.size()!=1 || !"ACTIVE".equals(status.getFirst()))throw conflict("ORGANIZATION_INACTIVE","활성 기관만 계정에 지정할 수 있습니다.");
        }
    }
    private void replaceAssignments(UUID id,List<String> roles,List<UUID> organizations) {
        jdbc.update("DELETE FROM identity.local_account_role WHERE acnt_id=?",id);
        for(String role:roles)jdbc.update("INSERT INTO identity.local_account_role(acnt_id,role_cd) VALUES (?,?)",id,role);
        jdbc.update("DELETE FROM identity.local_account_organization WHERE acnt_id=?",id);
        for(UUID organization:organizations)jdbc.update("INSERT INTO identity.local_account_organization(acnt_id,inst_id) VALUES (?,?)",id,organization);
    }
    private long usableAdminCount() {
        return jdbc.queryForObject("SELECT count(*) FROM identity.local_account a WHERE a.stts_cd='ACTIVE' AND EXISTS(SELECT 1 FROM identity.local_account_role r WHERE r.acnt_id=a.id AND r.role_cd='PLATFORM_ADMIN') AND NOT EXISTS(SELECT 1 FROM identity.local_account_organization m JOIN identity.organization o ON o.id=m.inst_id WHERE m.acnt_id=a.id AND o.stts_cd<>'ACTIVE')",Long.class);
    }
    private void validatePassword(String password) {
        if(password==null || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72 || password.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid password length");
    }
    private ManagedOrganization organization(UUID id) {
        var rows=jdbc.query("SELECT id,cd,inst_nm,stts_cd,lck_ver_no,crt_dt,mdfcn_dt FROM identity.organization WHERE id=?",this::mapOrganization,id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private ManagedOrganization mapOrganization(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new ManagedOrganization(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),"ACTIVE".equals(rs.getString(4)),rs.getLong(5),rs.getObject(6,java.time.OffsetDateTime.class),rs.getObject(7,java.time.OffsetDateTime.class));
    }
    private boolean organizationInUse(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.local_account_organization WHERE inst_id=?) OR EXISTS(SELECT 1 FROM integration.source_connection WHERE inst_id=?) OR EXISTS(SELECT 1 FROM catalog.dataset WHERE psn_inst_id=?) OR EXISTS(SELECT 1 FROM catalog.dataset_revision WHERE pvsn_inst_id=?) OR EXISTS(SELECT 1 FROM standard.dictionary WHERE inst_id=?)",Boolean.class,id,id,id,id,id));
    }
}

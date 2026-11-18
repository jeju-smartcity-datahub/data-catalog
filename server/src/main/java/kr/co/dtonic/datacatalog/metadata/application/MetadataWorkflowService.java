package kr.co.dtonic.datacatalog.metadata.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.persistence.MetadataRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MetadataWorkflowService {
    private static final String[] READ={"ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER","ORG_AUDITOR"};
    private final MetadataRepository repository;
    private final JdbcTemplate db;
    private final WorkflowAccess access;
    private final ObjectMapper json;
    private final MetadataDiscoveryService discovery;
    public MetadataWorkflowService(MetadataRepository repository,WorkflowAccess access,ObjectMapper json,MetadataDiscoveryService discovery) {
        this.repository=repository; this.db=repository.jdbc(); this.access=access; this.json=json;this.discovery=discovery;
    }
    @Transactional(readOnly=true)
    public MetadataDraft getDraft(UUID id,Authentication auth) {
        MetadataDraft draft=repository.draft(id);
        if(draft==null) throw missing();
        access.require(auth,draft.ownerOrganizationId(),READ);
        activeOrganization(draft.ownerOrganizationId());
        return draft;
    }
    @Transactional(readOnly=true)
    public MetadataPage list(Authentication auth,UUID organizationId,String q,String state,int page,int size) {
        var principal=access.principal(auth);
        var args=new ArrayList<Object>();
        String scope=scope(auth,principal,organizationId,args,"d.psn_inst_id");
        if(q!=null&&!q.isBlank()) { scope+=" AND (r.ttl ILIKE ? ESCAPE '!' OR r.expln ILIKE ? ESCAPE '!')"; args.add(like(q));args.add(like(q)); }
        if(state!=null) {
            if(!List.of("DRAFT","IN_REVIEW","APPROVED","REJECTED").contains(state)) throw invalid("상태 값을 확인하세요.");
            scope+=" AND r.stts_cd=?";args.add(state);
        }
        long total=db.queryForObject("SELECT count(*) "+MetadataRepository.DRAFT_FROM+scope,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return new MetadataPage(db.query(MetadataRepository.DRAFT_SELECT+MetadataRepository.DRAFT_FROM+scope+
                " ORDER BY d.mdfcn_dt DESC,d.id LIMIT ? OFFSET ?",MetadataRepository.DRAFT,args.toArray()),Page.of(page,size,total));
    }
    public MetadataDraft create(CreateDataset input,String key,Authentication auth,String requestId) {
        var principal=access.require(auth,input.ownerOrganizationId(),"ORG_EDITOR");
        activeOrganization(input.ownerOrganizationId());
        return once(principal,"create",key,input,MetadataDraft.class,()->{
            validateReferences(input.providerOrganizationId(),input.primaryCategoryId());
            UUID id=UUID.randomUUID(),revision=UUID.randomUUID();
            int inserted=db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?) ON CONFLICT(slug_nm) DO NOTHING",id,input.ownerOrganizationId(),input.slug());
            if(inserted==0) throw conflict("DUPLICATE_SLUG","이미 사용 중인 식별자입니다.");
            db.update("""
                INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,ttl,expln,pvsn_inst_id,
                main_clsf_id,rls_rng_cd,creatr_id,mdfr_id) VALUES (?,?,1,?,?,?,?,?,?,?)
                """,revision,id,input.title().strip(),input.description(),input.providerOrganizationId(),input.primaryCategoryId(),input.visibility(),principal.userId(),principal.userId());
            discovery.apply(revision,input.primaryCategoryId(),input.regionIds(),input.hubIds(),input.topicIds(),input.details());
            audit(id,principal.userId(),"DRAFT_CREATED",revision,requestId,Map.of("revisionId",revision));
            return repository.draft(id);
        });
    }
    public MetadataDraft edit(UUID id,EditDraft input,String etag,Authentication auth,String requestId) {
        var draft=editable(id,etag,auth);
        validateReferences(input.providerOrganizationId(),input.primaryCategoryId());
        var changed=new ArrayList<String>();
        if(!draft.title().equals(input.title().strip()))changed.add("title");
        if(!draft.description().equals(input.description()))changed.add("description");
        if(!draft.providerOrganizationId().equals(input.providerOrganizationId()))changed.add("providerOrganizationId");
        if(!Objects.equals(draft.primaryCategoryId(),input.primaryCategoryId()))changed.add("primaryCategoryId");
        if(!draft.visibility().equals(input.visibility()))changed.add("visibility");
        if(input.regionIds()!=null)changed.add("regionIds");
        if(input.hubIds()!=null)changed.add("hubIds");
        if(input.topicIds()!=null)changed.add("topicIds");
        if(input.details()!=null)changed.add("details");
        db.update("""
            UPDATE catalog.dataset_revision SET ttl=?,expln=?,pvsn_inst_id=?,main_clsf_id=?,
            rls_rng_cd=?,mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=? AND stts_cd='DRAFT'
            """,input.title().strip(),input.description(),input.providerOrganizationId(),input.primaryCategoryId(),input.visibility(),access.principal(auth).userId(),draft.revisionId());
        discovery.apply(draft.revisionId(),input.primaryCategoryId(),input.regionIds(),input.hubIds(),input.topicIds(),input.details());
        bump(id,draft.lockVersion());
        audit(id,access.principal(auth).userId(),"DRAFT_UPDATED",draft.revisionId(),requestId,Map.of("beforeVersion",draft.lockVersion(),"afterVersion",draft.lockVersion()+1,"changedFields",changed));
        return repository.draft(id);
    }
    public MetadataDraft newDraft(UUID id,String etag,String key,Authentication auth,String requestId) {
        var principal=authorized(id,auth,"ORG_EDITOR");
        return once(principal,"drafts:"+id,key,Map.of("etag",required(etag)),MetadataDraft.class,()->{
            repository.lockDataset(id);var current=repository.draft(id);match(etag,"dataset",id,current.lockVersion());
            if(!List.of("APPROVED","REJECTED").contains(current.state())) throw transition();
            UUID revision=UUID.randomUUID();
            db.update("""
                INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,ttl,expln,pvsn_inst_id,
                    main_clsf_id,rls_rng_cd,mtdt_cn,creatr_id,mdfr_id)
                SELECT ?,dtset_id AS dataset_id,rvsn_no+1,ttl AS title,expln AS description,pvsn_inst_id AS provider_organization_id,main_clsf_id AS primary_category_id,rls_rng_cd AS visibility,mtdt_cn AS metadata,?,?
                FROM catalog.dataset_revision WHERE id=?
                """,revision,principal.userId(),principal.userId(),current.revisionId());
            discovery.copy(current.revisionId(),revision);
            bump(id,current.lockVersion());audit(id,principal.userId(),"DRAFT_COPIED",revision,requestId,Map.of("sourceRevisionId",current.revisionId()));
            return repository.draft(id);
        });
    }
    public ReviewRequest submit(UUID id,String etag,String key,Authentication auth,String requestId) {
        var principal=authorized(id,auth,"ORG_EDITOR");
        return once(principal,"submit:"+id,key,Map.of("etag",required(etag)),ReviewRequest.class,()->{
            var draft=editable(id,etag,auth);RequiredMetadata.requireComplete(draft);
            validateReferences(draft.providerOrganizationId(),draft.primaryCategoryId());
            discovery.apply(draft.revisionId(),draft.primaryCategoryId(),null,null,null,null);
            db.update("""
                UPDATE catalog.dataset_revision SET mtdt_cn=jsonb_set(mtdt_cn,'{publicationLabels}',
                  jsonb_build_object('providerOrganizationName',(SELECT inst_nm FROM identity.organization WHERE id=pvsn_inst_id),
                    'primaryCategoryName',(SELECT nm AS name FROM classification.category WHERE id=main_clsf_id)),true)
                WHERE id=?
                """,draft.revisionId());
            String hash=revisionHash(draft.revisionId());UUID review=UUID.randomUUID();
            db.update("UPDATE catalog.dataset_revision SET stts_cd='IN_REVIEW',cn_hash_vl=? WHERE id=? AND stts_cd='DRAFT'",hash,draft.revisionId());
            db.update("INSERT INTO metadata.review_request(id,dtset_id,rvsn_id,cn_hash_vl,stts_cd,sbmsn_user_id) VALUES (?,?,?,?,'SUBMITTED',?)",
                    review,id,draft.revisionId(),hash,principal.userId());
            bump(id,draft.lockVersion());audit(id,principal.userId(),"REVIEW_SUBMITTED",review,requestId,Map.of("revisionId",draft.revisionId(),"contentHash",hash));
            return repository.review(review);
        });
    }
    @Transactional(readOnly=true)
    public ReviewRequest getReview(UUID id,Authentication auth) {
        var review=repository.review(id);if(review==null) throw missing();getDraft(review.datasetId(),auth);return review;
    }
    @Transactional(readOnly=true)
    public ReviewPage reviews(Authentication auth,UUID organizationId,String status,int page,int size) {
        var principal=access.principal(auth);var args=new ArrayList<Object>();
        String scope=scope(auth,principal,organizationId,args,"d.psn_inst_id");
        if(status!=null) {
            if(!List.of("SUBMITTED","APPROVED","REJECTED","WITHDRAWN").contains(status))throw invalid("검토 상태를 확인하세요.");
            scope+=" AND q.stts_cd=?";args.add(status);
        }
        String from=" FROM metadata.review_request q JOIN catalog.dataset d ON d.id=q.dtset_id ";
        long total=db.queryForObject("SELECT count(*)"+from+scope,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        var items=db.query(MetadataRepository.REVIEW_SELECT+" JOIN catalog.dataset d ON d.id=q.dtset_id "+scope+
                " ORDER BY q.sbmsn_dt DESC,q.id LIMIT ? OFFSET ?",MetadataRepository.REVIEW,args.toArray());
        return new ReviewPage(items,Page.of(page,size,total));
    }
    public ReviewRequest decide(UUID id,DecideReview input,String etag,String key,Authentication auth,String requestId) {
        var review=getReview(id,auth);var principal=authorized(review.datasetId(),auth,"ORG_REVIEWER");
        return once(principal,"decide:"+id,key,List.of(required(etag),input),ReviewRequest.class,()->{
            repository.lockDataset(review.datasetId());var current=repository.review(id);match(etag,"review",id,current.lockVersion());
            if(!current.status().equals("SUBMITTED"))throw transition();
            if("REJECT".equals(input.decision())&&(input.reason()==null||input.reason().isBlank()))throw invalid("반려 사유를 입력하세요.");
            if(!current.contentHash().equals(revisionHash(current.revisionId())))throw conflict("REVIEW_CONTENT_CHANGED","검토 대상 내용이 변경되었습니다.");
            if("APPROVE".equals(input.decision())) {
                var target=repository.draft(current.datasetId());
                if(!target.revisionId().equals(current.revisionId()))throw conflict("REVIEW_CONTENT_CHANGED","검토 대상 버전을 다시 확인하세요.");
                RequiredMetadata.requireComplete(target);
            }
            UUID decision=UUID.randomUUID();String state=input.decision().equals("APPROVE")?"APPROVED":"REJECTED";
            db.update("INSERT INTO metadata.review_decision(id,rvw_dmnd_id,rvw_cn_hash_vl,dcsn_cd,rvwr_id,rsn) VALUES (?,?,?,?,?,?)",
                    decision,id,current.contentHash(),input.decision(),principal.userId(),input.reason());
            db.update("UPDATE metadata.review_request SET stts_cd=?,lck_ver_no=lck_ver_no+1 WHERE id=? AND lck_ver_no=?",state,id,current.lockVersion());
            db.update("UPDATE catalog.dataset_revision SET stts_cd=? WHERE id=? AND stts_cd='IN_REVIEW'",state,current.revisionId());
            var draft=repository.draft(review.datasetId());bump(draft.id(),draft.lockVersion());
            audit(draft.id(),principal.userId(),"REVIEW_"+state,id,requestId,Map.of("decisionId",decision,"revisionId",current.revisionId()));
            return repository.review(id);
        });
    }
    public ReviewRequest withdrawReview(UUID id,Withdrawal input,String etag,String key,Authentication auth,String requestId) {
        var review=getReview(id,auth);var principal=authorized(review.datasetId(),auth,"ORG_EDITOR");
        return once(principal,"withdraw-review:"+id,key,List.of(required(etag),input),ReviewRequest.class,()->{
            repository.lockDataset(review.datasetId());var current=repository.review(id);match(etag,"review",id,current.lockVersion());
            if(!current.status().equals("SUBMITTED"))throw transition();
            db.update("UPDATE metadata.review_request SET stts_cd='WITHDRAWN',lck_ver_no=lck_ver_no+1 WHERE id=?",id);
            db.update("UPDATE catalog.dataset_revision SET stts_cd='REJECTED' WHERE id=?",current.revisionId());
            var draft=repository.draft(review.datasetId());bump(draft.id(),draft.lockVersion());
            audit(draft.id(),principal.userId(),"REVIEW_WITHDRAWN",id,requestId,Map.of("reason",input.reason()));
            return repository.review(id);
        });
    }
    public Publication publish(UUID id,PublishRevision input,String etag,String key,Authentication auth,String requestId) {
        var principal=authorized(id,auth,"ORG_PUBLISHER");
        return once(principal,"publish:"+id,key,List.of(required(etag),input),Publication.class,()->{
            repository.lockDataset(id);var draft=repository.draft(id);match(etag,"dataset",id,draft.lockVersion());
            if(!draft.state().equals("APPROVED")||!draft.revisionId().equals(input.revisionId())||!input.reviewDecisionId().equals(draft.reviewDecisionId()))throw transition();
            RequiredMetadata.requireComplete(draft);
            validateReferences(draft.providerOrganizationId(),draft.primaryCategoryId());
            long existing=db.queryForObject("SELECT count(*) FROM catalog.publication WHERE rvsn_id=?",Long.class,draft.revisionId());
            if(existing>0)throw conflict("ALREADY_PUBLISHED","이미 게시 이력이 있는 버전입니다. 새 초안을 만들어 검토하세요.");
            int version=db.queryForObject("SELECT coalesce(max(pstg_no),0)+1 FROM catalog.publication WHERE dtset_id=?",Integer.class,id);
            UUID publication=UUID.randomUUID();
            db.update("""
                INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,acs_stts_cd,rvw_dcsn_id,pstg_user_id)
                VALUES (?,?,?,?,'PUBLISHED','METADATA_ONLY',?,?)
                """,publication,id,draft.revisionId(),version,input.reviewDecisionId(),principal.userId());
            db.update("UPDATE catalog.dataset SET now_pstg_id=?,lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=? AND lck_ver_no=?",
                    publication,id,draft.lockVersion());
            audit(id,principal.userId(),"PUBLICATION_CREATED",publication,requestId,Map.of("revisionId",draft.revisionId(),"publicationNo",version,"accessState","METADATA_ONLY"));
            return repository.publication(publication);
        });
    }
    @Transactional(readOnly=true)
    public Publication getPublication(UUID id,Authentication auth) {
        var publication=repository.publication(id);if(publication==null)throw missing();getDraft(publication.datasetId(),auth);return publication;
    }
    public Publication withdraw(UUID id,Withdrawal input,String etag,String key,Authentication auth,String requestId) {
        var publication=getPublication(id,auth);var principal=authorized(publication.datasetId(),auth,"ORG_PUBLISHER");
        return once(principal,"withdraw-publication:"+id,key,List.of(required(etag),input),Publication.class,()->{
            repository.lockDataset(publication.datasetId());var current=repository.publication(id);match(etag,"publication",id,current.lockVersion());
            if(!current.state().equals("PUBLISHED"))throw transition();
            db.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=CURRENT_TIMESTAMP,rcntt_rsn=?,lck_ver_no=lck_ver_no+1 WHERE id=? AND lck_ver_no=?",
                    input.reason().strip(),id,current.lockVersion());
            db.update("UPDATE catalog.dataset SET now_pstg_id=CASE WHEN now_pstg_id=? THEN NULL ELSE now_pstg_id END,lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=?",
                    id,current.datasetId());
            audit(current.datasetId(),principal.userId(),"PUBLICATION_WITHDRAWN",id,requestId,Map.of("reason",input.reason()));
            return repository.publication(id);
        });
    }
    @Transactional(readOnly=true)
    public MetadataOptions options(Authentication auth) {
        var principal=access.principal(auth);var args=new ArrayList<Object>();
        String scope=scope(auth,principal,null,args,"o.id");
        var organizations=db.query("SELECT o.id,o.inst_nm AS name FROM identity.organization o"+scope+" AND o.stts_cd='ACTIVE' ORDER BY o.inst_nm,o.id",
                (r,n)->new Option(r.getObject("id",UUID.class),r.getString("name")),args.toArray());
        var categories=db.query("SELECT id,nm AS name FROM classification.category WHERE actvtn_yn ORDER BY sort_seq,nm,id",
                (r,n)->new Option(r.getObject("id",UUID.class),r.getString("name")));
        var topics=db.query("SELECT c.id,c.nm AS name,c.up_id AS parent_id FROM classification.category c JOIN classification.category p ON p.id=c.up_id WHERE c.actvtn_yn AND p.actvtn_yn ORDER BY c.sort_seq,c.nm,c.id",
                (r,n)->new TopicOption(r.getObject("id",UUID.class),r.getString("name"),r.getObject("parent_id",UUID.class)));
        var regions=db.query("SELECT id,nm AS name FROM catalog.discovery_lookup WHERE actvtn_yn AND type_cd='REGION' ORDER BY nm,id",(r,n)->new Option(r.getObject("id",UUID.class),r.getString("name")));
        var hubs=db.query("SELECT id,nm AS name FROM catalog.discovery_lookup WHERE actvtn_yn AND type_cd='HUB' ORDER BY nm,id",(r,n)->new Option(r.getObject("id",UUID.class),r.getString("name")));
        return new MetadataOptions(organizations,categories,regions,hubs,topics);
    }
    @Transactional(readOnly=true)
    public AuditPage auditEvents(UUID id,int page,int size,Authentication auth) {
        authorized(id,auth,"ORG_AUDITOR");long total=db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE dtset_id=?",Long.class,id);
        var items=db.query("SELECT id,ocrn_dt AS occurred_at,prcs_user_id AS actor_user_id,inst_id AS organization_id,dtset_id AS dataset_id,prcs_cd AS action,trgt_type_cd AS target_type,trgt_id AS target_id,dmnd_id AS request_id,rslt_cd AS result,chg_smry_cn AS change_summary FROM audit.audit_event WHERE dtset_id=? ORDER BY ocrn_dt DESC,id DESC LIMIT ? OFFSET ?",(r,n)->
                new AuditEvent(r.getObject("id",UUID.class),r.getObject("occurred_at",OffsetDateTime.class),r.getObject("actor_user_id",UUID.class),
                        r.getString("action"),r.getString("target_type"),r.getObject("target_id",UUID.class),r.getString("request_id"),r.getString("result"),readMap(r.getString("change_summary"))),id,size,(long)page*size);
        return new AuditPage(items,Page.of(page,size,total));
    }
    /** The standard module validates fields before the draft and its audit entry are saved. */
    public MetadataDraft replaceFields(UUID id,String etag,List<Map<String,Object>> fields,Authentication auth,String requestId) {
        var draft=editable(id,etag,auth);
        db.update("UPDATE catalog.dataset_revision SET mtdt_cn=jsonb_set(mtdt_cn,'{fields}',?::jsonb,true),mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=?",
                encode(fields),access.principal(auth).userId(),draft.revisionId());
        bump(id,draft.lockVersion());audit(id,access.principal(auth).userId(),"DRAFT_FIELDS_UPDATED",draft.revisionId(),requestId,Map.of("fieldCount",fields.size()));
        return repository.draft(id);
    }

    /** Integration supplies normalized, reviewed metadata; this port never changes publication or access. */
    @Transactional
    public MetadataDraft replaceImportedMetadata(UUID id,String etag,List<Map<String,Object>> fields,Map<String,Object> hubMetadata,
            Authentication auth,String requestId) {
        var draft=editable(id,etag,auth);
        if(fields!=null) db.update("UPDATE catalog.dataset_revision SET mtdt_cn=jsonb_set(mtdt_cn,'{fields}',?::jsonb,true) WHERE id=?",encode(fields),draft.revisionId());
        db.update("UPDATE catalog.dataset_revision SET mtdt_cn=jsonb_set(mtdt_cn,'{hubMetadata}',?::jsonb,true),mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=?",
                encode(hubMetadata),access.principal(auth).userId(),draft.revisionId());
        bump(id,draft.lockVersion());
        audit(id,access.principal(auth).userId(),"HUB_METADATA_APPLIED",draft.revisionId(),requestId,Map.of("fieldCount",fields==null?0:fields.size()));
        return repository.draft(id);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> getDraftMetadata(UUID id,Authentication auth) {
        var draft=getDraft(id,auth);return readMap(db.queryForObject("SELECT mtdt_cn::text AS metadata FROM catalog.dataset_revision WHERE id=?",String.class,draft.revisionId()));
    }
    /** Integration application uses this within its transaction even when source content has not changed. */
    public MetadataDraft lockForImport(UUID id,String etag,Authentication auth) {
        authorized(id,auth,"ORG_EDITOR");repository.lockDataset(id);var draft=repository.draft(id);
        match(etag,"dataset",id,draft.lockVersion());return draft;
    }
    private MetadataDraft editable(UUID id,String etag,Authentication auth) {
        authorized(id,auth,"ORG_EDITOR");repository.lockDataset(id);var draft=repository.draft(id);match(etag,"dataset",id,draft.lockVersion());
        if(!draft.state().equals("DRAFT"))throw transition();return draft;
    }
    private CatalogPrincipal authorized(UUID id,Authentication auth,String... roles) {
        var draft=getDraft(id,auth);return access.require(auth,draft.ownerOrganizationId(),roles);
    }
    private void validateReferences(UUID organizationId,UUID categoryId) {
        activeOrganization(organizationId);
        if(categoryId!=null&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM classification.category WHERE id=? AND actvtn_yn)",Boolean.class,categoryId)))throw invalid("활성 분류를 선택하세요.");
    }
    private void activeOrganization(UUID id) {
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.organization WHERE id=? AND stts_cd='ACTIVE')",Boolean.class,id)))throw invalid("활성 기관을 선택하세요.");
    }
    private String scope(Authentication auth,CatalogPrincipal principal,UUID organizationId,List<Object> args,String column) {
        if(!access.isPlatformAdmin(auth)&&auth.getAuthorities().stream().noneMatch(a->java.util.Arrays.stream(READ).anyMatch(r->a.getAuthority().equals("ROLE_"+r))))
            throw new org.springframework.security.access.AccessDeniedException("Administrative role required");
        String active=" AND EXISTS(SELECT 1 FROM identity.organization permitted_org WHERE permitted_org.id="+column+" AND permitted_org.stts_cd='ACTIVE')";
        if(organizationId!=null){
            access.require(auth,organizationId,READ);
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.organization WHERE id=? AND stts_cd='ACTIVE')",Boolean.class,organizationId)))
                throw new org.springframework.security.access.AccessDeniedException("Active organization required");
            args.add(organizationId);return " WHERE "+column+"=?"+active;
        }
        if(access.isPlatformAdmin(auth))return " WHERE true"+active;
        if(principal.organizationIds().isEmpty())return " WHERE false";
        var allowed=principal.organizationIds().stream().filter(id->{access.require(auth,id,READ);return true;}).toList();
        args.addAll(allowed);return " WHERE "+column+" IN ("+String.join(",",allowed.stream().map(x->"?").toList())+")"+active;
    }
    private void bump(UUID id,long version) {
        if(db.update("UPDATE catalog.dataset SET lck_ver_no=lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP WHERE id=? AND lck_ver_no=?",id,version)!=1)
            throw new DomainException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","다른 사용자가 변경했습니다. 다시 조회하세요.");
    }
    private String revisionHash(UUID id) {
        return digest(db.queryForObject("""
            SELECT jsonb_build_object('title',ttl,'description',expln,'providerOrganizationId',pvsn_inst_id,
                'primaryCategoryId',main_clsf_id,'visibility',rls_rng_cd,'metadata',mtdt_cn)::text
            FROM catalog.dataset_revision WHERE id=?
            """,String.class,id));
    }
    private void audit(UUID dataset,UUID actor,String action,UUID target,String requestId,Map<String,?> summary) {
        String targetType=action.startsWith("REVIEW_")?"REVIEW_REQUEST":action.startsWith("PUBLICATION_")?"PUBLICATION":"DATASET_REVISION";
        db.update("""
            INSERT INTO audit.audit_event(id,prcs_user_id,inst_id,dtset_id,prcs_cd,trgt_type_cd,trgt_id,dmnd_id,chg_smry_cn)
            SELECT ?,?,psn_inst_id AS owner_organization_id,id,?,?,?,?,?::jsonb FROM catalog.dataset WHERE id=?
            """,UUID.randomUUID(),actor,action,targetType,target,requestId,encode(summary),dataset);
    }
    private <T> T once(CatalogPrincipal principal,String operation,String key,Object request,Class<T> type,Supplier<T> work) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw invalid("8~128자의 Idempotency-Key가 필요합니다.");
        String principalScope=principal.userId().toString(),keyHash=digest(key),requestHash=digest(encode(request));
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",principalScope+":"+operation+":"+keyHash);
        var records=db.queryForList("SELECT dmnd_hash_vl AS request_hash,rslt_cn::text AS result FROM metadata.idempotency_record WHERE user_id=? AND job_nm=? AND key_hash_vl=? AND expry_dt>CURRENT_TIMESTAMP",principalScope,operation,keyHash);
        if(!records.isEmpty()) {
            var record=records.getFirst();if(!record.get("request_hash").equals(requestHash))throw conflict("IDEMPOTENCY_CONFLICT","동일 키가 다른 요청에 사용되었습니다.");
            try{return json.readValue((String)record.get("result"),type);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid saved workflow result");}
        }
        T result=work.get();
        db.update("""
            INSERT INTO metadata.idempotency_record(user_id,job_nm,key_hash_vl,dmnd_hash_vl,rslt_cn) VALUES (?,?,?,?,?::jsonb)
            ON CONFLICT(user_id,job_nm,key_hash_vl) DO UPDATE SET dmnd_hash_vl=excluded.dmnd_hash_vl,rslt_cn=excluded.rslt_cn,expry_dt=CURRENT_TIMESTAMP+interval '24 hours'
            """,principalScope,operation,keyHash,requestHash,encode(result));return result;
    }
    public static String etag(String kind,UUID id,long version){return "\""+kind+":"+id+":"+version+"\"";}
    public static void match(String supplied,String kind,UUID id,long version) {
        required(supplied);if(!etag(kind,id,version).equals(supplied))throw new DomainException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","다른 사용자가 변경했습니다. 다시 조회하세요.");
    }
    private static String required(String etag){if(etag==null||etag.isBlank())throw new DomainException(HttpStatus.PRECONDITION_REQUIRED,"PRECONDITION_REQUIRED","If-Match가 필요합니다.");return etag;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot serialize workflow");}}
    private Map<String,Object> readMap(String value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(JsonProcessingException e){throw new IllegalStateException("Invalid metadata");}}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String like(String value){return "%"+value.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private static DomainException missing(){return new DomainException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","대상을 찾을 수 없습니다.");}
    private static DomainException invalid(String message){return new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",message);}
    private static DomainException conflict(String code,String message){return new DomainException(HttpStatus.CONFLICT,code,message);}
    private static DomainException transition(){return conflict("INVALID_STATE_TRANSITION","현재 상태에서 수행할 수 없는 작업입니다.");}
}

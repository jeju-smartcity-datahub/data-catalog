package kr.co.dtonic.datacatalog.integration.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.ApplyCandidate;
import kr.co.dtonic.datacatalog.integration.api.IntegrationPublicationModels.*;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationCandidateRepository;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationCandidateRepository.Candidate;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IntegrationPublicationService {
    private final JdbcTemplate db;
    private final IntegrationService connections;
    private final IntegrationCandidateService candidates;
    private final IntegrationCandidateRepository repository;
    private final MetadataWorkflowService metadata;
    private final WorkflowAccess access;
    private final ObjectMapper json;
    private final IntegrationPublicationQueue queue;
    private final TransactionTemplate tx;
    private final boolean scheduled;
    public IntegrationPublicationService(JdbcTemplate db,IntegrationService connections,IntegrationCandidateService candidates,
            IntegrationCandidateRepository repository,MetadataWorkflowService metadata,WorkflowAccess access,ObjectMapper json,
            IntegrationPublicationQueue queue,PlatformTransactionManager manager,@Value("${catalog.integration.worker-enabled:false}") boolean scheduled){
        this.db=db;this.connections=connections;this.candidates=candidates;this.repository=repository;this.metadata=metadata;
        this.access=access;this.json=json;this.queue=queue;this.scheduled=scheduled;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public PublicationPolicy policy(UUID id,Authentication auth){connections.get(auth,id);return readPolicy(id);}
    private PublicationPolicy readPolicy(UUID id){
        var list=db.query("SELECT * FROM integration.publication_policy WHERE lnkg_id=?",(r,n)->
            new PublicationPolicy(id,r.getBoolean("enabled"),r.getString("department"),r.getString("license"),
                words(r.getString("keywords")),r.getObject("default_clsf_id",UUID.class),r.getLong("lck_ver_no")),id);
        return list.isEmpty()?new PublicationPolicy(id,false,null,null,List.of(),null,0):list.getFirst();
    }
    public static String policyEtag(PublicationPolicy value){return "\"publication-policy:"+value.connectionId()+":"+value.lockVersion()+"\"";}
    public PublicationPolicy savePolicy(UUID id,PublicationPolicyInput input,String etag,Authentication auth,String requestId){
        requirePublisher(id,auth);validateInput(input);
        if(input.enabled())localAuthentication(access.principal(auth).userId(),connections.get(auth,id).organizationId());
        return tx.execute(status->{
            db.queryForObject("SELECT id FROM integration.source_connection WHERE id=? FOR UPDATE",UUID.class,id);
            var previous=readPolicy(id);
            if(etag==null)throw fail(HttpStatus.PRECONDITION_REQUIRED,"IF_MATCH_REQUIRED","현재 게시 설정을 다시 불러오세요.");
            if(!policyEtag(previous).equals(etag))throw fail(HttpStatus.PRECONDITION_FAILED,"STALE_PUBLICATION_POLICY","게시 설정이 변경되었습니다. 다시 확인하세요.");
            if(input.defaultCategoryId()!=null)activeCategory(input.defaultCategoryId());
            db.update("""
                INSERT INTO integration.publication_policy(lnkg_id,enabled,department,license,keywords,default_clsf_id,prcs_user_id,lck_ver_no)
                VALUES (?,?,?,?,?::jsonb,?,?,1) ON CONFLICT(lnkg_id) DO UPDATE SET enabled=EXCLUDED.enabled,
                department=EXCLUDED.department,license=EXCLUDED.license,keywords=EXCLUDED.keywords,default_clsf_id=EXCLUDED.default_clsf_id,
                prcs_user_id=EXCLUDED.prcs_user_id,lck_ver_no=integration.publication_policy.lck_ver_no+1,mdfcn_dt=CURRENT_TIMESTAMP
                """,id,input.enabled(),blankNull(input.department()),blankNull(input.license()),encode(input.keywords()==null?List.of():input.keywords()),input.defaultCategoryId(),access.principal(auth).userId());
            event(id,access.principal(auth).userId(),"PUBLICATION_POLICY_UPDATED",requestId);
            if(input.enabled())queue.enqueueAll(id.toString());
            return readPolicy(id);
        });
    }
    public PublicationPreview preview(UUID id,PublicationPreviewInput input,Authentication auth){
        requirePublisher(id,auth);validateIds(input.candidateIds());var policy=readPolicy(id);
        return new PublicationPreview(input.candidateIds().stream().map(candidate->inspect(id,candidate,policy,auth,false)).toList(),policy);
    }
    private PublicationPreviewItem inspect(UUID connection,UUID id,PublicationPolicy policy,Authentication auth,boolean automatic){
        var candidate=repository.get(id,false);requireCandidate(connection,candidate);
        var detail=candidates.detail(id,auth);
        var draft=candidate.datasetId()==null?null:metadata.getDraft(candidate.datasetId(),auth);
        boolean already=alreadyPublished(candidate,draft);
        var missing=new ArrayList<String>();
        if(!already){
            if(detail.conflict())missing.add("수동 변경 충돌");
            if(draft!=null&&draft.state().equals("IN_REVIEW"))missing.add("검토 중");
            if(automatic&&draft!=null&&!automaticUpdateAllowed(draft))missing.add("수동 관리 또는 비공개 자료");
        }
        var content=candidates.publicationContent(id,auth);
        var merged=filledDetails(content.details(),policy,candidate);
        if(!already){
            if(blank(content.title()))missing.add("제목");
            if(blank(content.description()))missing.add("설명");
            if(blank(merged.department()))missing.add("담당부서");
            if(blank(merged.license()))missing.add("라이선스");
            if(merged.keywords().isEmpty())missing.add("키워드");
        }
        UUID category=draft!=null&&draft.primaryCategoryId()!=null?draft.primaryCategoryId():mappedCategory(candidate,policy.defaultCategoryId());
        String categoryName=category==null?null:db.queryForObject("SELECT nm FROM classification.category WHERE id=?",String.class,category);
        return new PublicationPreviewItem(id,content.title(),detail.etag(),detail.targetEtag(),!already&&missing.isEmpty(),List.copyOf(missing),category,categoryName,already);
    }
    public BatchPublicationResult publish(UUID id,PublishCandidatesInput input,String key,Authentication auth,String requestId){
        requirePublisher(id,auth);
        if(input.items()==null||input.items().isEmpty()||input.items().size()>100)throw fail(HttpStatus.BAD_REQUEST,"BATCH_SIZE_INVALID","한 번에 1~100개를 선택하세요.");
        validateIds(input.items().stream().map(PublishCandidateInput::candidateId).toList());
        if(input.policyVersion()==null||input.policyVersion()<0)throw fail(HttpStatus.BAD_REQUEST,"POLICY_VERSION_REQUIRED","게시 설정을 다시 확인하세요.");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw fail(HttpStatus.BAD_REQUEST,"IDEMPOTENCY_KEY_REQUIRED","요청 식별자가 필요합니다.");
        var policy=readPolicy(id);var actor=access.principal(auth).userId();
        for(var item:input.items())requireCandidate(id,repository.get(item.candidateId(),false));
        UUID batch=batch(id,actor,key,encode(input),"MANUAL",input.policyVersion());
        policy=decode(db.queryForObject("SELECT pstg_stng_cn::text FROM integration.publication_batch WHERE id=?",String.class,batch),PublicationPolicy.class);
        var results=new ArrayList<PublicationResult>();
        for(var item:input.items())results.add(executeItem(batch,id,item,policy,auth,requestId,false,null));
        return result(results);
    }
    private PublicationResult executeItem(UUID batch,UUID connection,PublishCandidateInput input,PublicationPolicy policy,Authentication auth,String requestId,boolean automatic,Long configurationVersion){
        try{return tx.execute(status->{
            requirePublisher(connection,auth);
            db.queryForObject("SELECT id FROM integration.publication_batch WHERE id=? FOR UPDATE",UUID.class,batch);
            var previous=storedResult(batch,input.candidateId());if(previous!=null)return previous;
            db.queryForObject("SELECT id FROM integration.source_connection WHERE id=? FOR SHARE",UUID.class,connection);
            db.query("SELECT lnkg_id FROM integration.publication_policy WHERE lnkg_id=? FOR SHARE",(r,n)->r.getObject(1,UUID.class),connection);
            var current=readPolicy(connection);
            if(current.lockVersion()!=policy.lockVersion()||(automatic&&!current.enabled()))return record(batch,new PublicationResult(input.candidateId(),null,null,"SKIPPED","POLICY_CHANGED","게시 설정이 변경되어 건너뛰었습니다."));
            if(automatic){
                Long version=db.queryForObject("SELECT lck_ver_no FROM integration.source_connection WHERE id=? AND use_yn FOR SHARE",Long.class,connection);
                if(!Objects.equals(version,configurationVersion))throw fail(HttpStatus.CONFLICT,"CONNECTION_CHANGED","허브 연결 설정이 변경되었습니다.");
                authCheckLocal(auth,connection);
            }
            var candidate=repository.get(input.candidateId(),true);requireCandidate(connection,candidate);
            var detail=candidates.detail(candidate.id(),auth);
            if(!Objects.equals(detail.etag(),input.etag())||!Objects.equals(detail.targetEtag(),input.targetEtag()))throw fail(HttpStatus.PRECONDITION_FAILED,"STALE_CANDIDATE","자료가 변경되었습니다. 다시 확인하세요.");
            var preview=inspect(connection,candidate.id(),policy,auth,automatic);
            if(preview.alreadyPublished())return record(batch,new PublicationResult(candidate.id(),candidate.datasetId(),metadata.getDraft(candidate.datasetId(),auth).currentPublicationId(),"SKIPPED","ALREADY_PUBLISHED","이미 게시된 자료입니다."));
            if(!preview.publishable())throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"PUBLICATION_INPUT_REQUIRED","확인 필요: "+String.join(", ",preview.missingFields()));
            MetadataDraft draft;
            if(candidate.datasetId()==null||!candidate.sourceHash().equals(candidate.appliedHash())){
                var applied=candidates.apply(candidate.id(),new ApplyCandidate(detail.targetEtag()),detail.etag(),"bulk-apply:"+batch+":"+candidate.id(),auth,requestId);
                draft=metadata.getDraft(applied.datasetId(),auth);
            }else draft=metadata.getDraft(candidate.datasetId(),auth);
            if(draft.state().equals("APPROVED")&&draft.currentPublicationId()==null&&draft.visibility().equals("PUBLIC")
                    &&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.publication WHERE rvsn_id=?)",Boolean.class,draft.revisionId()))){
                var publication=metadata.publish(draft.id(),new PublishRevision(draft.revisionId(),draft.reviewDecisionId()),datasetEtag(draft),"bulk-publish:"+batch+":"+candidate.id(),auth,requestId);
                return record(batch,new PublicationResult(candidate.id(),draft.id(),publication.id(),"PUBLISHED",null,"게시했습니다."));
            }
            if(!draft.state().equals("DRAFT"))draft=metadata.newDraft(draft.id(),datasetEtag(draft),"bulk-draft:"+batch+":"+candidate.id(),auth,requestId);
            var details=filledDetails(draft.details(),policy,candidate);
            UUID category=draft.primaryCategoryId()!=null?draft.primaryCategoryId():mappedCategory(candidate,policy.defaultCategoryId());
            draft=metadata.edit(draft.id(),new EditDraft(draft.title(),draft.description(),draft.providerOrganizationId(),category,"PUBLIC",null,null,null,details),datasetEtag(draft),auth,requestId);
            var review=metadata.submit(draft.id(),datasetEtag(draft),"bulk-submit:"+batch+":"+candidate.id(),auth,requestId);
            var decision=metadata.decide(review.id(),new DecideReview("APPROVE",automatic?"자동 게시 설정에 따라 처리":"선택 게시 요청에 따라 처리"),
                MetadataWorkflowService.etag("review",review.id(),review.lockVersion()),"bulk-review:"+batch+":"+candidate.id(),auth,requestId);
            draft=metadata.getDraft(draft.id(),auth);
            var publication=metadata.publish(draft.id(),new PublishRevision(draft.revisionId(),decision.decisionId()),datasetEtag(draft),"bulk-publish:"+batch+":"+candidate.id(),auth,requestId);
            event(connection,access.principal(auth).userId(),automatic?"CANDIDATE_AUTO_PUBLISHED":"CANDIDATE_BULK_PUBLISHED",requestId);
            return record(batch,new PublicationResult(candidate.id(),draft.id(),publication.id(),"PUBLISHED",null,"게시했습니다."));
        });}catch(DomainException failure){return recordFailure(batch,input.candidateId(),failure.code(),failure.getMessage());}
        catch(AccessDeniedException denied){return recordFailure(batch,input.candidateId(),"PUBLICATION_PERMISSION_DENIED","게시 권한 또는 기관 범위를 확인하세요.");}
        catch(RuntimeException unexpected){return recordFailure(batch,input.candidateId(),"PUBLICATION_FAILED","게시하지 못했습니다. 항목을 확인한 뒤 다시 시도하세요.");}
    }
    private PublicationResult recordFailure(UUID batch,UUID candidate,String code,String message){
        return tx.execute(status->{var previous=storedResult(batch,candidate);if(previous!=null)return previous;
            var row=repository.get(candidate,false);
            UUID connection=db.queryForObject("SELECT lnkg_id FROM integration.publication_batch WHERE id=?",UUID.class,batch);
            requireCandidate(connection,row);
            return record(batch,new PublicationResult(candidate,row.datasetId(),null,"FAILED",code,message));});
    }
    private UUID batch(UUID connection,UUID actor,String key,String body,String mode,long policyVersion){return tx.execute(status->{
        String digest=IntegrationService.digest(key),hash=IntegrationService.digest(body);
        db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,connection+":"+actor+":"+digest);
        var rows=db.query("SELECT id,dmnd_hash_vl FROM integration.publication_batch WHERE lnkg_id=? AND prcs_user_id=? AND idmpt_key_hash_vl=?",(r,n)->Map.entry(r.getObject("id",UUID.class),r.getString("dmnd_hash_vl")),connection,actor,digest);
        if(!rows.isEmpty()){
            if(!hash.equals(rows.getFirst().getValue()))throw fail(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","같은 요청 식별자의 내용이 다릅니다.");
            return rows.getFirst().getKey();
        }
        db.queryForObject("SELECT id FROM integration.source_connection WHERE id=? FOR SHARE",UUID.class,connection);
        var policy=readPolicy(connection);
        if(policy.lockVersion()!=policyVersion)throw fail(HttpStatus.PRECONDITION_FAILED,"STALE_PUBLICATION_POLICY","게시 설정이 변경되었습니다. 다시 확인하세요.");
        UUID id=UUID.randomUUID();db.update("INSERT INTO integration.publication_batch(id,lnkg_id,prcs_user_id,idmpt_key_hash_vl,dmnd_hash_vl,mode,pstg_stng_cn) VALUES (?,?,?,?,?,?,?::jsonb)",id,connection,actor,digest,hash,mode,encode(policy));return id;
    });}
    private PublicationResult storedResult(UUID batch,UUID candidate){
        var rows=db.query("SELECT pstg_rslt_cn::text FROM integration.publication_result WHERE batch_id=? AND sou_cand_id=?",(r,n)->decode(r.getString(1),PublicationResult.class),batch,candidate);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private PublicationResult record(UUID batch,PublicationResult result){
        db.update("INSERT INTO integration.publication_result(batch_id,sou_cand_id,pstg_rslt_cn) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",batch,result.candidateId(),encode(result));return result;
    }
    public PublicationHistory history(UUID connection,Authentication auth){
        connections.get(auth,connection);
        var rows=db.query("""
            SELECT * FROM (
            SELECT r.sou_cand_id,s.cand_cn->>'title' AS title,r.pstg_rslt_cn->>'status' AS status,r.pstg_rslt_cn->>'code' AS code,
            r.pstg_rslt_cn->>'message' AS message,b.mode,r.crt_dt FROM integration.publication_result r
            JOIN integration.publication_batch b ON b.id=r.batch_id JOIN integration.source_metadata s ON s.id=r.sou_cand_id WHERE b.lnkg_id=?
            UNION ALL SELECT t.sou_cand_id,s.cand_cn->>'title',CASE WHEN t.stts_cd='FAILED' THEN 'FAILED' ELSE 'SKIPPED' END,t.err_cd,t.message,'AUTO',t.crt_dt
            FROM integration.auto_publication_task t JOIN integration.source_metadata s ON s.id=t.sou_cand_id
            WHERE t.lnkg_id=? AND t.stts_cd IN ('FAILED','DONE') AND t.err_cd IS NOT NULL
            ) history ORDER BY crt_dt DESC,sou_cand_id LIMIT 100
            """,(r,n)->new PublicationHistoryItem(r.getObject("sou_cand_id",UUID.class),r.getString("title"),r.getString("status"),r.getString("code"),r.getString("message"),r.getString("mode"),r.getObject("crt_dt",OffsetDateTime.class)),connection,connection);
        var counts=db.queryForObject("SELECT count(*) FILTER (WHERE stts_cd='PENDING'),count(*) FILTER (WHERE stts_cd='RUNNING') FROM integration.auto_publication_task WHERE lnkg_id=?",
                (r,n)->new long[]{r.getLong(1),r.getLong(2)},connection);
        return new PublicationHistory(rows,counts[0],counts[1]);
    }
    @Scheduled(fixedDelayString="${catalog.integration.publication-poll-delay-ms:2000}")
    public void poll(){if(scheduled)for(int i=0;i<20&&runAutomaticOnce();i++){} }
    private record Task(UUID id,UUID connection,UUID candidate,String hash,long version,long connectionVersion){}
    public boolean runAutomaticOnce(){
        var task=tx.execute(status->{
            var list=db.query("SELECT * FROM integration.auto_publication_task WHERE stts_cd='PENDING' OR (stts_cd='RUNNING' AND lease_until<CURRENT_TIMESTAMP) ORDER BY crt_dt,id FOR UPDATE SKIP LOCKED LIMIT 1",(r,n)->new Task(r.getObject("id",UUID.class),r.getObject("lnkg_id",UUID.class),r.getObject("sou_cand_id",UUID.class),r.getString("sou_hash_vl"),r.getLong("pstg_stng_ver_no"),r.getLong("lnkg_stng_ver_no")));
            if(list.isEmpty())return null;var item=list.getFirst();db.update("UPDATE integration.auto_publication_task SET stts_cd='RUNNING',lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes' WHERE id=?",item.id());return item;
        });
        if(task==null)return false;
        try{
            var policy=readPolicy(task.connection());
            var candidate=repository.get(task.candidate(),false);
            if(!policy.enabled()||policy.lockVersion()!=task.version()){finishTask(task,"DONE","POLICY_CHANGED","자동 게시 설정이 변경되어 건너뛰었습니다.");return true;}
            if(candidate==null||!candidate.sourceHash().equals(task.hash())){finishTask(task,"DONE","CANDIDATE_CHANGED","다시 수집된 자료로 변경되어 건너뛰었습니다.");return true;}
            UUID actor=db.queryForObject("SELECT prcs_user_id FROM integration.publication_policy WHERE lnkg_id=?",UUID.class,task.connection());
            var orgs=db.query("SELECT inst_id FROM integration.source_connection WHERE id=? AND use_yn AND lck_ver_no=?",(r,n)->r.getObject(1,UUID.class),task.connection(),task.connectionVersion());
            if(orgs.isEmpty()){finishTask(task,"DONE","CONNECTION_CHANGED","허브 연결이 변경되거나 사용 중지되어 건너뛰었습니다.");return true;}
            UUID org=orgs.getFirst();
            Authentication auth=localAuthentication(actor,org);
            var detail=candidates.detail(candidate.id(),auth);
            UUID batch=batch(task.connection(),actor,"auto:"+task.id(),task.hash()+":"+task.version(),"AUTO",task.version());
            var result=executeItem(batch,task.connection(),new PublishCandidateInput(candidate.id(),detail.etag(),detail.targetEtag()),policy,auth,"auto-publication-"+task.id(),true,task.connectionVersion());
            finishTask(task,result.status().equals("FAILED")?"FAILED":"DONE",null,null);
        }catch(AccessDeniedException denied){finishTask(task,"FAILED","PUBLICATION_PERMISSION_DENIED","자동 게시 설정자의 현재 권한을 확인하세요.");}
        catch(RuntimeException unexpected){finishTask(task,"FAILED","AUTO_PUBLICATION_FAILED","자동 게시 조건을 확인하고 설정을 다시 저장하세요.");}
        return true;
    }
    private void finishTask(Task task,String state,String code,String message){db.update("UPDATE integration.auto_publication_task SET stts_cd=?,lease_until=NULL,err_cd=?,message=? WHERE id=?",state,code,message,task.id());}
    private void authCheckLocal(Authentication auth,UUID connection){localAuthentication(access.principal(auth).userId(),connections.get(auth,connection).organizationId());}
    private Authentication localAuthentication(UUID actor,UUID org){
        Boolean active=db.queryForObject("SELECT EXISTS(SELECT 1 FROM identity.local_account a JOIN identity.organization o ON o.id=? WHERE a.id=? AND a.stts_cd='ACTIVE' AND o.stts_cd='ACTIVE')",Boolean.class,org,actor);
        if(!Boolean.TRUE.equals(active))throw new AccessDeniedException("Active local publication account required");
        Set<String> roles=Set.copyOf(db.query("SELECT role_cd FROM identity.local_account_role WHERE acnt_id=?",(r,n)->r.getString(1),actor));
        Set<UUID> organizations=Set.copyOf(db.query("SELECT inst_id FROM identity.local_account_organization WHERE acnt_id=?",(r,n)->r.getObject(1,UUID.class),actor));
        var auth=UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(actor,organizations),null,roles.stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());
        for(String role:List.of("ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER"))access.require(auth,org,role);return auth;
    }
    private void requirePublisher(UUID connection,Authentication auth){var view=connections.get(auth,connection);for(String role:List.of("ORG_EDITOR","ORG_REVIEWER","ORG_PUBLISHER"))access.require(auth,view.organizationId(),role);}
    private boolean alreadyPublished(Candidate candidate,MetadataDraft draft){
        return draft!=null&&draft.visibility().equals("PUBLIC")&&candidate.sourceHash().equals(candidate.appliedHash())&&draft.currentPublicationId()!=null&&draft.state().equals("APPROVED")
            &&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.publication WHERE id=? AND rvsn_id=? AND stts_cd='PUBLISHED')",Boolean.class,draft.currentPublicationId(),draft.revisionId()));
    }
    private boolean automaticUpdateAllowed(MetadataDraft draft){
        return draft.state().equals("APPROVED")&&draft.visibility().equals("PUBLIC")&&draft.currentPublicationId()!=null
            &&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog.publication WHERE id=? AND stts_cd='PUBLISHED')",Boolean.class,draft.currentPublicationId()));
    }
    private MetadataDetails filledDetails(MetadataDetails current,PublicationPolicy policy,Candidate candidate){
        var keywords=current.keywords().isEmpty()?policy.keywords():current.keywords();
        if(keywords.isEmpty()){
            var source=readMap(candidate.sourceDetails());String category=source.get("category") instanceof String value?value.strip():null;
            if(!blank(category)&&category.length()<=100)keywords=List.of(category);
            else if(!blank(candidate.title())&&candidate.title().strip().length()<=100)keywords=List.of(candidate.title().strip());
        }
        return new MetadataDetails(blank(current.department())?policy.department():current.department(),blank(current.license())?policy.license():current.license(),current.updateFrequency(),current.spatialCoverage(),current.temporalStart(),current.temporalEnd(),keywords);
    }
    private UUID mappedCategory(Candidate candidate,UUID fallback){
        Object source=readMap(candidate.sourceDetails()).get("category");
        if(source instanceof String text&&!text.isBlank()){
            String name=text.strip();
            var exact=db.query("SELECT id FROM classification.category WHERE actvtn_yn AND (nm=? OR cd=?) ORDER BY up_id NULLS FIRST,id LIMIT 2",(r,n)->r.getObject(1,UUID.class),name,name);
            if(exact.size()==1)return exact.getFirst();
            String alias=Map.of("교통","교통물류","환경","환경기상","기상","환경기상","관광","문화관광","문화","문화관광","재난","재난안전","안전","재난안전","복지","사회복지","농업","농축수산","수산","농축수산").get(name);
            if(alias!=null){var mapped=db.query("SELECT id FROM classification.category WHERE actvtn_yn AND nm=? ORDER BY id LIMIT 2",(r,n)->r.getObject(1,UUID.class),alias);if(mapped.size()==1)return mapped.getFirst();}
        }
        if(fallback!=null)activeCategory(fallback);return fallback;
    }
    private void activeCategory(UUID id){if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM classification.category WHERE id=? AND actvtn_yn)",Boolean.class,id)))throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"CATEGORY_UNAVAILABLE","사용할 수 있는 분류를 선택하세요.");}
    private void event(UUID connection,UUID actor,String action,String requestId){
        db.update("INSERT INTO integration.connection_event(id,lnkg_id,prcs_user_id,prcs_cd,dmnd_id,stng_ver_no) SELECT ?,id,?,?,?,lck_ver_no FROM integration.source_connection WHERE id=?",UUID.randomUUID(),actor,action,requestId,connection);
    }
    private void requireCandidate(UUID connection,Candidate candidate){if(candidate==null||!candidate.connectionId().equals(connection))throw fail(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND","선택한 연결의 자료가 아닙니다.");}
    private static String datasetEtag(MetadataDraft value){return MetadataWorkflowService.etag("dataset",value.id(),value.lockVersion());}
    private static BatchPublicationResult result(List<PublicationResult> items){return new BatchPublicationResult(List.copyOf(items),(int)items.stream().filter(i->i.status().equals("PUBLISHED")).count(),(int)items.stream().filter(i->i.status().equals("SKIPPED")).count(),(int)items.stream().filter(i->i.status().equals("FAILED")).count());}
    private void validateIds(List<UUID> ids){if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw fail(HttpStatus.BAD_REQUEST,"BATCH_SELECTION_INVALID","중복 없이 1~100개를 선택하세요.");}
    private void validateInput(PublicationPolicyInput input){
        if(input.department()!=null&&input.department().length()>200||input.license()!=null&&input.license().length()>500||input.keywords()!=null&&(input.keywords().size()>20||input.keywords().stream().anyMatch(w->blank(w)||w.length()>100)))throw fail(HttpStatus.BAD_REQUEST,"INVALID_PUBLICATION_DEFAULTS","공통 입력값의 길이와 형식을 확인하세요.");
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static String blankNull(String value){return blank(value)?null:value.strip();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode publication data");}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Cannot read publication result");}}
    private List<String> words(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Cannot read publication keywords");}}
    private Map<String,Object> readMap(String value){try{return json.readValue(value,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Cannot read candidate metadata");}}
    private static DomainException fail(HttpStatus status,String code,String message){return new DomainException(status,code,message);}
}

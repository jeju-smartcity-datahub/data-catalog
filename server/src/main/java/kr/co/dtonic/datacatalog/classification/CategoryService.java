package kr.co.dtonic.datacatalog.classification;

import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.classification.CategoryContracts.*;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.CategoryInput;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.*;

@Service
public class CategoryService extends EgovAbstractServiceImpl {
    private final CategoryMapper mapper;
    private final GovernanceSupport support;
    public CategoryService(CategoryMapper mapper,GovernanceSupport support) { this.mapper=mapper; this.support=support; }
    @Transactional(readOnly=true)
    public List<CategoryDetails> list(Authentication auth) { return mapper.categories(readScope(auth)); }
    @Transactional(readOnly=true)
    public CategoryDetails get(UUID id,Authentication auth) {
        var query=readScope(auth);query.put("id",id.toString());var value=mapper.category(query);if(value==null)throw missing();return value;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CategoryDatasetPage datasets(UUID id,int page,int size,Authentication auth) {
        var actor=support.reader(auth);required(id);
        if(page<0 || page>100000 || size<1 || size>100) throw new IllegalArgumentException("조회 범위를 확인하세요.");
        var query=params("id",id.toString(),"platform",support.isPlatform(auth),
                "organizations",actor.organizationIds().stream().map(UUID::toString).toList(),"size",size,"offset",(long)page*size);
        long total=mapper.datasetCount(query);
        return new CategoryDatasetPage(mapper.datasets(query),page,size,total,(total+size-1)/size);
    }
    /** Backward-compatible in-process callers omit the newly introduced optional description. */
    @Transactional
    public CategoryDetails create(CategoryInput body,Authentication auth,String requestId) {
        return create(new CategoryDetailsInput(body.parentId(),body.code(),body.name(),"",body.active(),body.ordinal()),auth,requestId);
    }
    @Transactional
    public CategoryDetails create(CategoryDetailsInput body,Authentication auth,String requestId) {
        var actor=support.platform(auth);
        validateDescription(body.description());
        if(body.parentId()!=null) {
            var parent=required(body.parentId());
            if(!parent.active() || parent.parentId()!=null) throw conflict("CATEGORY_PARENT_INVALID","활성 분야 아래에만 주제를 등록할 수 있습니다.");
        }
        String id=UUID.randomUUID().toString();
        try { mapper.insertCategory(params("id",id,"parentId",body.parentId()==null?null:body.parentId().toString(),"code",body.code(),"name",body.name().strip(),"description",body.description(),"active",body.active(),"ordinal",body.ordinal())); }
        catch(DataIntegrityViolationException e) { throw conflict("CATEGORY_CONFLICT","분류 코드 중복 또는 참조를 확인하세요."); }
        support.audit(actor.userId(),null,"CATEGORY_CREATED","CATEGORY",id,requestId);
        return required(UUID.fromString(id));
    }
    @Transactional
    public CategoryDetails update(UUID id,String ifMatch,CategoryInput body,Authentication auth,String requestId) {
        support.platform(auth);
        return update(id,ifMatch,new CategoryDetailsInput(body.parentId(),body.code(),body.name(),required(id).description(),body.active(),body.ordinal()),auth,requestId);
    }
    @Transactional
    public CategoryDetails update(UUID id,String ifMatch,CategoryDetailsInput body,Authentication auth,String requestId) {
        var actor=support.platform(auth);
        validateDescription(body.description());
        var old=required(id); match(ifMatch,"category",id.toString(),old.lockVersion());
        if(!old.code().equals(body.code()) || !java.util.Objects.equals(old.parentId(),body.parentId()==null?null:body.parentId().toString()))
            throw conflict("CATEGORY_IDENTITY_IMMUTABLE","분류 코드와 부모는 변경할 수 없습니다.");
        if(!body.active() && old.usageCount()>0) throw conflict("CATEGORY_IN_USE","사용 중인 분류는 비활성화할 수 없습니다.");
        try {
            int updated=mapper.updateCategory(params("id",id.toString(),"version",old.lockVersion(),"name",body.name().strip(),"description",body.description(),"active",body.active(),"ordinal",body.ordinal()));
            if(updated!=1) throw versionConflict();
        } catch(DataIntegrityViolationException e) { throw conflict("CATEGORY_IN_USE","사용 중인 분류는 변경할 수 없습니다."); }
        support.audit(actor.userId(),null,"CATEGORY_UPDATED","CATEGORY",id.toString(),requestId);
        return required(id);
    }
    @Transactional
    public void delete(UUID id,String ifMatch,Authentication auth,String requestId) {
        var actor=support.platform(auth); var old=required(id); match(ifMatch,"category",id.toString(),old.lockVersion());
        if(old.usageCount()>0) throw conflict("CATEGORY_IN_USE","사용 중인 분류는 삭제할 수 없습니다.");
        try { if(mapper.deleteCategory(params("id",id.toString(),"version",old.lockVersion()))!=1) throw versionConflict(); }
        catch(DataIntegrityViolationException e) { throw conflict("CATEGORY_IN_USE","사용 중인 분류는 삭제할 수 없습니다."); }
        support.audit(actor.userId(),null,"CATEGORY_DELETED","CATEGORY",id.toString(),requestId);
    }
    private java.util.Map<String,Object> readScope(Authentication auth) {
        var principal=support.reader(auth);return params("platform",support.isPlatform(auth),"organizations",principal.organizationIds().stream().map(UUID::toString).toList());
    }
    private CategoryDetails required(UUID id) { var value=mapper.category(params("id",id.toString(),"platform",true,"organizations",List.of())); if(value==null) throw missing(); return value; }
    private void validateDescription(String description) { if(description==null || description.length()>4000) throw new IllegalArgumentException("분류 설명은 4000자 이내여야 합니다."); }
}

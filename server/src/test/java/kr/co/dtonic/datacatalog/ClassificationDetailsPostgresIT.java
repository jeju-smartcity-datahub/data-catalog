package kr.co.dtonic.datacatalog;

import java.util.*;
import kr.co.dtonic.datacatalog.classification.CategoryService;
import kr.co.dtonic.datacatalog.classification.CategoryContracts.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ClassificationDetailsPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired CategoryService categories;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test @Transactional
    void descriptionPersistsWithExistingEtagAuditAndPlatformBoundary() {
        var org=organization();var admin=actor(Set.of(org),"PLATFORM_ADMIN");
        var category=create(null,"교통 분야",admin);var id=UUID.fromString(category.id());
        assertThat(category.description()).isEqualTo("이동과 교통 관련 데이터");
        var changed=categories.update(id,tag(category),input(category,"설명 수정\n두 번째 줄"),admin,"classification-details-test");
        assertThat(changed.description()).isEqualTo("설명 수정\n두 번째 줄");assertThat(changed.lockVersion()).isEqualTo(1);
        assertThat(categories.list(admin)).anySatisfy(row->{assertThat(row.id()).isEqualTo(category.id());assertThat(row.description()).isEqualTo(changed.description());});
        assertThatThrownBy(()->categories.update(id,tag(category),input(category,"오래된 수정"),admin,"classification-details-test"))
                .isInstanceOfSatisfying(DomainException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
        assertThatThrownBy(()->categories.update(id,tag(changed),input(changed,"기관 수정"),actor(Set.of(org),"ORG_EDITOR"),"classification-details-test"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE trgt_id=?",Long.class,id)).isEqualTo(2);
    }

    @Test @Transactional
    void rootIncludesChildrenAndDeduplicatesAllMatchingVersionsWithoutLeakingOtherOwners() {
        var own=organization();var other=organization();var admin=actor(Set.of(),"PLATFORM_ADMIN");
        var root=create(null,"교통",admin);var topic=create(UUID.fromString(root.id()),"주차",admin);
        var unrelated=create(null,"다른 분야",admin);
        UUID dataset=dataset(own);revision(dataset,own,topic,1,"APPROVED","과거 버전");
        UUID matching=revision(dataset,own,topic,2,"APPROVED","최신 연결 버전");
        revision(dataset,own,unrelated,3,"DRAFT","다른 분야의 현재 초안");
        UUID foreign=dataset(other);revision(foreign,other,topic,1,"DRAFT","타 기관 비공개 제목");
        var reader=actor(Set.of(own),"ORG_AUDITOR");
        var page=categories.datasets(UUID.fromString(root.id()),0,20,reader);
        assertThat(page.totalElements()).isEqualTo(1);assertThat(page.items()).hasSize(1);
        var item=page.items().getFirst();assertThat(item.datasetId()).isEqualTo(dataset.toString());assertThat(item.revisionId()).isEqualTo(matching.toString());
        assertThat(item.title()).isEqualTo("최신 연결 버전");assertThat(item.revisionNo()).isEqualTo(2);assertThat(item.currentRevision()).isFalse();
        assertThat(item.fieldName()).isEqualTo("교통");assertThat(item.topicName()).isEqualTo("주차");assertThat(item.visibility()).isEqualTo("PRIVATE");
        assertThat(categories.get(UUID.fromString(root.id()),reader).usageCount()).isEqualTo(1);
        assertThat(categories.get(UUID.fromString(topic.id()),reader).usageCount()).isEqualTo(2);
        assertThat(categories.get(UUID.fromString(topic.id()),admin).usageCount()).isEqualTo(3);
        assertThat(categories.datasets(UUID.fromString(topic.id()),0,20,reader).totalElements()).isEqualTo(1);
        assertThat(categories.datasets(UUID.fromString(root.id()),0,20,admin).totalElements()).isEqualTo(2);
    }

    @Test @Transactional
    void publicationAndWithdrawalDescribeTheMatchedRevisionAndPaginationIsStable() {
        var org=organization();var admin=actor(Set.of(),"PLATFORM_ADMIN");var category=create(null,"자료",admin);
        UUID first=dataset(org),second=dataset(org);UUID firstRevision=revision(first,org,category,1,"APPROVED","게시 자료");
        UUID secondRevision=revision(second,org,category,1,"APPROVED","철회 자료");
        publication(first,firstRevision,false);publication(second,secondRevision,true);
        var reader=actor(Set.of(org),"ORG_REVIEWER");var id=UUID.fromString(category.id());
        var all=categories.datasets(id,0,20,reader);
        assertThat(all.items()).extracting(CategoryDatasetUsage::publicationState).containsExactlyInAnyOrder("PUBLISHED","WITHDRAWN");
        assertThat(all.items()).allSatisfy(item->assertThat(item.currentRevision()).isTrue());
        var page0=categories.datasets(id,0,1,reader);var page1=categories.datasets(id,1,1,reader);
        assertThat(page0.totalElements()).isEqualTo(2);assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items().getFirst().datasetId()).isNotEqualTo(page1.items().getFirst().datasetId());
        assertThat(categories.datasets(id,0,1,reader)).isEqualTo(page0);
        assertThat(categories.datasets(id,2,1,reader).items()).isEmpty();
    }

    @Test @Transactional
    void inactiveAndUnassignedOrganizationsExposeNeitherRowsNorCounts() {
        var org=organization();var admin=actor(Set.of(),"PLATFORM_ADMIN");var category=create(null,"범위",admin);
        UUID dataset=dataset(org);revision(dataset,org,category,1,"DRAFT","비공개 자료");var id=UUID.fromString(category.id());
        assertThat(categories.datasets(id,0,20,actor(Set.of(),"ORG_AUDITOR")).totalElements()).isZero();
        assertThat(categories.get(id,actor(Set.of(),"ORG_AUDITOR")).usageCount()).isZero();
        var outsider=actor(Set.of(organization()),"ORG_AUDITOR");
        assertThat(categories.get(id,outsider).usageCount()).isZero();
        assertThat(categories.list(outsider)).filteredOn(row->row.id().equals(category.id())).allSatisfy(row->assertThat(row.usageCount()).isZero());
        assertThat(categories.get(id,admin).usageCount()).isEqualTo(1);
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        assertThat(categories.datasets(id,0,20,actor(Set.of(org),"ORG_EDITOR")).items()).isEmpty();
        assertThat(categories.get(id,actor(Set.of(org),"ORG_EDITOR")).usageCount()).isZero();
        assertThat(categories.datasets(id,0,20,admin).totalElements()).isZero();
        assertThatThrownBy(()->categories.datasets(id,0,20,actor(Set.of(org),"UNRELATED"))).isInstanceOf(AccessDeniedException.class);
        var generic=UsernamePasswordAuthenticationToken.authenticated("browser-user","unused",List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
        assertThatThrownBy(()->categories.datasets(id,0,20,generic)).isInstanceOf(AccessDeniedException.class);
    }

    @Test @Transactional
    void descriptionChangeDoesNotBypassInUseOrIdentityGuards() {
        var org=organization();var admin=actor(Set.of(org),"PLATFORM_ADMIN");var category=create(null,"참조 분야",admin);var id=UUID.fromString(category.id());
        revision(dataset(org),org,category,1,"DRAFT","자료");
        var updated=categories.update(id,tag(category),input(category,"사용 중 설명 수정"),admin,"classification-details-test");
        assertThat(updated.description()).isEqualTo("사용 중 설명 수정");
        assertThatThrownBy(()->categories.update(id,tag(updated),new CategoryDetailsInput(null,updated.code(),updated.name(),updated.description(),false,0),admin,"classification-details-test"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(()->categories.delete(id,tag(updated),admin,"classification-details-test")).isInstanceOf(DomainException.class);
        assertThatThrownBy(()->categories.update(id,tag(updated),new CategoryDetailsInput(null,"changed-code",updated.name(),"설명",true,0),admin,"classification-details-test"))
                .isInstanceOf(DomainException.class);
    }

    @Test @Transactional
    void apiEnforcesBoundsDescriptionValidationAndAnonymousBoundary() throws Exception {
        var org=organization();var admin=actor(Set.of(org),"PLATFORM_ADMIN");var category=create(null,"조회",admin);String url="/api/admin/v1/categories/"+category.id();
        mvc.perform(get(url+"/datasets").header("X-Role","PLATFORM_ADMIN")).andExpect(status().isUnauthorized());
        for(String query:List.of("?page=-1","?page=100001","?size=0","?size=101"))
            mvc.perform(get(url+"/datasets"+query).with(authentication(admin))).andExpect(status().isBadRequest());
        mvc.perform(get(url+"/datasets?page=0&size=1").with(authentication(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.items").isEmpty());
        String body="{\"code\":\""+category.code()+"\",\"name\":\"조회\",\"description\":\""+"a".repeat(4001)+"\",\"active\":true,\"ordinal\":0}";
        mvc.perform(patch(url).with(authentication(admin)).with(csrf()).header("If-Match",tag(category)).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/v1/categories/"+UUID.randomUUID()+"/datasets").with(authentication(admin))).andExpect(status().isNotFound());
    }

    @Test @Transactional
    void databaseBoundsDescriptionEvenOutsideHttpValidation() {
        var admin=actor(Set.of(),"PLATFORM_ADMIN");var category=create(null,"DB 설명",admin);
        assertThatThrownBy(()->db.update("UPDATE classification.category SET expln=? WHERE id=?","a".repeat(4001),UUID.fromString(category.id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CategoryDetails create(UUID parent,String name,Authentication admin) {
        return categories.create(new CategoryDetailsInput(parent,"class-"+UUID.randomUUID(),name,"이동과 교통 관련 데이터",true,0),admin,"classification-details-test");
    }
    private CategoryDetailsInput input(CategoryDetails c,String description) { return new CategoryDetailsInput(c.parentId()==null?null:UUID.fromString(c.parentId()),c.code(),c.name(),description,c.active(),c.ordinal()); }
    private String tag(CategoryDetails c) { return GovernanceSupport.etag("category",c.id(),c.lockVersion()); }
    private UUID organization() { UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES(?,?,?)",id,id.toString(),"분류 시험 기관");return id; }
    private UUID dataset(UUID org) { UUID id=UUID.randomUUID();db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES(?,?,?)",id,org,id.toString());return id; }
    private UUID revision(UUID dataset,UUID org,CategoryDetails category,int version,String state,String title) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,main_clsf_id,rls_rng_cd) VALUES(?,?,?,?,?,?,?,'PRIVATE')",
                id,dataset,version,state,title,org,UUID.fromString(category.id()));return id;
    }
    private void publication(UUID dataset,UUID revision,boolean withdrawn) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,rcntt_dt) VALUES(?,?,?,1,?,?)",
                id,dataset,revision,withdrawn?"WITHDRAWN":"PUBLISHED",withdrawn?java.time.OffsetDateTime.now():null);
        if(!withdrawn)db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",id,dataset);
    }
    private Authentication actor(Set<UUID> organizations,String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(new CatalogPrincipal(UUID.randomUUID(),organizations),"unused",
                Arrays.stream(roles).map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList());
    }
}

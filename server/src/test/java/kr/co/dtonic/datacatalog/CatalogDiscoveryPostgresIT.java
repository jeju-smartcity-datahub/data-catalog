package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.discovery.DiscoveryLookupController.*;
import kr.co.dtonic.datacatalog.catalog.discovery.DiscoveryLookupService;
import kr.co.dtonic.datacatalog.classification.CategoryService;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.identity.CatalogPrincipal;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class CatalogDiscoveryPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired MetadataWorkflowService metadata;
    @Autowired DiscoveryLookupService lookups;
    @Autowired CatalogQueryService catalog;
    @Autowired CategoryService categories;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }
    private final UUID actor=UUID.randomUUID(),reviewer=UUID.randomUUID();
    private final String marker="Discovery-"+UUID.randomUUID();
    private Authentication principal(UUID organization,UUID user,String role) {
        return new UsernamePasswordAuthenticationToken(new CatalogPrincipal(user,organization==null?Set.of():Set.of(organization)),"unused",List.of(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    private Authentication platform() { return principal(null,actor,"PLATFORM_ADMIN"); }
    private UUID organization() {
        UUID id=UUID.randomUUID();db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),marker);return id;
    }
    private UUID category(UUID parent) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO classification.category(id,up_id,cd,nm) VALUES (?,?,?,?)",id,parent,id.toString(),marker+id);return id;
    }
    private DiscoveryLookup lookup(String kind,String name) { return lookups.create(new CreateDiscoveryLookup(kind,"x-"+UUID.randomUUID(),name,true),platform(),"discovery-test"); }
    private MetadataDraft draft(UUID org,UUID primary,List<UUID> regions,List<UUID> hubs,List<UUID> topics,String visibility) {
        return metadata.create(new CreateDataset(org,"d-"+UUID.randomUUID(),marker,"description",org,primary,visibility,regions,hubs,topics,
                new MetadataDetails("담당 부서","표시용 라이선스","일별","수동 공간 설명",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),List.of("교통"))),UUID.randomUUID().toString(),principal(org,actor,"ORG_EDITOR"),"discovery-test");
    }
    private Publication publish(MetadataDraft draft) {
        var editor=principal(draft.ownerOrganizationId(),actor,"ORG_EDITOR");
        var request=metadata.submit(draft.id(),tag(draft),UUID.randomUUID().toString(),editor,"discovery-test");
        var decision=metadata.decide(request.id(),new DecideReview("APPROVE",null),MetadataWorkflowService.etag("review",request.id(),request.lockVersion()),UUID.randomUUID().toString(),principal(draft.ownerOrganizationId(),reviewer,"ORG_REVIEWER"),"discovery-test");
        var approved=metadata.getDraft(draft.id(),editor);
        return metadata.publish(draft.id(),new PublishRevision(approved.revisionId(),decision.decisionId()),tag(approved),UUID.randomUUID().toString(),principal(draft.ownerOrganizationId(),actor,"ORG_PUBLISHER"),"discovery-test");
    }
    private String tag(MetadataDraft draft) { return MetadataWorkflowService.etag("dataset",draft.id(),draft.lockVersion()); }
    private CatalogFilter filter(List<UUID> regions,List<UUID> hubs,List<UUID> topics) { return new CatalogFilter(marker,null,null,null,regions,hubs,topics); }

    @Test void independentFiltersUseOrWithinAndAcrossGroupsAndSelfExcludingDistinctFacets() throws Exception {
        UUID org=organization(),field=category(null),topic=category(field),otherTopic=category(field);
        var r1=lookup("REGION","지역 하나");var r2=lookup("REGION","지역 둘");var h1=lookup("HUB","허브 하나");var h2=lookup("HUB","허브 둘");
        var a=draft(org,field,List.of(r1.id(),r2.id()),List.of(h1.id()),List.of(topic,otherTopic),"PUBLIC");publish(a);
        var b=draft(org,field,List.of(r2.id()),List.of(h2.id()),List.of(topic),"PUBLIC");publish(b);
        assertThat(catalog.listPublished(0,10,filter(List.of(r1.id(),r2.id()),null,null)).page().totalElements()).isEqualTo(2);
        assertThat(catalog.listPublished(0,10,filter(List.of(r1.id()),List.of(h2.id()),null)).items()).isEmpty();
        var chosen=filter(List.of(r1.id()),List.of(h1.id()),List.of(topic,otherTopic));
        assertThat(catalog.listPublished(0,10,chosen).page().totalElements()).isEqualTo(1);
        var facets=catalog.facets(chosen);
        assertThat(facets.regions()).hasSize(2).allMatch(v->v.count()==1);
        assertThat(facets.topics()).hasSize(2).allMatch(v->v.count()==1);
        UUID unknown=UUID.randomUUID();
        assertThat(catalog.facets(filter(List.of(unknown),null,null)).regions()).anyMatch(v->v.id().equals(unknown.toString())&&v.count()==0&&v.name().equals("선택한 항목")&&v.selected());
        mvc.perform(get("/api/catalog/v1/datasets").param("q",marker).param("regionId",r1.id().toString()).param("hubId",h1.id().toString()).param("topicId",topic.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test void draftAndPrivateLabelsNeverLeakThroughPublicFacetsOrOptionsAndInactiveOwnerHidesThem() throws Exception {
        UUID org=organization();var hidden=lookup("REGION","비공개 지역 이름");var shown=lookup("REGION","공개 지역 이름");
        draft(org,null,List.of(hidden.id()),List.of(),List.of(),"PUBLIC");
        publish(draft(org,null,List.of(hidden.id()),List.of(),List.of(),"PRIVATE"));
        var publicDraft=draft(org,null,List.of(shown.id()),List.of(),List.of(),"PUBLIC");publish(publicDraft);
        assertThat(catalog.facets(filter(null,null,null)).regions()).extracting(v->v.name()).containsExactly("공개 지역 이름");
        mvc.perform(get("/api/catalog/v1/regions")).andExpect(status().isOk()).andExpect(content().string(not(containsString("비공개 지역 이름"))));
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",org);
        assertThat(catalog.facets(filter(null,null,null)).regions()).isEmpty();
        mvc.perform(get("/api/catalog/v1/datasets/"+publicDraft.id())).andExpect(status().isNotFound());
    }

    @Test void snapshotLabelsAndDetailsStayImmutableAcrossRenameNewDraftAndOldCallerEdit() {
        UUID org=organization(),field=category(null),topic=category(field);var region=lookup("REGION","게시 지역명");var hub=lookup("HUB","게시 허브명");
        var original=draft(org,field,List.of(region.id()),List.of(hub.id()),List.of(topic),"PUBLIC");publish(original);
        lookups.edit(region.id(),new EditDiscoveryLookup("변경된 분류명",true),MetadataWorkflowService.etag("discovery-lookup",region.id(),region.lockVersion()),platform(),"discovery-test");
        assertThat(catalog.detail(original.id(),1).regions().getFirst().name()).isEqualTo("게시 지역명");
        var editor=principal(org,actor,"ORG_EDITOR");var approved=metadata.getDraft(original.id(),editor);
        var next=metadata.newDraft(original.id(),tag(approved),UUID.randomUUID().toString(),editor,"discovery-test");
        // IntegrationCandidateService intentionally uses this compatibility constructor.
        var edited=metadata.edit(next.id(),new EditDraft("새 원천 제목","새 설명",org,field,"PUBLIC"),tag(next),editor,"discovery-test");
        assertThat(edited.regionIds()).containsExactly(region.id());assertThat(edited.hubIds()).containsExactly(hub.id());assertThat(edited.topicIds()).containsExactly(topic);
        assertThat(edited.details().department()).isEqualTo("담당 부서");
        assertThat(catalog.detail(original.id(),null).title()).isEqualTo(marker);
        publish(edited);
        assertThat(catalog.detail(original.id(),2).regions().getFirst().name()).isEqualTo("변경된 분류명");
        assertThat(catalog.detail(original.id(),1).regions().getFirst().name()).isEqualTo("게시 지역명");
        assertThat(catalog.detail(original.id(),1).details().temporalStart()).isEqualTo(LocalDate.of(2026,1,1));
    }

    @Test void lookupWritesRequireTrustedPlatformAndStrongEtagAndProtectAnyRevisionReference() throws Exception {
        UUID org=organization();var lookup=lookup("HUB","보호 허브");
        mvc.perform(get("/api/admin/v1/discovery-lookups").param("kind","HUB")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/discovery-lookups").param("kind","HUB").with(authentication(principal(org,actor,"ORG_EDITOR")))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/v1/discovery-lookups/"+lookup.id()).with(authentication(platform())).with(csrf()).contentType("application/json").content("{\"name\":\"수정\",\"active\":true}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(delete("/api/admin/v1/discovery-lookups/"+lookup.id()).with(authentication(platform())).with(csrf()).header("If-Match","\"discovery-lookup:"+UUID.randomUUID()+":0\""))
                .andExpect(status().isPreconditionFailed());
        draft(org,null,List.of(),List.of(lookup.id()),List.of(),"PRIVATE");
        assertThat(lookups.get(lookup.id(),platform()).referenceCount()).isEqualTo(1);
        mvc.perform(delete("/api/admin/v1/discovery-lookups/"+lookup.id()).with(authentication(platform())).with(csrf()).header("If-Match",MetadataWorkflowService.etag("discovery-lookup",lookup.id(),0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOOKUP_IN_USE"));
        assertThat(db.queryForObject("SELECT count(*) FROM audit.audit_event WHERE trgt_id=? AND prcs_cd='DISCOVERY_LOOKUP_CREATED'",Long.class,lookup.id())).isEqualTo(1);
        var unused=lookup("REGION","미사용");assertThat(lookups.deactivate(unused.id(),MetadataWorkflowService.etag("discovery-lookup",unused.id(),0),platform(),"discovery-test").active()).isFalse();
    }

    @Test void topicParentValidationAndUsageCountsCoverIndependentLinks() {
        UUID org=organization(),field=category(null),topic=category(field),different=category(null);var editor=principal(org,actor,"ORG_EDITOR");
        assertThatThrownBy(()->draft(org,different,List.of(),List.of(),List.of(topic),"PRIVATE")).isInstanceOf(DomainException.class);
        var draft=draft(org,field,List.of(),List.of(),List.of(topic),"PRIVATE");
        assertThat(categories.get(topic,platform()).usageCount()).isEqualTo(1);
        assertThat(categories.datasets(topic,0,10,editor).totalElements()).isEqualTo(1);
        assertThatThrownBy(()->metadata.edit(draft.id(),new EditDraft(marker,"x",org,different,"PRIVATE"),tag(draft),editor,"discovery-test")).isInstanceOf(DomainException.class);
    }

    @Test void explicitEmptyValuesClearOptionalMetadataWhileLegacyPayloadHashRemainsStable() throws Exception {
        UUID org=organization();var region=lookup("REGION","선택 지역");var draft=draft(org,null,List.of(region.id()),List.of(),List.of(),"PRIVATE");
        var input=new EditDraft(marker,"description",org,null,"PRIVATE",List.of(),List.of(),List.of(),MetadataDetails.empty());
        var cleared=metadata.edit(draft.id(),input,tag(draft),principal(org,actor,"ORG_EDITOR"),"discovery-test");
        assertThat(cleared.regionIds()).isEmpty();assertThat(cleared.details().keywords()).isEmpty();assertThat(cleared.details().department()).isNull();
        assertThat(lookups.get(region.id(),platform()).referenceCount()).isZero();
        String old=json.writeValueAsString(new CreateDataset(org,"legacy","t","d",org,null,"PRIVATE"));
        assertThat(old).doesNotContain("regionIds","hubIds","topicIds","details");
        mvc.perform(patch("/api/admin/v1/datasets/"+draft.id()+"/draft").with(authentication(principal(org,actor,"ORG_EDITOR"))).with(csrf())
                .header("If-Match",tag(cleared)).contentType("application/json").content("{\"title\":\"x\",\"description\":\"x\",\"providerOrganizationId\":\""+org+"\",\"visibility\":\"PRIVATE\",\"details\":{\"temporalStart\":\"2026-12-01\",\"temporalEnd\":\"2026-01-01\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test void approvedReferenceRowsCannotBeMutatedAndLegacySnapshotGetsNoInferredTags() {
        UUID org=organization();var region=lookup("REGION","고정 지역");var draft=draft(org,null,List.of(region.id()),List.of(),List.of(),"PUBLIC");publish(draft);
        var legacy=metadata.create(new CreateDataset(org,"old-"+UUID.randomUUID(),marker,"old",org,null,"PUBLIC"),UUID.randomUUID().toString(),principal(org,actor,"ORG_EDITOR"),"discovery-test");
        // Legacy draft input still omits optional JSON fields; complete registration metadata explicitly before review.
        legacy=metadata.edit(legacy.id(),new EditDraft(legacy.title(),legacy.description(),org,null,"PUBLIC",null,null,null,
                new MetadataDetails("시험 담당부서","시험용 이용조건",null,null,null,null,List.of("기존초안"))),tag(legacy),principal(org,actor,"ORG_EDITOR"),"discovery-test");publish(legacy);
        assertThat(catalog.detail(legacy.id(),null).regions()).isEmpty();assertThat(catalog.detail(legacy.id(),null).hubs()).isEmpty();
        assertThatThrownBy(()->db.update("DELETE FROM catalog.revision_discovery WHERE rvsn_id=?",draft.revisionId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.catalog.application.CatalogFilter;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogPublicPostgresIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogQueryService catalog;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test
    void filtersWithinGroupUseOrAndAcrossGroupsUseAndWithDisjunctiveFacets() throws Exception {
        UUID orgA=organization("Organization A"), orgB=organization("Organization B");
        UUID categoryA=category("Category A"), categoryB=category("Category B");
        UUID first=dataset(orgA,categoryA,"A catalog public test","PUBLIC","{}");
        dataset(orgB,categoryA,"B catalog public test","PUBLIC","{}");
        dataset(orgA,categoryB,"C catalog public test","PUBLIC","{}");
        dataset(orgB,categoryB,"Private catalog public test","PRIVATE","{}");
        var filter=new CatalogFilter("catalog public test",List.of(orgA,orgB),List.of(categoryA),"title,asc");
        assertThat(catalog.listPublished(0,10,filter).items()).extracting("title").containsExactly("A catalog public test","B catalog public test");
        var selected=new CatalogFilter("catalog public test",List.of(orgA),List.of(categoryA),"title,asc");
        assertThat(catalog.listPublished(0,10,selected).items()).extracting("id").containsExactly(first.toString());
        var facets=catalog.facets(selected);
        assertThat(facets.countMode()).isEqualTo("EXCLUDE_OWN_GROUP");
        assertThat(facets.organizations()).extracting("count").containsExactly(1L,1L);
        assertThat(facets.categories()).extracting("count").containsExactly(1L,1L);
        mvc.perform(get("/api/catalog/v1/datasets").param("q","catalog public test")
                .param("organizationId",orgA.toString(),orgB.toString()).param("categoryId",categoryA.toString()).param("sort","title,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void searchTreatsSqlWildcardCharactersAsLiteralAndRejectsUnsupportedSort() throws Exception {
        UUID org=organization("Literal");
        dataset(org,null,"catalog public test 100%_safe","PUBLIC","{}");
        dataset(org,null,"catalog public test other","PUBLIC","{}");
        var query=new CatalogFilter("%_",List.of(),List.of(),"relevance");
        assertThat(catalog.listPublished(0,10,query).items()).extracting("title").containsExactly("catalog public test 100%_safe");
        mvc.perform(get("/api/catalog/v1/datasets").param("sort","title;DROP TABLE catalog.dataset"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(get("/api/catalog/v1/datasets").param("organizationId","not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void publicDetailsExposeOnlyApprovedPublicFieldsAndSnapshotLabels() throws Exception {
        UUID org=organization("Changed live organization"), cat=category("Changed live category");
        String metadata="""
                {"secret":"NEVER_EXPOSE","publicationLabels":{"providerOrganizationName":"Published organization","primaryCategoryName":"Published category"},
                 "details":{"updateFrequency":"1th 00:00","spatialCoverage":"Nationwide","license":"CC BY","keywords":[]},
                 "hubMetadata":{"providerOrganization":"GIS","providerSystem":"GIS","ownership":"jeju","datamodelId":"G_SPEED_BUMP","sourceModifiedAt":"2023-06-12T06:43:55.956Z","provisioningRequestId":"PRIVATE_PROVISIONING_ID","metadataInfo":[{"code":"PERMISSION","value":"PRIVATE_PERMISSION"}]},
                 "fields":[
                   {"fieldPath":"count","physicalName":"count","displayName":"공개 수","description":"합계","dataType":"INTEGER","nullable":false,"unit":"개","publicVisible":true,"mappingEvidence":"PRIVATE_EVIDENCE"},
                   {"fieldPath":"credential","physicalName":"credential","publicVisible":false,"description":"PRIVATE_FIELD"},
                   {"fieldPath":"unclassified","physicalName":"unclassified","description":"UNCLASSIFIED_FIELD"}]}
                """;
        UUID id=dataset(org,cat,"Public details","PUBLIC",metadata);
        var response=mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("ETag","\"publication:"+id+":1\""))
                .andExpect(jsonPath("$.organization.name").value("Published organization"))
                .andExpect(jsonPath("$.category.name").value("Published category"))
                .andExpect(jsonPath("$.details.updateFrequency").value("1th 00:00"))
                .andExpect(jsonPath("$.details.spatialCoverage").value("Nationwide"))
                .andExpect(jsonPath("$.details.license").value("CC BY"))
                .andExpect(jsonPath("$.hubMetadata.providerOrganization").value("GIS"))
                .andExpect(jsonPath("$.hubMetadata.providerSystem").value("GIS"))
                .andExpect(jsonPath("$.hubMetadata.ownership").value("jeju"))
                .andExpect(jsonPath("$.hubMetadata.dataModelId").value("G_SPEED_BUMP"))
                .andExpect(jsonPath("$.hubMetadata.sourceModifiedAt").value("2023-06-12T06:43:55.956Z"))
                .andExpect(jsonPath("$.fields.length()").value(1))
                .andExpect(jsonPath("$.fields[0].physicalName").value("count"))
                .andExpect(jsonPath("$.access.capabilities[0]").value("METADATA_JSON"))
                .andExpect(jsonPath("$.access.capabilities[1]").value("DCAT_JSONLD")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("NEVER_EXPOSE","PRIVATE_EVIDENCE","PRIVATE_FIELD","UNCLASSIFIED_FIELD","mappingEvidence",
                "PRIVATE_PROVISIONING_ID","PRIVATE_PERMISSION","provisioningRequestId","metadataInfo");
        assertThat(catalog.listPublished(0,10,new CatalogFilter("Public details",List.of(),List.of(),null)).items().getFirst().organization().name()).isEqualTo("Published organization");
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/fields")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/access")).andExpect(status().isOk()).andExpect(jsonPath("$.accessState").value("METADATA_ONLY"));
    }

    @Test
    void privateAndWithdrawnCurrentPublicationBlockEveryHistoricalEndpoint() throws Exception {
        UUID org=organization("Visibility");
        UUID id=dataset(org,null,"Public v1","PUBLIC","{}");
        addPublication(id,org,2,"Public v2","PUBLIC");
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/catalog/v1/datasets/"+id+"/versions/1")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Public v1"));
        jdbc.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=clock_timestamp() WHERE dtset_id=? AND pstg_no=2",id);
        assertAllHidden(id);
        UUID privateId=dataset(org,null,"Private only","ORGANIZATION","{}");
        assertAllHidden(privateId);
        UUID republishedPrivate=dataset(org,null,"Former public","PUBLIC","{}");
        addPublication(republishedPrivate,org,2,"Now private","PRIVATE");
        assertAllHidden(republishedPrivate);
    }

    @Test
    void facetsDoNotDiscoverPrivateLabelsAndKeepUnknownSelectedZero() {
        UUID org=organization("PRIVATE_LABEL");
        dataset(org,null,"Private facet","PRIVATE","{}");
        var facets=catalog.facets(new CatalogFilter("Private facet",List.of(org),List.of(),null));
        assertThat(facets.organizations()).hasSize(1);
        assertThat(facets.organizations().getFirst().name()).isEqualTo("선택한 항목");
        assertThat(facets.organizations().getFirst().count()).isZero();
        assertThat(facets.organizations().getFirst().selected()).isTrue();
    }

    private void assertAllHidden(UUID id) throws Exception {
        for(String suffix:List.of("","/fields","/access","/versions","/versions/1")) {
            mvc.perform(get("/api/catalog/v1/datasets/"+id+suffix)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"));
        }
    }
    private UUID organization(String name) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),name);return id;
    }
    private UUID category(String name) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO classification.category(id,cd,nm) VALUES (?,?,?)",id,id.toString(),name);return id;
    }
    private UUID dataset(UUID org,UUID category,String title,String visibility,String metadata) {
        UUID id=UUID.randomUUID(),revision=UUID.randomUUID(),publication=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)",id,org,id.toString());
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,main_clsf_id,rls_rng_cd,mtdt_cn) VALUES (?,?,1,'APPROVED',?,?,?,?,?::jsonb)",
                revision,id,title,org,category,visibility,metadata);
        jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES (?,?,?,1,'PUBLISHED',clock_timestamp())",publication,id,revision);
        jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,id);return id;
    }
    private void addPublication(UUID id,UUID org,int number,String title,String visibility) {
        UUID revision=UUID.randomUUID(),publication=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd) VALUES (?,?,?,'APPROVED',?,?,?)",revision,id,number,title,org,visibility);
        jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES (?,?,?,?,'PUBLISHED',clock_timestamp())",publication,id,revision,number);
        jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,id);
    }
}

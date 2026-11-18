package kr.co.dtonic.datacatalog;

import java.util.UUID;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Cross-module public visibility must hold when owner and provider are different organizations. */
@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicVisibilityPostgresIT {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test
    void inactiveOwnerHidesAllPublicSurfacesWhileProviderRemainsActive() throws Exception {
        var fixture = published();
        mvc.perform(get("/api/catalog/v1/datasets/" + fixture.dataset())).andExpect(status().isOk());
        mvc.perform(get("/api/docs/v1/datasets/" + fixture.dataset())).andExpect(status().isOk());
        db.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?", fixture.owner());

        mvc.perform(get("/api/catalog/v1/datasets").param("q", fixture.title()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get("/api/catalog/v1/datasets/facets").param("q", fixture.title()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(fixture.provider().toString()))));
        for (String suffix : new String[]{"", "/fields", "/access", "/versions", "/versions/1"}) {
            mvc.perform(get("/api/catalog/v1/datasets/" + fixture.dataset() + suffix)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/docs/v1/datasets/" + fixture.dataset())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(fixture.dataset().toString()))));
        mvc.perform(get("/api/catalog/v1/dcat/download").param("format", "turtle"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(fixture.dataset().toString()))));
    }

    @Test
    void renamingProviderDoesNotRewriteThePublishedDcatSnapshot() throws Exception {
        var fixture = published();
        String changed = "Renamed live provider " + fixture.provider();
        db.update("UPDATE identity.organization SET inst_nm=? WHERE id=?", changed, fixture.provider());
        mvc.perform(get("/api/catalog/v1/datasets/" + fixture.dataset())).andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.name").value("Published provider snapshot"));
        mvc.perform(get("/api/catalog/v1/dcat").accept("application/ld+json")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Published provider snapshot")))
                .andExpect(content().string(not(containsString(changed))));
        mvc.perform(get("/api/catalog/v1/dcat/download").param("format", "turtle")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Published provider snapshot")))
                .andExpect(content().string(not(containsString(changed))));
    }

    private Fixture published() {
        UUID owner=UUID.randomUUID(), provider=UUID.randomUUID(), dataset=UUID.randomUUID(), revision=UUID.randomUUID(), publication=UUID.randomUUID();
        String title="Public boundary " + dataset;
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", owner, owner.toString(), "Fixture owner");
        db.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)", provider, provider.toString(), "Fixture live provider");
        db.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)", dataset, owner, dataset.toString());
        db.update("""
            INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,pvsn_inst_id,rls_rng_cd,mtdt_cn)
            VALUES (?,?,1,'APPROVED',?,?,'PUBLIC','{"publicationLabels":{"providerOrganizationName":"Published provider snapshot"}}'::jsonb)
            """, revision, dataset, title, provider);
        db.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd) VALUES (?,?,?,1,'PUBLISHED')", publication,dataset,revision);
        db.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,dataset);
        return new Fixture(owner,provider,dataset,title);
    }
    private record Fixture(UUID owner,UUID provider,UUID dataset,String title) {}
}

package kr.co.dtonic.datacatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class NaturalCatalogSearchPostgresIT {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresDatabase.configure(registry); }

    @Test void naturalRequestReturnsSummaryEvidenceAndWorkingPublishedShortcut() throws Exception {
        UUID org=organization("제주도"),id=dataset(org,"공영주차장 현황","주차장 위치와 이용 가능한 면수","PUBLIC","{\"details\":{\"keywords\":[\"교통\"]}}");
        dataset(org,"기상관측","날씨 관측소","PUBLIC","{}");
        var result=search("제주 교통 데이터를 찾아줘");
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
        assertThat(result.path("interpretation").path("terms").toString()).isEqualTo("[\"제주\",\"교통\"]");
        var match=result.path("matches").get(0);
        assertThat(match.path("summary").asText()).isEqualTo("주차장 위치와 이용 가능한 면수");
        assertThat(match.path("score").asInt()).isEqualTo(4);
        assertThat(match.path("reasons")).hasSize(2);
        String shortcut=match.path("detailPath").asText();
        assertThat(shortcut).isEqualTo("/datasets/"+id);
        mvc.perform(get("/api/catalog/v1/datasets/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("공영주차장 현황"));
        mvc.perform(get("/api/catalog/v1/search/facets").param("q","제주 교통 데이터를 찾아줘"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organizations[0].count").value(1));
    }
    @Test void everySubjectMustMatchAndPrivateFieldsOrUnpublishedDataNeverBecomeSearchEvidence() throws Exception {
        UUID org=organization("공개 기관");
        dataset(org,"안전 통계","공개 설명","PUBLIC","{\"secret\":\"비밀상표\",\"fields\":[{\"description\":\"비밀상표\",\"publicVisible\":false}]}");
        dataset(org,"비밀상표 교통","숨겨진 설명","PRIVATE","{}");
        assertThat(search("비밀상표 데이터 찾아줘").path("items")).isEmpty();
        assertThat(search("안전 교통 찾아줘").path("items")).isEmpty();
        assertThat(search("안전 찾아줘").toString()).doesNotContain("비밀상표","secret","fields");
    }
    @Test void actualRuleScoresControlRelevanceOrderAndPagination() throws Exception {
        UUID org=organization("평가 기관");
        UUID top=dataset(org,"주차 평가자료","현황","PUBLIC","{}");
        UUID second=dataset(org,"평가자료","현황","PUBLIC","{\"details\":{\"keywords\":[\"주차\"]}}");
        UUID third=dataset(org,"평가자료","주차 현황","PUBLIC","{}");
        // Commit-based publication tests share this database; rank only this test's provider fixtures.
        var first=mvc.perform(get("/api/catalog/v1/search").param("q","주차 자료 찾아줘").param("organizationId",org.toString()).param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(3)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).path("items")).extracting(n->n.path("id").asText()).containsExactly(top.toString(),second.toString());
        mvc.perform(get("/api/catalog/v1/search").param("q","주차 자료 찾아줘").param("organizationId",org.toString()).param("page","1").param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(third.toString()));
    }
    @Test void filtersStillApplyAndWildcardCharactersCannotBroadenNaturalSearch() throws Exception {
        UUID org=organization("Literal provider"),other=organization("Other provider");
        dataset(org,"100%_safe","표본","PUBLIC","{}");dataset(other,"100xxsafe","표본","PUBLIC","{}");
        assertThat(search("%_ 찾아줘").path("items")).hasSize(1);
        mvc.perform(get("/api/catalog/v1/search").param("q","%_ 찾아줘").param("organizationId",other.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/catalog/v1/search").param("q","%_ 찾아줘").param("sort","invalid"))
                .andExpect(status().isBadRequest());
    }
    @Test void escapedJsonKeywordsAreMatchedAsPublicValuesNotSerializedJson() throws Exception {
        UUID org=organization("인코딩 검사");
        dataset(org,"인코딩 표본","설명","PUBLIC","{\"details\":{\"keywords\":[\"back\\\\slash\"]}}");
        var result=search("back\\slash 찾아줘");
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("matches").get(0).path("reasons").get(0).path("field").asText()).isEqualTo("키워드");
        assertThat(result.path("matches").get(0).path("score").asInt()).isEqualTo(3);
    }
    @Test void withdrawnOrInactiveOrganizationCannotBeDiscoveredThroughNaturalQueries() throws Exception {
        UUID firstOrg=organization("차단 검사"),secondOrg=organization("차단 검사");
        UUID withdrawn=dataset(firstOrg,"회귀검사 주차장","폐기 대상","PUBLIC","{}");
        dataset(secondOrg,"회귀검사 주차장","비활성 기관 대상","PUBLIC","{}");
        assertThat(search("회귀검사 주차장 찾아줘").path("items")).hasSize(2);
        jdbc.update("UPDATE catalog.publication SET stts_cd='WITHDRAWN',rcntt_dt=clock_timestamp() WHERE dtset_id=?",withdrawn);
        jdbc.update("UPDATE identity.organization SET stts_cd='INACTIVE' WHERE id=?",secondOrg);
        assertThat(search("회귀검사 주차장 찾아줘").path("items")).isEmpty();
        mvc.perform(get("/api/catalog/v1/search/facets").param("q","회귀검사 주차장 찾아줘"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organizations").isEmpty());
    }
    @Test void requestWithoutSubjectsIsExplainedRatherThanReturningEverything() throws Exception {
        UUID org=organization("주제어 검사");dataset(org,"공개 자료","설명","PUBLIC","{}");
        var result=search("자료 좀 보여줘");
        assertThat(result.path("items")).isEmpty();
        assertThat(result.path("interpretation").path("message").asText()).contains("주제어");
    }
    private JsonNode search(String q) throws Exception {
        return json.readTree(mvc.perform(get("/api/catalog/v1/search").param("q",q)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private UUID organization(String name) { UUID id=UUID.randomUUID();jdbc.update("INSERT INTO identity.organization(id,cd,inst_nm) VALUES (?,?,?)",id,id.toString(),name);return id; }
    private UUID dataset(UUID org,String title,String description,String visibility,String metadata) {
        UUID id=UUID.randomUUID(),revision=UUID.randomUUID(),publication=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.dataset(id,psn_inst_id,slug_nm) VALUES (?,?,?)",id,org,id.toString());
        jdbc.update("INSERT INTO catalog.dataset_revision(id,dtset_id,rvsn_no,stts_cd,ttl,expln,pvsn_inst_id,rls_rng_cd,mtdt_cn) VALUES (?,?,1,'APPROVED',?,?,?,?,?::jsonb)",revision,id,title,description,org,visibility,metadata);
        jdbc.update("INSERT INTO catalog.publication(id,dtset_id,rvsn_id,pstg_no,stts_cd,pstg_dt) VALUES (?,?,?,1,'PUBLISHED',clock_timestamp())",publication,id,revision);
        jdbc.update("UPDATE catalog.dataset SET now_pstg_id=? WHERE id=?",publication,id);return id;
    }
}

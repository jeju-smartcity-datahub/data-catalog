package kr.co.dtonic.datacatalog;

import java.time.OffsetDateTime;
import java.util.List;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary;
import kr.co.dtonic.datacatalog.catalog.application.CatalogSearchQuery;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CatalogSearchQueryTest {
    @Test void koreanRequestAndParticlesBecomeExplicitSubjectTerms() {
        assertThat(CatalogSearchQuery.parse("제주도의 주차 데이터를 찾아줘").terms()).containsExactly("제주","주차");
        assertThat(CatalogSearchQuery.parse("제주에 있는 공영주차장 정보 보여주세요").terms()).containsExactly("제주","공영주차장");
    }
    @Test void boundedDocumentedAliasesAndDuplicatesAreDeterministic() {
        assertThat(CatalogSearchQuery.parse("find parking data in Jeju parking please").terms()).containsExactly("주차","제주");
        assertThat(CatalogSearchQuery.parse("Ｊｅｊｕ 주차공간").terms()).containsExactly("제주","주차");
    }
    @Test void genericRequestDoesNotPretendToUnderstandADataSubject() {
        var query=CatalogSearchQuery.parse("데이터 좀 찾아줘");
        assertThat(query.needsSubject()).isTrue();
        assertThat(query.interpretation().message()).contains("주제어");
        assertThat(CatalogSearchQuery.parse("").needsSubject()).isFalse();
    }
    @Test void unknownTermsAndSqlWildcardsRemainLiteralNotInventedIntent() {
        assertThat(CatalogSearchQuery.parse("우주선 %_ 자료 찾아줘").terms()).containsExactly("우주선","%_");
    }
    @Test void excessiveQueryTermsAreRejectedInsteadOfTruncated() {
        assertThatThrownBy(()->CatalogSearchQuery.parse("a b c d e f g h i")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CatalogSearchQuery.parse("x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explanationUsesOnlyActualPublicMatchesAndPublishedDetailPath() {
        var dataset=new DatasetSummary("00000000-0000-0000-0000-000000000001","공영주차장 현황","공영주차장 위치와 면수",
                new DatasetSummary.NamedReference("org","제주도"),null,OffsetDateTime.now(),"METADATA_ONLY",List.of(),List.of(),List.of(),
                new MetadataDetails(null,null,null,null,null,null,List.of("교통")),1,null);
        var match=CatalogSearchQuery.parse("제주 교통 정보 알려줘").explain(dataset);
        assertThat(match.reasons()).extracting("field").containsExactly("관리기관","키워드");
        assertThat(match.score()).isEqualTo(4);
        assertThat(match.scoreRule()).contains("합산");
        assertThat(match.detailPath()).isEqualTo("/datasets/"+dataset.id());
        assertThat(match.summary()).isEqualTo(dataset.description());
    }
}

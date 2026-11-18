package kr.co.dtonic.datacatalog.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.dtonic.datacatalog.catalog.application.CatalogSearchQuery;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SearchPrivacyPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void onlyFixedCodesSurviveMixedPersonalAndControlledInput()throws Exception{
        var value=SearchQueryRepresentation.from(CatalogSearchQuery.parse("제주 주차 홍길동 person@example.invalid 010-1234-5678"));
        assertThat(value.controlledTerms()).containsExactly("JEJU","PARKING");
        assertThat(value.hasUnclassifiedTerms()).isTrue();
        String stored=json.writeValueAsString(value);
        assertThat(stored).doesNotContain("홍길동","person","010","example").contains("CONTROLLED_SEARCH");
    }
    @Test void unknownInputIsNotHashedStoredOrInvented()throws Exception{
        String text="alice-secret-query";
        var value=SearchQueryRepresentation.from(CatalogSearchQuery.parse(text));
        assertThat(value.intent()).isEqualTo("UNCLASSIFIED_SEARCH");
        assertThat(value.controlledTerms()).isEmpty();
        assertThat(json.writeValueAsString(value)).doesNotContain(text,SearchHistoryRepository.tokenHash(text));
    }
    @Test void aliasesBecomeFixedConceptsAndBrowseHasNoUnknownContent(){
        assertThat(SearchQueryRepresentation.from(CatalogSearchQuery.parse("parking jeju" )).controlledTerms()).containsExactly("JEJU","PARKING");
        assertThat(SearchQueryRepresentation.from(CatalogSearchQuery.parse("" )).intent()).isEqualTo("BROWSE");
        assertThat(SearchQueryRepresentation.from(CatalogSearchQuery.parse("찾아줘" )).intent()).isEqualTo("REQUEST_WITHOUT_SUBJECT");
    }
    @Test void directRepresentationCannotCarryAnUnknownName(){
        assertThatThrownBy(()->new SearchQueryRepresentation(SearchQueryRepresentation.VERSION,"CONTROLLED_SEARCH",List.of("person-name"),false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SearchQueryRepresentation(SearchQueryRepresentation.VERSION,"person-name",List.of(),false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyLiteralSearchKeepsItsExistingLongQueryBehaviorButStoresNoTerms(){
        var value=SearchQueryRepresentation.fromLegacyQuery("a b c d e f g h i");
        assertThat(value.intent()).isEqualTo("UNCLASSIFIED_SEARCH");assertThat(value.controlledTerms()).isEmpty();
    }
    @Test void fiveCalendarYearsClampLeapDayInUtc(){
        assertThat(SearchRetentionPolicy.expiresAt(OffsetDateTime.parse("2024-02-29T12:34:56Z"))).isEqualTo(OffsetDateTime.parse("2029-02-28T12:34:56Z"));
        assertThat(SearchRetentionPolicy.expiresAt(OffsetDateTime.parse("2023-03-01T01:00:00+09:00"))).isEqualTo(OffsetDateTime.parse("2028-02-28T16:00:00Z"));
        var policy=new SearchRetentionPolicy(Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"),ZoneOffset.UTC));
        assertThat(policy.now()).isEqualTo(OffsetDateTime.parse("2031-01-01T00:00:00Z"));
    }
    @Test void feedbackCapabilityCannotLeakThroughRecordToString(){
        String token="a".repeat(43);var id=java.util.UUID.randomUUID();
        assertThat(new SearchHistoryContracts.FeedbackOffer(id,token).toString()).doesNotContain(token,id.toString());
        assertThat(new SearchHistoryContracts.FeedbackInput(id,token,SearchHistoryContracts.Rating.HELPFUL).toString()).doesNotContain(token,id.toString());
    }
}

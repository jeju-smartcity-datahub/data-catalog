package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchEventRecorderPrivacyTest {
    private final SearchHistoryRepository repository=mock(SearchHistoryRepository.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private SearchEventRecorder recorder(){
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new SearchEventRecorder(repository,new ObjectMapper(),transactions,new SearchRetentionPolicy(Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"),ZoneOffset.UTC)));
    }
    @Test void persistenceFailureIsObservableAsUnavailableFeedbackAndDoesNotBreakRead()throws Exception{
        doThrow(new DataAccessResourceFailureException("synthetic confidential payload must not be logged"))
                .when(repository).insert(any(),anyBoolean(),anyString(),anyLong(),anyLong(),any(),anyList(),nullable(Integer.class),nullable(Integer.class),anyString(),any());
        assertThat(recorder().capture(false,SearchQueryRepresentation.legacy(false),Map.of(),List.of(),0,1,0,10)).isNull();
        verify(transactions).rollback(any());
    }
    @Test void oldCallerCorrelationAndUnknownFilterStringsNeverReachStorage()throws Exception{
        var recorder=recorder();
        recorder.record("person-secret-id",true,Map.of("q",List.of("person@example.invalid"),"sort",List.of("relevance","person-secret-id")),0,1);
        var id=ArgumentCaptor.forClass(UUID.class);var filters=ArgumentCaptor.forClass(String.class);
        verify(repository).insert(id.capture(),eq(true),filters.capture(),eq(0L),eq(1L),any(),anyList(),isNull(),isNull(),anyString(),any());
        assertThat(id.getValue().toString()).isNotEqualTo("person-secret-id");
        assertThat(filters.getValue()).isEqualTo("{\"sort\":[\"relevance\"]}").doesNotContain("person","example","\"q\"");
    }
    @Test void everyCaptureHasAnIndependentEventAndToken()throws Exception{
        var recorder=recorder();
        var a=recorder.capture(false,SearchQueryRepresentation.legacy(false),Map.of(),List.of(),0,1,0,10);
        var b=recorder.capture(false,SearchQueryRepresentation.legacy(false),Map.of(),List.of(),0,1,0,10);
        assertThat(a.eventId()).isNotEqualTo(b.eventId());assertThat(a.feedbackToken()).hasSize(43).isNotEqualTo(b.feedbackToken());
    }
}

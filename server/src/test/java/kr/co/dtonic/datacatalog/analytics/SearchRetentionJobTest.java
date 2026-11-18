package kr.co.dtonic.datacatalog.analytics;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchRetentionJobTest {
    private final SearchHistoryRepository repository=mock(SearchHistoryRepository.class);
    private final SearchRetentionPolicy policy=mock(SearchRetentionPolicy.class);
    @Test void disabledRetentionCannotDeleteAnyRows(){
        new SearchRetentionJob(repository,policy,false).scheduled();
        verifyNoInteractions(repository,policy);
    }
    @Test void enabledRetentionStopsAfterTenBoundedBatches(){
        var now=OffsetDateTime.parse("2030-01-01T00:00:00Z");when(policy.now()).thenReturn(now);
        when(repository.purgeExpired(now,1000)).thenReturn(1000);
        new SearchRetentionJob(repository,policy,true).scheduled();
        verify(repository,times(10)).purgeExpired(now,1000);verifyNoMoreInteractions(repository);
    }
    @Test void shortBatchStopsAndUnavailableDatabaseDoesNotEscapeScheduler(){
        when(policy.now()).thenReturn(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        when(repository.purgeExpired(any(),eq(1000))).thenReturn(3).thenThrow(new DataAccessResourceFailureException("synthetic payload"));
        var job=new SearchRetentionJob(repository,policy,true);job.scheduled();
        verify(repository,times(1)).purgeExpired(any(),eq(1000));
        assertThatCode(job::scheduled).doesNotThrowAnyException();
        verify(repository,times(2)).purgeExpired(any(),eq(1000));
    }
}

package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.security.SecureRandom;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.params;

/** Public catalog telemetry only. No principal, IP address, raw query or credential is accepted. */
@Component
public class SearchEventRecorder {
    private static final Logger LOG=LoggerFactory.getLogger(SearchEventRecorder.class);
    private static final Set<String> FILTERS=Set.of("organizationId","categoryId","regionId","hubId","topicId");
    private static final Set<String> SORTS=Set.of("publishedAt_desc","publishedAt_asc","title_asc","title_desc","relevance");
    private final SearchHistoryRepository repository;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final SearchRetentionPolicy retention;
    private final SecureRandom random=new SecureRandom();
    public SearchEventRecorder(SearchHistoryRepository repository,ObjectMapper json,PlatformTransactionManager transactions,SearchRetentionPolicy retention) {
        this.repository=repository; this.json=json;this.retention=retention;transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(2);
    }
    public SearchHistoryContracts.FeedbackOffer record(String requestId,boolean hasQuery,Map<String,List<String>> filters,long resultCount,long durationMs) {
        return capture(hasQuery,SearchQueryRepresentation.legacy(hasQuery),filters,List.of(),resultCount,durationMs,null,null);
    }
    public SearchHistoryContracts.FeedbackOffer capture(boolean hasQuery,SearchQueryRepresentation representation,
            Map<String,List<String>> filters,List<DatasetSummary> displayed,long resultCount,long durationMs,Integer page,Integer size) {
        if(resultCount<0 || durationMs<0||displayed==null||displayed.size()>100||representation==null)return null;
        try {
            Map<String,List<String>> safe=new TreeMap<>();
            filters.forEach((key,values)->{
                if(FILTERS.contains(key)&&values!=null)safe.put(key,repository.approvedFilterIds(key,values.stream().filter(Objects::nonNull).filter(SearchEventRecorder::uuid).distinct().sorted().limit(20).toList()));
                if(key.equals("sort")&&values!=null)safe.put(key,values.stream().filter(Objects::nonNull).filter(SORTS::contains).distinct().sorted().limit(1).toList());
            });
            var references=new ArrayList<SearchHistoryContracts.ResultReference>();
            for(int i=0;i<displayed.size();i++){
                var result=displayed.get(i);
                if(result.publicationNo()<1)throw new IllegalArgumentException("A displayed result lacks its publication version");
                references.add(new SearchHistoryContracts.ResultReference(UUID.fromString(result.id()),result.publicationNo(),i));
            }
            UUID id=UUID.randomUUID();byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String encoded=json.writeValueAsString(safe);
            transaction.executeWithoutResult(status->{try{repository.insert(id,hasQuery,encoded,resultCount,durationMs,representation,references,page,size,SearchHistoryRepository.tokenHash(token),retention.now());}catch(JsonProcessingException invalid){throw new IllegalStateException("Invalid controlled search representation");}});
            return new SearchHistoryContracts.FeedbackOffer(id,token);
        } catch(RuntimeException | JsonProcessingException e) {
            // Analytics failure must not turn a successful catalog read into a failure; do not log payloads.
            LOG.warn("Search event persistence unavailable: type={}",e.getClass().getSimpleName());
            return null;
        }
    }
    private static boolean uuid(String value){try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}}
}

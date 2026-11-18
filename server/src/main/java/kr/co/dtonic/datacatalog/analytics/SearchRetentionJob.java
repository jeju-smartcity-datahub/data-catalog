package kr.co.dtonic.datacatalog.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes only the reviewed online search-history tables; never backups or catalog data. */
@Component
public class SearchRetentionJob {
    private static final Logger LOG=LoggerFactory.getLogger(SearchRetentionJob.class);
    private final SearchHistoryRepository repository;
    private final SearchRetentionPolicy policy;
    private final boolean enabled;
    public SearchRetentionJob(SearchHistoryRepository repository,SearchRetentionPolicy policy,
            @Value("${catalog.search-history.retention-enabled:false}") boolean enabled){this.repository=repository;this.policy=policy;this.enabled=enabled;}
    @Scheduled(cron="0 30 3 * * *",zone="UTC")
    public void scheduled(){
        if(!enabled)return;
        int removed=0;
        try{
            for(int i=0;i<10;i++){int count=repository.purgeExpired(policy.now(),1000);removed+=count;if(count<1000)break;}
            LOG.info("Search history retention completed: removedEvents={}",removed);
        }catch(org.springframework.dao.DataAccessException failure){LOG.warn("Search history retention unavailable: type={}",failure.getClass().getSimpleName());}
    }
}

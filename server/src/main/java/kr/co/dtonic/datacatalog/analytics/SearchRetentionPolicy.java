package kr.co.dtonic.datacatalog.analytics;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class SearchRetentionPolicy {
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public SearchRetentionPolicy(){this(Clock.systemUTC());}
    SearchRetentionPolicy(Clock clock){this.clock=clock;}
    public OffsetDateTime now(){return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);}
    public static OffsetDateTime expiresAt(OffsetDateTime occurredAt){return occurredAt.withOffsetSameInstant(ZoneOffset.UTC).plusYears(5);}
}

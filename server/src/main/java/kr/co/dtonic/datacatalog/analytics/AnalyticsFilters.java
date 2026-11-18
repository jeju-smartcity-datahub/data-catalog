package kr.co.dtonic.datacatalog.analytics;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.http.HttpStatus;
import static kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts.*;

/** Shared list/statistics/export filter validation. No catalog query text or IP input. */
final class AnalyticsFilters {
    private AnalyticsFilters() {}
    static AnalyticsReportFilters search(OffsetDateTime from,OffsetDateTime to,Boolean hasQuery,Boolean zeroResults) {
        range(from,to,true);
        return new AnalyticsReportFilters(utc(from),utc(to),null,null,null,hasQuery,zeroResults);
    }
    static AnalyticsReportFilters analysis(OffsetDateTime from,OffsetDateTime to,String kind,String status,String query,boolean required) {
        range(from,to,required);
        if(kind!=null&&!Set.of("DEFINITION_EXPORT","FIELD_MAPPING","DEFINITION_IMPORT").contains(kind)) throw invalid("지원하지 않는 분석 방식입니다.");
        if(status!=null&&!Set.of("SUCCEEDED","FAILED").contains(status)) throw invalid("지원하지 않는 분석 상태입니다.");
        String identifier=query==null||query.isBlank()?null:query.strip().toLowerCase(Locale.ROOT);
        if(identifier!=null&&!identifier.matches("[a-z0-9._:-]{1,100}")) throw invalid("실행 ID·엔진 버전·해시 식별자만 검색할 수 있습니다(ASCII 100자 이내).");
        return new AnalyticsReportFilters(utc(from),utc(to),kind,status,identifier,null,null);
    }
    static void page(int page,int size) { if(page<0||size<1||size>100) throw invalid("페이지는 0 이상, 크기는 1~100이어야 합니다."); }
    private static void range(OffsetDateTime from,OffsetDateTime to,boolean required) {
        if(!required&&from==null&&to==null)return;
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)
            throw invalid("조회 시작·종료를 함께 입력하고 기간을 31일 이내로 지정하세요.");
    }
    private static OffsetDateTime utc(OffsetDateTime value) { return value==null?null:value.withOffsetSameInstant(ZoneOffset.UTC); }
    static DomainException invalid(String message) { return new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",message); }
}

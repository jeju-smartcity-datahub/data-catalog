package kr.co.dtonic.datacatalog.analytics;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.AnalysisRun;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.SearchStatistics;

@Repository
public class AnalyticsMapper extends EgovAbstractMapper {
    public record SearchRow(String id,String requestId,OffsetDateTime occurredAt,boolean hasQuery,String normalizedFilters,long resultCount,long durationMs,String searchScope) {}
    public void search(Map<String,Object> values) { insert("Analytics.search",values); }
    public List<SearchRow> searches(Map<String,Object> values) { return selectList("Analytics.searches",values); }
    public SearchStatistics statistics(Map<String,Object> values) { return selectOne("Analytics.statistics",values); }
    public List<AnalysisRun> analyses(Map<String,Object> values) { return selectList("Analytics.analyses",values); }
    public AnalyticsReportContracts.AnalysisStatistics analysisStatistics(Map<String,Object> values) { return selectOne("Analytics.analysisStatistics",values); }
    public AnalysisRun analysis(String id) { return selectOne("Analytics.analysis",id); }
    public String evidence(String id) { return selectOne("Analytics.evidence",id); }
}

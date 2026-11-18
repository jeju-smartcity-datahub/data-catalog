package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.*;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import kr.co.dtonic.datacatalog.standard.definition.DefinitionContracts.AnalysisEvidence;
import kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static kr.co.dtonic.datacatalog.standard.application.GovernanceSupport.params;

@Service
@Transactional(readOnly=true)
public class AnalyticsService {
    private final AnalyticsMapper mapper;
    private final GovernanceSupport support;
    private final ObjectMapper json;
    public AnalyticsService(AnalyticsMapper mapper,GovernanceSupport support,ObjectMapper json) { this.mapper=mapper;this.support=support;this.json=json; }
    public List<SearchEvent> history(OffsetDateTime from,OffsetDateTime to,int page,int size,Authentication auth) {
        return history(from,to,page,size,null,null,auth);
    }
    public List<SearchEvent> history(OffsetDateTime from,OffsetDateTime to,int page,int size,Boolean hasQuery,Boolean zeroResults,Authentication auth) {
        support.platform(auth); AnalyticsFilters.page(page,size);
        return searches(AnalyticsFilters.search(from,to,hasQuery,zeroResults),page,size,null);
    }
    public SearchStatistics statistics(OffsetDateTime from,OffsetDateTime to,Authentication auth) {
        return statistics(from,to,null,null,auth);
    }
    public SearchStatistics statistics(OffsetDateTime from,OffsetDateTime to,Boolean hasQuery,Boolean zeroResults,Authentication auth) {
        support.platform(auth);return mapper.statistics(parameters(null,AnalyticsFilters.search(from,to,hasQuery,zeroResults),0,1,null));
    }
    public List<AnalysisRun> analyses(UUID organization,int page,int size,Authentication auth) {
        return analyses(organization,page,size,null,null,null,null,null,auth);
    }
    public List<AnalysisRun> analyses(UUID organization,int page,int size,OffsetDateTime from,OffsetDateTime to,String kind,String status,String query,Authentication auth) {
        support.scoped(auth,organization,"ORG_AUDITOR"); AnalyticsFilters.page(page,size);
        return mapper.analyses(parameters(organization,AnalyticsFilters.analysis(from,to,kind,status,query,false),page,size,null));
    }
    public AnalysisStatistics analysisStatistics(UUID organization,OffsetDateTime from,OffsetDateTime to,String kind,String status,String query,Authentication auth) {
        support.scoped(auth,organization,"ORG_AUDITOR");
        return mapper.analysisStatistics(parameters(organization,AnalyticsFilters.analysis(from,to,kind,status,query,true),0,1,null));
    }
    public AnalysisRun analysis(UUID id,Authentication auth) {
        var run=mapper.analysis(id.toString());if(run==null) throw GovernanceSupport.missing();
        support.scoped(auth,UUID.fromString(run.organizationId()),"ORG_AUDITOR");return run;
    }
    public List<AnalysisEvidence> evidence(UUID id,Authentication auth) {
        analysis(id,auth);
        try { return json.readValue(mapper.evidence(id.toString()),new TypeReference<>() {}); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored analysis evidence",e); }
    }
    List<SearchEvent> searches(AnalyticsReportFilters filter,int page,int size,OffsetDateTime cutoff) {
        return mapper.searches(parameters(null,filter,page,size,cutoff)).stream().map(r->new SearchEvent(
                r.id(),r.requestId(),r.occurredAt(),r.hasQuery(),filters(r.normalizedFilters()),r.resultCount(),r.durationMs(),r.searchScope())).toList();
    }
    static Map<String,Object> parameters(UUID organization,AnalyticsReportFilters filter,int page,int size,OffsetDateTime cutoff) {
        return params("organization",organization==null?null:organization.toString(),"from",filter.from(),"to",filter.to(),
                "analysisKind",filter.analysisKind(),"status",filter.status(),"query",filter.query(),"hasQuery",filter.hasQuery(),
                "zeroResults",filter.zeroResults(),"cutoff",cutoff,"size",size,"offset",(long)page*size);
    }
    private Map<String,List<String>> filters(String value) {
        try { return json.readValue(value,new TypeReference<>() {}); } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored filter format",e); }
    }
}

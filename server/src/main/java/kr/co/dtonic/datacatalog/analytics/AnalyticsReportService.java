package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.AnalysisRun;
import kr.co.dtonic.datacatalog.standard.api.GovernanceModels.SearchEvent;
import kr.co.dtonic.datacatalog.standard.application.GovernanceSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts.*;

@Service
public class AnalyticsReportService {
    private final JdbcTemplate db;
    private final GovernanceSupport support;
    private final AnalyticsMapper mapper;
    private final AnalyticsService analytics;
    private final ObjectMapper json;
    private final TransactionTemplate snapshot;
    public AnalyticsReportService(JdbcTemplate db,GovernanceSupport support,AnalyticsMapper mapper,AnalyticsService analytics,ObjectMapper json,PlatformTransactionManager transactions) {
        this.db=db;this.support=support;this.mapper=mapper;this.analytics=analytics;this.json=json;
        snapshot=new TransactionTemplate(transactions);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setTimeout(30);
    }

    public AnalyticsReport create(AnalyticsReportRequest input,String key,Authentication auth,String requestId) {
        var filters=validate(input);
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw AnalyticsFilters.invalid("Idempotency-Key를 입력하세요.");
        // Concurrent identical requests can race with a repeatable-read snapshot while waiting on the key lock.
        // Retry the whole transaction, not individual queries from an already fixed/aborted snapshot.
        for(int attempt=0;;attempt++) {
            try { return snapshot.execute(ignored->createSnapshot(input,filters,key,auth,requestId)); }
            catch(DataAccessException failure) { if(attempt>=2||!serializationFailure(failure))throw failure; }
        }
    }
    private AnalyticsReport createSnapshot(AnalyticsReportRequest input,AnalyticsReportFilters filters,String key,Authentication auth,String requestId) {
        UUID actor=authorize(input.kind(),input.organizationId(),auth);
        String digest=hash(key.getBytes(StandardCharsets.UTF_8));
        String requestHash=hash(encode(List.of(input.kind(),input.format(),input.organizationId()==null?"":input.organizationId().toString(),filters)).getBytes(StandardCharsets.UTF_8));
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",actor+":analytics-report:"+digest);
        var existing=db.queryForList("SELECT id,dmnd_hash_vl AS request_hash FROM analytics.report WHERE rqstr_id=? AND key_hash_vl=?",actor,digest);
        if(!existing.isEmpty()) {
            if(!existing.getFirst().get("request_hash").equals(requestHash))throw new DomainException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","동일 키를 다른 보고서 조건에 사용할 수 없습니다.");
            return get((UUID)existing.getFirst().get("id"),auth);
        }
        OffsetDateTime cutoff=db.queryForObject("SELECT CURRENT_TIMESTAMP",OffsetDateTime.class);
        UUID id=UUID.randomUUID();
        var parameters=AnalyticsService.parameters(input.organizationId(),filters,0,5001,cutoff);
        List<?> rows; Object statistics;List<String> headers;var csv=new ArrayList<List<String>>();
        if(input.kind().equals("SEARCH")) {
            List<SearchEvent> values=analytics.searches(filters,0,5001,cutoff);rows=values;
            if(values.size()>5000)throw AnalyticsReportWriter.limit();
            statistics=mapper.statistics(parameters);
            headers=List.of("id","requestId","occurredAt","hasQuery","normalizedFilters","resultCount","durationMs","searchScope");
            if(input.format().equals("CSV"))for(var row:values)csv.add(Arrays.asList(row.id(),row.requestId(),row.occurredAt().toString(),Boolean.toString(row.hasQuery()),encode(row.normalizedFilters()),Long.toString(row.resultCount()),Long.toString(row.durationMs()),row.searchScope()));
        } else {
            List<AnalysisRun> values=mapper.analyses(parameters);rows=values;
            if(values.size()>5000)throw AnalyticsReportWriter.limit();
            statistics=mapper.analysisStatistics(parameters);
            headers=List.of("id","organizationId","datasetRevisionId","kind","engineVersion","requestedBy","startedAt","finishedAt","status","fieldCount","sourceHash","errorCode");
            if(input.format().equals("CSV"))for(var row:values)csv.add(Arrays.asList(row.id(),row.organizationId(),row.datasetRevisionId(),row.kind(),row.engineVersion(),row.requestedBy(),row.startedAt().toString(),row.finishedAt().toString(),row.status(),Integer.toString(row.fieldCount()),row.sourceHash(),row.errorCode()));
        }
        var document=new LinkedHashMap<String,Object>();
        document.put("reportId",id);document.put("kind",input.kind());document.put("organizationId",input.organizationId());document.put("filters",filters);
        document.put("cutoff",cutoff);document.put("rowCount",rows.size());document.put("statistics",statistics);document.put("rows",rows);
        byte[] file=input.format().equals("JSON")?AnalyticsReportWriter.json(json,document):AnalyticsReportWriter.csv(headers,csv);
        int inserted=db.update("""
            INSERT INTO analytics.report(id,rqstr_id,inst_id,type_cd,file_fmt_cd,inq_cnd_cn,crtr_dt,row_cnt,key_hash_vl,dmnd_hash_vl,cn_hash_vl,file_cn,crt_dt,expry_dt)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?,?,?) ON CONFLICT(rqstr_id,key_hash_vl) DO NOTHING
            """,id,actor,input.organizationId(),input.kind(),input.format(),encode(filters),cutoff,rows.size(),digest,requestHash,hash(file),file,cutoff,cutoff.plusHours(24));
        if(inserted!=1)throw new IllegalStateException("Report key lock lost consistency");
        support.audit(actor,input.organizationId(),"ANALYTICS_REPORT_CREATED","ANALYTICS_REPORT",id.toString(),requestId);
        return get(id,auth);
    }
    @Transactional(readOnly=true)
    public AnalyticsReport get(UUID id,Authentication auth) {
        UUID actor=support.reader(auth).userId();
        var rows=db.query("SELECT id,rqstr_id AS requested_by,inst_id AS organization_id,type_cd AS kind,file_fmt_cd AS format,inq_cnd_cn AS filters,crtr_dt AS cutoff,row_cnt AS row_count,key_hash_vl AS key_digest,dmnd_hash_vl AS request_hash,cn_hash_vl AS content_hash,file_cn AS file_content,crt_dt AS created_at,expry_dt AS expires_at FROM analytics.report WHERE id=? AND rqstr_id=?",(r,n)->new AnalyticsReport(r.getObject("id",UUID.class),r.getString("kind"),r.getString("format"),r.getObject("organization_id",UUID.class),"SUCCEEDED",
                r.getInt("row_count"),r.getObject("cutoff",OffsetDateTime.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),r.getString("content_hash"),"analytics-"+r.getString("id")+(r.getString("format").equals("JSON")?".json":".csv"),decode(r.getString("filters"))),id,actor);
        if(rows.isEmpty())throw GovernanceSupport.missing();
        var report=rows.getFirst();authorize(report.kind(),report.organizationId(),auth);
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT expry_dt>clock_timestamp() FROM analytics.report WHERE id=?",Boolean.class,id)))
            throw new DomainException(HttpStatus.GONE,"REPORT_EXPIRED","보고서가 만료되었습니다. 새 키로 보고서를 요청하세요.");
        return report;
    }
    @Transactional(readOnly=true)
    public ReportDownload download(UUID id,Authentication auth) {
        var report=get(id,auth);
        return new ReportDownload(report,db.queryForObject("SELECT file_cn AS file_content FROM analytics.report WHERE id=?",byte[].class,id));
    }
    private UUID authorize(String kind,UUID organization,Authentication auth) {
        return (kind.equals("SEARCH")?support.platform(auth):support.scoped(auth,organization,"ORG_AUDITOR")).userId();
    }
    private AnalyticsReportFilters validate(AnalyticsReportRequest input) {
        if(input==null||!("JSON".equals(input.format())||"CSV".equals(input.format())))throw AnalyticsFilters.invalid("보고서 형식을 확인하세요.");
        if("SEARCH".equals(input.kind())) {
            if(input.organizationId()!=null||input.analysisKind()!=null||input.status()!=null||input.query()!=null)throw AnalyticsFilters.invalid("검색 보고서에는 분석 조건을 지정할 수 없습니다.");
            return AnalyticsFilters.search(input.from(),input.to(),input.hasQuery(),input.zeroResults());
        }
        if(!"ANALYSIS".equals(input.kind())||input.organizationId()==null||input.hasQuery()!=null||input.zeroResults()!=null)throw AnalyticsFilters.invalid("분석 보고서에는 기관과 분석 조건만 지정하세요.");
        return AnalyticsFilters.analysis(input.from(),input.to(),input.analysisKind(),input.status(),input.query(),true);
    }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid analytics report value",e);} }
    private AnalyticsReportFilters decode(String value) { try{return json.readValue(value,AnalyticsReportFilters.class);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid analytics report filters",e);} }
    private static String hash(byte[] value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    private static boolean serializationFailure(Throwable error) { for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&("40001".equals(sql.getSQLState())||"40P01".equals(sql.getSQLState())))return true;return false; }
}

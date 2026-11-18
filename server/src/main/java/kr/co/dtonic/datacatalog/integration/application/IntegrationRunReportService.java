package kr.co.dtonic.datacatalog.integration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.CsvCells;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded run evidence only. Source values, endpoints, credentials and actor IDs never enter the projection. */
@Service
public class IntegrationRunReportService {
    public static final int MAX_ITEMS = 10_000;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ERRORS = Set.of("CONNECTION_UNAVAILABLE", "ATTEMPTS_EXHAUSTED",
            "ADAPTER_UNAVAILABLE", "CAPABILITY_UNSUPPORTED", "SOURCE_NOT_APPROVED", "SECRET_STORE_UNAVAILABLE",
            "INTEGRATION_CONFIGURATION_UNAVAILABLE", "INTEGRATION_EXECUTION_FAILED", "SOURCE_ITEMS_FAILED",
            "SOURCE_ITEM_INVALID", "CHECKPOINT_CYCLE", "PAGE_LIMIT_EXCEEDED", "AUTHENTICATION_FAILED",
            "RATE_LIMITED", "SOURCE_UNAVAILABLE", "TIMEOUT", "INVALID_SCHEMA", "RESPONSE_TOO_LARGE", "SOURCE_REJECTED");
    private static final Set<String> RUN_STATES = Set.of("QUEUED", "RUNNING", "RETRY_WAIT", "SUCCEEDED", "PARTIAL_FAILED", "FAILED");
    private static final List<String> CSV_HEADERS = List.of("schemaVersion", "generatedAt", "dataScope", "runId",
            "connectionId", "organizationId", "adapterCode", "runKind", "runStatus", "runAttempt",
            "configurationVersion", "mappingVersion", "runErrorCode", "runCreatedAt", "runStartedAt", "runFinishedAt",
            "totalItems", "candidateItems", "unchangedItems", "failedItems", "otherItems", "currentCandidateReferences",
            "itemOrdinal", "sourceReferenceSha256", "itemStatus", "sourceHash", "itemErrorCode", "itemAttempt",
            "itemUpdatedAt", "candidateId", "candidateState", "fieldCount", "evidenceCode");
    private final IntegrationService integration;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public IntegrationRunReportService(IntegrationService integration, JdbcTemplate db, ObjectMapper json) {
        this.integration = integration; this.db = db; this.json = json;
    }

    public record ReportRun(UUID id, UUID connectionId, UUID organizationId, String adapterCode, String kind,
            String status, int attempt, long configurationVersion, String mappingVersion, String errorCode,
            String createdAt, String startedAt, String finishedAt) { }
    public record ReportSummary(int totalItems, int candidateItems, int unchangedItems, int failedItems,
            int otherItems, int currentCandidateReferences) { }
    public record ReportItem(int ordinal, String sourceReferenceSha256, String status, String sourceHash,
            String errorCode, int attempt, String updatedAt, UUID candidateId, String candidateState,
            Integer fieldCount, String evidenceCode) { }
    public record RunReport(int schemaVersion, String generatedAt, String dataScope, ReportRun run,
            ReportSummary summary, List<ReportItem> items) { }
    public record ReportDownload(String contentType, String filename, byte[] content) {
        public ReportDownload { content=content.clone(); }
        @Override public byte[] content() { return content.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof ReportDownload download && java.util.Objects.equals(contentType,download.contentType)
                    && java.util.Objects.equals(filename,download.filename) && java.util.Arrays.equals(content,download.content);
        }
        @Override public int hashCode() { return 31*java.util.Objects.hash(contentType,filename)+java.util.Arrays.hashCode(content); }
        @Override public String toString() { return "IntegrationReportDownload[bytes="+content.length+"]"; }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RunReport report(UUID runId, Authentication auth) {
        // Preserve the existing editor/reviewer/platform and active-organization boundary, including foreign-run404.
        var run = integration.run(auth, runId);
        var connection = integration.get(auth, run.connectionId());
        var count = db.queryForObject("SELECT count(*) FROM integration.sync_item WHERE sync_excn_id=?", Long.class, runId);
        if (count == null || count > MAX_ITEMS) throw tooLarge();
        var rows = db.query("""
            SELECT i.otsd_id,i.stts_cd,i.sou_hash_vl,i.err_cd,i.prcstry_nmtm,i.mdfcn_dt,m.id AS candidate_id,
              CASE WHEN m.id IS NULL THEN NULL WHEN m.dtset_id IS NULL THEN 'NEW'
                   WHEN m.aplcn_sou_hash_vl=m.sou_hash_vl THEN 'APPLIED' ELSE 'UPDATED' END AS candidate_state,
              CASE WHEN jsonb_typeof(m.cand_cn #> '{details,fields}')='array'
                   THEN jsonb_array_length(m.cand_cn #> '{details,fields}') ELSE NULL END AS field_count
            FROM integration.sync_item i
            LEFT JOIN integration.source_metadata m ON m.sou_lnkg_id=? AND m.otsd_id=i.otsd_id
              AND m.sou_hash_vl=i.sou_hash_vl
            WHERE i.sync_excn_id=? ORDER BY i.otsd_id LIMIT ?
            """, (r, n) -> {
                String state = Set.of("CANDIDATE", "UNCHANGED", "FAILED").contains(r.getString("stts_cd"))
                        ? r.getString("stts_cd") : "UNKNOWN";
                String sourceHash = hashOrNull(r.getString("sou_hash_vl"));
                UUID candidate = sourceHash == null ? null : r.getObject("candidate_id", UUID.class);
                String evidence = state.equals("FAILED") ? "SOURCE_ITEM_FAILED"
                        : candidate == null ? "HISTORICAL_HASH_ONLY"
                        : state.equals("UNCHANGED") ? "UNCHANGED_NORMALIZED_SOURCE" : "NORMALIZED_CANDIDATE";
                Integer fields = candidate == null ? null : (Integer) r.getObject("field_count");
                return new ReportItem(n + 1, IntegrationService.digest(r.getString("otsd_id")), state, sourceHash,
                        error(r.getString("err_cd")), r.getInt("prcstry_nmtm"), utc(r.getObject("mdfcn_dt", OffsetDateTime.class)),
                        candidate, candidate == null ? null : r.getString("candidate_state"), fields, evidence);
            }, run.connectionId(), runId, MAX_ITEMS + 1);
        if (rows.size() > MAX_ITEMS || rows.size() != count) throw tooLarge();
        int candidates = 0, unchanged = 0, failed = 0, other = 0, references = 0;
        for (var row : rows) {
            switch (row.status()) {
                case "CANDIDATE" -> candidates++;
                case "UNCHANGED" -> unchanged++;
                case "FAILED" -> failed++;
                default -> other++;
            }
            if (row.candidateId() != null) references++;
        }
        var details = new ReportRun(run.id(), run.connectionId(), connection.organizationId(),
                tag(connection.adapterCode()), Set.of("CHECK", "SYNC").contains(run.kind()) ? run.kind() : "UNKNOWN",
                RUN_STATES.contains(run.status()) ? run.status() : "UNKNOWN", run.attempt(), run.configurationVersion(),
                tag(run.mappingVersion()), error(run.errorCode()), utc(run.createdAt()), utc(run.startedAt()), utc(run.finishedAt()));
        return new RunReport(1, utc(OffsetDateTime.now(ZoneOffset.UTC)), "METADATA_ONLY", details,
                new ReportSummary(rows.size(), candidates, unchanged, failed, other, references), List.copyOf(rows));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportDownload download(UUID runId, String format, Authentication auth) {
        if (format == null || !Set.of("json", "csv").contains(format)) throw new DomainException(HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT", "리포트 형식은 json 또는 csv입니다.");
        var report = report(runId, auth);
        byte[] body;
        try { body = format.equals("json") ? json.writeValueAsBytes(report) : csv(report); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot serialize bounded run report"); }
        if (body.length > MAX_BYTES) throw tooLarge();
        return new ReportDownload(format.equals("json") ? "application/json;charset=UTF-8" : "text/csv;charset=UTF-8",
                "hub-run-" + runId + "." + format, body);
    }

    private static byte[] csv(RunReport report) {
        var content = new StringBuilder("\uFEFF");
        appendRow(content, CSV_HEADERS);
        if (report.items().isEmpty()) appendRow(content, csvValues(report, null));
        else for (var item : report.items()) {
            appendRow(content, csvValues(report, item));
            if (content.length() > MAX_BYTES) throw tooLarge();
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static List<Object> csvValues(RunReport report, ReportItem item) {
        var r = report.run(); var s = report.summary(); var values = new ArrayList<Object>();
        java.util.Collections.addAll(values, report.schemaVersion(), report.generatedAt(), report.dataScope(),
                r.id(), r.connectionId(), r.organizationId(), r.adapterCode(), r.kind(), r.status(), r.attempt(),
                r.configurationVersion(), r.mappingVersion(), r.errorCode(), r.createdAt(), r.startedAt(), r.finishedAt(),
                s.totalItems(), s.candidateItems(), s.unchangedItems(), s.failedItems(), s.otherItems(), s.currentCandidateReferences());
        if (item == null) for (int i = 0; i < 11; i++) values.add(null);
        else java.util.Collections.addAll(values, item.ordinal(), item.sourceReferenceSha256(), item.status(),
                item.sourceHash(), item.errorCode(), item.attempt(), item.updatedAt(), item.candidateId(),
                item.candidateState(), item.fieldCount(), item.evidenceCode());
        return values;
    }
    private static void appendRow(StringBuilder output, List<?> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) output.append(',');
            output.append(csvCell(values.get(i)));
        }
        output.append("\r\n");
    }
    static String csvCell(Object value) {
        return CsvCells.quote(value);
    }
    private static String utc(OffsetDateTime value) { return value == null ? null : value.toInstant().toString(); }
    private static String hashOrNull(String value) { return value != null && value.matches("[a-fA-F0-9]{64}") ? value.toLowerCase(java.util.Locale.ROOT) : null; }
    private static String error(String value) { return value == null ? null : ERRORS.contains(value) ? value : "UNRECOGNIZED_ERROR"; }
    private static String tag(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") ? value : "UNRECOGNIZED"; }
    private static DomainException tooLarge() { return new DomainException(HttpStatus.PAYLOAD_TOO_LARGE,
            "REPORT_TOO_LARGE", "수집 리포트는 항목10,000건과10MiB 이내에서 제공합니다."); }
}

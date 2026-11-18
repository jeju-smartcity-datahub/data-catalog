package kr.co.dtonic.datacatalog.metadata.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.*;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import kr.co.dtonic.datacatalog.metadata.application.MetadataDiscoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Parameter-bound SQL sharing the application datasource and transaction manager. */
@Repository
public class MetadataRepository {
    private final JdbcTemplate jdbc;
    public MetadataRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    public static final String DRAFT_FROM="""
        FROM catalog.dataset d
        JOIN LATERAL (SELECT * FROM catalog.dataset_revision x WHERE x.dtset_id=d.id ORDER BY rvsn_no DESC LIMIT 1) r ON true
        LEFT JOIN metadata.review_request q ON q.rvsn_id=r.id
        LEFT JOIN metadata.review_decision v ON v.rvw_dmnd_id=q.id
        """;
    public static final String DRAFT_SELECT="""
        SELECT d.id,d.slug_nm AS slug,d.psn_inst_id AS owner_organization_id,r.id AS revision_id,r.rvsn_no AS revision_no,r.stts_cd AS state,r.ttl AS title,r.expln AS description,
          r.pvsn_inst_id AS provider_organization_id,r.main_clsf_id AS primary_category_id,r.rls_rng_cd AS visibility,d.lck_ver_no AS lock_version,d.now_pstg_id AS current_publication_id,
          q.id AS review_request_id,v.id AS review_decision_id,r.mtdt_cn::text AS metadata,d.mdfcn_dt AS updated_at
        """;
    private static final ObjectMapper SNAPSHOT_JSON=new ObjectMapper();
    public static final RowMapper<MetadataDraft> DRAFT=(r,n)-> {
        final com.fasterxml.jackson.databind.JsonNode snapshot;
        try { snapshot=SNAPSHOT_JSON.readTree(r.getString("metadata")); }
        catch(JsonProcessingException e) { throw new SQLException("Invalid metadata snapshot",e); }
        return new MetadataDraft(uuid(r,"id"),r.getString("slug"),
            uuid(r,"owner_organization_id"),uuid(r,"revision_id"),r.getInt("revision_no"),r.getString("state"),
            r.getString("title"),r.getString("description"),uuid(r,"provider_organization_id"),uuid(r,"primary_category_id"),
            r.getString("visibility"),r.getLong("lock_version"),uuid(r,"current_publication_id"),uuid(r,"review_request_id"),uuid(r,"review_decision_id"),
            MetadataDiscoveryService.ids(snapshot.path("discovery").path("regions")),MetadataDiscoveryService.ids(snapshot.path("discovery").path("hubs")),
            MetadataDiscoveryService.ids(snapshot.path("discovery").path("topics")),MetadataDetails.read(snapshot.path("details")),r.getObject("updated_at",OffsetDateTime.class));
    };
    public static final String REVIEW_SELECT="""
        SELECT q.id,q.dtset_id AS dataset_id,q.rvsn_id AS revision_id,q.cn_hash_vl AS content_hash,q.stts_cd AS status,q.sbmsn_user_id AS submitted_by,q.sbmsn_dt AS submitted_at,q.lck_ver_no AS lock_version,v.id AS decision_id,v.dcsn_cd AS decision,v.rsn AS reason FROM metadata.review_request q
        LEFT JOIN metadata.review_decision v ON v.rvw_dmnd_id=q.id
        """;
    public static final RowMapper<ReviewRequest> REVIEW=(r,n)->new ReviewRequest(uuid(r,"id"),uuid(r,"dataset_id"),
            uuid(r,"revision_id"),r.getString("content_hash"),r.getString("status"),uuid(r,"submitted_by"),
            r.getObject("submitted_at",OffsetDateTime.class),r.getLong("lock_version"),uuid(r,"decision_id"),r.getString("decision"),r.getString("reason"));
    public static final RowMapper<Publication> PUBLICATION=(r,n)->new Publication(uuid(r,"id"),uuid(r,"dataset_id"),
            uuid(r,"revision_id"),r.getInt("publication_no"),r.getString("state"),r.getString("access_state"),
            r.getObject("published_at",OffsetDateTime.class),r.getObject("withdrawn_at",OffsetDateTime.class),r.getLong("lock_version"),r.getString("withdrawal_reason"));
    public MetadataDraft draft(UUID id) { return first(jdbc.query(DRAFT_SELECT+DRAFT_FROM+" WHERE d.id=?",DRAFT,id)); }
    public ReviewRequest review(UUID id) { return first(jdbc.query(REVIEW_SELECT+" WHERE q.id=?",REVIEW,id)); }
    public Publication publication(UUID id) { return first(jdbc.query("SELECT id,dtset_id AS dataset_id,rvsn_id AS revision_id,pstg_no AS publication_no,stts_cd AS state,acs_stts_cd AS access_state,pstg_dt AS published_at,rcntt_dt AS withdrawn_at,rvw_dcsn_id AS review_decision_id,pstg_user_id AS published_by,lck_ver_no AS lock_version,rcntt_rsn AS withdrawal_reason FROM catalog.publication WHERE id=?",PUBLICATION,id)); }
    public void lockDataset(UUID id) { jdbc.queryForObject("SELECT id FROM catalog.dataset WHERE id=? FOR UPDATE",UUID.class,id); }
    public static UUID uuid(ResultSet row,String column) throws SQLException { return row.getObject(column,UUID.class); }
    private static <T> T first(List<T> rows) { return rows.isEmpty()?null:rows.getFirst(); }
}

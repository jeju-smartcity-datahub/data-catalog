package kr.co.dtonic.datacatalog.integration.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.integration.api.IntegrationCandidateModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationCandidateRepository {
    private final JdbcTemplate db;
    public IntegrationCandidateRepository(JdbcTemplate db) { this.db=db; }
    public record Candidate(UUID id,UUID connectionId,String externalId,String sourceHash,String title,String description,
            int revisionNo,UUID datasetId,String appliedHash,String appliedTitle,String appliedDescription,
            long applicationVersion,OffsetDateTime updatedAt,String sourceDetails,String appliedDetails) { }
    public record ApplicationRow(CandidateApplication application,String requestHash) { }
    private static final String SELECT="""
        SELECT id,sou_lnkg_id AS source_connection_id,otsd_id AS external_id,sou_hash_vl AS source_hash,cand_cn->>'title' AS title,
        cand_cn->>'description' AS description,rvsn_no AS revision_no,dtset_id AS dataset_id,aplcn_sou_hash_vl AS applied_source_hash,
        aplcn_ttl AS applied_title,aplcn_expln AS applied_description,aplcn_ver_no AS application_version,mdfcn_dt AS updated_at,
        coalesce(cand_cn->'details','{}'::jsonb)::text AS source_details,aplcn_mtdt_cn::text AS applied_details FROM integration.source_metadata
        """;
    private static final RowMapper<Candidate> ROW=(r,n)->new Candidate(r.getObject("id",UUID.class),r.getObject("source_connection_id",UUID.class),
            r.getString("external_id"),r.getString("source_hash"),r.getString("title"),r.getString("description"),r.getInt("revision_no"),
            r.getObject("dataset_id",UUID.class),r.getString("applied_source_hash"),r.getString("applied_title"),r.getString("applied_description"),
            r.getLong("application_version"),r.getObject("updated_at",OffsetDateTime.class),r.getString("source_details"),r.getString("applied_details"));
    public Candidate get(UUID id,boolean lock) {
        var rows=db.query(SELECT+" WHERE id=?"+(lock?" FOR UPDATE":""),ROW,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Candidate> list(UUID connection,int page,int size) { return db.query(SELECT+" WHERE sou_lnkg_id=? ORDER BY mdfcn_dt DESC,id LIMIT ? OFFSET ?",ROW,connection,size,(long)page*size); }
    public long count(UUID connection) { return db.queryForObject("SELECT count(*) FROM integration.source_metadata WHERE sou_lnkg_id=?",Long.class,connection); }
    public ApplicationRow application(UUID candidate,UUID actor,String digest) {
        var rows=db.query("SELECT id,sou_mtdt_id AS source_metadata_id,sou_rvsn_no AS source_revision_no,sou_hash_vl AS source_hash,dtset_id AS dataset_id,dtset_rvsn_id AS dataset_revision_id,dtset_lck_ver_no AS dataset_lock_version,prcs_user_id AS actor_id,dmnd_id AS request_id,idmpt_key_hash_vl AS idempotency_digest,dmnd_hash_vl AS request_hash,aplcn_dt AS applied_at FROM integration.candidate_application WHERE sou_mtdt_id=? AND prcs_user_id=? AND idmpt_key_hash_vl=?",(r,n)->new ApplicationRow(
                new CandidateApplication(r.getObject("id",UUID.class),r.getObject("source_metadata_id",UUID.class),r.getString("source_hash"),
                        r.getObject("dataset_id",UUID.class),r.getObject("dataset_revision_id",UUID.class),r.getLong("dataset_lock_version"),r.getObject("applied_at",OffsetDateTime.class)),
                r.getString("request_hash")),candidate,actor,digest);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void link(Candidate candidate,UUID dataset) {
        db.update("""
            UPDATE integration.source_metadata SET dtset_id=?,aplcn_sou_hash_vl=sou_hash_vl,aplcn_ttl=cand_cn->>'title',
            aplcn_expln=cand_cn->>'description',aplcn_mtdt_cn=aplcn_mtdt_cn || coalesce(cand_cn->'details','{}'::jsonb),
            aplcn_ver_no=aplcn_ver_no+1,aplcn_dt=CURRENT_TIMESTAMP WHERE id=?
            """,dataset,candidate.id());
    }
    public CandidateApplication record(Candidate candidate,UUID dataset,UUID revision,long version,UUID actor,String requestId,String digest,String hash) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO integration.candidate_application(id,sou_mtdt_id,sou_rvsn_no,sou_hash_vl,dtset_id,dtset_rvsn_id,
                dtset_lck_ver_no,prcs_user_id,dmnd_id,idmpt_key_hash_vl,dmnd_hash_vl) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,candidate.id(),candidate.revisionNo(),candidate.sourceHash(),dataset,revision,version,actor,requestId,digest,hash);
        var saved=application(candidate.id(),actor,digest);
        if(saved==null) throw new IllegalStateException("Candidate application was not persisted");
        return saved.application();
    }
}

package kr.co.dtonic.datacatalog.integration.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationScheduleRepository {
    private final JdbcTemplate jdbc;
    public IntegrationScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Schedule(UUID connectionId, boolean enabled, int intervalSeconds,
            OffsetDateTime nextRunAt, OffsetDateTime lastRequestedAt, UUID lastRunId,
            String lastErrorCode, UUID updatedBy, long lockVersion, OffsetDateTime updatedAt) { }

    public record ConnectionScope(UUID organizationId, long configurationVersion) { }

    public Schedule get(UUID id) {
        return jdbc.query("SELECT * FROM integration.sync_schedule WHERE sou_lnkg_id=?", this::row, id)
                .stream().findFirst().orElse(null);
    }

    /** Lock order is always connection, then schedule, matching manual run submission. */
    public ConnectionScope lockConnection(UUID id, boolean dueOnly) {
        String restriction = dueOnly ? " AND c.use_yn AND o.stts_cd='ACTIVE'" : "";
        String skip = dueOnly ? " SKIP LOCKED" : "";
        return jdbc.query("SELECT c.inst_id,c.lck_ver_no FROM integration.source_connection c "
                        + "JOIN identity.organization o ON o.id=c.inst_id WHERE c.id=?" + restriction
                        + " FOR UPDATE OF c" + skip,
                (rs, n) -> new ConnectionScope(rs.getObject("inst_id", UUID.class), rs.getLong("lck_ver_no")), id)
                .stream().findFirst().orElse(null);
    }

    public List<UUID> dueIds() {
        return jdbc.query("""
                SELECT s.sou_lnkg_id FROM integration.sync_schedule s
                JOIN integration.source_connection c ON c.id=s.sou_lnkg_id
                JOIN identity.organization o ON o.id=c.inst_id
                WHERE s.use_yn AND s.next_excn_dt<=clock_timestamp() AND c.use_yn AND o.stts_cd='ACTIVE'
                ORDER BY s.next_excn_dt,s.sou_lnkg_id LIMIT 100
                """, (rs, n) -> rs.getObject(1, UUID.class));
    }

    public Schedule lockDue(UUID id) {
        return jdbc.query("""
                SELECT * FROM integration.sync_schedule WHERE sou_lnkg_id=? AND use_yn
                AND next_excn_dt<=clock_timestamp() FOR UPDATE SKIP LOCKED
                """, this::row, id).stream().findFirst().orElse(null);
    }

    public void save(UUID id, boolean enabled, int interval, UUID actor) {
        jdbc.update("""
                INSERT INTO integration.sync_schedule(sou_lnkg_id,use_yn,cycl_sec_cnt,next_excn_dt,stng_user_id)
                VALUES (?,?,?,CASE WHEN ? THEN clock_timestamp() ELSE NULL END,?)
                ON CONFLICT(sou_lnkg_id) DO UPDATE SET use_yn=EXCLUDED.use_yn,
                  cycl_sec_cnt=EXCLUDED.cycl_sec_cnt,stng_user_id=EXCLUDED.stng_user_id,
                  next_excn_dt=CASE WHEN NOT EXCLUDED.use_yn THEN NULL
                    WHEN NOT integration.sync_schedule.use_yn
                      OR integration.sync_schedule.cycl_sec_cnt<>EXCLUDED.cycl_sec_cnt THEN clock_timestamp()
                    ELSE integration.sync_schedule.next_excn_dt END,
                  last_err_cd=NULL,lck_ver_no=integration.sync_schedule.lck_ver_no+1,mdfcn_dt=clock_timestamp()
                """, id, enabled, interval, enabled, actor);
    }

    public void requested(UUID id, UUID runId) {
        jdbc.update("""
                UPDATE integration.sync_schedule SET next_excn_dt=clock_timestamp()+(cycl_sec_cnt*interval '1 second'),
                  last_dmnd_dt=clock_timestamp(),last_excn_id=?,last_err_cd=NULL WHERE sou_lnkg_id=?
                """, runId, id);
    }

    /** An error cannot overwrite a concurrent configuration change or another dispatcher result. */
    public void failed(Schedule expected, String code) {
        jdbc.update("""
                UPDATE integration.sync_schedule SET
                  next_excn_dt=clock_timestamp()+(LEAST(cycl_sec_cnt,60)*interval '1 second'),last_err_cd=?
                WHERE sou_lnkg_id=? AND use_yn AND lck_ver_no=? AND next_excn_dt=?
                """, code, expected.connectionId(), expected.lockVersion(), expected.nextRunAt());
    }

    public void audit(UUID id, UUID actor, String action, long version) {
        jdbc.update("""
                INSERT INTO integration.connection_event(id,lnkg_id,prcs_user_id,prcs_cd,dmnd_id,stng_ver_no)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), id, actor, action, UUID.randomUUID().toString(), version);
    }

    private Schedule row(ResultSet rs, int index) throws SQLException {
        return new Schedule(rs.getObject("sou_lnkg_id", UUID.class), rs.getBoolean("use_yn"),
                rs.getInt("cycl_sec_cnt"), rs.getObject("next_excn_dt", OffsetDateTime.class),
                rs.getObject("last_dmnd_dt", OffsetDateTime.class), rs.getObject("last_excn_id", UUID.class),
                rs.getString("last_err_cd"), rs.getObject("stng_user_id", UUID.class),
                rs.getLong("lck_ver_no"), rs.getObject("mdfcn_dt", OffsetDateTime.class));
    }
}

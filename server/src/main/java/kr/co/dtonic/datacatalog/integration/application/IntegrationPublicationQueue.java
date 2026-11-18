package kr.co.dtonic.datacatalog.integration.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called inside the collection page transaction: a restart cannot lose publication work. */
@Component
public class IntegrationPublicationQueue {
    private final JdbcTemplate db;
    public IntegrationPublicationQueue(JdbcTemplate db){this.db=db;}
    public void enqueue(String connection,List<String> externalIds){
        for(String external:externalIds)db.update("""
            INSERT INTO integration.auto_publication_task(id,lnkg_id,sou_cand_id,sou_hash_vl,pstg_stng_ver_no,lnkg_stng_ver_no)
            SELECT gen_random_uuid(),s.sou_lnkg_id,s.id,s.sou_hash_vl,p.lck_ver_no,c.lck_ver_no
            FROM integration.source_metadata s JOIN integration.publication_policy p ON p.lnkg_id=s.sou_lnkg_id
            JOIN integration.source_connection c ON c.id=p.lnkg_id
            JOIN integration.sync_run r ON r.id=s.last_excn_id AND r.stng_ver_no=c.lck_ver_no
            WHERE s.sou_lnkg_id=?::uuid AND s.otsd_id=? AND p.enabled AND c.use_yn
            ON CONFLICT(sou_cand_id,sou_hash_vl,pstg_stng_ver_no,lnkg_stng_ver_no) DO NOTHING
            """,connection,external);
    }
    public void enqueueAll(String connection){db.update("""
        INSERT INTO integration.auto_publication_task(id,lnkg_id,sou_cand_id,sou_hash_vl,pstg_stng_ver_no,lnkg_stng_ver_no)
        SELECT gen_random_uuid(),s.sou_lnkg_id,s.id,s.sou_hash_vl,p.lck_ver_no,c.lck_ver_no
        FROM integration.source_metadata s JOIN integration.publication_policy p ON p.lnkg_id=s.sou_lnkg_id
        JOIN integration.source_connection c ON c.id=p.lnkg_id
        JOIN integration.sync_run r ON r.id=s.last_excn_id AND r.stng_ver_no=c.lck_ver_no
        WHERE s.sou_lnkg_id=?::uuid AND p.enabled AND c.use_yn
        ON CONFLICT(sou_cand_id,sou_hash_vl,pstg_stng_ver_no,lnkg_stng_ver_no) DO NOTHING
        """,connection);}
}

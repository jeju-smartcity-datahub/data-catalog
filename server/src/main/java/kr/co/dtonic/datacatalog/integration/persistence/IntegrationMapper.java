package kr.co.dtonic.datacatalog.integration.persistence;

import java.util.*;
import kr.co.dtonic.datacatalog.integration.api.IntegrationModels.ItemView;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationMapper extends EgovAbstractMapper {
    public static Map<String, Object> args(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
    public IntegrationRows.Connection connection(String id) { return selectOne("Integration.connection", args("id", id)); }
    public IntegrationRows.Connection lockConnection(String id) { return selectOne("Integration.lockConnection", args("id", id)); }
    public List<IntegrationRows.Connection> connections(String org) { return selectList("Integration.connections", args("org", org)); }
    public int createConnection(Map<String, Object> values) { return insert("Integration.createConnection", values); }
    public int updateConnection(Map<String, Object> values) { return update("Integration.updateConnection", values); }
    public int activeRuns(String id) { return selectOne("Integration.activeRuns", args("id", id)); }
    public boolean organizationActive(String id) { return selectOne("Integration.organizationActive", args("id", id)); }
    public boolean executionAllowed(String id, long version) { return selectOne("Integration.executionAllowed", args("id", id, "version", version)); }
    public void audit(Map<String, Object> values) { insert("Integration.audit", values); }
    public List<IntegrationRows.Event> events(String id) { return selectList("Integration.events", args("id", id)); }
    public int createRun(Map<String, Object> values) { return insert("Integration.createRun", values); }
    public IntegrationRows.Run run(String id) { return selectOne("Integration.run", args("id", id)); }
    public IntegrationRows.Run idempotentRun(Map<String, Object> values) { return selectOne("Integration.idempotentRun", values); }
    public List<IntegrationRows.Run> runs(String id) { return selectList("Integration.runs", args("id", id)); }
    public IntegrationRows.Run claim(String token) { return selectOne("Integration.claim", args("token", token)); }
    public int fence(Map<String, Object> values) { return update("Integration.fence", values); }
    public int finish(Map<String, Object> values) { return update("Integration.finish", values); }
    public int retry(Map<String, Object> values) { return update("Integration.retry", values); }
    public String checkpoint(String id) { return selectOne("Integration.checkpoint", args("id", id)); }
    public void checkpoint(Map<String, Object> values) { insert("Integration.saveCheckpoint", values); }
    public int progress(Map<String, Object> values) { return update("Integration.progress", values); }
    public int reportProgress(Map<String, Object> values) { return update("Integration.reportProgress", values); }
    public int countProgress(Map<String, Object> values) { return update("Integration.countProgress", values); }
    public String sourceHash(Map<String, Object> values) { return selectOne("Integration.sourceHash", values); }
    public int candidate(Map<String, Object> values) { return insert("Integration.candidate", values); }
    public int touchUnchangedSource(Map<String,Object> values) { return update("Integration.touchUnchangedSource",values); }
    public void item(Map<String, Object> values) { insert("Integration.item", values); }
    public List<ItemView> items(Map<String, Object> values) { return selectList("Integration.items", values); }
    public long itemCount(String id) { return selectOne("Integration.itemCount", args("id", id)); }
    public boolean hasFailedItems(String id) { return selectOne("Integration.hasFailedItems",args("id",id)); }
}

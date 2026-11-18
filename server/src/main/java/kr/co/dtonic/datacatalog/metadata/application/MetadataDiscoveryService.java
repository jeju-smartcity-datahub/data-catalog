package kr.co.dtonic.datacatalog.metadata.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Called within the owning metadata aggregate transaction after the dataset lock/authorization. */
@Service
public class MetadataDiscoveryService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public MetadataDiscoveryService(JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }
    public void apply(UUID revision,UUID primary,List<UUID> regions,List<UUID> hubs,List<UUID> topics,MetadataDetails details) {
        var previous=tree(db.queryForObject("SELECT mtdt_cn::text AS metadata FROM catalog.dataset_revision WHERE id=?",String.class,revision));
        var regionIds=regions==null?ids(previous.path("discovery").path("regions")):checked(regions);
        var hubIds=hubs==null?ids(previous.path("discovery").path("hubs")):checked(hubs);
        var topicIds=topics==null?ids(previous.path("discovery").path("topics")):checked(topics);
        var normalized=normalize(details==null?MetadataDetails.read(previous.path("details")):details);
        var regionValues=lookup(regionIds,"REGION");var hubValues=lookup(hubIds,"HUB");
        var topicValues=topics(topicIds,primary);
        db.update("DELETE FROM catalog.revision_discovery WHERE rvsn_id=?",revision);
        insert(revision,"REGION",regionValues);insert(revision,"HUB",hubValues);
        db.update("DELETE FROM catalog.revision_topic WHERE rvsn_id=?",revision);
        topicValues.forEach(v->db.update("INSERT INTO catalog.revision_topic(rvsn_id,tpc_id,nm_snpst) VALUES (?,?,?)",revision,v.id(),v.name()));
        var discovery=new LinkedHashMap<String,Object>();
        discovery.put("regions",regionValues);discovery.put("hubs",hubValues);discovery.put("topics",topicValues);
        db.update("UPDATE catalog.dataset_revision SET mtdt_cn=jsonb_set(jsonb_set(mtdt_cn,'{discovery}',?::jsonb,true),'{details}',?::jsonb,true) WHERE id=?",
                encode(discovery),encode(normalized),revision);
    }
    public void copy(UUID from,UUID to) {
        db.update("INSERT INTO catalog.revision_discovery(rvsn_id,clsf_id,type_cd,nm_snpst) SELECT ?,clsf_id AS lookup_id,type_cd AS kind,nm_snpst AS name_snapshot FROM catalog.revision_discovery WHERE rvsn_id=?",to,from);
        db.update("INSERT INTO catalog.revision_topic(rvsn_id,tpc_id,nm_snpst) SELECT ?,tpc_id AS topic_id,nm_snpst AS name_snapshot FROM catalog.revision_topic WHERE rvsn_id=?",to,from);
    }
    private List<Named> lookup(List<UUID> ids,String kind) {
        var result=new ArrayList<Named>();
        for(UUID id:ids.stream().sorted().toList()) {
            var rows=db.query("SELECT id,nm AS name FROM catalog.discovery_lookup WHERE id=? AND type_cd=? AND actvtn_yn FOR SHARE",(r,n)->new Named(r.getObject("id",UUID.class),r.getString("name")),id,kind);
            if(rows.isEmpty())throw invalid("활성 상태인 지역·허브 분류를 선택하세요.");
            result.add(rows.getFirst());
        }
        return List.copyOf(result);
    }
    private List<Named> topics(List<UUID> ids,UUID primary) {
        if(ids.isEmpty())return List.of();
        if(primary==null)throw invalid("주제를 지정하려면 분야를 선택하세요.");
        var parents=db.query("SELECT coalesce(up_id,id) AS root FROM classification.category WHERE id=? AND actvtn_yn FOR SHARE",(r,n)->r.getObject("root",UUID.class),primary);
        if(parents.isEmpty())throw invalid("활성 분야를 선택하세요.");
        UUID parent=parents.getFirst();
        var activeRoot=db.queryForList("SELECT id FROM classification.category WHERE id=? AND up_id IS NULL AND actvtn_yn FOR SHARE",parent);
        if(activeRoot.isEmpty())throw invalid("활성 상위 분야를 선택하세요.");
        var result=new ArrayList<Named>();
        for(UUID id:ids.stream().sorted().toList()) {
            var rows=db.query("SELECT id,nm AS name FROM classification.category WHERE id=? AND up_id=? AND actvtn_yn FOR SHARE",(r,n)->new Named(r.getObject("id",UUID.class),r.getString("name")),id,parent);
            if(rows.isEmpty())throw invalid("선택 분야에 속한 활성 주제를 선택하세요.");
            result.add(rows.getFirst());
        }
        return List.copyOf(result);
    }
    private void insert(UUID revision,String kind,List<Named> values) {
        values.forEach(v->db.update("INSERT INTO catalog.revision_discovery(rvsn_id,clsf_id,type_cd,nm_snpst) VALUES (?,?,?,?)",revision,v.id(),kind,v.name()));
    }
    public static MetadataDetails normalize(MetadataDetails details) {
        var keywords=details.keywords()==null?List.<String>of():details.keywords();
        if(keywords.size()>20)throw invalid("키워드는 최대 20개입니다.");
        var normalized=new ArrayList<String>();
        for(String word:keywords) {
            String clean=clean(word,100);
            if(clean==null||normalized.contains(clean))throw invalid("비어 있거나 중복된 키워드를 제거하세요.");
            normalized.add(clean);
        }
        if(details.temporalStart()!=null&&details.temporalEnd()!=null&&details.temporalEnd().isBefore(details.temporalStart()))throw invalid("기간 종료일은 시작일 이후여야 합니다.");
        return new MetadataDetails(clean(details.department(),200),clean(details.license(),500),clean(details.updateFrequency(),100),
                clean(details.spatialCoverage(),500),details.temporalStart(),details.temporalEnd(),List.copyOf(normalized));
    }
    private static String clean(String value,int max) {
        if(value==null)return null;
        String clean=value.strip();if(clean.length()>max||clean.codePoints().anyMatch(Character::isISOControl))throw invalid("메타데이터 길이 또는 제어 문자를 확인하세요.");
        return clean.isEmpty()?null:clean;
    }
    private static List<UUID> checked(List<UUID> ids) {
        if(ids.size()>20||ids.stream().anyMatch(java.util.Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw invalid("분류 ID는 중복 없이 최대 20개까지 선택하세요.");
        return List.copyOf(ids);
    }
    public static List<UUID> ids(JsonNode values) {
        var result=new ArrayList<UUID>();
        if(values.isArray())values.forEach(v->result.add(UUID.fromString(v.path("id").asText())));
        return List.copyOf(result);
    }
    private JsonNode tree(String value) { try { return json.readTree(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored metadata",e); } }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize metadata",e); } }
    private record Named(UUID id,String name) {}
    private static DomainException invalid(String message) { return new DomainException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",message); }
}

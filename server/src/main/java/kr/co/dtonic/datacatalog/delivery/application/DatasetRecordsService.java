package kr.co.dtonic.datacatalog.delivery.application;

import com.fasterxml.jackson.databind.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.catalog.application.CatalogQueryService;
import kr.co.dtonic.datacatalog.catalog.api.DatasetDetail.PublishedField;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.delivery.api.DataAccessModels.*;
import kr.co.dtonic.datacatalog.identity.WorkflowAccess;
import kr.co.dtonic.datacatalog.integration.adapter.ApprovedHubHttpTransport;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.application.KetiRecordsEndpointResolver;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.SecretReferenceProvider;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationMapper;
import kr.co.dtonic.datacatalog.integration.persistence.IntegrationRows.Connection;
import kr.co.dtonic.datacatalog.metadata.application.MetadataWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DatasetRecordsService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final WorkflowAccess access;
    private final MetadataWorkflowService metadata;
    private final IntegrationMapper connections;
    private final CatalogQueryService catalog;
    private final ApprovedHubHttpTransport transport;
    private final EndpointPolicy endpoints;
    private final KetiRecordsEndpointResolver recordEndpoints;
    private final SecretReferenceProvider secrets;
    private final TransactionTemplate tx;
    private record Stored(DataAccessView view,Long checkedVersion) { }
    private record Fetched(List<JsonNode> rows,Long totalCount,OffsetDateTime fetchedAt) { }
    public DatasetRecordsService(JdbcTemplate db,ObjectMapper json,WorkflowAccess access,
            MetadataWorkflowService metadata,IntegrationMapper connections,CatalogQueryService catalog,
            ApprovedHubHttpTransport transport,EndpointPolicy endpoints,KetiRecordsEndpointResolver recordEndpoints,SecretReferenceProvider secrets,
            PlatformTransactionManager transactions) {
        this.db=db;this.json=json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.access=access;this.metadata=metadata;this.connections=connections;this.catalog=catalog;
        this.transport=transport;this.endpoints=endpoints;this.recordEndpoints=recordEndpoints;this.secrets=secrets;this.tx=new TransactionTemplate(transactions);
    }
    public DataAccessView settings(UUID id,Authentication auth) {
        metadata.getDraft(id,auth);
        var stored=load(id);
        return stored==null?new DataAccessView(id,false,null,false,"NGSI_LD","/entities","datasetId","","q",Map.of(),0,false,null):stored.view();
    }
    public DataAccessView save(UUID id,DataAccessInput input,String etag,Authentication auth) {
        validate(input);
        return tx.execute(status->{
            var org=authorize(id,auth);
            var connection=connection(input.connectionId(),org);
            endpoints.validate(connection.endpoint());
            if("KETI_DATASET".equals(input.mode())&&(!"KETI_DATACORE".equals(connection.adapterCode())||db.queryForObject(
                    "SELECT count(*) FROM integration.source_metadata WHERE dtset_id=? AND sou_lnkg_id=? AND otsd_id=?",
                    Long.class,id,input.connectionId(),input.scopeValue())!=1))throw invalid("수집된 데이터셋의 연결과 식별값을 선택하세요.");
            var old=load(id); requireVersion(id,old==null?0:old.view().lockVersion(),etag);
            String encoded=encode(input.fixedQuery());
            int changed;
            if(old==null||!old.view().configured()) changed=db.update("""
                INSERT INTO delivery.dataset_data_access(dtset_id,sou_lnkg_id,use_yn,inq_mode_cd,inq_path,fltr_fld_nm,fltr_vl,
                    fltr_param_nm,fix_inq_cn,lck_ver_no,mdfr_id)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,1,?) ON CONFLICT DO NOTHING
                """,id,input.connectionId(),input.enabled(),input.mode(),input.queryPath(),input.scopeField(),input.scopeValue(),input.scopeParameter(),encoded,access.principal(auth).userId());
            else changed=db.update("""
                UPDATE delivery.dataset_data_access SET sou_lnkg_id=?,use_yn=?,inq_mode_cd=?,inq_path=?,fltr_fld_nm=?,fltr_vl=?,
                    fltr_param_nm=?,fix_inq_cn=?::jsonb,lck_ver_no=lck_ver_no+1,insp_lnkg_ver_no=NULL,insp_dt=NULL,
                    mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE dtset_id=? AND lck_ver_no=?
                """,input.connectionId(),input.enabled(),input.mode(),input.queryPath(),input.scopeField(),input.scopeValue(),input.scopeParameter(),encoded,access.principal(auth).userId(),id,old.view().lockVersion());
            if(changed!=1) throw stale();
            var result=load(id).view(); audit(id,auth,"SETTINGS_SAVED",result.lockVersion());return result;
        });
    }
    public DataAccessCheck check(UUID id,String etag,Authentication auth) {
        var org=authorize(id,auth);var stored=required(id,false);requireVersion(id,stored.view().lockVersion(),etag);
        var connection=connection(stored.view().connectionId(),org);
        var records=fetch(stored.view(),connection,1,0,false);
        return tx.execute(status->{
            authorize(id,auth);
            var current=connection(stored.view().connectionId(),org);
            if(current.lockVersion()!=connection.lockVersion()) throw stale();
            int changed;
            if(!stored.view().configured()) {
                var automatic=load(id);
                if(automatic==null||automatic.view().configured()||!automatic.view().equals(stored.view()))throw stale();
                var v=stored.view();
                changed=db.update("""
                    INSERT INTO delivery.dataset_data_access(dtset_id,sou_lnkg_id,use_yn,inq_mode_cd,inq_path,fltr_fld_nm,fltr_vl,
                        fltr_param_nm,fix_inq_cn,lck_ver_no,insp_lnkg_ver_no,insp_dt,mdfr_id)
                    VALUES(?,?,?,?,?,?,?,?,?::jsonb,1,?,CURRENT_TIMESTAMP,?) ON CONFLICT DO NOTHING
                    """,id,v.connectionId(),v.enabled(),v.mode(),v.queryPath(),v.scopeField(),v.scopeValue(),v.scopeParameter(),encode(v.fixedQuery()),connection.lockVersion(),access.principal(auth).userId());
            } else changed=db.update("""
                UPDATE delivery.dataset_data_access SET insp_lnkg_ver_no=?,insp_dt=CURRENT_TIMESTAMP,
                  lck_ver_no=lck_ver_no+1,mdfr_id=?,mdfcn_dt=CURRENT_TIMESTAMP WHERE dtset_id=? AND lck_ver_no=?
                """,connection.lockVersion(),access.principal(auth).userId(),id,stored.view().lockVersion());
            if(changed!=1)throw stale();
            var view=load(id).view();audit(id,auth,"QUERY_CHECKED",view.lockVersion());return new DataAccessCheck(view,records.rows().size());
        });
    }
    public boolean available(UUID id) { var stored=load(id);return stored!=null&&stored.view().ready()&&!catalog.detail(id,null).fields().isEmpty(); }
    public RecordPage records(UUID id,int limit,int offset) {
        if(limit<1||limit>1000||offset<0||offset>10000)throw invalid("조회 범위를 확인하세요.");
        var publication=catalog.detail(id,null);
        if(publication.fields().isEmpty())throw unavailable();
        var before=required(id,true);
        UUID owner=db.queryForObject("SELECT psn_inst_id FROM catalog.dataset WHERE id=?",UUID.class,id);
        var connection=connection(before.view().connectionId(),owner);
        var fetched=fetch(before.view(),connection,limit,offset,true);
        var raw=fetched.rows().subList(0,Math.min(fetched.rows().size(),limit));
        var projected=project(raw,publication.fields(),json);
        // Network I/O is outside a database transaction; revocation and changes are read again.
        var after=catalog.detail(id,null);var current=required(id,true);
        if(after.publicationNo()!=publication.publicationNo()||current.view().lockVersion()!=before.view().lockVersion()
                ||!current.view().connectionId().equals(before.view().connectionId())||!current.view().scopeValue().equals(before.view().scopeValue())
                || connections.connection(connection.id()).lockVersion()!=connection.lockVersion())throw stale();
        return new RecordPage(id,publication.publicationNo(),publication.fields().stream()
            .map(f->new RecordColumn(f.fieldPath(),f.displayName()==null||f.displayName().isBlank()?f.physicalName():f.displayName(),f.dataType(),f.unit())).toList(),
            projected,offset,limit,offset+limit<=10000&&fetched.rows().size()>limit,fetched.totalCount(),fetched.fetchedAt(),
            latestRecordTimestamp(raw,publication.fields(),"modifiedAt"),latestRecordTimestamp(raw,publication.fields(),"observedAt"));
    }
    private Fetched fetch(DataAccessView view,Connection connection,int limit,int offset,boolean lookAhead) {
        char[] credential=null;
        try {
            if(connection.secretReference()!=null)credential=secrets.resolve(connection.secretReference());
            var context=new HubAdapter.Context(recordEndpoints.resolve(connection.adapterCode(),connection.endpoint()),credential,connection.timeoutSeconds(),1_048_576);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(connection.timeoutSeconds(),15));
            var result=new ArrayList<JsonNode>();
            int target=limit+(lookAhead?1:0);
            Long totalCount=null;
            boolean inconsistentCount=false;
            while(result.size()<target) {
                int size=Math.min(100,target-result.size());
                var query=new LinkedHashMap<>(view.fixedQuery());
                if("KETI_DATASET".equals(view.mode())) {
                    query.put("datasetId",view.scopeValue());query.put("options","sysAttrs");
                } else if("NGSI_LD".equals(view.mode())) {
                    query.put("q",view.scopeField()+"=="+json.writeValueAsString(view.scopeValue()));
                    query.put("options","sysAttrs");query.put("count","true");
                } else query.put(view.scopeParameter(),view.scopeValue());
                query.put("limit",Integer.toString(size));query.put("offset",Integer.toString(offset+result.size()));
                var response=transport.getRecords(context,view.queryPath(),query,!"JSON_ARRAY".equals(view.mode()),deadline);
                var array=json.readTree(response.body());
                if(array==null||!array.isArray()||array.size()>size)throw upstream();
                Long responseCount=parseCount(response.totalCount());
                if(responseCount!=null) {
                    if(totalCount!=null&&!totalCount.equals(responseCount))inconsistentCount=true;
                    totalCount=responseCount;
                }
                for(var row:array) {
                    var scope=valueAt(row,view.scopeField());
                    if(!row.isObject()||((!"KETI_DATASET".equals(view.mode())||!scope.isMissingNode())
                            &&!view.scopeValue().equals(scope.asText(null))))throw upstream();
                    result.add(row);
                }
                if(totalCount!=null&&((!result.isEmpty()&&totalCount<offset+(long)result.size())
                        ||(array.size()<size&&totalCount>offset+(long)result.size())))inconsistentCount=true;
                if(array.size()<size)break;
            }
            return new Fetched(List.copyOf(result),inconsistentCount?null:totalCount,OffsetDateTime.now());
        } catch(DomainException ex){throw ex;}
        catch(Exception ex){throw upstream();}
        finally {if(credential!=null)Arrays.fill(credential,'\0');}
    }
    static Long parseCount(String value) {
        if(value==null||!value.matches("[0-9]{1,19}"))return null;
        try{return Long.valueOf(value);}catch(NumberFormatException exception){return null;}
    }
    static OffsetDateTime latestRecordTimestamp(List<JsonNode> rows,List<PublishedField> fields,String property) {
        OffsetDateTime latest=null;
        for(var row:rows) {
            if("modifiedAt".equals(property))latest=later(latest,row.path(property));
            for(var field:fields) {
                JsonNode attribute=row;
                for(String segment:field.fieldPath().split("\\."))
                    attribute=attribute.isObject()&&attribute.has(segment)?attribute.path(segment):unwrap(attribute).path(segment);
                if(attribute.isObject())latest=later(latest,attribute.path(property));
                if(field.fieldPath().equals(property)||field.fieldPath().endsWith("."+property))latest=later(latest,unwrap(attribute));
            }
        }
        return latest;
    }
    private static OffsetDateTime later(OffsetDateTime current,JsonNode value) {
        if(!value.isTextual()||value.textValue().length()>100)return current;
        try {
            var parsed=OffsetDateTime.parse(value.textValue());
            return current==null||parsed.isAfter(current)?parsed:current;
        } catch(java.time.format.DateTimeParseException exception){return current;}
    }
    static List<Map<String,Object>> project(List<JsonNode> rows,List<PublishedField> fields,ObjectMapper json) {
        return rows.stream().map(row->{
            Map<String,Object> output=new LinkedHashMap<>();
            for(var field:fields) {
                var value=valueAt(row,field.fieldPath());
                // Structured parents may contain unpublished children; only scalar values or scalar arrays leave here.
                Object safe=value.isValueNode()?json.convertValue(value,Object.class):null;
                if(value.isArray()&&java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(JsonNode::isValueNode))
                    safe=json.convertValue(value,Object.class);
                if(value.isObject()&&isGeometry(value))safe=Map.of("type",value.path("type").asText(),"coordinates",json.convertValue(value.path("coordinates"),Object.class));
                output.put(field.fieldPath(),safe);
            }
            return output;
        }).toList();
    }
    static JsonNode valueAt(JsonNode row,String path) {
        JsonNode value=row;
        for(String segment:path.split("\\.")) {
            value=value.isObject()&&value.has(segment)?value.path(segment):unwrap(value).path(segment);
        }
        return unwrap(value);
    }
    private static JsonNode unwrap(JsonNode value) {
        if(value.isObject()&&value.path("type").isTextual()) {
            String type=value.path("type").asText();
            if(Set.of("Property","GeoProperty").contains(type)&&value.has("value"))return value.path("value");
            if("Relationship".equals(type)&&value.has("object"))return value.path("object");
        }
        return value;
    }
    private static boolean isGeometry(JsonNode value) {
        return Set.of("Point","MultiPoint","LineString","MultiLineString","Polygon","MultiPolygon").contains(value.path("type").asText())
            &&value.path("coordinates").isArray()&&numericCoordinates(value.path("coordinates"),0);
    }
    private static boolean numericCoordinates(JsonNode value,int depth) {
        if(depth>5)return false;
        if(value.isNumber())return Double.isFinite(value.asDouble());
        if(!value.isArray()||value.size()>10000)return false;
        for(var child:value)if(!numericCoordinates(child,depth+1))return false;
        return true;
    }
    private UUID authorize(UUID id,Authentication auth) {
        UUID org=metadata.getDraft(id,auth).ownerOrganizationId();
        access.require(auth,org,"ORG_EDITOR");access.require(auth,org,"ORG_PUBLISHER");return org;
    }
    private Connection connection(UUID id,UUID org) {
        var value=connections.connection(id.toString());
        if(value==null||!org.toString().equals(value.organizationId())||!value.enabled()||!connections.organizationActive(org.toString()))
            throw unavailable();
        return value;
    }
    private Stored required(UUID id,boolean ready) {
        var value=load(id);if(value==null||(ready&&!value.view().ready()))throw unavailable();return value;
    }
    private Stored load(UUID id) {
        var rows=db.query("""
            SELECT a.*,c.use_yn AS connection_enabled,c.lck_ver_no AS connection_version,o.stts_cd AS organization_status,
                   (c.inst_id=d.psn_inst_id) AS matching_organization
            FROM delivery.dataset_data_access a JOIN integration.source_connection c ON c.id=a.sou_lnkg_id
            JOIN identity.organization o ON o.id=c.inst_id JOIN catalog.dataset d ON d.id=a.dtset_id WHERE a.dtset_id=?
            """,(r,n)->{
                Long checked=r.getObject("insp_lnkg_ver_no",Long.class);
                Map<String,String> query=new LinkedHashMap<>();try{json.readTree(r.getString("fix_inq_cn")).fields().forEachRemaining(e->query.put(e.getKey(),e.getValue().asText()));}catch(Exception ex){throw upstream();}
                boolean ready=r.getBoolean("use_yn")&&r.getBoolean("connection_enabled")&&r.getBoolean("matching_organization")
                    &&"ACTIVE".equals(r.getString("organization_status"))&&checked!=null&&checked==r.getLong("connection_version");
                return new Stored(new DataAccessView(id,true,r.getObject("sou_lnkg_id",UUID.class),r.getBoolean("use_yn"),r.getString("inq_mode_cd"),
                    r.getString("inq_path"),r.getString("fltr_fld_nm"),r.getString("fltr_vl"),r.getString("fltr_param_nm"),query,
                    r.getLong("lck_ver_no"),ready,r.getObject("insp_dt",OffsetDateTime.class)),checked);
            },id);
        return rows.isEmpty()?automatic(id):rows.getFirst();
    }
    private Stored automatic(UUID id) {
        var rows=db.query("""
            SELECT DISTINCT c.id,c.lck_ver_no,s.otsd_id FROM catalog.dataset d
            JOIN catalog.publication p ON p.id=d.now_pstg_id AND p.dtset_id=d.id AND p.stts_cd='PUBLISHED'
            JOIN catalog.dataset_revision r ON r.id=p.rvsn_id AND r.stts_cd='APPROVED' AND r.rls_rng_cd='PUBLIC'
            JOIN identity.organization owner_org ON owner_org.id=d.psn_inst_id AND owner_org.stts_cd='ACTIVE'
            JOIN identity.organization provider_org ON provider_org.id=r.pvsn_inst_id AND provider_org.stts_cd='ACTIVE'
            JOIN integration.source_metadata s ON s.dtset_id=d.id
            JOIN integration.candidate_application a ON a.sou_mtdt_id=s.id AND a.dtset_rvsn_id=p.rvsn_id AND a.dtset_id=d.id
            JOIN integration.source_connection c ON c.id=s.sou_lnkg_id AND c.inst_id=d.psn_inst_id
                AND c.adptr_cd='KETI_DATACORE' AND c.use_yn=true
            WHERE d.id=? AND d.mng_stts_cd='ACTIVE' LIMIT 2
            """,(r,n)->new Stored(new DataAccessView(id,false,r.getObject("id",UUID.class),true,"KETI_DATASET","/entities",
                "datasetId",r.getString("otsd_id"),"datasetId",Map.of(),-1-r.getLong("lck_ver_no"),true,null),r.getLong("lck_ver_no")),id);
        return rows.size()==1?rows.getFirst():null;
    }
    static void validate(DataAccessInput input) {
        if(input==null||input.connectionId()==null||input.mode()==null||!Set.of("KETI_DATASET","NGSI_LD","JSON_ARRAY").contains(input.mode())
                ||input.queryPath()==null||!input.queryPath().matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+")
                ||input.scopeField()==null||!input.scopeField().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
                ||input.scopeValue()==null||input.scopeValue().isBlank()||input.scopeValue().length()>300
                ||input.scopeParameter()==null||!input.scopeParameter().matches("[A-Za-z][A-Za-z0-9_]{0,79}")
                ||input.fixedQuery()==null||input.fixedQuery().size()>10)throw invalid("조회 경로와 데이터셋 조건을 확인하세요.");
        if("KETI_DATASET".equals(input.mode())&&(!"/entities".equals(input.queryPath())||!"datasetId".equals(input.scopeField())||!"datasetId".equals(input.scopeParameter())))
            throw invalid("KETI 데이터 조회는 /entities의 datasetId 조건을 사용합니다.");
        if(!"NGSI_LD".equals(input.mode())&&Set.of("limit","offset","options","count").contains(input.scopeParameter()))
            throw invalid("데이터셋 조건의 매개변수를 확인하세요.");
        for(var e:input.fixedQuery().entrySet()) {
            if(!Set.of("type","attrs","lang").contains(e.getKey())||e.getValue()==null||e.getValue().length()>1000
                    ||e.getValue().chars().anyMatch(c->c<32||c==127))throw invalid("추가 조건은 type, attrs, lang만 입력할 수 있습니다.");
        }
    }
    public static String etag(DataAccessView view){return "\"data-access:"+view.datasetId()+":"+view.lockVersion()+"\"";}
    private static void requireVersion(UUID id,long version,String supplied){
        if(supplied==null)throw new DomainException(HttpStatus.PRECONDITION_REQUIRED,"PRECONDITION_REQUIRED","설정을 다시 조회하세요.");
        if(!supplied.equals("\"data-access:"+id+":"+version+"\""))throw stale();
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw invalid("추가 조건을 확인하세요.");}}
    private void audit(UUID id,Authentication auth,String action,long version){db.update("INSERT INTO delivery.data_access_event(id,dtset_id,prcs_user_id,prcs_cd,stng_ver_no) VALUES(?,?,?,?,?)",UUID.randomUUID(),id,access.principal(auth).userId(),action,version);}
    private static DomainException invalid(String message){return new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,"DATA_ACCESS_INVALID",message);}
    private static DomainException unavailable(){return new DomainException(HttpStatus.NOT_FOUND,"DATA_ACCESS_UNAVAILABLE","데이터 조회 연결이 설정되지 않았거나 사용할 수 없습니다.");}
    private static DomainException upstream(){return new DomainException(HttpStatus.BAD_GATEWAY,"DATA_SOURCE_UNAVAILABLE","허브의 데이터 조회 응답을 확인하지 못했습니다.");}
    private static DomainException stale(){return new DomainException(HttpStatus.PRECONDITION_FAILED,"VERSION_CONFLICT","조회 설정 또는 게시 상태가 변경되었습니다. 다시 조회하세요.");}
}

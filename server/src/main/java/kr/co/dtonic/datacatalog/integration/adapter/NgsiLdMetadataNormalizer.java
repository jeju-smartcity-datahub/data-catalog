package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter.AdapterFailure;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;

/** Bounded schema projection. Never resolves a context URL or stores observation/relationship values. */
public final class NgsiLdMetadataNormalizer {
    private static final String NS="https://uri.etsi.org/ngsi-ld/";
    private static final String DEFAULT=NS+"default-context/";
    private static final String XSD="http://www.w3.org/2001/XMLSchema#";
    private static final Set<String> CORE_CONTEXTS=Set.of(
        NS+"v1/ngsi-ld-core-context.jsonld",NS+"v1/ngsi-ld-core-context-v1.3.jsonld",
        NS+"v1/ngsi-ld-core-context-v1.4.jsonld",NS+"v1/ngsi-ld-core-context-v1.5.jsonld",
        NS+"v1/ngsi-ld-core-context-v1.6.jsonld",NS+"v1/ngsi-ld-core-context-v1.7.jsonld",
        NS+"v1/ngsi-ld-core-context-v1.8.jsonld",NS+"v1/ngsi-ld-core-context-v1.9.jsonld");
    private static final Set<String> SYSTEM=Set.of("@id","@type",NS+"createdAt",NS+"modifiedAt",NS+"observedAt",NS+"datasetId",NS+"instanceId",NS+"unitCode",NS+"scope");
    private final Map<String,JsonNode> configured=new TreeMap<>();

    public NgsiLdMetadataNormalizer(ObjectMapper json,String configuredContextMappings) {
        try {
            if(configuredContextMappings!=null&&configuredContextMappings.getBytes(StandardCharsets.UTF_8).length>262144)throw invalid();
            JsonNode root=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(configuredContextMappings==null||configuredContextMappings.isBlank()?"{}":configuredContextMappings);
            if(root==null||!root.isObject()||root.size()>32)throw invalid();
            var iterator=root.fields();
            while(iterator.hasNext()) {
                var entry=iterator.next();String uri=iri(entry.getKey(),2000,false);
                if(!entry.getValue().isObject()||CORE_CONTEXTS.contains(uri)
                    ||entry.getValue().has("@context")&&(entry.getValue().size()!=1||!entry.getValue().get("@context").isObject()))throw invalid();
                configured.put(uri,entry.getValue().deepCopy());
            }
            // Configuration must be self-contained and valid before it can be used by a collection run.
            for(var entry:configured.entrySet())new Context().load(entry.getValue(),new HashSet<>(),0);
        } catch(Exception malformed) { throw new IllegalArgumentException("Invalid NGSI-LD context mapping configuration"); }
    }

    public HubAdapter.Metadata normalize(JsonNode entity) throws AdapterFailure {
        try {
            if(entity==null||!entity.isObject()||entity.size()>550)throw invalid();
            Context context=new Context();context.load(entity.get("@context"),new HashSet<>(),0);
            Map<String,Member> members=members(entity,context);
            String id=text(single(value(members,"@id")),300);
            // Same bounded source-ID contract as the existing integration persistence/worker.
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,299}"))throw invalid();
            iri(id,300,false);
            List<String> types=types(value(members,"@type"),context);
            String modified=temporal(value(members,NS+"modifiedAt"));
            temporal(value(members,NS+"createdAt"));
            var fields=new TreeMap<String,Map<String,Object>>();
            for(var entry:members.entrySet()) {
                if(SYSTEM.contains(entry.getKey()))continue;
                schema(entry.getValue().node(),entry.getValue().label(),entry.getKey(),"",context,0,fields);
            }
            String title=descriptive(members,List.of("https://schema.org/name","http://schema.org/name","http://purl.org/dc/terms/title",NS+"name",DEFAULT+"name"),context,300);
            if(title.isBlank())title=id;
            String description=descriptive(members,List.of("https://schema.org/description","http://schema.org/description","http://purl.org/dc/terms/description",NS+"description",DEFAULT+"description"),context,10000);
            var details=new TreeMap<String,Object>();details.put("sourceEntityId",id);
            if(!modified.isBlank())details.put("sourceModifiedAt",modified);
            details.put("type",types.getFirst());details.put("typeUri",types.getFirst());details.put("entityTypes",types);
            details.put("context",List.copyOf(context.references));
            var mappings=new TreeMap<String,String>();
            for(var entry:members.entrySet())if(!entry.getKey().startsWith("@"))mappings.put(entry.getValue().label(),entry.getKey());
            details.put("contextTerms",mappings);
            var projected=new ArrayList<Map<String,Object>>();int ordinal=0;
            for(var field:fields.values()){field.put("ordinal",ordinal++);projected.add(Collections.unmodifiableMap(field));}
            details.put("fields",List.copyOf(projected));
            // Observation timestamps are retained for source provenance, not metadata revision churn.
            var revision=new TreeMap<>(details);revision.remove("sourceModifiedAt");
            String hash=hash(title+"\n"+description+"\n"+NormalizedHubMetadata.encode(revision));
            if(NormalizedHubMetadata.encode(details).getBytes(StandardCharsets.UTF_8).length>220*1024)throw invalid();
            return new HubAdapter.Metadata(id,hash,title,description,Collections.unmodifiableMap(details));
        } catch(AdapterFailure failure) { throw failure; }
          catch(RuntimeException malformed) { throw invalid(); }
    }

    private void schema(JsonNode raw,String label,String attributeUri,String parent,Context context,int depth,
                        Map<String,Map<String,Object>> output) throws AdapterFailure {
        if(depth>12)throw invalid();
        List<JsonNode> instances=arrayOrOne(raw,100);
        if(instances.isEmpty())throw invalid();
        String path=parent.isEmpty()?path(label,attributeUri):parent+"."+path(label,attributeUri);
        if(path.length()>200)path="uri_"+hash(parent+"/"+attributeUri).substring(0,32);
        for(JsonNode instance:instances) {
            if(!instance.isObject())throw invalid();
            Context nested=new Context(context);nested.load(instance.get("@context"),new HashSet<>(),0);
            var values=members(instance,nested);List<String> kinds=types(value(values,"@type"),nested);
            if(kinds.size()!=1||!Set.of(NS+"Property",NS+"Relationship",NS+"GeoProperty").contains(kinds.getFirst()))throw invalid();
            String kind=kinds.getFirst().substring(NS.length());
            JsonNode content=value(values,kind.equals("Relationship")?NS+"hasObject":NS+"hasValue");
            if(content==null)throw invalid();
            String dataType;
            if(kind.equals("Relationship")) {
                var targets=arrayOrOne(content,100);if(targets.isEmpty())throw invalid();
                for(JsonNode target:targets) {
                    JsonNode targetValue=target.isObject()?target.get("@id"):target;
                    iri(text(targetValue,2000),2000,false);
                }
                dataType="STRING";
            } else dataType=kind.equals("GeoProperty")?geoType(content)
                :!instance.has("@type")&&content.isArray()?"ARRAY":valueType(content);
            String observed=temporal(value(values,NS+"observedAt"));
            temporal(value(values,NS+"createdAt"));temporal(value(values,NS+"modifiedAt"));
            JsonNode dataset=value(values,NS+"datasetId");
            if(dataset!=null)iri(text(singleId(dataset),2000),2000,false);
            String unit=value(values,NS+"unitCode")==null?null:text(scalar(value(values,NS+"unitCode")),100);
            var field=new TreeMap<String,Object>();field.put("fieldPath",path);field.put("physicalName",path);
            field.put("displayName",label.length()<=200?label:path);field.put("description","");
            field.put("dataType",dataType);field.put("sourceDataType",kind+":"+dataType);
            field.put("nullable",true);field.put("publicVisible",true);field.put("attributeType",kind);
            field.put("attributeUri",attributeUri);field.put("hasObservedAt",!observed.isBlank());
            field.put("hasUnitCode",unit!=null);if(unit!=null)field.put("unit",unit);
            Map<String,Object> prior=output.putIfAbsent(path,field);
            if(prior!=null) {
                if(!Objects.equals(prior.get("attributeUri"),attributeUri)||!Objects.equals(prior.get("dataType"),dataType)
                    ||!Objects.equals(prior.get("attributeType"),kind)||!Objects.equals(prior.get("unit"),unit))throw invalid();
                if(!observed.isBlank())prior.put("hasObservedAt",true);
            }
            if(output.size()>500)throw invalid();
            for(var child:values.entrySet())if(!SYSTEM.contains(child.getKey())&&!Set.of(NS+"hasValue",NS+"hasObject").contains(child.getKey()))
                schema(child.getValue().node(),child.getValue().label(),child.getKey(),path,nested,depth+1,output);
        }
    }

    private static String valueType(JsonNode input)throws AdapterFailure {
        JsonNode literal=literal(input);
        JsonNode value=scalar(input);
        if(value==null||value.isNull())throw invalid();
        if(literal!=null&&literal.has("@type")) {
            String type=text(literal.get("@type"),2000);
            if(type.equals(XSD+"dateTime")||type.equals(XSD+"dateTimeStamp")) {
                validateTypedDate(text(value,100),false,type.endsWith("dateTimeStamp"));return "DATETIME";
            }
            if(type.equals(XSD+"date")) {
                validateTypedDate(text(value,100),true,false);return "DATE";
            }
            if(!type.equals("@json"))iri(type,2000,true);
        }
        if(value.isTextual())return "STRING";
        if(value.isIntegralNumber())return "INTEGER";
        if(value.isNumber())return "NUMBER";
        if(value.isBoolean())return "BOOLEAN";
        if(value.isArray())return "ARRAY";
        if(value.isObject())return "OBJECT";
        throw invalid();
    }
    private static void validateTypedDate(String value,boolean date,boolean timezoneRequired)throws AdapterFailure {
        try {
            var calendar=javax.xml.datatype.DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(value);
            if(!calendar.isValid()||!calendar.getXMLSchemaType().equals(date?javax.xml.datatype.DatatypeConstants.DATE:javax.xml.datatype.DatatypeConstants.DATETIME)
                ||timezoneRequired&&calendar.getTimezone()==javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED)throw invalid();
        }catch(IllegalArgumentException malformed){throw invalid();}
    }
    private static String geoType(JsonNode input)throws AdapterFailure {
        JsonNode value=scalar(input);
        geometry(value,0,new int[]{0});
        return "OBJECT";
    }
    /** Validate a bounded RFC7946 geometry, then discard all coordinates. Empty coordinate shapes are refused. */
    private static void geometry(JsonNode node,int depth,int[] count)throws AdapterFailure {
        if(node==null||!node.isObject()||depth>12)throw invalid();
        String kind=text(node.get("type"),100);
        if(node.has("bbox")) {
            JsonNode box=node.get("bbox");
            if(!box.isArray()||box.size()!=4&&box.size()!=6)throw invalid();
            for(JsonNode v:box)if(!v.isNumber()||!Double.isFinite(v.doubleValue()))throw invalid();
        }
        if(kind.equals("GeometryCollection")) {
            if(node.has("coordinates"))throw invalid();JsonNode children=node.get("geometries");
            if(children==null||!children.isArray()||children.size()>1000)throw invalid();
            for(JsonNode child:children)geometry(child,depth+1,count);return;
        }
        if(node.has("geometries"))throw invalid();JsonNode coordinates=node.get("coordinates");
        switch(kind) {
            case "Point" -> position(coordinates,count);
            case "MultiPoint" -> positions(coordinates,1,false,count);
            case "LineString" -> positions(coordinates,2,false,count);
            case "MultiLineString" -> {
                boundedArray(coordinates,1);for(JsonNode line:coordinates)positions(line,2,false,count);
            }
            case "Polygon" -> polygon(coordinates,count);
            case "MultiPolygon" -> {
                boundedArray(coordinates,1);for(JsonNode polygon:coordinates)polygon(polygon,count);
            }
            default -> throw invalid();
        }
    }
    private static void polygon(JsonNode polygon,int[] count)throws AdapterFailure {
        boundedArray(polygon,1);for(JsonNode ring:polygon)positions(ring,4,true,count);
    }
    private static void positions(JsonNode list,int minimum,boolean closed,int[] count)throws AdapterFailure {
        boundedArray(list,minimum);for(JsonNode point:list)position(point,count);
        if(closed) {
            var first=list.get(0);var last=list.get(list.size()-1);if(first.size()!=last.size())throw invalid();
            for(int i=0;i<first.size();i++)if(Double.compare(first.get(i).doubleValue(),last.get(i).doubleValue())!=0)throw invalid();
        }
    }
    private static void boundedArray(JsonNode list,int minimum)throws AdapterFailure {
        if(list==null||!list.isArray()||list.size()<minimum||list.size()>10000)throw invalid();
    }
    private static void position(JsonNode point,int[] count)throws AdapterFailure {
        if(point==null||!point.isArray()||point.size()<2||point.size()>3||++count[0]>10000)throw invalid();
        for(JsonNode value:point)if(!value.isNumber()||!Double.isFinite(value.doubleValue()))throw invalid();
        if(Math.abs(point.get(0).doubleValue())>180||Math.abs(point.get(1).doubleValue())>90)throw invalid();
    }
    private String descriptive(Map<String,Member> values,List<String> names,Context context,int max)throws AdapterFailure {
        for(String name:names)if(values.containsKey(name)) {
            JsonNode attribute=single(values.get(name).node());
            if(!attribute.isObject())throw invalid();
            var members=members(attribute,context);
            if(!types(value(members,"@type"),context).equals(List.of(NS+"Property")))throw invalid();
            JsonNode rawContent=value(members,NS+"hasValue");
            if(!attribute.has("@type")&&rawContent!=null&&rawContent.isArray())throw invalid();
            JsonNode content=scalar(rawContent);
            if(content==null||!content.isTextual()||content.textValue().length()>max
                ||content.textValue().codePoints().anyMatch(Character::isISOControl))throw invalid();
            return content.textValue().strip();
        }
        return "";
    }
    private static JsonNode scalar(JsonNode node)throws AdapterFailure {
        if(node==null)throw invalid();JsonNode literal=literal(node);
        return literal!=null?literal.get("@value"):node;
    }
    /** Expanded hasValue is an array of value objects; a normalized value array is actual JSON data. */
    private static JsonNode literal(JsonNode node)throws AdapterFailure {
        if(node!=null&&node.isArray()&&node.size()==1&&node.get(0).isObject()&&node.get(0).has("@value"))node=node.get(0);
        if(node!=null&&node.isObject()&&node.has("@value")) {
            var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);
            if(!Set.of("@value","@type","@language").containsAll(keys)||node.has("@type")&&node.has("@language"))throw invalid();
            if(node.has("@language")&&!validLanguageTag(text(node.get("@language"),100)))throw invalid();
            return node;
        }
        return null;
    }
    private static boolean validLanguageTag(String value) {
        String[] parts = value.split("-", -1);
        if (!parts[0].matches("[A-Za-z]{1,8}")) return false;
        for (int index = 1; index < parts.length; index++)
            if (!parts[index].matches("[A-Za-z0-9]{1,8}")) return false;
        return true;
    }
    private static JsonNode singleId(JsonNode node)throws AdapterFailure {node=single(node);return node.isObject()?node.get("@id"):node;}
    private static JsonNode single(JsonNode node)throws AdapterFailure {
        if(node==null)throw invalid();if(node.isArray()){if(node.size()!=1)throw invalid();return node.get(0);}return node;
    }
    private static List<JsonNode> arrayOrOne(JsonNode node,int max)throws AdapterFailure {
        if(node==null)throw invalid();if(!node.isArray())return List.of(node);
        if(node.size()>max)throw invalid();var list=new ArrayList<JsonNode>();node.forEach(list::add);return list;
    }
    private static String temporal(JsonNode value)throws AdapterFailure {
        if(value==null)return "";String text=text(scalar(value),100);
        try{return OffsetDateTime.parse(text).toInstant().toString();}catch(RuntimeException invalid){throw invalid();}
    }
    private static List<String> types(JsonNode value,Context context)throws AdapterFailure {
        if(value==null)throw invalid();var types=new TreeSet<String>();
        for(JsonNode item:arrayOrOne(value,20))if(!types.add(context.expand(text(item,2000))))throw invalid();
        if(types.isEmpty())throw invalid();return List.copyOf(types);
    }
    private static Map<String,Member> members(JsonNode object,Context context)throws AdapterFailure {
        if(object==null||!object.isObject())throw invalid();var result=new TreeMap<String,Member>();
        var fields=object.fields();while(fields.hasNext()) {
            var entry=fields.next();if(entry.getKey().equals("@context"))continue;
            String key=context.expand(entry.getKey());
            if(result.putIfAbsent(key,new Member(entry.getKey(),entry.getValue()))!=null)throw invalid();
        }return result;
    }
    private static JsonNode value(Map<String,Member> values,String key){return values.containsKey(key)?values.get(key).node():null;}
    private record Member(String label,JsonNode node){}
    private static String path(String label,String uri){return label.matches("[A-Za-z_][A-Za-z0-9_-]{0,99}")?label:"uri_"+hash(uri).substring(0,32);}
    private static String text(JsonNode value,int max)throws AdapterFailure {
        if(value==null||!value.isTextual())throw invalid();String text=value.textValue().strip();
        if(text.isBlank()||text.length()>max||text.codePoints().anyMatch(Character::isISOControl))throw invalid();return text;
    }
    private static String iri(String value,int max,boolean fragment)throws AdapterFailure {
        if(value==null||value.isBlank()||value.length()>max||value.indexOf('?')>=0
            ||value.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw invalid();
        try{URI uri=URI.create(value);if(!uri.isAbsolute()||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||(!fragment&&uri.getRawFragment()!=null)
            ||!Set.of("http","https","urn").contains(uri.getScheme())||!uri.normalize().equals(uri))throw invalid();
            if(!uri.getScheme().equals("urn")&&uri.getHost()==null)throw invalid();return value;
        }catch(IllegalArgumentException malformed){throw invalid();}
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    private static AdapterFailure invalid(){return new AdapterFailure(AdapterFailure.Reason.INVALID_SCHEMA,0);}

    private final class Context {
        private final Map<String,String> terms=new TreeMap<>();private String vocab=DEFAULT;
        private final Set<String> references=new TreeSet<>();
        private final Set<String> prefixes=new HashSet<>();
        Context(){terms.put("id","@id");terms.put("type","@type");
            for(String name:List.of("Property","Relationship","GeoProperty","observedAt","createdAt","modifiedAt","datasetId","instanceId","unitCode","scope"))terms.put(name,NS+name);
            terms.put("value",NS+"hasValue");terms.put("object",NS+"hasObject");terms.put("ngsi-ld",NS);prefixes.add("ngsi-ld");}
        Context(Context source){terms.putAll(source.terms);vocab=source.vocab;references.addAll(source.references);prefixes.addAll(source.prefixes);}
        void load(JsonNode node,Set<String> visiting,int depth)throws AdapterFailure {
            if(node==null)return;if(depth>12)throw invalid();
            if(node.isArray()){if(node.size()>100)throw invalid();for(JsonNode child:node)load(child,visiting,depth+1);return;}
            if(node.isTextual()){
                String uri=iri(text(node,2000),2000,false);references.add(uri);
                if(CORE_CONTEXTS.contains(uri))return;
                if(!configured.containsKey(uri)||!visiting.add(uri))throw invalid();
                load(configured.get(uri),visiting,depth+1);visiting.remove(uri);return;
            }
            if(!node.isObject()||node.size()>503)throw invalid();
            if(node.has("@context")){if(node.size()!=1)throw invalid();load(node.get("@context"),visiting,depth+1);return;}
            if(node.has("@vocab"))vocab=iri(text(node.get("@vocab"),2000),2000,true);
            var raw=new TreeMap<String,String>();var declaredPrefixes=new HashSet<String>();var iterator=node.fields();
            while(iterator.hasNext()){
                var entry=iterator.next();String term=entry.getKey();if(term.equals("@vocab"))continue;
                if(term.equals("@version")&&entry.getValue().isNumber()&&entry.getValue().asDouble()==1.1)continue;
                if(term.equals("@protected")&&entry.getValue().isBoolean())continue;
                if(term.startsWith("@")||term.length()>200||!term.matches("[A-Za-z_][A-Za-z0-9_.-]*"))throw invalid();
                JsonNode definition=entry.getValue();
                if(definition.isObject()){
                    var names=new HashSet<String>();definition.fieldNames().forEachRemaining(names::add);
                    if(!Set.of("@id","@type","@container","@prefix","@protected").containsAll(names)||!definition.has("@id"))throw invalid();
                    if(definition.has("@prefix")&&(!definition.get("@prefix").isBoolean()||!definition.get("@prefix").booleanValue())
                        ||definition.has("@protected")&&!definition.get("@protected").isBoolean())throw invalid();
                    if(definition.has("@type")) {
                        String coercion=text(definition.get("@type"),2000);
                        if(!Set.of("@id","@vocab","@json").contains(coercion))iri(coercion,2000,true);
                    }
                    // Other container semantics require a full JSON-LD processor and are refused explicitly.
                    if(definition.has("@container")&&!Set.of("@set").contains(text(definition.get("@container"),100)))throw invalid();
                    if(definition.path("@prefix").asBoolean(false))declaredPrefixes.add(term);
                    definition=definition.get("@id");
                }
                String definitionText=text(definition,2000);
                if(definitionText.startsWith("@")&&!Set.of("@id","@type").contains(definitionText))throw invalid();
                raw.put(term,definitionText);
                if(definitionText.endsWith("/")||definitionText.endsWith("#")||definitionText.endsWith(":"))declaredPrefixes.add(term);
            }
            if(raw.size()>500)throw invalid();
            for(String term:raw.keySet()){
                String resolved=resolve(term,raw,declaredPrefixes,new HashSet<>(),0);String previous=terms.putIfAbsent(term,resolved);
                if(previous!=null&&!previous.equals(resolved))throw invalid();
                if(declaredPrefixes.contains(term))prefixes.add(term);
            }
        }
        private String resolve(String term,Map<String,String> raw,Set<String> declaredPrefixes,Set<String> visiting,int depth)throws AdapterFailure {
            if(depth>12||!visiting.add(term))throw invalid();String value=raw.get(term);
            if(value.equals("@id")||value.equals("@type"))return value;
            if(raw.containsKey(value))return resolve(value,raw,declaredPrefixes,visiting,depth+1);
            int colon=value.indexOf(':');
            if(colon>0){String prefix=value.substring(0,colon);String mapped=raw.containsKey(prefix)&&declaredPrefixes.contains(prefix)?resolve(prefix,raw,declaredPrefixes,new HashSet<>(visiting),depth+1):prefixes.contains(prefix)?terms.get(prefix):null;
                if(mapped!=null&&!mapped.startsWith("@"))return iri(mapped+value.substring(colon+1),2000,true);
                return iri(value,2000,true);}
            if(terms.containsKey(value))return terms.get(value);
            return iri(vocab+value,2000,true);
        }
        String expand(String term)throws AdapterFailure {
            if(term.equals("@id")||term.equals("@type"))return term;
            if(term.startsWith("@"))throw invalid();if(terms.containsKey(term))return terms.get(term);
            int colon=term.indexOf(':');if(colon>0){String prefix=prefixes.contains(term.substring(0,colon))?terms.get(term.substring(0,colon)):null;
                return iri(prefix==null?term:prefix+term.substring(colon+1),2000,true);}
            return iri(vocab+term,2000,true);
        }
    }
}

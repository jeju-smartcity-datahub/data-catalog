package kr.co.dtonic.datacatalog.integration.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** Bounded descriptive metadata only. Arbitrary hub responses never enter a candidate. */
public final class NormalizedHubMetadata {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TEXT=Set.of("providerOrganization","providerSystem","ownership","datasetExtension","category","updateInterval","license","department","restrictions","datamodelId","type","typeUri");
    private static final Set<String> TYPES=Set.of("STRING","INTEGER","NUMBER","BOOLEAN","DATE","DATETIME","OBJECT","ARRAY");
    private NormalizedHubMetadata() {}

    public static Map<String,Object> details(Map<String,Object> input) {
        var result=new TreeMap<String,Object>();
        for(String key:TEXT) if(input.get(key)!=null) result.put(key,text(input.get(key),4000));
        if(input.get("sourceEntityId")!=null)result.put("sourceEntityId",iri(input.get("sourceEntityId"),300));
        if(input.get("sourceModifiedAt")!=null) {
            String modified=text(input.get("sourceModifiedAt"),100);
            try { OffsetDateTime.parse(modified); }catch(RuntimeException malformed) { throw invalid(); }
            result.put("sourceModifiedAt",modified);
        }
        if(input.get("entityTypes")!=null) {
            if(!(input.get("entityTypes") instanceof List<?> values)||values.isEmpty()||values.size()>20)throw invalid();
            result.put("entityTypes",values.stream().map(value->iri(value,2000)).distinct().sorted().toList());
        }
        if(input.get("contextTerms")!=null) {
            if(!(input.get("contextTerms") instanceof Map<?,?> values)||values.size()>600)throw invalid();
            var mappings=new TreeMap<String,String>();
            values.forEach((key,value)->mappings.put(text(key,2000),iri(value,2000)));
            result.put("contextTerms",Collections.unmodifiableMap(mappings));
        }
        for(String key:List.of("keywords","targetRegions","context")) if(input.get(key)!=null) {
            if(!(input.get(key) instanceof List<?> values)||values.size()>100)throw invalid();
            var entries=new ArrayList<String>();
            for(Object value:values) entries.add(text(value,key.equals("context")?2000:500));
            result.put(key,key.equals("context")?List.copyOf(entries):entries.stream().distinct().sorted().toList());
        }
        if(input.get("providingApiUri")!=null) {
            String value=text(input.get("providingApiUri"),2000);
            URI uri;try { uri=URI.create(value); }catch(IllegalArgumentException e) { throw invalid(); }
            // Query parameters may contain access tokens; only a plain documented endpoint is retained.
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw invalid();
            result.put("providingApiUri",value);
        }
        if(input.containsKey("fields"))result.put("fields",fields(input.get("fields"),true));
        if(encode(result).getBytes(StandardCharsets.UTF_8).length>262144)throw invalid();
        return Collections.unmodifiableMap(result);
    }

    public static List<Map<String,Object>> catalogFields(Object fields) { return fields(fields,false); }

    private static List<Map<String,Object>> fields(Object value,boolean source) {
        if(!(value instanceof List<?> values)||values.size()>500)throw invalid();
        var result=new ArrayList<Map<String,Object>>();var paths=new HashSet<String>();var names=new HashSet<String>();var ordinals=new HashSet<Integer>();
        for(Object item:values) {
            if(!(item instanceof Map<?,?> field))throw invalid();
            var row=new TreeMap<String,Object>();
            for(String key:List.of("fieldPath","physicalName","displayName")) {
                String name=text(field.get(key),200);if(name.isBlank()||name.codePoints().anyMatch(Character::isISOControl))throw invalid();row.put(key,name);
            }
            row.put("description",field.get("description")==null?"":text(field.get("description"),4000));
            String type=text(field.get("dataType"),30);if(!TYPES.contains(type))throw invalid();row.put("dataType",type);
            if(!(field.get("nullable") instanceof Boolean nullable)||!(field.get("publicVisible") instanceof Boolean visible))throw invalid();
            row.put("nullable",nullable);row.put("publicVisible",visible);
            int ordinal=integer(field.get("ordinal"),0,1000000);row.put("ordinal",ordinal);
            row.put("maxLength",field.get("maxLength")==null?null:integer(field.get("maxLength"),1,1000000));
            row.put("unit",field.get("unit")==null?null:text(field.get("unit"),100));
            row.put("approvedTermId",null);row.put("mappingEvidence",null);
            if(source) {
                if(field.get("sourceDataType")!=null)row.put("sourceDataType",text(field.get("sourceDataType"),100));
                if(field.get("attributeType")!=null)row.put("attributeType",text(field.get("attributeType"),100));
                if(field.get("accessMode")!=null)row.put("accessMode",text(field.get("accessMode"),100));
                if(field.get("attributeUri")!=null)row.put("attributeUri",text(field.get("attributeUri"),2000));
                for(String flag:List.of("hasObservedAt","hasUnitCode","isRequired"))if(field.get(flag)!=null) {
                    if(!(field.get(flag) instanceof Boolean))throw invalid();row.put(flag,field.get(flag));
                }
                if(field.get("isRequired") instanceof Boolean required&&required.booleanValue()==nullable.booleanValue())throw invalid();
            }
            if(!paths.add((String)row.get("fieldPath"))||!names.add((String)row.get("physicalName"))||!ordinals.add(ordinal))throw invalid();
            result.add(Collections.unmodifiableMap(row));
        }
        result.sort(Comparator.comparingInt(row->(Integer)row.get("ordinal")));
        return List.copyOf(result);
    }

    public static String encode(Object value) {
        try { return JSON.writeValueAsString(ordered(value)); }
        catch(JsonProcessingException e) { throw invalid(); }
    }
    private static Object ordered(Object value) {
        if(value instanceof Map<?,?> map) {
            var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),ordered(v)));return sorted;
        }
        if(value instanceof List<?> list)return list.stream().map(NormalizedHubMetadata::ordered).toList();
        return value;
    }
    private static String text(Object value,int max) {
        if(!(value instanceof String string))throw invalid();
        String clean=string.strip();if(clean.length()>max||clean.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid();return clean;
    }
    private static int integer(Object value,int min,int max) {
        if(!(value instanceof Number number)||number.doubleValue()!=number.intValue()||number.intValue()<min||number.intValue()>max)throw invalid();return number.intValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid normalized hub metadata"); }
    private static String iri(Object value,int max) {
        String string=text(value,max);URI uri;
        try { uri=URI.create(string); }catch(RuntimeException malformed) { throw invalid(); }
        if(!uri.isAbsolute()||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||string.chars().anyMatch(Character::isWhitespace))throw invalid();
        return string;
    }
}

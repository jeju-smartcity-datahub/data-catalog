package kr.co.dtonic.datacatalog.metadata.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Descriptive metadata never grants permission to retrieve or redistribute source data. */
@Schema(requiredProperties={"department","license","updateFrequency","spatialCoverage","temporalStart","temporalEnd","keywords"})
public record MetadataDetails(@Size(max=200) String department, @Size(max=500) String license,
        @Size(max=100) String updateFrequency, @Size(max=500) String spatialCoverage,
        LocalDate temporalStart, LocalDate temporalEnd, @Size(max=20) List<@Size(max=100) String> keywords) {
    public MetadataDetails { keywords=keywords==null?List.of():keywords; }
    public static MetadataDetails empty() { return new MetadataDetails(null,null,null,null,null,null,List.of()); }
    public static MetadataDetails read(JsonNode value) {
        if(!value.isObject())return empty();
        var words=new java.util.ArrayList<String>();
        if(value.path("keywords").isArray())value.path("keywords").forEach(v->words.add(v.asText()));
        String start=text(value,"temporalStart"),end=text(value,"temporalEnd");
        return new MetadataDetails(text(value,"department"),text(value,"license"),text(value,"updateFrequency"),text(value,"spatialCoverage"),
                start==null?null:LocalDate.parse(start),end==null?null:LocalDate.parse(end),List.copyOf(words));
    }
    private static String text(JsonNode node,String key) { return node.path(key).isTextual()?node.path(key).asText():null; }
}

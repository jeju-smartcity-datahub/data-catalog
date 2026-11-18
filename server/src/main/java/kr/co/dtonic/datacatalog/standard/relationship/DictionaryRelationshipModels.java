package kr.co.dtonic.datacatalog.standard.relationship;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class DictionaryRelationshipModels {
    private DictionaryRelationshipModels() {}
    public record WordAttributes(@Size(max=200) String englishName,@Size(max=100) String abbreviation,
            @NotNull @Size(max=30) List<@NotBlank @Size(max=200) String> synonyms) {}
    public record DomainAttributes(@Min(1) @Max(1000000) Integer maxLength,@Min(1) @Max(1000) Integer precision,
            @Min(0) @Max(1000) Integer scale,@Size(max=200) String format,@Size(max=100) String unit,
            @NotNull @Size(max=1000) List<@NotBlank @Size(max=200) String> allowedValues) {}
    public record TermAttributes(@NotBlank @Size(max=200) String physicalName,@NotNull UUID domainEntryId,
            @NotNull @Size(min=1,max=32) List<@NotNull UUID> wordEntryIds) {}
    @Schema(requiredProperties={"entryId","kind","lockVersion"})
    public record EntryRelationships(UUID entryId,String kind,long lockVersion,WordAttributes word,DomainAttributes domain,TermAttributes term) {}
}

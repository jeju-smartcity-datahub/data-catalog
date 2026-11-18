package kr.co.dtonic.datacatalog.standard.export;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class DictionaryExportModels {
    private DictionaryExportModels() {}
    public record ExportRequest(@NotNull @Pattern(regexp="WORD|TERM|DOMAIN") String kind,
            @Size(max=200) String query,@Pattern(regexp="DRAFT|IN_REVIEW|APPROVED|REJECTED") String state,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @Size(min=1,max=100) java.util.List<@NotNull UUID> entryIds) {
        public ExportRequest(String kind,String query,String state){this(kind,query,state,null);}
    }
    @Schema(requiredProperties={"id","dictionaryId","kind","status","entryCount","createdAt","expiresAt","contentHash","fileName"})
    public record DictionaryExport(UUID id,UUID dictionaryId,String kind,String query,String state,String status,
            int entryCount,OffsetDateTime createdAt,OffsetDateTime expiresAt,String contentHash,String fileName) {}
    public record Download(DictionaryExport export,byte[] bytes) {
        public Download { bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Download download && java.util.Objects.equals(export,download.export)
                    && java.util.Arrays.equals(bytes,download.bytes);
        }
        @Override public int hashCode() { return 31*java.util.Objects.hashCode(export)+java.util.Arrays.hashCode(bytes); }
        @Override public String toString() { return "DictionaryDownload[bytes="+bytes.length+"]"; }
    }
}

package kr.co.dtonic.datacatalog.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SearchHistoryContracts {
    private SearchHistoryContracts(){}
    public record FeedbackOffer(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID eventId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,description="Single-event feedback capability; never placed in URLs or logs") String feedbackToken) {
        @Override public String toString(){return "FeedbackOffer[redacted]";}
    }
    public enum Rating { HELPFUL, NOT_HELPFUL }
    public record FeedbackInput(@NotNull UUID eventId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String feedbackToken,@NotNull Rating rating) {
        @Override public String toString(){return "FeedbackInput[redacted]";}
    }
    public record FeedbackForm(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String csrfHeaderName,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String csrfToken) {
        @Override public String toString(){return "FeedbackForm[redacted]";}
    }
    public record ResultReference(UUID datasetId,int publicationNo,int ordinal){}
    public record HistoryDetail(UUID eventId,OffsetDateTime occurredAt,OffsetDateTime expiresAt,
            SearchQueryRepresentation queryRepresentation,List<ResultReference> returnedResults,
            Rating rating,OffsetDateTime ratedAt,Integer returnedPage,Integer returnedSize,
            String resultScope,boolean legacyIncomplete){}
}

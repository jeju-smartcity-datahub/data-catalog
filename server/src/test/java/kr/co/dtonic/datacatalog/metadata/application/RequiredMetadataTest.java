package kr.co.dtonic.datacatalog.metadata.application;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.MetadataDraft;
import kr.co.dtonic.datacatalog.metadata.api.MetadataDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class RequiredMetadataTest {
    @Test void completeMetadataAllowsEveryExistingVisibility() {
        for (String visibility : List.of("PUBLIC", "ORGANIZATION", "PRIVATE"))
            assertThatCode(() -> RequiredMetadata.requireComplete(draft("title", "description", UUID.randomUUID(), visibility,
                    details("department", "license", List.of("keyword"))))).doesNotThrowAnyException();
    }

    @Test void emptyDescriptionDepartmentLicenseAndKeywordsIdentifyOnlyMissingFields() {
        for (String missing : Arrays.asList(null, "", " \t\n", "\u00a0\u2007\u202f")) {
            rejects(draft("title", missing, UUID.randomUUID(), "PUBLIC", details("department", "license", List.of("keyword"))), "설명");
            rejects(draft("title", "description", UUID.randomUUID(), "PUBLIC", details(missing, "license", List.of("keyword"))), "담당부서");
            rejects(draft("title", "description", UUID.randomUUID(), "PUBLIC", details("department", missing, List.of("keyword"))), "라이선스");
        }
        List<List<String>> cases=Arrays.asList(null,List.of(),Arrays.asList("keyword",null),List.of(" "),List.of("\u00a0"));
        for (List<String> words : cases)
            rejects(draft("title", "description", UUID.randomUUID(), "PUBLIC", details("department", "license", words)), "키워드");
    }

    @Test void providerVisibilityAndLegacyTitleCannotBypassTheRegistrationGate() {
        rejects(draft("title", "description", null, "PUBLIC", details("department", "license", List.of("keyword"))), "제공기관");
        for (String visibility : Arrays.asList(null, "", "UNKNOWN"))
            rejects(draft("title", "description", UUID.randomUUID(), visibility, details("department", "license", List.of("keyword"))), "공개등급");
        rejects(draft(" ", "description", UUID.randomUUID(), "PUBLIC", details("department", "license", List.of("keyword"))), "제목");
    }

    @Test void missingDetailsReportsAllRequiredNamesWithoutEchoingMetadataValues() {
        var value=draft("title", "sensitive-content-not-for-error", UUID.randomUUID(), "PRIVATE", null);
        assertThatThrownBy(() -> RequiredMetadata.requireComplete(value)).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(failure.code()).isEqualTo("REQUIRED_METADATA_MISSING");
            assertThat(failure.getMessage()).contains("담당부서", "라이선스", "키워드").doesNotContain("sensitive-content-not-for-error", "title");
        });
    }

    private void rejects(MetadataDraft value,String field) {
        assertThatThrownBy(() -> RequiredMetadata.requireComplete(value)).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.code()).isEqualTo("REQUIRED_METADATA_MISSING");
            assertThat(failure.getMessage()).contains(field);
        });
    }
    private MetadataDetails details(String department,String license,List<String> words) {
        return new MetadataDetails(department,license,null,null,null,null,words);
    }
    private MetadataDraft draft(String title,String description,UUID provider,String visibility,MetadataDetails details) {
        return new MetadataDraft(UUID.randomUUID(),"unit-draft",UUID.randomUUID(),UUID.randomUUID(),1,"DRAFT",title,description,
                provider,null,visibility,0,null,null,null,List.of(),List.of(),List.of(),details,OffsetDateTime.now());
    }
}

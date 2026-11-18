package kr.co.dtonic.datacatalog.metadata.application;

import java.util.ArrayList;
import java.util.List;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.metadata.api.MetadataContracts.MetadataDraft;
import org.springframework.http.HttpStatus;

/** Working drafts may be incomplete; registration review and publication require complete metadata. */
final class RequiredMetadata {
    private RequiredMetadata() { }

    static void requireComplete(MetadataDraft draft) {
        var missing = new ArrayList<String>();
        if (blank(draft.title())) missing.add("제목");
        if (blank(draft.description())) missing.add("설명");
        if (draft.providerOrganizationId() == null) missing.add("제공기관");
        if (draft.visibility() == null || !List.of("PUBLIC", "ORGANIZATION", "PRIVATE").contains(draft.visibility())) missing.add("공개등급");
        var details = draft.details();
        if (details == null || blank(details.department())) missing.add("담당부서");
        if (details == null || blank(details.license())) missing.add("라이선스");
        if (details == null || details.keywords() == null || details.keywords().isEmpty()
                || details.keywords().stream().anyMatch(RequiredMetadata::blank)) missing.add("키워드");
        if (!missing.isEmpty()) throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,
                "REQUIRED_METADATA_MISSING", "등록 필수항목을 입력하세요: " + String.join(", ", missing) + ". 초안은 보관할 수 있습니다.");
    }

    private static boolean blank(String value) {
        return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}

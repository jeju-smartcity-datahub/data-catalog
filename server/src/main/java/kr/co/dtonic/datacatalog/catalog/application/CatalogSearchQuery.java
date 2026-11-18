package kr.co.dtonic.datacatalog.catalog.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kr.co.dtonic.datacatalog.catalog.api.CatalogSearchPage;
import kr.co.dtonic.datacatalog.catalog.api.DatasetSummary;

/** Versioned, deterministic query rules. Scores express matched rules, never measured accuracy. */
public record CatalogSearchQuery(String original, List<String> terms) {
    public static final String VERSION = "catalog-query-rules-v1";
    private static final Set<String> REQUEST_WORDS = Set.of(
            "데이터", "데이터셋", "자료", "정보", "목록", "관련", "관련된", "대한", "있는", "가능한", "곳",
            "찾아줘", "찾아주세요", "찾아", "찾고", "싶어", "싶어요", "보여줘", "보여주세요", "알려줘", "알려주세요",
            "추천해줘", "추천해주세요", "검색해줘", "검색해주세요", "검색", "추천", "해주세요", "해줘", "좀", "주세요",
            "find", "show", "me", "please", "data", "dataset", "datasets", "information", "about", "in", "for");
    private static final List<String> PARTICLES = List.of("에서", "으로", "까지", "부터", "관련", "에", "의", "을", "를", "은", "는", "이", "가");
    private static final Map<String, String> ALIASES = Map.of(
            "제주도", "제주", "제주지역", "제주", "jeju", "제주", "parking", "주차", "주차공간", "주차");

    public static CatalogSearchQuery parse(String query) {
        String original = query == null ? "" : query.strip();
        if (original.length() > 200) throw new IllegalArgumentException("Search query exceeds 200 characters");
        String normalized = Normalizer.normalize(original, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var terms = new LinkedHashSet<String>();
        for (String token : normalized.split("[\\s,;:!?。！？\\\"'()\\[\\]{}]+")) {
            if (token.isBlank() || REQUEST_WORDS.contains(token)) continue;
            for (String particle : PARTICLES) {
                if (token.endsWith(particle) && token.length() - particle.length() >= 2) {
                    token = token.substring(0, token.length() - particle.length());
                    break;
                }
            }
            if (REQUEST_WORDS.contains(token)) continue;
            terms.add(ALIASES.getOrDefault(token, token));
        }
        if (terms.size() > 8) throw new IllegalArgumentException("Search supports up to eight subject terms");
        return new CatalogSearchQuery(original, List.copyOf(terms));
    }

    public boolean needsSubject() { return !original.isBlank() && terms.isEmpty(); }

    public CatalogSearchPage.Interpretation interpretation() {
        return new CatalogSearchPage.Interpretation(VERSION, terms, needsSubject()
                ? "검색할 주제어를 포함해 주세요."
                : terms.isEmpty() ? "공개 데이터 목록입니다."
                : "요청 표현을 제외한 주제어가 모두 일치하는 공개 메타데이터를 찾았습니다.");
    }

    public CatalogSearchPage.Match explain(DatasetSummary dataset) {
        var reasons = new ArrayList<CatalogSearchPage.Reason>();
        int score = 0;
        for (String term : terms) {
            int best = 0;
            if (contains(dataset.title(), term)) { reasons.add(new CatalogSearchPage.Reason(term, "제목", 4)); best = 4; }
            if (contains(dataset.description(), term)) { reasons.add(new CatalogSearchPage.Reason(term, "설명", 2)); best = Math.max(best, 2); }
            if (dataset.details().keywords().stream().anyMatch(value -> contains(value, term))) {
                reasons.add(new CatalogSearchPage.Reason(term, "키워드", 3)); best = Math.max(best, 3);
            }
            if (contains(dataset.organization().name(), term)) { reasons.add(new CatalogSearchPage.Reason(term, "관리기관", 1)); best = Math.max(best, 1); }
            if (dataset.category() != null && contains(dataset.category().name(), term)) { reasons.add(new CatalogSearchPage.Reason(term, "분야", 1)); best = Math.max(best, 1); }
            if (dataset.regions().stream().anyMatch(value -> contains(value.name(), term))) { reasons.add(new CatalogSearchPage.Reason(term, "지역", 1)); best = Math.max(best, 1); }
            if (dataset.hubs().stream().anyMatch(value -> contains(value.name(), term))) { reasons.add(new CatalogSearchPage.Reason(term, "허브", 1)); best = Math.max(best, 1); }
            if (dataset.topics().stream().anyMatch(value -> contains(value.name(), term))) { reasons.add(new CatalogSearchPage.Reason(term, "주제", 1)); best = Math.max(best, 1); }
            score += best;
        }
        String description = dataset.description();
        String summary = description.length() > 240 ? description.substring(0, description.offsetByCodePoints(0, Math.min(240, description.codePointCount(0, description.length())))) + "…" : description;
        return new CatalogSearchPage.Match(dataset.id(), summary, List.copyOf(reasons), score,
                "제목 4·키워드 3·설명 2·그 외 공개메타정보 1점 중 주제어별 최고점을 합산합니다.", "/datasets/" + dataset.id());
    }

    private static boolean contains(String value, String term) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(term);
    }
}

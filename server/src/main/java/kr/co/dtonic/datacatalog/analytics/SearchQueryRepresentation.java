package kr.co.dtonic.datacatalog.analytics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.co.dtonic.datacatalog.catalog.application.CatalogSearchQuery;

/** Only server-owned fixed codes cross the persistence boundary. Unknown input is never retained. */
public record SearchQueryRepresentation(String policyVersion,String intent,List<String> controlledTerms,boolean hasUnclassifiedTerms) {
    public static final String VERSION="catalog-search-privacy-v1";
    private static final Map<String,String> TERMS=Map.ofEntries(
            Map.entry("제주","JEJU"),Map.entry("주차","PARKING"),Map.entry("교통","TRAFFIC"),
            Map.entry("버스","BUS"),Map.entry("날씨","WEATHER"),Map.entry("기상","WEATHER"),
            Map.entry("환경","ENVIRONMENT"),Map.entry("대기","AIR"),Map.entry("수질","WATER_QUALITY"),
            Map.entry("관광","TOURISM"),Map.entry("인구","POPULATION"),Map.entry("안전","SAFETY"),
            Map.entry("에너지","ENERGY"),Map.entry("농업","AGRICULTURE"),Map.entry("해양","OCEAN"));
    private static final Set<String> INTENTS=Set.of("BROWSE","REQUEST_WITHOUT_SUBJECT","CONTROLLED_SEARCH","UNCLASSIFIED_SEARCH");
    public SearchQueryRepresentation {
        if(!VERSION.equals(policyVersion)||!INTENTS.contains(intent)||controlledTerms==null
                ||controlledTerms.size()>8||!TERMS.values().containsAll(controlledTerms))throw new IllegalArgumentException("Invalid controlled search representation");
        controlledTerms=controlledTerms.stream().distinct().sorted().toList();
    }
    public static SearchQueryRepresentation from(CatalogSearchQuery query) {
        var controlled=query.terms().stream().filter(TERMS::containsKey).map(TERMS::get).distinct().sorted().toList();
        boolean unknown=query.terms().stream().anyMatch(t->!TERMS.containsKey(t));
        String intent=query.original().isBlank()?"BROWSE":query.needsSubject()?"REQUEST_WITHOUT_SUBJECT":controlled.isEmpty()?"UNCLASSIFIED_SEARCH":"CONTROLLED_SEARCH";
        return new SearchQueryRepresentation(VERSION,intent,controlled,unknown);
    }
    public static SearchQueryRepresentation legacy(boolean hasQuery) {
        return new SearchQueryRepresentation(VERSION,hasQuery?"UNCLASSIFIED_SEARCH":"BROWSE",List.of(),hasQuery);
    }
    public static SearchQueryRepresentation fromLegacyQuery(String input){
        try{return from(CatalogSearchQuery.parse(input));}
        catch(IllegalArgumentException unsupportedNaturalForm){return legacy(input!=null&&!input.isBlank());}
    }
}

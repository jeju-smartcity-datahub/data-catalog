package kr.co.dtonic.datacatalog;

import kr.co.dtonic.datacatalog.standard.application.DefinitionExporter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefinitionExporterTest {
    @Test
    void neutralizesFormulasEvenBehindWhitespaceOrInvisiblePrefix() {
        for(String attack:new String[]{"=HYPERLINK(\"https://example.invalid\")","+1+1","-1+1","@SUM(1)","\t\r =1","\uFEFF=1"})
            assertThat(DefinitionExporter.cell(attack)).startsWith("\"'");
    }
    @Test
    void quotesCommasQuotesAndNewlinesWithoutCorruptingOrdinaryKoreanText() {
        assertThat(DefinitionExporter.cell("제주, \"필드\"\n설명")).isEqualTo("\"제주, \"\"필드\"\"\n설명\"");
        assertThat(DefinitionExporter.cell(null)).isEqualTo("\"\"");
    }
}

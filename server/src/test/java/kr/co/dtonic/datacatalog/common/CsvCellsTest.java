package kr.co.dtonic.datacatalog.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CsvCellsTest {
    @Test void neutralizesAsciiAndFullWidthFormulaPrefixesAfterInvisibleCharacters() {
        for (String prefix : new String[]{"", " ", "\u00a0", "\u202f", "\u200b", "\uFEFF", "\u0000", "\u200b\u00a0"}) {
            for (String operator : new String[]{"=", "+", "-", "@", "＝", "＋", "－", "＠"}) {
                String input = prefix + operator + "1";
                assertThat(CsvCells.quote(input)).as("prefix/operator combination").isEqualTo("\"'" + input + "\"");
            }
        }
    }

    @Test void neutralizesLeadingCellControlCharactersAndQuotesSeparators() {
        for (String input : new String[]{"\ttext", "\rtext", "\ntext", " \t=1", "=1,\"x\";=2"}) {
            assertThat(CsvCells.quote(input)).isEqualTo("\"'" + input.replace("\"", "\"\"") + "\"");
        }
    }

    @Test void retainsOrdinaryValuesAndEmbeddedLineBreaks() {
        assertThat(CsvCells.quote("제주, \"필드\"\n설명")).isEqualTo("\"제주, \"\"필드\"\"\n설명\"");
        assertThat(CsvCells.quote("  일반 텍스트")).isEqualTo("\"  일반 텍스트\"");
        assertThat(CsvCells.quote("00123")).isEqualTo("\"00123\"");
        assertThat(CsvCells.quote(12)).isEqualTo("\"12\"");
        assertThat(CsvCells.quote(null)).isEqualTo("\"\"");
        assertThat(CsvCells.quote(" ")).isEqualTo("\" \"");
    }
}

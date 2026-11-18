package kr.co.dtonic.datacatalog.integration.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntegrationRunReportServiceTest {
    @Test void csvCellsQuoteContentAndNeutralizeFormulaAndControlPrefixes() {
        for (String value : new String[]{"=1+1", "+SUM(1)", "-1+2", "@SUM(1)", "  =HYPERLINK(\"x\")", "\ttext", "\rtext", "\ntext"})
            assertThat(IntegrationRunReportService.csvCell(value)).startsWith("\"'").endsWith("\"");
        assertThat(IntegrationRunReportService.csvCell("quote\" comma, line\nnext")).isEqualTo("\"quote\"\" comma, line\nnext\"");
        assertThat(IntegrationRunReportService.csvCell(null)).isEqualTo("\"\"");
        assertThat(IntegrationRunReportService.csvCell(12)).isEqualTo("\"12\"");
    }
}

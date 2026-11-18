package kr.co.dtonic.datacatalog;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import kr.co.dtonic.datacatalog.analytics.AnalyticsReportContracts;
import kr.co.dtonic.datacatalog.integration.application.IntegrationRunReportService;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.standard.export.DictionaryExportModels;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BinaryContractsTest {
    @Test void downloadedContentCannotBeChangedThroughCallerOwnedArrays() {
        byte[] original="private report payload".getBytes(StandardCharsets.UTF_8);
        byte[] expected=original.clone();
        var analytics=new AnalyticsReportContracts.ReportDownload(null,original);
        var dictionary=new DictionaryExportModels.Download(null,original);
        var integration=new IntegrationRunReportService.ReportDownload("text/csv","report.csv",original);
        Arrays.fill(original,(byte)0);
        Arrays.fill(analytics.bytes(),(byte)0);
        Arrays.fill(dictionary.bytes(),(byte)0);
        Arrays.fill(integration.content(),(byte)0);
        assertThat(analytics.bytes()).isEqualTo(expected);
        assertThat(dictionary.bytes()).isEqualTo(expected);
        assertThat(integration.content()).isEqualTo(expected);
        assertSameValue(analytics,new AnalyticsReportContracts.ReportDownload(null,expected));
        assertSameValue(dictionary,new DictionaryExportModels.Download(null,expected));
        assertSameValue(integration,new IntegrationRunReportService.ReportDownload("text/csv","report.csv",expected));
        assertThat(integration).isNotEqualTo(new IntegrationRunReportService.ReportDownload("text/csv","other.csv",expected));
        for(Object download:new Object[]{analytics,dictionary,integration})
            assertThat(download.toString()).doesNotContain("private report payload");
    }

    @Test void credentialValuesCompareCorrectlyWithoutExposingOrPreventingCleanup() {
        char[] owned="sensitive-test-value".toCharArray();
        URI endpoint=URI.create("https://hub.invalid");
        var context=new HubAdapter.Context(endpoint,owned,5,1024);
        var same=new HubAdapter.Context(endpoint,owned.clone(),5,1024);
        assertSameValue(context,same);
        assertThat(context.toString()).doesNotContain("sensitive-test-value","hub.invalid");
        Arrays.fill(owned,'\0');
        assertThat(context.credential()).containsOnly('\0');
        assertThat(context).isNotEqualTo(same);
    }

    private static void assertSameValue(Object first,Object second) {
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}

package kr.co.dtonic.datacatalog.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalyticsReportWriterTest {
    @Test void neutralizesWhitespacePrefixedFormulasAndPreservesQuotedMultilineText() {
        var bytes=AnalyticsReportWriter.csv(List.of("value"),List.of(Arrays.asList(" =1+1","\t@SUM(A1)","\uFEFF-1","+cmd","text,\"quoted\"\nnext",null)));
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("\"' =1+1\"","\"'\t@SUM(A1)\"","\"'\uFEFF-1\"","\"'+cmd\"","\"text,\"\"quoted\"\"\nnext\"");
    }
    @Test void bothFormatsRejectOversizeOutputBeforeReturningAnArtifact() {
        var large="한".repeat(3_000_000);
        assertThatThrownBy(()->AnalyticsReportWriter.csv(List.of("value"),List.of(List.of(large)))).isInstanceOf(DomainException.class).hasMessageContaining("8MiB");
        assertThatThrownBy(()->AnalyticsReportWriter.json(new ObjectMapper(),Map.of("value",large))).isInstanceOf(DomainException.class).hasMessageContaining("8MiB");
    }
}

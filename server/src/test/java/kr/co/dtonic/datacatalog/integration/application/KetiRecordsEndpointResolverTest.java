package kr.co.dtonic.datacatalog.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KetiRecordsEndpointResolverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EndpointPolicy endpoints = new EndpointPolicy("https://metadata.invalid,https://broker.invalid",
            "http://hub.invalid:8080,http://hub.invalid:8082");

    @Test void usesOnlyTheExplicitKetiMappingAndRetainsConfiguredBasePaths() throws Exception {
        var resolver = resolver(Map.of("http://hub.invalid:8080/manager/", "http://hub.invalid:8082/broker/"));
        assertThat(resolver.resolve("KETI_DATACORE", "http://HUB.invalid:8080/manager").toString())
                .isEqualTo("http://hub.invalid:8082/broker");
        assertThat(resolver.resolve("KETI_DATACORE", "http://hub.invalid:8080/other").toString())
                .isEqualTo("http://hub.invalid:8080/other");
        assertThat(resolver.resolve("NGSI_LD", "http://hub.invalid:8080/manager").toString())
                .isEqualTo("http://hub.invalid:8080/manager");
    }

    @Test void doesNotInferAPortWhenNoMappingExists() throws Exception {
        var resolver = resolver(Map.of());
        assertThat(resolver.resolve("KETI_DATACORE", "http://hub.invalid:8080").toString())
                .isEqualTo("http://hub.invalid:8080");
        assertThatThrownBy(() -> resolver.resolve("KETI_DATACORE", "http://unknown.invalid:8080"))
                .isInstanceOf(kr.co.dtonic.datacatalog.common.DomainException.class);
    }

    @Test void recognizesEquivalentDefaultPortsWithoutBroadeningTheOrigin() throws Exception {
        var resolver = resolver(Map.of("https://metadata.invalid/", "https://broker.invalid/data"));
        assertThat(resolver.resolve("KETI_DATACORE", "https://metadata.invalid:443").toString())
                .isEqualTo("https://broker.invalid:443/data");
    }

    @Test void rejectsUnapprovedSourceAndDestinationOrigins() {
        assertThatThrownBy(() -> resolver(Map.of("http://hub.invalid:8080", "http://hub.invalid:8083")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(Map.of("http://unknown.invalid:8080", "http://hub.invalid:8082")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsCredentialsQueriesTraversalAndEncodedPaths() {
        for (String target : new String[]{"http://user:password@hub.invalid:8082", "http://hub.invalid:8082/?token=x",
                "http://hub.invalid:8082/#fragment", "http://hub.invalid:8082/a/../entities", "http://hub.invalid:8082/%2e%2e"})
            assertThatThrownBy(() -> resolver(Map.of("http://hub.invalid:8080", target)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid KETI records endpoint mappings");
    }

    @Test void rejectsMalformedAndAmbiguousConfigurations() {
        for (String value : new String[]{"[]", "null", "{} {}", "{\"http://hub.invalid:8080\":1}",
                "{\"http://hub.invalid:8080\":\"http://hub.invalid:8082\",\"http://hub.invalid:8080\":\"http://hub.invalid:8082\"}",
                "{\"https://metadata.invalid\":\"https://broker.invalid\",\"https://metadata.invalid:443/\":\"https://broker.invalid\"}"})
            assertThatThrownBy(() -> new KetiRecordsEndpointResolver(endpoints, json, value))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    private KetiRecordsEndpointResolver resolver(Map<String,String> mappings) throws Exception {
        return new KetiRecordsEndpointResolver(endpoints, json, json.writeValueAsString(mappings));
    }
}

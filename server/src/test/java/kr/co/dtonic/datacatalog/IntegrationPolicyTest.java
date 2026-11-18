package kr.co.dtonic.datacatalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.application.AdapterRegistry;
import kr.co.dtonic.datacatalog.integration.application.EndpointPolicy;
import kr.co.dtonic.datacatalog.integration.application.IntegrationService;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntegrationPolicyTest {
    @Test
    void invalidAddressAndUnapprovedAddressHaveDifferentActions() {
        var policy = new EndpointPolicy("https://hub-contract.invalid");
        for(String value:List.of("hub-contract.invalid:8080","https://host:","https://host:65536",
                "https://user:password@hub-contract.invalid","https://hub-contract.invalid?token=secret")) {
            assertThatThrownBy(()->policy.validate(value)).isInstanceOfSatisfying(DomainException.class,
                    error->assertThat(error.code()).isEqualTo("INVALID_ENDPOINT"));
        }
        assertThatThrownBy(()->policy.validate("https://another.invalid"))
                .isInstanceOfSatisfying(DomainException.class,error->assertThat(error.code()).isEqualTo("SOURCE_NOT_APPROVED"));
        assertThat(new EndpointPolicy("","http://hub-contract.invalid:8080").validate("http://hub-contract.invalid:8080/manager").getScheme())
                .isEqualTo("http");
    }
    @Test
    void emptyAllowlistAndUnapprovedOrAmbiguousUrlsAreRejected() {
        var policy = new EndpointPolicy("https://hub-contract.invalid");
        assertThat(policy.validate("https://hub-contract.invalid/metadata").getHost()).isEqualTo("hub-contract.invalid");
        for (String url : List.of("http://hub-contract.invalid/metadata", "https://other.invalid/metadata",
                "https://hub-contract.invalid:8443/metadata", "https://user:password@hub-contract.invalid/metadata",
                "https://hub-contract.invalid/metadata?token=sensitive", "https://hub-contract.invalid/metadata#fragment",
                "https://hub-contract.invalid/a/../admin", "https://hub-contract.invalid/%2e%2e/admin", "http://127.0.0.1")) {
            assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> new EndpointPolicy("").validate("https://hub-contract.invalid")).isInstanceOf(DomainException.class);
    }

    @Test
    void missingAdapterAndUnsupportedCapabilityAreNotSuccessfulEmptyResults() {
        assertThatThrownBy(() -> new AdapterRegistry(List.of()).require("CKAN", HubAdapter.Capability.TEST_CONNECTION))
                .isInstanceOf(DomainException.class);
        HubAdapter onlyDiscovery = new HubAdapter() {
            public String code() { return "TEST_ONLY"; }
            public Set<Capability> capabilities() { return Set.of(Capability.DISCOVER_DATASETS); }
            public void check(Context context) { throw new UnsupportedOperationException(); }
            public Page discover(Context context, String checkpoint) { throw new UnsupportedOperationException(); }
        };
        assertThatThrownBy(() -> new AdapterRegistry(List.of(onlyDiscovery)).require("TEST_ONLY", HubAdapter.Capability.FETCH_SAMPLE))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void retryClassificationAndEtagValidationAreBounded() {
        assertThat(new HubAdapter.AdapterFailure(HubAdapter.AdapterFailure.Reason.RATE_LIMITED, 999999).retryAfterSeconds()).isEqualTo(3600);
        assertThat(new HubAdapter.AdapterFailure(HubAdapter.AdapterFailure.Reason.AUTHENTICATION_FAILED, 0).retryable()).isFalse();
        UUID id = UUID.randomUUID();
        assertThat(IntegrationService.version("\"connection:" + id + ":12\"", id)).isEqualTo(12);
        assertThatThrownBy(() -> IntegrationService.version(null, id)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> IntegrationService.version("W/\"12\"", id)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> IntegrationService.version("\"connection:" + UUID.randomUUID() + ":12\"", id)).isInstanceOf(DomainException.class);
    }
}

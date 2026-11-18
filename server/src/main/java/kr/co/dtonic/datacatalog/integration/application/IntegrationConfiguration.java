package kr.co.dtonic.datacatalog.integration.application;

import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.domain.SecretReferenceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import kr.co.dtonic.datacatalog.integration.adapter.MountedFileSecretProvider;
import kr.co.dtonic.datacatalog.integration.adapter.ApprovedHubHttpTransport;
import kr.co.dtonic.datacatalog.integration.adapter.KetiDataCoreAdapter;
import kr.co.dtonic.datacatalog.integration.adapter.NgsiLdAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
public class IntegrationConfiguration {
    @Bean(destroyMethod = "close")
    ApprovedHubHttpTransport approvedHubHttpTransport(EndpointPolicy endpoints, ObjectMapper json,
            @Value("${catalog.integration.pinned-addresses:{}}") String pins,
            @Value("${catalog.integration.allowed-private-addresses:}") String privateAddresses) {
        return new ApprovedHubHttpTransport(endpoints, json, pins, privateAddresses);
    }
    @Bean
    KetiDataCoreAdapter ketiDataCoreAdapter(ApprovedHubHttpTransport http, ObjectMapper json,
            @Value("${catalog.integration.keti.page-size:10}") int pageSize) {
        return new KetiDataCoreAdapter(http, json, pageSize);
    }
    @Bean
    NgsiLdAdapter ngsiLdAdapter(ApprovedHubHttpTransport http, ObjectMapper json,
            @Value("${catalog.integration.ngsi-ld.page-size:50}") int pageSize,
            @Value("${catalog.integration.ngsi-ld.context-mappings:{}}") String contextMappings) {
        return new NgsiLdAdapter(http, json, pageSize, contextMappings);
    }
    @Bean
    @ConditionalOnMissingBean(SecretReferenceProvider.class)
    SecretReferenceProvider unavailableSecretProvider(@Value("${catalog.integration.secret-directory:}") String directory) {
        if (!directory.isBlank()) return new MountedFileSecretProvider(Path.of(directory));
        return new SecretReferenceProvider() {
            public boolean available() { return false; }
            public char[] resolve(String reference) { throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SECRET_STORE_UNAVAILABLE", "서버 비밀 저장소가 연결되지 않았습니다."); }
        };
    }
}

package kr.co.dtonic.datacatalog.integration.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit deployment routing for KETI record queries; metadata collection keeps its registered endpoint. */
@Component
public final class KetiRecordsEndpointResolver {
    private final EndpointPolicy endpoints;
    private final Map<URI, URI> mappings;

    public KetiRecordsEndpointResolver(EndpointPolicy endpoints, ObjectMapper json,
            @Value("${catalog.integration.keti.records-endpoint-mappings:{}}") String configured) {
        this.endpoints = endpoints;
        try {
            var root = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(configured);
            if (root == null || !root.isObject() || root.size() > 100) throw invalid();
            Map<URI, URI> values = new LinkedHashMap<>();
            var entries = root.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.getValue().isTextual() || entry.getKey().length() > 2000
                        || entry.getValue().textValue().length() > 2000) throw invalid();
                URI metadata = canonical(endpoints.validate(entry.getKey()));
                URI records = canonical(endpoints.validate(entry.getValue().textValue()));
                if (values.put(metadata, records) != null) throw invalid();
            }
            mappings = Map.copyOf(values);
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    public URI resolve(String adapterCode, String metadataEndpoint) {
        URI metadata = endpoints.validate(metadataEndpoint);
        if (!"KETI_DATACORE".equals(adapterCode)) return metadata;
        URI target = mappings.get(canonical(metadata));
        return target == null ? metadata : endpoints.validate(target.toString());
    }

    private static URI canonical(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        try { return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, path, null, null); }
        catch (java.net.URISyntaxException rejected) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid KETI records endpoint mappings");
    }
}

package kr.co.dtonic.datacatalog.apidocs.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.dtonic.datacatalog.delivery.persistence.PublicDeliveryPublication;
import org.springframework.stereotype.Component;

/** Both serializations use the same RDF triple projection. No fictitious Distribution/DataService. */
@Component
public class DcatSerializer {
    private static final String DCAT = "http://www.w3.org/ns/dcat#";
    private static final String DCT = "http://purl.org/dc/terms/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String XSD_TIME = "http://www.w3.org/2001/XMLSchema#dateTime";
    private static final String XSD_DATE = "http://www.w3.org/2001/XMLSchema#date";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String VCARD = "http://www.w3.org/2006/vcard/ns#";
    private static final String CATALOG = "urn:datacatalog:public-catalog";
    private final ObjectMapper json;
    public DcatSerializer(ObjectMapper json) { this.json = json; }

    public String serialize(List<PublicDeliveryPublication> rows, boolean turtle) {
        List<Triple> triples = new ArrayList<>();
        triples.add(new Triple(CATALOG, RDF_TYPE, DCAT + "Catalog", true, null));
        for (var row : rows) {
            String dataset = "urn:uuid:" + row.datasetId();
            String provider = "urn:uuid:" + row.organizationId();
            triples.add(new Triple(CATALOG, DCAT + "dataset", dataset, true, null));
            triples.add(new Triple(dataset, RDF_TYPE, DCAT + "Dataset", true, null));
            triples.add(new Triple(dataset, DCT + "identifier", row.datasetId(), false, null));
            triples.add(new Triple(dataset, DCT + "title", row.title(), false, null));
            triples.add(new Triple(dataset, DCT + "description", row.description(), false, null));
            triples.add(new Triple(dataset, DCAT + "version", Integer.toString(row.publicationVersion()), false, null));
            // Instant's ISO representation always includes seconds, as xsd:dateTime requires.
            triples.add(new Triple(dataset, DCT + "issued", row.publishedAt().toInstant().toString(), false, XSD_TIME));
            triples.add(new Triple(dataset, DCT + "publisher", provider, true, null));
            triples.add(new Triple(provider, RDF_TYPE, FOAF + "Organization", true, null));
            triples.add(new Triple(provider, FOAF + "name", row.organizationName(), false, null));
            if (row.modifiedAt() != null)
                triples.add(new Triple(dataset, DCT + "modified", row.modifiedAt().toInstant().toString(), false, XSD_TIME));
            if (row.categoryId() != null && row.categoryName() != null)
                named(triples, dataset, DCAT + "theme", "urn:uuid:" + UUID.fromString(row.categoryId()),
                        SKOS + "Concept", SKOS + "prefLabel", row.categoryName());
            publicMetadata(triples, dataset, row.publicMetadata());
        }
        return turtle ? turtle(triples.stream().distinct().toList()) : jsonLd(triples.stream().distinct().toList());
    }

    private void publicMetadata(List<Triple> triples, String dataset, String stored) {
        if (stored == null) return;
        final JsonNode metadata;
        try { metadata = json.readTree(stored); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid public metadata projection", invalid); }
        if (metadata == null || !metadata.isObject()) throw new IllegalStateException("Invalid public metadata projection");
        JsonNode details = metadata.path("details");
        referenceOrLabel(triples, dataset, DCT + "license", "license", DCT + "LicenseDocument", text(details, "license"));
        referenceOrLabel(triples, dataset, DCT + "accrualPeriodicity", "frequency", DCT + "Frequency", text(details, "updateFrequency"));
        String spatial = text(details, "spatialCoverage");
        if (spatial != null) named(triples, dataset, DCT + "spatial", dataset + ":spatial", DCT + "Location", RDFS_LABEL, spatial);
        String department = text(details, "department");
        if (department != null) named(triples, dataset, DCAT + "contactPoint", dataset + ":contact", VCARD + "Organization", VCARD + "fn", department);
        for (JsonNode keyword : details.path("keywords"))
            if (keyword.isTextual() && !keyword.textValue().isBlank())
                triples.add(new Triple(dataset, DCAT + "keyword", keyword.textValue(), false, null));
        String start = text(details, "temporalStart"), end = text(details, "temporalEnd");
        if (start != null || end != null) {
            String period = dataset + ":temporal";
            triples.add(new Triple(dataset, DCT + "temporal", period, true, null));
            triples.add(new Triple(period, RDF_TYPE, DCT + "PeriodOfTime", true, null));
            if (start != null) triples.add(new Triple(period, DCAT + "startDate", LocalDate.parse(start).toString(), false, XSD_DATE));
            if (end != null) triples.add(new Triple(period, DCAT + "endDate", LocalDate.parse(end).toString(), false, XSD_DATE));
        }
        namedReferences(triples, dataset, metadata.path("regions"), DCT + "spatial", DCT + "Location", RDFS_LABEL);
        namedReferences(triples, dataset, metadata.path("topics"), DCAT + "theme", SKOS + "Concept", SKOS + "prefLabel");
    }

    private static void namedReferences(List<Triple> triples, String dataset, JsonNode values,
            String predicate, String type, String label) {
        if (!values.isArray()) return;
        for (JsonNode value : values) {
            String id = text(value, "id"), name = text(value, "name");
            if (id != null && name != null) named(triples, dataset, predicate, "urn:uuid:" + UUID.fromString(id), type, label, name);
        }
    }

    private static String text(JsonNode object, String key) {
        JsonNode value = object.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static void referenceOrLabel(List<Triple> triples, String dataset, String predicate,
            String suffix, String type, String value) {
        if (value == null) return;
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() && !uri.isOpaque() && List.of("https", "http").contains(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && value.chars().noneMatch(c -> "<>\"{}|^`\\".indexOf(c) >= 0 || Character.isWhitespace(c))) {
                triples.add(new Triple(dataset, predicate, uri.toASCIIString(), true, null));
                return;
            }
        } catch (IllegalArgumentException invalidUri) { /* Human-readable values remain labels, not fabricated IRIs. */ }
        named(triples, dataset, predicate, dataset + ":" + suffix, type, RDFS_LABEL, value);
    }

    private static void named(List<Triple> triples, String dataset, String predicate,
            String resource, String type, String label, String value) {
        triples.add(new Triple(dataset, predicate, resource, true, null));
        triples.add(new Triple(resource, RDF_TYPE, type, true, null));
        triples.add(new Triple(resource, label, value, false, null));
    }

    private String jsonLd(List<Triple> triples) {
        Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        for (var triple : triples) {
            var node = nodes.computeIfAbsent(triple.subject(), key -> json.createObjectNode().put("@id", key));
            if (triple.predicate().equals(RDF_TYPE)) {
                node.withArray("@type").add(triple.value());
            } else {
                var value = json.createObjectNode().put(triple.iri() ? "@id" : "@value", triple.value());
                if (triple.datatype() != null) value.put("@type", triple.datatype());
                node.withArray(triple.predicate()).add(value);
            }
        }
        var result = json.createObjectNode();
        var graph = result.putArray("@graph");
        nodes.values().forEach(graph::add);
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("DCAT serialization failed"); }
    }

    private String turtle(List<Triple> triples) {
        var result = new StringBuilder();
        for (var triple : triples) {
            result.append('<').append(triple.subject()).append("> <").append(triple.predicate()).append("> ");
            if (triple.iri()) result.append('<').append(triple.value()).append('>');
            else {
                result.append('"').append(escape(triple.value())).append('"');
                if (triple.datatype() != null) result.append("^^<").append(triple.datatype()).append('>');
            }
            result.append(" .\n");
        }
        return result.toString();
    }

    private String escape(String value) {
        var out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private record Triple(String subject, String predicate, String value, boolean iri, String datatype) {}
}

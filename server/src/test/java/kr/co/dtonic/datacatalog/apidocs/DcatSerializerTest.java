package kr.co.dtonic.datacatalog.apidocs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import kr.co.dtonic.datacatalog.apidocs.application.DcatSerializer;
import kr.co.dtonic.datacatalog.delivery.persistence.PublicDeliveryPublication;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DcatSerializerTest {
    @Test void longSafeLicenseUriRemainsAReferenceAndUnsafeCharactersRemainLabels() throws Exception {
        var json = new ObjectMapper(); var serializer = new DcatSerializer(json);
        String safe = "https://example.invalid/" + "license".repeat(1000);
        for (String license : List.of(safe, safe + " value", safe + "\\nvalue", safe + "<value>")) {
            var metadata = json.createObjectNode(); metadata.putObject("details").put("license", license);
            var row = new PublicDeliveryPublication("bbe71fb2-67ba-497a-8b5c-3c545d52076b", "c28dcb1a-130a-42b0-9ac8-b95b57297c88", 1,
                    "공개 데이터", "공개 설명", "f71586ae-d1e2-4bcc-9976-df471f0cae01", "기관",
                    OffsetDateTime.parse("2026-10-16T00:00:00Z"), "METADATA_ONLY", null, null, null, json.writeValueAsString(metadata));
            var graph = json.readTree(serializer.serialize(List.of(row), false)).get("@graph");
            String expected = license.equals(safe) ? safe : "urn:uuid:" + row.datasetId() + ":license";
            assertThat(graph.get(1).get("http://purl.org/dc/terms/license").get(0).get("@id").asText()).isEqualTo(expected);
            if (!license.equals(safe)) assertThat(graph.toString()).contains(json.writeValueAsString(license));
        }
    }

    @Test void issuedDateTimeAlwaysHasSecondsAndPreservesTheSourceInstant() throws Exception {
        var json = new ObjectMapper(); var serializer = new DcatSerializer(json);
        for (String time : List.of("2026-10-16T00:00:00Z", "2026-10-16T09:01:00+09:00", "2026-10-16T00:00:01.123456789Z")) {
            var source = OffsetDateTime.parse(time);
            var row = new PublicDeliveryPublication("bbe71fb2-67ba-497a-8b5c-3c545d52076b", "c28dcb1a-130a-42b0-9ac8-b95b57297c88", 1,
                    "Synthetic timestamp", "Timestamp boundary", "f71586ae-d1e2-4bcc-9976-df471f0cae01", "Synthetic provider", source, "METADATA_ONLY", null, null, null, null);
            String value = json.readTree(serializer.serialize(List.of(row), false)).get("@graph").get(1)
                    .get("http://purl.org/dc/terms/issued").get(0).get("@value").asText();
            var lexical = javax.xml.datatype.DatatypeFactory.newInstance().newXMLGregorianCalendar(value);
            assertThat(lexical.isValid()).isTrue();
            assertThat(lexical.getSecond()).isNotEqualTo(javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED);
            assertThat(OffsetDateTime.parse(value).toInstant()).isEqualTo(source.toInstant());
            assertThat(serializer.serialize(List.of(row), true)).contains("\"" + value + "\"^^<http://www.w3.org/2001/XMLSchema#dateTime>");
        }
    }

    @Test void sameProjectionEscapesRdfLiteralsAndNeverInventsDistributionOrService() throws Exception {
        var json = new ObjectMapper(); var serializer = new DcatSerializer(json);
        var row = new PublicDeliveryPublication("bbe71fb2-67ba-497a-8b5c-3c545d52076b", "c28dcb1a-130a-42b0-9ac8-b95b57297c88", 2,
                "제목 \"인용\"\n다음", "역슬래시 \\ 및 탭\t제어\u0001", "f71586ae-d1e2-4bcc-9976-df471f0cae01", "기관",
                OffsetDateTime.parse("2026-10-16T00:00:00Z"), "METADATA_ONLY", null, null, null, null);
        String ld = serializer.serialize(List.of(row), false), ttl = serializer.serialize(List.of(row), true);
        var graph = json.readTree(ld).get("@graph");
        assertThat(graph).hasSize(3);
        assertThat(graph.get(1).get("http://purl.org/dc/terms/title").get(0).get("@value").asText()).isEqualTo(row.title());
        assertThat(ttl).contains("제목 \\\"인용\\\"\\n다음", "역슬래시 \\\\ 및 탭\\t제어\\u0001", "urn:uuid:" + row.datasetId());
        assertThat(ld).doesNotContain("Distribution", "DataService", "accessURL", "endpointURL");
        assertThat(ttl).doesNotContain("Distribution", "DataService", "accessURL", "endpointURL");
        assertThat(serializer.serialize(List.of(row), false)).isEqualTo(ld);
    }

    @Test void publicDescriptionsPreserveTextWithoutTreatingInvalidUrisAsRdfSyntax() throws Exception {
        var json = new ObjectMapper(); var serializer = new DcatSerializer(json);
        var metadata = json.createObjectNode();
        var details = metadata.putObject("details");
        details.put("license", "https://example.invalid/license> . <urn:injected> \"line\"\nnext");
        details.put("department", "기상 \"부서\"\n팀");
        details.put("updateFrequency", "매시간");
        details.put("spatialCoverage", "제주");
        details.put("temporalStart", "2026-01-01");
        details.put("temporalEnd", "2026-12-31");
        details.putArray("keywords").add("기상").add("따옴표 \" 역슬래시 \\ 탭\t 개행\n 제어\u0001");
        metadata.put("credentials", "SECRET_NOT_PUBLIC");
        var row = new PublicDeliveryPublication("bbe71fb2-67ba-497a-8b5c-3c545d52076b", "c28dcb1a-130a-42b0-9ac8-b95b57297c88", 2,
                "제주 기상", "공개 메타데이터", "f71586ae-d1e2-4bcc-9976-df471f0cae01", "제주도청",
                OffsetDateTime.parse("2026-10-16T00:00:00Z"), "METADATA_ONLY", OffsetDateTime.parse("2026-10-15T23:00:00Z"),
                null, null, json.writeValueAsString(metadata));
        String ld = serializer.serialize(List.of(row), false), ttl = serializer.serialize(List.of(row), true);
        assertThat(ld).contains("LicenseDocument", "keyword", "PeriodOfTime", "accrualPeriodicity", "modified")
                .doesNotContain("SECRET_NOT_PUBLIC", "Distribution", "DataService");
        assertThat(ttl).doesNotContain("<urn:injected> \"line\"");
        var graph = json.readTree(ld).get("@graph");
        assertThat(graph.get(1).get("http://purl.org/dc/terms/license").get(0).get("@id").asText())
                .isEqualTo("urn:uuid:" + row.datasetId() + ":license");
        java.nio.file.Path directory = java.nio.file.Path.of("target", "dcat-rdf");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("escaping.jsonld"), ld);
        java.nio.file.Files.writeString(directory.resolve("escaping.ttl"), ttl);
    }
}

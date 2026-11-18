package kr.co.dtonic.datacatalog.apidocs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.co.dtonic.datacatalog.apidocs.application.ConsumerOpenApiGenerator;
import kr.co.dtonic.datacatalog.apidocs.application.ConsumerOpenApiGenerator.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConsumerOpenApiGeneratorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConsumerOpenApiGenerator generator = new ConsumerOpenApiGenerator(json);
    private final UUID dataset = UUID.fromString("bce8d2a0-594b-4ffd-a6ab-ddcc3f03236c");
    private final UUID publication = UUID.fromString("84a2bc36-7f16-4cba-99d5-ddf7d096473a");
    private final Set<String> origins = Set.of("https://fixture.example");

    @Test void deterministicDocumentUsesExactContractSchemaEndpointAndSyntheticExamples() throws Exception {
        Contract contract = fixture("https://fixture.example/observations", schema(), example());
        var first = generator.generate(contract, origins);
        var second = generator.generate(contract, origins);
        assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
        assertThat(first.specHash()).isEqualTo(second.specHash()).hasSize(64);
        assertThat(first.spec().at("/openapi").asText()).isEqualTo("3.1.1");
        assertThat(first.spec().at("/paths/~1observations/get/responses/200/content/application~1json/schema")).isEqualTo(schema());
        assertThat(first.spec().at("/x-publication-id").asText()).isEqualTo(publication.toString());
        assertThat(first.spec().at("/x-verification-level").asText()).isEqualTo("FIXTURE");
        assertThat(first.curlExample()).isEqualTo("curl --request GET 'https://fixture.example/observations?limit=10' --header 'X-API-Key: YOUR_API_KEY'");
        assertThat(first.canonicalJson()).doesNotContain("/api/admin", "jdbc:", "secret_ref");
    }

    @Test void mismatchedExamplesMissingRequiredExtraPrivateAndWrongTypeAreRejected() throws Exception {
        for (String body : List.of("{}", "{\"station\":\"synthetic\",\"temperature\":\"warm\"}",
                "{\"station\":\"synthetic\",\"temperature\":21,\"internal\":1}")) {
            assertThatThrownBy(() -> generator.generate(fixture("https://fixture.example/observations", schema(), json.readTree(body)), origins))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void remoteReferencesUnsupportedKeywordsAndPrivateFieldsAreRejected() throws Exception {
        for (String input : List.of("{\"$ref\":\"https://fixture.example/schema\"}",
                "{\"type\":\"string\",\"pattern\":\".*\"}",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"access_token\":{\"type\":\"string\"}}}")) {
            assertThatThrownBy(() -> generator.generate(fixture("https://fixture.example/observations", json.readTree(input), example()), origins))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void sensitiveNamesAreRejectedAtEveryPositionAndPublicNameBoundsRemainSupported() throws Exception {
        for (String name : List.of("passwordField", "fieldSECRETvalue", "fieldCredential", "access_token",
                "apiKeyName", "fieldAuthorization", "private_keyValue")) {
            var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
            schema.putObject("properties").putObject(name).put("type", "string");
            var sample = json.createObjectNode().put(name, "public");
            assertThatThrownBy(() -> generator.generate(fixture("https://fixture.example/records", schema, sample), origins))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Sensitive field is not public");
        }
        String name = "field" + "x".repeat(75);
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject(name).put("type", "string");
        var document = generator.generate(fixture("https://fixture.example/records", schema,
                json.createObjectNode().put(name, "public")), origins);
        assertThat(document.canonicalJson()).contains(name);
    }

    @Test void endpointsRejectInternalHostsPathsQueriesAndNonApprovedOrigin() throws Exception {
        for (String endpoint : List.of("http://fixture.example/observations", "https://localhost/records", "https://127.0.0.1/records",
                "https://10.0.0.1/records", "https://fixture.example/api/admin/datasets", "https://fixture.example/actuator/health",
                "https://fixture.example/records?apiKey=sensitive", "https://fixture.example/%61dmin/records",
                "https://other.example/records", "https://fixture.example/x/../records", "https://user:password@fixture.example/records")) {
            assertThatThrownBy(() -> generator.generate(fixture(endpoint, schema(), example()), origins))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void secretLikeValuesHtmlAndUnapprovedSamplesAreRejected() throws Exception {
        for (String value : List.of("Bearer actual-token-value", "token=not-for-publication", "<script>alert(1)</script>", "https://internal.example/")) {
            ObjectNode sample = example(); sample.put("station", value);
            assertThatThrownBy(() -> generator.generate(fixture("https://fixture.example/records", schema(), sample), origins))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var c = fixture("https://fixture.example/records", schema(), example());
        assertThatThrownBy(() -> generator.generate(new Contract(c.datasetId(), c.publicationId(), 1, 1, c.title(), c.description(),
                c.endpoint(), c.verification(), c.authentication(), c.apiKeyHeader(), c.parameters(), c.responseSchema(),
                c.responseExample(), c.errors(), false), origins)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void boundsAndNullableUnionAreEnforced() throws Exception {
        var schema = schema();
        ((ObjectNode) schema.at("/properties/temperature")).put("minimum", -50).put("maximum", 60);
        var sample = example(); sample.put("temperature", 61);
        assertThatThrownBy(() -> generator.generate(fixture("https://fixture.example/records", schema, sample), origins))
                .isInstanceOf(IllegalArgumentException.class);
        sample.putNull("temperature");
        assertThatCode(() -> generator.generate(fixture("https://fixture.example/records", schema, sample), origins)).doesNotThrowAnyException();
    }

    @Test void fixtureOriginCannotBeMarkedRealContract() throws Exception {
        var c = fixture("https://fixture.example/records", schema(), example());
        assertThatThrownBy(() -> generator.generate(new Contract(c.datasetId(), c.publicationId(), 1, 1, c.title(), c.description(),
                c.endpoint(), Verification.REAL_CONTRACT, c.authentication(), c.apiKeyHeader(), c.parameters(), c.responseSchema(),
                c.responseExample(), c.errors(), true), origins)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalHashIgnoresObjectKeyOrderButChangesWithContractSchema() throws Exception {
        assertThat(generator.canonical(json.readTree("{\"b\":2,\"a\":1}"))).isEqualTo(generator.canonical(json.readTree("{\"a\":1,\"b\":2}")));
        var original = generator.generate(fixture("https://fixture.example/records", schema(), example()), origins);
        var revisedSchema = schema(); ((ObjectNode) revisedSchema.at("/properties/station")).put("maxLength", 100);
        var revised = generator.generate(fixture("https://fixture.example/records", revisedSchema, example()), origins);
        assertThat(revised.schemaHash()).isNotEqualTo(original.schemaHash());
        assertThat(revised.specHash()).isNotEqualTo(original.specHash());
    }

    private Contract fixture(String endpoint, JsonNode schema, JsonNode sample) throws Exception {
        return new Contract(dataset, publication, 1, 1, "시험용 관측 계약", "실제 허브가 아닌 가상 계약", endpoint,
                Verification.FIXTURE, Authentication.API_KEY_HEADER, "X-API-Key",
                List.of(new Parameter("limit", "조회 개수", false, json.readTree("{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}"), json.readTree("10"))),
                schema, sample, List.of(new ErrorResponse(401, "원천 계약 인증 실패"), new ErrorResponse(429, "원천 계약 요청 한도 초과")), true);
    }

    private ObjectNode schema() throws Exception {
        return (ObjectNode) json.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"station\",\"temperature\"],\"properties\":{\"station\":{\"type\":\"string\"},\"temperature\":{\"type\":[\"number\",\"null\"]}}}");
    }

    private ObjectNode example() throws Exception {
        return (ObjectNode) json.readTree("{\"station\":\"synthetic-station\",\"temperature\":21.5}");
    }
}

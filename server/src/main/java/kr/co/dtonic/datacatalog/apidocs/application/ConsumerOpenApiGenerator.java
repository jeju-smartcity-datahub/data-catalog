package kr.co.dtonic.datacatalog.apidocs.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Offline generator for an explicit, already reviewed GET/JSON upstream contract.
 * It performs no network requests, registers no routes and does not publish a contract.
 * Deliberately rejects unsupported schema keywords instead of silently dropping constraints.
 */
@Component
public class ConsumerOpenApiGenerator {
    public static final String VERSION = "p5-get-json-1";
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "items",
            "additionalProperties", "enum", "description", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|secret|credential|token|api.?key|authorization|private.?key)");
    private static final Pattern SECRET_VALUE = Pattern.compile("(?i)(bearer\\s+[a-z0-9_.-]{8,}|-----BEGIN.*PRIVATE KEY|(?:password|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s]+|jdbc:|https?://(?:localhost|127\\.|10\\.|192\\.168\\.|169\\.254\\.))");
    private final ObjectMapper json;
    public ConsumerOpenApiGenerator(ObjectMapper json) { this.json = json; }

    public GeneratedDocument generate(Contract contract, Set<String> approvedOrigins) {
        require(contract != null && contract.datasetId() != null && contract.publicationId() != null,
                "Published identity is required");
        require(contract.publicationVersion() > 0 && contract.contractVersion() > 0, "Versions must be positive");
        require(contract.verification() != null && contract.authentication() != null, "Contract policy is required");
        require(contract.approvedSyntheticExample(), "Only explicitly approved synthetic examples are accepted");
        safeText(contract.title(), 300); safeText(contract.description(), 10000);
        require(!contract.title().isBlank(), "Title is required");
        URI endpoint = endpoint(contract.endpoint(), approvedOrigins, contract.verification());
        validateSchema(contract.responseSchema(), 0);
        require(canonical(contract.responseSchema()).length() <= 100000, "Schema is too large");
        validateExample(contract.responseSchema(), contract.responseExample(), 0);
        scan(contract.responseExample());
        require(canonical(contract.responseExample()).length() <= 100000, "Example is too large");
        require(contract.parameters() != null && contract.parameters().size() <= 30, "Invalid parameter count");
        require(contract.errors() != null && !contract.errors().isEmpty(), "Verified error responses are required");

        ObjectNode spec = json.createObjectNode().put("openapi", "3.1.1");
        spec.putObject("info").put("title", contract.title()).put("version", Integer.toString(contract.contractVersion()))
                .put("description", contract.description());
        String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        spec.putArray("servers").addObject().put("url", origin);
        var operation = spec.putObject("paths").putObject(endpoint.getRawPath()).putObject("get");
        operation.put("operationId", "getDataset_" + contract.datasetId().toString().replace('-', '_') + "_v" + contract.contractVersion());
        operation.put("summary", contract.title());
        var parameters = operation.putArray("parameters");
        Set<String> names = new HashSet<>();
        var query = new ArrayList<String>();
        for (var parameter : contract.parameters()) {
            require(parameter != null && parameter.name() != null && parameter.name().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    && !SECRET_KEY.matcher(parameter.name()).find() && names.add(parameter.name()), "Invalid public parameter name");
            safeText(parameter.description(), 1000);
            validateSchema(parameter.schema(), 0);
            require(!Set.of("object", "array").contains(type(parameter.schema())), "Only scalar query parameters are supported");
            validateExample(parameter.schema(), parameter.example(), 0);
            require(!parameter.example().isNull(), "Query example must be non-null");
            scan(parameter.example());
            var node = parameters.addObject().put("name", parameter.name()).put("in", "query")
                    .put("required", parameter.required()).put("description", parameter.description());
            node.set("schema", parameter.schema().deepCopy()); node.set("example", parameter.example().deepCopy());
            query.add(parameter.name() + "=" + URLEncoder.encode(parameter.example().asText(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        var responses = operation.putObject("responses");
        var success = responses.putObject("200").put("description", "검증된 제공 계약의 응답").putObject("content").putObject("application/json");
        success.set("schema", contract.responseSchema().deepCopy()); success.set("example", contract.responseExample().deepCopy());
        Set<Integer> codes = new HashSet<>();
        for (var error : contract.errors()) {
            require(error != null && error.status() >= 400 && error.status() <= 599 && codes.add(error.status()), "Invalid error response");
            safeText(error.description(), 1000);
            responses.putObject(Integer.toString(error.status())).put("description", error.description());
        }
        String authHeader = "";
        if (contract.authentication() == Authentication.ANONYMOUS) operation.putArray("security");
        else {
            var scheme = spec.putObject("components").putObject("securitySchemes").putObject("consumerAuth");
            if (contract.authentication() == Authentication.API_KEY_HEADER) {
                require(contract.apiKeyHeader() != null && contract.apiKeyHeader().matches("X-[A-Za-z0-9-]{1,60}"), "Explicit API-key header required");
                scheme.put("type", "apiKey").put("in", "header").put("name", contract.apiKeyHeader());
                authHeader = " --header '" + contract.apiKeyHeader() + ": YOUR_API_KEY'";
            } else {
                scheme.put("type", "http").put("scheme", "bearer");
                authHeader = " --header 'Authorization: Bearer YOUR_ACCESS_TOKEN'";
            }
            operation.putArray("security").addObject().putArray("consumerAuth");
        }
        spec.put("x-dataset-id", contract.datasetId().toString()).put("x-publication-id", contract.publicationId().toString())
                .put("x-publication-version", contract.publicationVersion()).put("x-verification-level", contract.verification().name())
                .put("x-generator-version", VERSION).put("x-example-kind", "APPROVED_SYNTHETIC");
        String schemaHash = sha256(canonical(contract.responseSchema()));
        spec.put("x-schema-sha256", schemaHash);
        String serialized = canonical(spec);
        String url = endpoint.toASCIIString() + (query.isEmpty() ? "" : "?" + String.join("&", query));
        // URI validation forbids apostrophes, so shell quoting cannot be escaped by an endpoint.
        String curl = "curl --request GET '" + url + "'" + authHeader;
        return new GeneratedDocument(spec, serialized, sha256(serialized), schemaHash, curl,
                contract.responseExample().deepCopy(), contract.verification());
    }

    private URI endpoint(String value, Set<String> origins, Verification verification) {
        require(value != null && value.length() <= 2000 && !value.contains("'") && !value.contains("%")
                && !value.contains("\\"), "Unsafe endpoint");
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException exception) { throw invalid("Invalid endpoint"); }
        String host = uri.getHost();
        require("https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null && uri.getQuery() == null
                && uri.getFragment() == null && uri.getPort() == -1, "Public HTTPS endpoint required");
        host = host.toLowerCase(Locale.ROOT);
        require(host.contains(".") && host.matches("[a-z][a-z0-9.-]+") && !host.endsWith(".")
                && !host.endsWith(".local") && !host.endsWith(".internal") && !host.endsWith(".localhost"), "Internal host forbidden");
        if (verification == Verification.REAL_CONTRACT) require(!host.endsWith(".example") && !host.endsWith(".test")
                && !host.endsWith(".invalid"), "Fixture host cannot be a real contract");
        require(origins != null && origins.contains("https://" + host), "Endpoint is not approved");
        String path = uri.getRawPath();
        require(path != null && path.startsWith("/") && !path.contains("..") && !path.contains("//")
                && !Pattern.compile("(?i)(^|/)(admin|actuator|internal|swagger|v3|secrets?)(/|$)").matcher(path).find(), "Internal or ambiguous path forbidden");
        return uri;
    }

    private void validateSchema(JsonNode schema, int depth) {
        require(schema != null && schema.isObject() && depth <= 12 && schema.size() <= 12, "Unsupported schema");
        schema.fieldNames().forEachRemaining(key -> require(KEYWORDS.contains(key), "Unsupported schema keyword"));
        String type = type(schema);
        if (schema.has("description")) safeText(schema.get("description").asText(), 1000);
        if (type.equals("object")) {
            require(schema.path("properties").isObject() && schema.path("properties").size() <= 100
                    && schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean(), "Objects require explicit public properties");
            schema.get("properties").fields().forEachRemaining(entry -> {
                require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,79}") && !SECRET_KEY.matcher(entry.getKey()).find(), "Sensitive field is not public");
                validateSchema(entry.getValue(), depth + 1);
            });
            if (schema.has("required")) {
                require(schema.get("required").isArray(), "Required must be an array");
                Set<String> required = new HashSet<>();
                schema.get("required").forEach(item -> require(item.isTextual() && schema.get("properties").has(item.asText())
                        && required.add(item.asText()), "Unknown or duplicate required field"));
            }
        } else if (type.equals("array")) validateSchema(schema.get("items"), depth + 1);
        else require(!schema.has("properties") && !schema.has("required") && !schema.has("items")
                && !schema.has("additionalProperties"), "Type and schema keywords disagree");
        if (!type.equals("object")) require(!schema.has("properties") && !schema.has("required") && !schema.has("additionalProperties"), "Object keyword on another type");
        if (!type.equals("array")) require(!schema.has("items") && !schema.has("minItems") && !schema.has("maxItems"), "Array keyword on another type");
        if (!type.equals("string")) require(!schema.has("minLength") && !schema.has("maxLength"), "String keyword on another type");
        if (!type.equals("number") && !type.equals("integer")) require(!schema.has("minimum") && !schema.has("maximum"), "Numeric keyword on another type");
        for (String bound : List.of("minimum", "maximum")) if (schema.has(bound)) require(schema.get(bound).isNumber(), "Numeric bound required");
        for (String bound : List.of("minLength", "maxLength", "minItems", "maxItems")) if (schema.has(bound))
            require(schema.get(bound).isIntegralNumber() && schema.get(bound).canConvertToInt() && schema.get(bound).asInt() >= 0, "Nonnegative bound required");
        for (var bounds : List.of(List.of("minimum", "maximum"), List.of("minLength", "maxLength"), List.of("minItems", "maxItems")))
            if (schema.has(bounds.get(0)) && schema.has(bounds.get(1))) require(schema.get(bounds.get(0)).decimalValue()
                    .compareTo(schema.get(bounds.get(1)).decimalValue()) <= 0, "Inverted bounds");
        if (schema.has("enum")) {
            require(schema.get("enum").isArray() && !schema.get("enum").isEmpty() && schema.get("enum").size() <= 100, "Invalid enum");
            ObjectNode withoutEnum = schema.deepCopy(); withoutEnum.remove("enum");
            schema.get("enum").forEach(value -> { validateExample(withoutEnum, value, depth); scan(value); });
        }
    }

    private String type(JsonNode schema) {
        JsonNode type = schema.get("type");
        require(type != null, "Explicit schema type required");
        String result;
        if (type.isArray()) {
            require(type.size() == 2 && type.get(0).isTextual() && type.get(1).isTextual()
                    && (type.get(0).asText().equals("null") ^ type.get(1).asText().equals("null")), "Only nullable type unions supported");
            result = type.get(type.get(0).asText().equals("null") ? 1 : 0).asText();
        } else { require(type.isTextual(), "Invalid schema type"); result = type.asText(); }
        require(Set.of("object", "array", "string", "integer", "number", "boolean").contains(result), "Unsupported type");
        return result;
    }

    private void validateExample(JsonNode schema, JsonNode value, int depth) {
        require(value != null && depth <= 12, "Missing or deep example");
        if (value.isNull()) {
            require(schema.get("type").isArray(), "Null violates response schema");
            if (schema.has("enum")) {
                boolean permitsNull = false; for (var item : schema.get("enum")) if (item.isNull()) permitsNull = true;
                require(permitsNull, "Null violates enum");
            }
            return;
        }
        String type = type(schema);
        boolean matches = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber(); case "boolean" -> value.isBoolean();
            default -> false;
        };
        require(matches, "Example type does not match schema");
        if (type.equals("object")) {
            schema.path("required").forEach(key -> require(value.has(key.asText()), "Required example field missing"));
            value.fields().forEachRemaining(entry -> {
                require(schema.path("properties").has(entry.getKey()), "Unexpected example field");
                validateExample(schema.get("properties").get(entry.getKey()), entry.getValue(), depth + 1);
            });
        } else if (type.equals("array")) value.forEach(item -> validateExample(schema.get("items"), item, depth + 1));
        if (schema.has("enum")) {
            boolean included = false; for (var item : schema.get("enum")) if (item.equals(value)) included = true;
            require(included, "Example violates enum");
        }
        if (value.isNumber()) {
            if (schema.has("minimum")) require(value.decimalValue().compareTo(schema.get("minimum").decimalValue()) >= 0, "Example below minimum");
            if (schema.has("maximum")) require(value.decimalValue().compareTo(schema.get("maximum").decimalValue()) <= 0, "Example above maximum");
        }
        int length = value.isTextual() ? value.textValue().codePointCount(0, value.textValue().length()) : value.size();
        String min = value.isTextual() ? "minLength" : "minItems", max = value.isTextual() ? "maxLength" : "maxItems";
        if (schema.has(min)) require(length >= schema.get(min).asInt(), "Example below size minimum");
        if (schema.has(max)) require(length <= schema.get(max).asInt(), "Example above size maximum");
    }

    private void scan(JsonNode value) {
        if (value.isTextual()) safeText(value.asText(), 10000);
        else if (value.isContainerNode()) value.elements().forEachRemaining(this::scan);
    }

    private void safeText(String value, int max) {
        require(value != null && value.length() <= max && !value.contains("<") && !value.contains(">")
                && !Pattern.compile("(?i)https?://").matcher(value).find() && !SECRET_VALUE.matcher(value).find(), "Unsafe public text or example");
    }

    public String canonical(JsonNode value) {
        try { return json.writeValueAsString(sorted(value)); }
        catch (JsonProcessingException exception) { throw invalid("Cannot serialize contract"); }
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, sorted(value.get(key)))); return result;
        }
        if (value.isArray()) { var result = json.createArrayNode(); value.forEach(item -> result.add(sorted(item))); return result; }
        return value.deepCopy();
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    public enum Verification { FIXTURE, REAL_CONTRACT }
    public enum Authentication { ANONYMOUS, API_KEY_HEADER, BEARER }
    public record Parameter(String name, String description, boolean required, JsonNode schema, JsonNode example) {}
    public record ErrorResponse(int status, String description) {}
    public record Contract(UUID datasetId, UUID publicationId, int publicationVersion, int contractVersion,
            String title, String description, String endpoint, Verification verification, Authentication authentication,
            String apiKeyHeader, List<Parameter> parameters, JsonNode responseSchema, JsonNode responseExample,
            List<ErrorResponse> errors, boolean approvedSyntheticExample) {}
    public record GeneratedDocument(JsonNode spec, String canonicalJson, String specHash, String schemaHash,
            String curlExample, JsonNode responseExample, Verification verification) {}
}

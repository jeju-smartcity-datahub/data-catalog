package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;

/** Data Manager dataset metadata and field definitions from the referenced data model. */
public final class KetiDataCoreAdapter implements HubAdapter {
    private final ApprovedHubHttpTransport http;
    private final ObjectMapper json;
    private final int pageSize;
    public KetiDataCoreAdapter(ApprovedHubHttpTransport http, ObjectMapper json, int pageSize) {
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("KETI page size must be 1 to 100");
        this.http = http; this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS); this.pageSize = pageSize;
    }
    @Override public String code() { return "KETI_DATACORE"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.TEST_CONNECTION, Capability.DISCOVER_DATASETS,
            Capability.FETCH_METADATA, Capability.FETCH_SCHEMA); }
    @Override public boolean resumesFromPreviousRun() { return false; }
    @Override public void check(Context context) throws AdapterFailure { datasetIds(context, deadline(context)); }

    @Override public Page discover(Context context, String checkpoint) throws AdapterFailure {
        long deadline = deadline(context);
        context.report(Phase.FETCHING_LIST,null);
        List<String> ids = datasetIds(context, deadline);
        context.report(Phase.FETCHING_DETAILS,ids.size());
        String fingerprint = hash(String.join("\n", ids));
        int offset = cursor(checkpoint, fingerprint, ids.size());
        int end = Math.min(ids.size(), offset + pageSize);
        List<Item> items = new ArrayList<>();
        Map<String, JsonNode> models = new HashMap<>();
        int pageBytes = 0;
        for (int i = offset; i < end; i++) {
            context.report(Phase.FETCHING_DETAILS,ids.size());
            String id = ids.get(i);
            Item item;
            try {
                JsonNode response = object(get(context, "/datasets/" + pathId(id, false), deadline));
                boolean datasetEnvelope = response.has("datasetBaseInfo");
                JsonNode dataset = datasetEnvelope ? object(response.get("datasetBaseInfo")) : response;
                if (!id.equals(requiredText(dataset, "id", 300))) throw invalid();
                String title = requiredText(dataset, "name", 300);
                String description = optionalText(dataset, "description", 10000);
                Map<String, Object> details = new LinkedHashMap<>();
                for (String key : List.of("providerOrganization", "providerSystem", "ownership", "datasetExtension",
                        "category", "updateInterval", "license", "department", "restrictions"))
                    copyText(dataset, key, details);
                JsonNode keyword = alias(dataset, "keywords", "keyword");
                if (keyword != null && !keyword.isNull()) details.put("keywords", strings(keyword, 100, 500));
                JsonNode regions = dataset.get("targetRegions");
                if (regions != null && !regions.isNull()) details.put("targetRegions", regions.isTextual()
                        ? List.of(boundedText(regions, 500, false)) : strings(regions, 100, 500));
                String providing = optionalText(dataset, "providingApiUri", 4000);
                if (!providing.isBlank() && safeMetadataUri(providing)) details.put("providingApiUri", providing);
                if (datasetEnvelope) {
                    // Dataset metadata does not itself describe record fields.
                    details.put("fields", List.of());
                    if (dataset.hasNonNull("modifiedAt")) details.put("sourceModifiedAt", requiredText(dataset, "modifiedAt", 100));
                    supplementEnvelopeMetadata(response, details);
                }
                JsonNode modelRef = alias(dataset, "datamodelId", "dataModelId");
                if (modelRef != null && !modelRef.isNull() && !boundedText(modelRef, 300, false).isBlank()) {
                    String modelId = boundedText(modelRef, 300, true);
                    if (!validModelId(modelId)) throw invalid();
                    details.put("datamodelId", modelId);
                    JsonNode model = models.get(modelId);
                    if (model == null) {
                        model = object(get(context, "/datamodels/" + pathId(modelId, true), deadline));
                        if (!modelId.equals(requiredText(model, "id", 300))) throw invalid();
                        models.put(modelId, model);
                    }
                    details.put("type", requiredText(model, "type", 4000));
                    copyText(model, "typeUri", details);
                    JsonNode contexts = alias(model, "context", "@context");
                    if (contexts != null && !contexts.isNull()) details.put("context", strings(contexts, 100, 2000));
                    List<Map<String, Object>> fields = new ArrayList<>();
                    flatten(model.get("attributes"), "", 0, fields, new HashSet<>());
                    details.put("fields", fields);
                }
                details = NormalizedHubMetadata.details(details);
                String revision = hash(title + "\n" + description + "\n" + NormalizedHubMetadata.encode(details));
                item = Item.success(new Metadata(id, revision, title, description, details));
            } catch (AdapterFailure failure) {
                if (failure.reason() != AdapterFailure.Reason.INVALID_SCHEMA) throw failure;
                item = Item.failure(id);
            } catch (IllegalArgumentException invalidItem) { item = Item.failure(id); }
            int itemBytes;
            if (item.metadata() == null) itemBytes = id.length();
            else itemBytes = id.length() + item.metadata().title().length() + item.metadata().description().length()
                    + item.metadata().sourceRevision().length()
                    + NormalizedHubMetadata.encode(item.metadata().details()).getBytes(StandardCharsets.UTF_8).length;
            if (itemBytes > 220 * 1024) { item = Item.failure(id); itemBytes = 1024; }
            if (!items.isEmpty() && pageBytes + itemBytes > 220 * 1024) { end = i; break; }
            items.add(item); pageBytes += itemBytes;
        }
        return new Page(List.copyOf(items), end < ids.size() ? fingerprint + ":" + end : null, end == ids.size());
    }

    private List<String> datasetIds(Context context, long deadline) throws AdapterFailure {
        ApprovedHubHttpTransport.Response response = http.get(context, "/datasets", deadline);
        JsonNode list = parse(response.body());
        if (!list.isArray() || list.size() > 10000) throw invalid();
        if (response.totalCount() != null) {
            try { if (Integer.parseInt(response.totalCount()) != list.size()) throw invalid(); }
            catch (NumberFormatException invalidCount) { throw invalid(); }
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode entry : list) {
            String id = requiredText(object(entry), "id", 300);
            if (!validId(id) || !unique.add(id)) throw invalid();
        }
        return unique.stream().sorted().toList();
    }
    private JsonNode get(Context context, String path, long deadline) throws AdapterFailure {
        return parse(http.get(context, path, deadline).body());
    }
    private static void supplementEnvelopeMetadata(JsonNode response, Map<String, Object> details) throws AdapterFailure {
        JsonNode metadata = response.get("metadataInfo");
        if (metadata == null || metadata.isNull()) return;
        if (!metadata.isArray() || metadata.size() > 1000) throw invalid();
        for (JsonNode entry : metadata) {
            object(entry);
            String name = optionalText(entry, "metadataName", 200);
            String key = switch (name) { case "CLASSIFICATION" -> "category"; case "OWNERSHIP" -> "ownership"; default -> null; };
            if (key != null && !details.containsKey(key) && entry.hasNonNull("metadataValue"))
                details.put(key, requiredText(entry, "metadataValue", 4000));
        }
    }
    private JsonNode parse(byte[] bytes) throws AdapterFailure {
        try { JsonNode node = json.readTree(bytes); if (node == null) throw invalid(); return node; }
        catch (IOException invalidJson) { throw invalid(); }
    }
    private static int cursor(String value, String fingerprint, int count) throws AdapterFailure {
        if (value == null || value.isBlank()) return 0;
        String[] parts = value.split(":", -1);
        try {
            if (parts.length != 2 || !parts[0].equals(fingerprint) || !parts[1].matches("[0-9]{1,8}")) throw invalid();
            int offset = Integer.parseInt(parts[1]);
            if (offset < 0 || offset >= count) throw invalid();
            return offset;
        } catch (NumberFormatException malformed) { throw invalid(); }
    }
    private static void flatten(JsonNode attributes, String prefix, int depth, List<Map<String, Object>> output,
                                Set<String> paths) throws AdapterFailure {
        if (attributes == null || !attributes.isArray() || depth > 12) throw invalid();
        for (JsonNode raw : attributes) {
            JsonNode attribute = object(raw);
            String name = requiredText(attribute, "name", 200);
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (path.length() > 200 || !paths.add(path) || output.size() >= 500) throw invalid();
            String valueType = requiredText(attribute, "valueType", 100);
            boolean required = false;
            if (attribute.hasNonNull("isRequired")) {
                if (!attribute.get("isRequired").isBoolean()) throw invalid();
                required = attribute.get("isRequired").booleanValue();
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldPath", path); field.put("physicalName", path); field.put("displayName", name);
            field.put("description", optionalText(attribute, "description", 4000));
            field.put("dataType", catalogType(valueType)); field.put("sourceDataType", valueType);
            field.put("nullable", !required); field.put("ordinal", output.size()); field.put("publicVisible", true);
            if (attribute.hasNonNull("isRequired")) field.put("isRequired", required);
            if (attribute.hasNonNull("attributeType")) field.put("attributeType", requiredText(attribute, "attributeType", 100));
            if (attribute.hasNonNull("attributeUri")) field.put("attributeUri", requiredText(attribute, "attributeUri", 2000));
            if (attribute.hasNonNull("accessMode")) field.put("accessMode", requiredText(attribute, "accessMode", 100));
            for (String flag : List.of("hasObservedAt", "hasUnitCode")) if (attribute.hasNonNull(flag)) {
                if (!attribute.get(flag).isBoolean()) throw invalid();
                field.put(flag, attribute.get(flag).booleanValue());
            }
            if (attribute.hasNonNull("maxLength")) {
                JsonNode length = attribute.get("maxLength");
                String number = length.isIntegralNumber() ? length.asText() : boundedText(length, 20, true);
                // KETI also permits compound DB length strings (e.g. "3,3"). They are
                // not a scalar character limit; retain the field without inventing one.
                if (!number.matches("[0-9]{1,7},[0-9]{1,7}")) {
                    try { int max = Integer.parseInt(number); if (max < 1 || max > 1_000_000) throw invalid(); field.put("maxLength", max); }
                    catch (NumberFormatException malformed) { throw invalid(); }
                }
            }
            if (attribute.hasNonNull("unitCode")) field.put("unit", requiredText(attribute, "unitCode", 100));
            output.add(field);
            for (String nested : List.of("objectMembers", "childAttributes"))
                if (attribute.hasNonNull(nested)) flatten(attribute.get(nested), path, depth + 1, output, paths);
        }
    }
    private static String catalogType(String value) throws AdapterFailure {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "string" -> "STRING";
            case "integer", "long" -> "INTEGER";
            case "double", "float", "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            // KETI Date is a zoned timestamp (DataModelManager DATE -> TIMESTAMP), not a calendar date.
            case "date", "datetime" -> "DATETIME";
            case "object", "geojson" -> "OBJECT";
            case "array", "arraystring", "arrayinteger", "arraylong", "arraydouble", "arrayboolean", "arrayobject" -> "ARRAY";
            default -> throw invalid();
        };
    }
    private static JsonNode alias(JsonNode node, String first, String second) throws AdapterFailure {
        JsonNode a = node.get(first), b = node.get(second);
        if (a != null && !a.isNull() && b != null && !b.isNull() && !a.equals(b)) throw invalid();
        return a == null || a.isNull() ? b : a;
    }
    private static JsonNode object(JsonNode node) throws AdapterFailure { if (node == null || !node.isObject()) throw invalid(); return node; }
    private static String requiredText(JsonNode node, String key, int max) throws AdapterFailure { return boundedText(node.get(key), max, true); }
    private static String optionalText(JsonNode node, String key, int max) throws AdapterFailure {
        return !node.hasNonNull(key) ? "" : boundedText(node.get(key), max, false);
    }
    private static String boundedText(JsonNode value, int max, boolean required) throws AdapterFailure {
        if (value == null || !value.isTextual() || value.textValue().length() > max
                || required && value.textValue().isBlank() || value.textValue().indexOf('\0') >= 0) throw invalid();
        return value.textValue();
    }
    private static void copyText(JsonNode source, String key, Map<String, Object> output) throws AdapterFailure {
        if (source.hasNonNull(key)) output.put(key, boundedText(source.get(key), 4000, false));
    }
    private static List<String> strings(JsonNode node, int maxItems, int maxLength) throws AdapterFailure {
        if (!node.isArray() || node.size() > maxItems) throw invalid();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(boundedText(item, maxLength, true));
        return List.copyOf(result);
    }
    private static boolean safeMetadataUri(String value) {
        try { URI uri = URI.create(value); return Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean validId(String value) { return value.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,299}")
            && !value.contains("//") && List.of(value.split("/", -1)).stream().noneMatch(p -> p.equals(".") || p.equals("..") || p.isEmpty()); }
    private static boolean validModelId(String value) {
        if (validId(value)) return true;
        if (!value.matches("https?://[a-zA-Z0-9._:/-]+") || value.length() > 300) return false;
        try {
            URI identifier = URI.create(value);
            if (identifier.getHost() == null || identifier.getUserInfo() != null || identifier.getQuery() != null
                    || identifier.getFragment() != null || !identifier.normalize().equals(identifier)
                    || identifier.getPath().contains("//")) return false;
            return List.of(identifier.getPath().split("/", -1)).stream().noneMatch(p -> p.equals(".") || p.equals(".."));
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String pathId(String value, boolean model) throws AdapterFailure {
        if (!(model ? validModelId(value) : validId(value))) throw invalid();
        // The official model controller handles slash-separated IDs as a path, and reads the raw request URI.
        return model ? value : value.replace("/", "%2F");
    }
    private static long deadline(Context context) { return System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(30, context.timeoutSeconds()))); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static AdapterFailure invalid() { return new AdapterFailure(AdapterFailure.Reason.INVALID_SCHEMA, 0); }
}

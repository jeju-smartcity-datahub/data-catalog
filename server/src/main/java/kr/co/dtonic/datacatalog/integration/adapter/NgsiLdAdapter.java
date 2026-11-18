package kr.co.dtonic.datacatalog.integration.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;

/** Bounded local NGSI-LD query, schema projection only. No entity writes, raw data copying or context fetch. */
public final class NgsiLdAdapter implements HubAdapter {
    private static final int MAX_ENTITIES = 10000;
    private static final int MAX_REMOTE_PAGES = 100;
    private static final int MAX_SCAN_BYTES = 16 * 1024 * 1024;
    private static final int MAX_NORMALIZED_PAGE_BYTES = 220 * 1024;
    private final ApprovedHubHttpTransport http;
    private final ObjectMapper json;
    private final NgsiLdMetadataNormalizer normalizer;
    private final int pageSize;

    public NgsiLdAdapter(ApprovedHubHttpTransport http, ObjectMapper json, int pageSize, String contextMappings) {
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("NGSI-LD page size must be1 to100");
        this.http = http;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.pageSize = pageSize;
        this.normalizer = new NgsiLdMetadataNormalizer(this.json, contextMappings);
    }

    @Override public String code() { return "NGSI_LD"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.TEST_CONNECTION,
            Capability.DISCOVER_DATASETS, Capability.FETCH_METADATA, Capability.FETCH_SCHEMA); }
    @Override public boolean resumesFromPreviousRun() { return false; }

    @Override public void check(Context context) throws AdapterFailure {
        if (snapshot(context,false).stream().anyMatch(item -> item.metadata() == null)) throw invalid();
    }

    @Override public Page discover(Context context, String checkpoint) throws AdapterFailure {
        // Re-read the bounded list on each normalized page. The semantic fingerprint
        // rejects a changing list rather than silently resuming against shifted offsets.
        List<Item> all = snapshot(context,true);
        context.report(Phase.FETCHING_DETAILS,all.size());
        String fingerprint = hash(all.stream().map(item -> item.externalId() + ":"
                + (item.metadata() == null ? "INVALID" : item.metadata().sourceRevision())).collect(Collectors.joining("\n")));
        int offset = cursor(checkpoint, fingerprint, all.size());
        int end = Math.min(all.size(), offset + pageSize);
        int bytes = 0;
        var selected = new ArrayList<Item>();
        for (int i = offset; i < end; i++) {
            Item item = all.get(i);
            int size = item.externalId().length() + (item.metadata() == null ? 0
                    : item.metadata().title().length() + item.metadata().description().length()
                    + NormalizedHubMetadata.encode(item.metadata().details()).getBytes(StandardCharsets.UTF_8).length);
            if (!selected.isEmpty() && bytes + size > MAX_NORMALIZED_PAGE_BYTES) { end = i; break; }
            if (size > MAX_NORMALIZED_PAGE_BYTES) item = Item.failure(item.externalId());
            selected.add(item); bytes += Math.min(size, MAX_NORMALIZED_PAGE_BYTES);
        }
        return new Page(List.copyOf(selected), end < all.size() ? fingerprint + ":" + end : null, end == all.size());
    }

    private List<Item> snapshot(Context context,boolean reportProgress) throws AdapterFailure {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, Math.min(30, context.timeoutSeconds())));
        var items = new TreeMap<String,Item>();
        Integer expectedCount = null;
        int offset = 0, bytes = 0;
        long normalizedBytes = 0;
        for (int page = 0; page < MAX_REMOTE_PAGES; page++) {
            if(reportProgress)context.report(Phase.FETCHING_LIST,expectedCount);
            var response = http.getNgsiEntities(context, pageSize, offset, deadline);
            bytes += response.body().length;
            if (bytes > MAX_SCAN_BYTES) throw oversized();
            JsonNode list;
            try { list = json.readTree(response.body()); }
            catch (IOException malformed) { throw invalid(); }
            if (list == null || !list.isArray() || list.size() > pageSize) throw invalid();
            Integer count = count(response.totalCount());
            if (page == 0) expectedCount = count;
            else if (!Objects.equals(expectedCount, count)) throw invalid();
            if (count != null && offset + list.size() > count) throw invalid();
            if (list.isEmpty() && count != null && offset < count) throw invalid();
            if(reportProgress)context.report(Phase.FETCHING_DETAILS,expectedCount);
            for (JsonNode entity : list) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new AdapterFailure(AdapterFailure.Reason.TIMEOUT,0);
                String id = sourceId(entity);
                if (items.containsKey(id)) throw invalid();
                Item item;
                try { item = Item.success(normalizer.normalize(entity)); }
                catch (AdapterFailure malformedItem) {
                    if (malformedItem.reason() != AdapterFailure.Reason.INVALID_SCHEMA) throw malformedItem;
                    item = Item.failure(id);
                }
                normalizedBytes += item.metadata() == null ? id.length()
                        : NormalizedHubMetadata.encode(item.metadata().details()).getBytes(StandardCharsets.UTF_8).length
                        + item.metadata().title().length() + item.metadata().description().length();
                if (normalizedBytes > MAX_SCAN_BYTES) throw oversized();
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new AdapterFailure(AdapterFailure.Reason.TIMEOUT,0);
                items.put(id,item);
                if (items.size() > MAX_ENTITIES) throw oversized();
            }
            offset += list.size();
            if (count != null ? offset == count : list.size() < pageSize) return List.copyOf(items.values());
        }
        throw oversized();
    }

    private static Integer count(String value) throws AdapterFailure {
        if (value == null) return null;
        if (!value.matches("[0-9]{1,5}")) throw invalid();
        try { int count = Integer.parseInt(value); if (count > MAX_ENTITIES) throw oversized(); return count; }
        catch (NumberFormatException invalidNumber) { throw invalid(); }
    }

    private static String sourceId(JsonNode entity) throws AdapterFailure {
        if (entity == null || !entity.isObject()) throw invalid();
        JsonNode compact = entity.get("id"), expanded = entity.get("@id");
        if (compact != null && expanded != null && !compact.equals(expanded)) throw invalid();
        JsonNode value = compact == null ? expanded : compact;
        if (value == null || !value.isTextual()
                || !value.textValue().matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,299}")) throw invalid();
        return value.textValue();
    }

    private static int cursor(String value, String fingerprint, int count) throws AdapterFailure {
        if (value == null || value.isBlank()) return 0;
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || !fingerprint.equals(parts[0]) || !parts[1].matches("[0-9]{1,5}")) throw invalid();
        try { int offset = Integer.parseInt(parts[1]); if (offset >= count) throw invalid(); return offset; }
        catch (NumberFormatException invalidNumber) { throw invalid(); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static AdapterFailure invalid() { return new AdapterFailure(AdapterFailure.Reason.INVALID_SCHEMA,0); }
    private static AdapterFailure oversized() { return new AdapterFailure(AdapterFailure.Reason.RESPONSE_TOO_LARGE,0); }
}

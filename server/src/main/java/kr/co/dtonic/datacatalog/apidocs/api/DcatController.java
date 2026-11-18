package kr.co.dtonic.datacatalog.apidocs.api;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.dtonic.datacatalog.apidocs.application.ConsumerOpenApiGenerator;
import kr.co.dtonic.datacatalog.delivery.application.PublicDeliveryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DcatController {
    private final PublicDeliveryService service;
    public DcatController(PublicDeliveryService service) { this.service = service; }

    @Operation(operationId = "getPublicDcat", summary = "현재 공개 게시 메타데이터 DCAT 피드", description = "Accept로 JSON-LD 또는 Turtle 선택. 기본 JSON-LD. page=0, size=100, 최대 500이며 Link next와 X-Total-Count 제공.")
    @GetMapping(value = "/api/catalog/v1/dcat", produces = {"application/ld+json", "text/turtle"})
    public ResponseEntity<String> feed(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestHeader(value = "Accept", defaultValue = "application/ld+json") String accept) {
        var accepted = MediaType.parseMediaTypes(accept);
        accepted.sort(MediaType.QUALITY_VALUE_COMPARATOR.thenComparing(MediaType.SPECIFICITY_COMPARATOR));
        for (var mediaType : accepted) {
            if (mediaType.getQualityValue() <= 0) continue;
            if (mediaType.isCompatibleWith(MediaType.parseMediaType("application/ld+json"))) return response(page, size, false, false);
            if (mediaType.isCompatibleWith(MediaType.parseMediaType("text/turtle"))) return response(page, size, true, false);
        }
        throw new IllegalArgumentException("Unsupported feed media type");
    }

    @Operation(operationId = "downloadPublicDcat", summary = "동일 공개 조건으로 DCAT 피드 다운로드")
    @GetMapping("/api/catalog/v1/dcat/download")
    public ResponseEntity<String> download(@RequestParam(defaultValue = "jsonld") String format,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        if (!format.equals("jsonld") && !format.equals("turtle")) throw new IllegalArgumentException("Unsupported format");
        return response(page, size, format.equals("turtle"), true);
    }

    private ResponseEntity<String> response(int page, int size, boolean turtle, boolean attachment) {
        var feed = service.feed(page, size, turtle);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.VARY, "Accept").header("X-Total-Count", Long.toString(feed.total()))
                .header(HttpHeaders.CONTENT_TYPE, (turtle ? "text/turtle" : "application/ld+json") + ";charset=UTF-8")
                .eTag("\"" + ConsumerOpenApiGenerator.sha256(page + ":" + size + ":" + feed.total() + ":" + feed.body()) + "\"");
        if (((long) page + 1) * size < feed.total()) response.header(HttpHeaders.LINK,
                "</api/catalog/v1/dcat?page=" + (page + 1) + "&size=" + size + ">; rel=\"next\"");
        if (attachment) response.header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"catalog-" + page + (turtle ? ".ttl\"" : ".jsonld\""));
        return response.body(feed.body());
    }
}

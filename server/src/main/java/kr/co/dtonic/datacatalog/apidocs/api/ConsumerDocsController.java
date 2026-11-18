package kr.co.dtonic.datacatalog.apidocs.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.dtonic.datacatalog.delivery.application.PublicDeliveryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsumerDocsController {
    private final PublicDeliveryService service;
    public ConsumerDocsController(PublicDeliveryService service) { this.service = service; }

    @Operation(operationId = "getConsumerDocsAvailability", summary = "현재 공개 게시본의 소비자 API 문서 준비 상태")
    @GetMapping(value = "/api/docs/v1/datasets/{datasetId}", produces = "application/json")
    public ResponseEntity<ConsumerDocsAvailability> availability(@PathVariable UUID datasetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.availability(datasetId));
    }
}

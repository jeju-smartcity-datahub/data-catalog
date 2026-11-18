package kr.co.dtonic.datacatalog.delivery.api;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.co.dtonic.datacatalog.common.CsvCells;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.delivery.application.DatasetRecordsService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static kr.co.dtonic.datacatalog.delivery.api.DataAccessModels.*;

@RestController
public class DatasetRecordsController {
    private final DatasetRecordsService service;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public DatasetRecordsController(DatasetRecordsService service,com.fasterxml.jackson.databind.ObjectMapper json){this.service=service;this.json=json;}
    @GetMapping("/api/admin/v1/datasets/{id}/data-access")
    public ResponseEntity<DataAccessView> settings(@PathVariable UUID id,Authentication auth){return response(service.settings(id,auth));}
    @PutMapping("/api/admin/v1/datasets/{id}/data-access")
    public ResponseEntity<DataAccessView> save(@PathVariable UUID id,@Valid @RequestBody DataAccessInput input,
            @RequestHeader(value="If-Match",required=false) String etag,Authentication auth){return response(service.save(id,input,etag,auth));}
    @PostMapping("/api/admin/v1/datasets/{id}/data-access/check")
    public ResponseEntity<DataAccessCheck> check(@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag,Authentication auth){
        var result=service.check(id,etag,auth);return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .eTag(DatasetRecordsService.etag(result.settings())).body(result);
    }
    @GetMapping("/api/catalog/v1/datasets/{id}/records")
    public ResponseEntity<RecordPage> records(@PathVariable UUID id,@RequestParam(defaultValue="10") int limit,
            @RequestParam(defaultValue="0") int offset){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.records(id,limit,offset));}
    @GetMapping("/api/catalog/v1/datasets/{id}/records/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,@RequestParam(defaultValue="json") String format,
            @RequestParam(defaultValue="1000") int limit,@RequestParam(defaultValue="0") int offset) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(!java.util.Set.of("json","csv").contains(format))throw new DomainException(HttpStatus.BAD_REQUEST,"INVALID_FORMAT","JSON 또는 CSV 형식을 선택하세요.");
        var page=service.records(id,limit,offset);byte[] body;
        if(format.equals("json"))body=json.writeValueAsBytes(page.items());
        else {
            var csv=new StringBuilder("\uFEFF");
            csv.append(page.columns().stream().map(c->CsvCells.quote(c.key())).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
            for(var row:page.items()) {
                for(int index=0;index<page.columns().size();index++) {
                    if(index>0)csv.append(',');Object value=row.get(page.columns().get(index).key());
                    csv.append(CsvCells.quote(value instanceof java.util.Collection<?>||value instanceof java.util.Map<?,?>?json.writeValueAsString(value):value));
                }
                csv.append("\r\n");
            }
            body=csv.toString().getBytes(StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"dataset-"+id+"."+format+"\"")
            .contentType(MediaType.parseMediaType(format.equals("json")?"application/json":"text/csv;charset=UTF-8")).body(body);
    }
    private static ResponseEntity<DataAccessView> response(DataAccessView view){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(DatasetRecordsService.etag(view)).body(view);}
}

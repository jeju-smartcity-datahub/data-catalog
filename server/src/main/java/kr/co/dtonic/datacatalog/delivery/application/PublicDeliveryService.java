package kr.co.dtonic.datacatalog.delivery.application;

import java.util.List;
import java.util.UUID;
import kr.co.dtonic.datacatalog.apidocs.api.ConsumerDocsAvailability;
import kr.co.dtonic.datacatalog.apidocs.application.DcatSerializer;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.delivery.persistence.DeliveryMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicDeliveryService {
    private final DeliveryMapper mapper;
    private final DcatSerializer serializer;
    private final DatasetRecordsService records;

    public PublicDeliveryService(DeliveryMapper mapper, DcatSerializer serializer, DatasetRecordsService records) {
        this.mapper = mapper;
        this.serializer = serializer;
        this.records = records;
    }

    @Transactional(readOnly = true)
    public ConsumerDocsAvailability availability(UUID datasetId) {
        var publication = mapper.currentPublic(datasetId);
        if (publication == null) throw new DomainException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "공개된 데이터셋을 찾을 수 없습니다.");
        boolean ready=records.available(datasetId);
        var documents=new java.util.ArrayList<>(List.of("/api/catalog/v1/datasets/"+publication.datasetId(),"/api/catalog/v1/dcat"));
        if(ready)documents.add("/api/catalog/v1/datasets/"+publication.datasetId()+"/records");
        return new ConsumerDocsAvailability(publication.datasetId(), publication.publicationId(),
                publication.publicationVersion(), ready?"SERVICE_READY":publication.accessState(), ready?"RECORDS_API":"METADATA_API",
                ready?"공개 필드의 데이터를 조회하고 JSON·CSV로 내려받을 수 있습니다.":"게시된 메타정보와 필드정보를 JSON API로 조회할 수 있습니다.",documents);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Feed feed(int page, int size, boolean turtle) {
        if (page < 0 || size < 1 || size > 500) throw new IllegalArgumentException("Invalid feed pagination");
        var rows = mapper.publicFeed(page, size);
        long total = mapper.publicCount();
        return new Feed(serializer.serialize(rows, turtle), total);
    }

    public record Feed(String body, long total) {}
}

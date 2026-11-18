package kr.co.dtonic.datacatalog.delivery.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.egovframe.rte.psl.dataaccess.EgovAbstractMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryMapper extends EgovAbstractMapper {
    public PublicDeliveryPublication currentPublic(UUID datasetId) {
        return selectOne("Delivery.currentPublic", Map.of("datasetId", datasetId.toString()));
    }

    public List<PublicDeliveryPublication> publicFeed(int page, int size) {
        return selectList("Delivery.publicFeed", Map.of("offset", (long) page * size, "size", size));
    }

    public long publicCount() { return selectOne("Delivery.publicCount"); }
}

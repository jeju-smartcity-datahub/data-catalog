package kr.co.dtonic.datacatalog.integration.application;

import java.util.*;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.domain.HubAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdapterRegistry {
    private final Map<String, HubAdapter> adapters;
    public AdapterRegistry(List<HubAdapter> available) {
        Map<String, HubAdapter> registered = new HashMap<>();
        for (HubAdapter adapter : available) {
            if (registered.putIfAbsent(adapter.code(), adapter) != null) throw new IllegalStateException("Duplicate adapter code");
        }
        adapters = Map.copyOf(registered);
    }
    public Optional<HubAdapter> find(String code) { return Optional.ofNullable(adapters.get(code)); }
    public HubAdapter require(String code, HubAdapter.Capability capability) {
        HubAdapter adapter = find(code).orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ADAPTER_UNAVAILABLE", "선택한 연동 방식을 서버에서 지원하지 않습니다."));
        if (!adapter.capabilities().contains(capability)) throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY,
                "CAPABILITY_UNSUPPORTED", "허브가 요청한 기능을 지원하지 않습니다.");
        return adapter;
    }
}

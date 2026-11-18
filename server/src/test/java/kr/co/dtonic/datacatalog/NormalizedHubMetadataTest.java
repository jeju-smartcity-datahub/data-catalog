package kr.co.dtonic.datacatalog;

import java.util.*;
import kr.co.dtonic.datacatalog.integration.domain.NormalizedHubMetadata;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NormalizedHubMetadataTest {
    @Test void stableOrderingAndOnlyApprovedKeysAreStored() {
        var first=new LinkedHashMap<String,Object>();first.put("keywords",List.of("water","river","water"));first.put("license","Public");first.put("dataStoreUri","secret");
        var second=new LinkedHashMap<String,Object>();second.put("license","Public");second.put("keywords",List.of("river","water"));
        assertThat(NormalizedHubMetadata.encode(NormalizedHubMetadata.details(first))).isEqualTo(NormalizedHubMetadata.encode(NormalizedHubMetadata.details(second))).doesNotContain("secret");
    }
    @Test void invalidFieldsAndCredentialBearingUrisAreRejected() {
        assertThatThrownBy(()->NormalizedHubMetadata.details(Map.of("fields",List.of(Map.of("fieldPath","x"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NormalizedHubMetadata.details(Map.of("providingApiUri","https://example.invalid/data?token=secret"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NormalizedHubMetadata.details(Map.of("providingApiUri","https://user:secret@example.invalid/data"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiredAndNullableFlagsUseBooleanValues() throws Exception {
        var field=new HashMap<String,Object>(Map.of("fieldPath","x","physicalName","x","displayName","X",
                "dataType","STRING","nullable",Boolean.TRUE,"publicVisible",true,"ordinal",0));
        // Deliberately distinct boxed values, as supplied by a non-Jackson adapter.
        field.put("isRequired",Boolean.class.getConstructor(boolean.class).newInstance(true));
        assertThatThrownBy(()->NormalizedHubMetadata.details(Map.of("fields",List.of(field))))
                .isInstanceOf(IllegalArgumentException.class);
        field.put("isRequired",Boolean.FALSE);
        assertThat(NormalizedHubMetadata.details(Map.of("fields",List.of(field)))).containsKey("fields");
    }
}

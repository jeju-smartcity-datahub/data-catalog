package kr.co.dtonic.datacatalog.integration.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import kr.co.dtonic.datacatalog.common.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class MountedFileSecretProviderTest {
    @TempDir Path directory;

    @Test void acceptsBoundedAsciiNamesAndNestedReferences() throws Exception {
        var provider = new MountedFileSecretProvider(directory);
        for (String reference : List.of("hub_a-1", "jeju/hub_a-1", "x".repeat(200))) {
            Path file = directory.resolve(reference + ".json");
            Files.createDirectories(file.getParent()); Files.writeString(file, "{\"type\":\"NONE\"}");
            assertThat(provider.resolve(reference)).isEqualTo("{\"type\":\"NONE\"}".toCharArray());
        }
    }

    @Test void rejectsOversizedAndMalformedReferencesBeforeFileAccessWithoutStackOverflow() {
        var provider = new MountedFileSecretProvider(directory);
        assertTimeout(Duration.ofSeconds(2), () -> {
            for (String reference : List.of("a/".repeat(100000), "x".repeat(201), "", "/hub", "hub/",
                    "hub//key", "../hub", "hub/../key", "hub\\key", "hub.key", "hub key", "hub\u0000key")) {
                assertThatThrownBy(() -> provider.resolve(reference)).isInstanceOf(DomainException.class)
                        .hasMessage("서버 인증 설정을 읽을 수 없습니다.");
            }
            assertThatThrownBy(() -> provider.resolve(null)).isInstanceOf(DomainException.class);
        });
    }
}

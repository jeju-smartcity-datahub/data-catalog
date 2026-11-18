package kr.co.dtonic.datacatalog.integration.adapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import kr.co.dtonic.datacatalog.common.DomainException;
import kr.co.dtonic.datacatalog.integration.domain.SecretReferenceProvider;
import org.springframework.http.HttpStatus;

/** A reference resolves only below the administrator-mounted directory; file contents never enter errors. */
public final class MountedFileSecretProvider implements SecretReferenceProvider {
    private static final int MAX_BYTES = 16_384;
    private final Path directory;
    public MountedFileSecretProvider(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    @Override public boolean available() { return Files.isDirectory(directory); }
    @Override public char[] resolve(String reference) {
        byte[] bytes = null;
        try {
            if (reference == null || reference.isEmpty() || reference.length() > 200
                    || reference.startsWith("/") || reference.endsWith("/") || reference.contains("//")
                    || !reference.matches("[a-zA-Z0-9_/-]+"))
                throw new IOException();
            Path root = directory.toRealPath();
            Path file = root.resolve(reference + ".json").normalize();
            if (!file.startsWith(root)) throw new IOException();
            Path current = root;
            for (Path part : root.relativize(file)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IOException();
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException();
            var decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            char[] result = new char[decoded.remaining()]; decoded.get(result);
            if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
            return result;
        } catch (IOException | RuntimeException rejected) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_STORE_UNAVAILABLE", "서버 인증 설정을 읽을 수 없습니다.");
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
}

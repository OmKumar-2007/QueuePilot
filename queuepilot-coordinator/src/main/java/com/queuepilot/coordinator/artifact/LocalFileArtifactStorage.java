package com.queuepilot.coordinator.artifact;

import com.queuepilot.coordinator.config.QueuePilotProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Stores artifacts as files named by their artifact id (a UUID) under {@code <dataDir>/artifacts}.
 * File names never come from user input, so path traversal is impossible.
 */
@Component
public class LocalFileArtifactStorage implements ArtifactStorage {

    private static final Pattern KEY = Pattern.compile("[0-9a-f-]{36}");

    private final Path root;

    public LocalFileArtifactStorage(QueuePilotProperties properties) throws IOException {
        this.root = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredFile store(String key, InputStream content, long maxBytes) throws IOException {
        Path temp = Files.createTempFile(root, "upload-", ".tmp");
        MessageDigest digest = sha256();
        long size = 0;
        try (DigestInputStream in = new DigestInputStream(content, digest);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                size += n;
                if (size > maxBytes) {
                    throw new TooLargeException(maxBytes);
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, resolve(key), StandardCopyOption.ATOMIC_MOVE);
        return new StoredFile(size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return root.resolve(key);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

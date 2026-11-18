package network.ike.docs.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// SHA-256 helpers used for source hashes, plan hashes and stable ids.
public final class IngestHash {

    private IngestHash() {
    }

    /// Hash a byte array.
    ///
    /// @param data the bytes
    /// @return lowercase hex SHA-256
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    /// Hash a string as UTF-8.
    ///
    /// @param text the text
    /// @return lowercase hex SHA-256
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /// Hash a file's bytes, streaming so large PDFs are not held in memory.
    ///
    /// @param file the file to hash
    /// @return lowercase hex SHA-256
    /// @throws IOException if the file cannot be read
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = digest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                md.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}

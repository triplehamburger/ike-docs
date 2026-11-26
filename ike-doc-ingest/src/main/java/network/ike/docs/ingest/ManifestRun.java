package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/// Helpers a goal uses to keep a {@link Manifest}: open the one on disk for
/// the document being ingested, and hash the files a stage read or wrote.
public final class ManifestRun {

    private ManifestRun() {
    }

    /// The manifest on disk when it is for the same source file with the same
    /// hash, and a fresh empty one otherwise. A manifest that cannot be read,
    /// or that is for another document or another version of it, is not an
    /// error: nothing in it can be trusted, so every stage runs again.
    ///
    /// @param file   the manifest file, normally `ingest.yaml` in the output folder
    /// @param source the document being ingested now
    /// @return the manifest to continue from
    public static Manifest open(Path file, Manifest.Source source) {
        if (Files.isRegularFile(file)) {
            try {
                Manifest recorded = Manifest.read(file);
                if (recorded.source().equals(source)) {
                    return recorded;
                }
            } catch (IOException e) {
                // an unreadable manifest is replaced
            }
        }
        return Manifest.of(source);
    }

    /// SHA-256 of every regular file under a folder, by path relative to a base.
    ///
    /// @param base the folder the paths are relative to
    /// @param dir  the folder to walk; a missing folder gives an empty map
    /// @return the hashes, in path order, with forward slashes
    /// @throws IOException when a file cannot be read
    public static Map<String, String> hashTree(Path base, Path dir) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return hashes;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                hashes.put(relative(base, file), IngestHash.sha256Hex(file));
            }
        }
        return hashes;
    }

    /// SHA-256 of the named files that exist, by path relative to a base.
    ///
    /// @param base  the folder the paths are relative to
    /// @param files the files; one that is not a regular file is left out
    /// @return the hashes, in path order, with forward slashes
    /// @throws IOException when a file cannot be read
    public static Map<String, String> hashFiles(Path base, Collection<Path> files) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (Path file : files) {
            if (Files.isRegularFile(file)) {
                hashes.put(relative(base, file), IngestHash.sha256Hex(file));
            }
        }
        return hashes;
    }

    private static String relative(Path base, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = base.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute).toString().replace('\\', '/');
    }
}

package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Resolves a path and tells whether it stays inside an allowed root.
///
/// The check is real: a relative path is read against the root, `..` and
/// symbolic links are resolved through the part of the path that exists, and
/// the result must lie under the root's real path. What happens on an escape
/// depends on the mode:
///
/// - {@link #recording(Path)} adds a WARNING finding `path-outside-root` and
///   returns the path, so a run is never stopped;
/// - {@link #enforcing(Path)} throws {@link AccessDeniedException};
/// - {@link #permissive()} has no root and allows every path.
///
/// The stages use the recording jail first; enforcement is a later change of
/// the jail they are given.
public final class PathJail {

    private final Path root;
    private final boolean enforce;

    private PathJail(Path root, boolean enforce) {
        this.root = root;
        this.enforce = enforce;
    }

    /// A jail with no root: every path is allowed.
    ///
    /// @return the jail
    public static PathJail permissive() {
        return new PathJail(null, false);
    }

    /// A jail that records a path outside the root as a warning.
    ///
    /// @param root the allowed root
    /// @return the jail
    public static PathJail recording(Path root) {
        return new PathJail(realRoot(root), false);
    }

    /// A jail that refuses a path outside the root.
    ///
    /// @param root the allowed root
    /// @return the jail
    public static PathJail enforcing(Path root) {
        return new PathJail(realRoot(root), true);
    }

    /// Resolve a path against the root.
    ///
    /// @param path     the path to resolve, absolute or relative to the root
    /// @param findings receives a WARNING `path-outside-root` when a recording jail meets an escape
    /// @return the absolute, normalised path with symbolic links resolved where they exist
    /// @throws AccessDeniedException when an enforcing jail meets an escape
    /// @throws IOException           when the path cannot be resolved
    public Path resolve(Path path, List<Finding> findings) throws IOException {
        if (root == null) {
            return path.toAbsolutePath().normalize();
        }
        Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        Path real = real(absolute);
        if (!real.startsWith(root)) {
            String message = "the path resolves to " + real + ", outside the allowed root " + root;
            if (enforce) {
                throw new AccessDeniedException(path.toString(), null, message);
            }
            findings.add(Finding.warning("path-outside-root", path.toString(), message));
        }
        return real;
    }

    private static Path realRoot(Path root) {
        try {
            return real(root.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve the jail root " + root + ": " + e.getMessage(), e);
        }
    }

    /// The real path of the part that exists, with the missing remainder appended.
    private static Path real(Path absolute) throws IOException {
        Path existing = absolute;
        Path remainder = null;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            remainder = remainder == null ? name : name.resolve(remainder);
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path real = existing.toRealPath();
        return remainder == null ? real : real.resolve(remainder).normalize();
    }
}

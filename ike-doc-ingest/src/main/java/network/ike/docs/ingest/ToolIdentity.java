package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// What an ingestion stage ran: the program, the script it was given, and the
/// hash of each, so a manifest can show later which tool produced a file and a
/// stage can be skipped only while the tool is unchanged.
///
/// Every part but the program may be absent (`null`): a program found on the
/// `PATH` has a path and hash, a name that cannot be resolved has neither, a
/// tool with no script argument has no script, and the version and lockfile
/// hash are recorded only when the caller supplies them.
///
/// @param program        the program as resolved to an absolute path, or as given when it
///                       could not be resolved
/// @param programSha256  SHA-256 of the program file, or null when it was not resolved
/// @param script         the first argument that is an existing file, as an absolute path,
///                       or null
/// @param scriptSha256   SHA-256 of the script file, or null
/// @param version        the version the caller declared, or null
/// @param lockfileSha256 SHA-256 of the dependency lockfile the caller named, or null
public record ToolIdentity(String program, String programSha256, String script, String scriptSha256,
                           String version, String lockfileSha256) {

    /// Validate the identity.
    ///
    /// @throws IllegalArgumentException when the program is blank
    public ToolIdentity {
        if (program == null || program.isBlank()) {
            throw new IllegalArgumentException("a tool identity needs a program");
        }
    }

    /// Resolve a command to its identity. Never starts the program: the
    /// program is looked up as a path or on the `PATH` and hashed as a file.
    ///
    /// @param command  the program and its arguments
    /// @param workDir  the directory a relative program or script is read from, or null for
    ///                 the current one
    /// @param version  the version the caller declares for the tool, or null
    /// @param lockfile the dependency lockfile to hash, or null
    /// @return the identity
    /// @throws IOException              when a file that exists cannot be read for hashing
    /// @throws IllegalArgumentException when the command is empty
    public static ToolIdentity resolve(List<String> command, Path workDir, String version, Path lockfile)
            throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("a command needs at least a program");
        }
        Path base = workDir == null ? Path.of("").toAbsolutePath() : workDir.toAbsolutePath();
        String given = command.get(0);
        Path program = find(given, base);
        String programText = program == null ? given : program.toString();
        String programHash = program == null ? null : IngestHash.sha256Hex(program);
        Path script = null;
        for (String argument : command.subList(1, command.size())) {
            Path candidate = regularFile(argument, base);
            if (candidate != null) {
                script = candidate;
                break;
            }
        }
        String lockHash = lockfile != null && Files.isRegularFile(lockfile)
                ? IngestHash.sha256Hex(lockfile) : null;
        return new ToolIdentity(programText, programHash, script == null ? null : script.toString(),
                script == null ? null : IngestHash.sha256Hex(script), blankToNull(version), lockHash);
    }

    /// The identity as an ordered map for the manifest; absent parts are left out.
    ///
    /// @return the map
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("program", program);
        putIfPresent(map, "program-sha256", programSha256);
        putIfPresent(map, "script", script);
        putIfPresent(map, "script-sha256", scriptSha256);
        putIfPresent(map, "version", version);
        putIfPresent(map, "lockfile-sha256", lockfileSha256);
        return map;
    }

    /// Read an identity back from the map {@link #toMap()} wrote.
    ///
    /// @param map the loaded map
    /// @return the identity
    /// @throws IllegalArgumentException when the program is missing
    public static ToolIdentity fromMap(Map<String, Object> map) {
        return new ToolIdentity(string(map, "program"), string(map, "program-sha256"),
                string(map, "script"), string(map, "script-sha256"), string(map, "version"),
                string(map, "lockfile-sha256"));
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static Path find(String program, Path base) {
        if (program.contains("/") || program.contains("\\")) {
            return regularFile(program, base);
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        List<String> extensions = List.of("");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String pathext = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            List<String> all = new java.util.ArrayList<>(List.of(""));
            for (String ext : pathext.split(";")) {
                if (!ext.isBlank()) {
                    all.add(ext.toLowerCase(Locale.ROOT));
                }
            }
            extensions = all;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                Path found = regularFile(program + ext, Path.of(dir));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Path regularFile(String name, Path base) {
        try {
            Path candidate = Path.of(name);
            Path resolved = candidate.isAbsolute() ? candidate : base.resolve(candidate);
            return Files.isRegularFile(resolved) ? resolved.toAbsolutePath().normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}

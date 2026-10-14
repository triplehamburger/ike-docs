package network.ike.docs.plugin.registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Generate the topic registry outside a Maven goal: the command-line entry
 * point for what {@code idoc:topic-registry} does, and the run both share.
 * It reads sources and writes only the registry file, exactly as the goal
 * does; see {@code TopicRegistryMojo} for the registry's purpose and shape.
 *
 * <p>Run from the {@code ike-docs} reactor root with {@code exec:java}:
 *
 * <pre>
 *   mvn exec:java -pl ike-doc-maven-plugin \
 *     -Dexec.mainClass=network.ike.docs.plugin.registry.TopicRegistryMain \
 *     -Dexec.args="--base /path/to/project/topics"
 * </pre>
 *
 * <p>Options, each matching a goal parameter:
 *
 * <pre>
 *   --base DIR        the module directory (default: the working directory)
 *   --roots a,b       directories to scan (default: BASE/src/docs/asciidoc)
 *   --output FILE     where the registry is written (default: BASE/target/topic-registry.yaml)
 *   --add a,b         add files to the existing registry without rescanning
 *   --root-file FILE  only that file and what it includes
 *   --max-files N     the most .adoc files one root may hold (default: 5000)
 * </pre>
 *
 * <p>Relative paths resolve against {@code --base}.
 *
 * @since 123
 */
public final class TopicRegistryMain {

    /** The default for {@link Options#maxFiles()}, as the goal's {@code maxFiles}. */
    public static final int DEFAULT_MAX_FILES = 5000;

    private TopicRegistryMain() {}

    /**
     * One registry run's settings.
     *
     * @param base     the module directory; relative paths resolve against it
     * @param roots    directories to scan
     * @param output   where the registry is written
     * @param maxFiles the most {@code .adoc} files one root may hold
     * @param add      files to add without rescanning; empty for a full scan
     * @param rootFile the file to build the registry from, or {@code null} to scan every file
     */
    public record Options(Path base, List<Path> roots, Path output, int maxFiles,
                          List<Path> add, Path rootFile) {
    }

    /**
     * Generate the registry from the command line.
     *
     * @param args the options described on this class
     */
    public static void main(String[] args) {
        try {
            run(parse(args), System.out::println, line -> System.err.println("WARNING " + line));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            System.err.println("topic-registry: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Read the command-line options.
     *
     * @param args the options described on this class
     * @return the settings
     * @throws IllegalArgumentException for an unknown option or a missing value
     */
    static Options parse(String[] args) {
        Path base = Path.of("").toAbsolutePath();
        String roots = null;
        String output = null;
        String add = null;
        String rootFile = null;
        int maxFiles = DEFAULT_MAX_FILES;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            String value = args[++i];
            switch (option) {
                case "--base" -> base = Path.of(value).toAbsolutePath();
                case "--roots" -> roots = value;
                case "--output" -> output = value;
                case "--add" -> add = value;
                case "--root-file" -> rootFile = value;
                case "--max-files" -> maxFiles = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("Unknown option " + option
                        + "; expected --base, --roots, --output, --add, --root-file or --max-files");
            }
        }
        List<Path> rootPaths = roots == null
                ? List.of(base.resolve("src/docs/asciidoc"))
                : paths(base, roots);
        Path out = base.resolve(output == null ? "target/topic-registry.yaml" : output);
        List<Path> added = add == null ? List.of() : paths(base, add);
        return new Options(base, rootPaths, out, maxFiles, added,
                rootFile == null ? null : base.resolve(rootFile));
    }

    private static List<Path> paths(Path base, String commaSeparated) {
        List<Path> paths = new ArrayList<>();
        for (String p : commaSeparated.split(",")) {
            if (!p.isBlank()) {
                paths.add(base.resolve(p.strip()));
            }
        }
        return paths;
    }

    /**
     * Generate the registry, or add files to it, reporting progress line by line.
     *
     * @param options the settings
     * @param info    receives the registry tree and the summary
     * @param warn    receives each finding
     * @throws IllegalArgumentException for settings that cannot run
     * @throws IllegalStateException    when a root holds more than {@code maxFiles} files
     * @throws IOException              when a file cannot be read or the registry written
     */
    public static void run(Options options, Consumer<String> info, Consumer<String> warn)
            throws IOException {
        long started = System.nanoTime();
        Path base = options.base();
        Path out = options.output();
        boolean fromRootFile = options.rootFile() != null;
        if (fromRootFile && !options.add().isEmpty()) {
            throw new IllegalArgumentException("rootFile and add cannot be combined;"
                    + " run with one or the other");
        }
        if (!options.add().isEmpty()) {
            if (Files.isRegularFile(out)) {
                addToRegistry(options, info, warn, started);
                return;
            }
            info.accept("idoc:topic-registry — no generated registry at " + out
                    + " yet, running the full scan");
        }
        Path defaultRoot = base.resolve("src/docs/asciidoc");
        List<Path> rootPaths = options.roots();
        if (!fromRootFile && rootPaths.size() == 1 && rootPaths.get(0).toAbsolutePath().normalize()
                .equals(defaultRoot.toAbsolutePath().normalize())
                && !Files.isDirectory(defaultRoot)) {
            info.accept("idoc:topic-registry — no src/docs/asciidoc in " + base.getFileName()
                    + ", skipping");
            return;
        }

        List<TopicRegistry.Scan> scans = new ArrayList<>();
        if (fromRootFile) {
            scans.add(scanFromRootFile(base, options.rootFile(), rootPaths, info));
        } else {
            for (Path root : rootPaths) {
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException("Registry root does not exist: " + root
                            + " (check the roots)");
                }
                try {
                    scans.add(TopicRegistry.scan(root, options.maxFiles()));
                } catch (IOException e) {
                    throw new IOException("Cannot scan " + root + ": " + e.getMessage(), e);
                }
            }
        }

        String yaml = TopicRegistry.yaml(TopicRegistry.model(scans, base, Instant.now()));
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Cannot write the registry to " + out + ": " + e.getMessage(), e);
        }

        int topics = 0;
        int others = 0;
        int findings = 0;
        for (TopicRegistry.Scan scan : scans) {
            for (String line : TopicRegistry.tree(scan, base)) {
                info.accept(line);
            }
            topics += scan.topics().size();
            others += scan.others().size();
            findings += scan.findings().size();
            for (String finding : scan.findings()) {
                warn.accept("registry: " + finding);
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        info.accept(topics + " topic" + (topics == 1 ? "" : "s") + ", " + others
                + " other file" + (others == 1 ? "" : "s") + ", " + findings
                + " finding" + (findings == 1 ? "" : "s") + "; topic registry written to "
                + shown(base, out) + " (" + millis + " ms)");
    }

    private static TopicRegistry.Scan scanFromRootFile(Path base, Path file, List<Path> rootPaths,
                                                       Consumer<String> info) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Root file does not exist: " + file
                    + " (check the root file)");
        }
        try {
            Path realFile = file.toRealPath();
            for (Path root : rootPaths) {
                if (Files.isDirectory(root) && realFile.startsWith(root.toRealPath())) {
                    info.accept("idoc:topic-registry — from root file " + shown(base, file));
                    return TopicRegistry.scanFrom(root, file);
                }
            }
        } catch (IOException e) {
            throw new IOException("Cannot scan from " + file + ": " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("Root file " + file + " is under none of the registry roots "
                + rootPaths + " (check the roots)");
    }

    private static void addToRegistry(Options options, Consumer<String> info, Consumer<String> warn,
                                      long started) throws IOException {
        Path base = options.base();
        Path out = options.output();
        Map<String, Object> model;
        try {
            model = TopicRegistry.load(out);
        } catch (IOException e) {
            throw new IOException("Cannot read the generated registry " + out + ": "
                    + e.getMessage(), e);
        }
        List<TopicHeader> added;
        try {
            added = TopicRegistry.add(model, base, options.add(), Instant.now());
        } catch (IOException e) {
            throw new IOException("Cannot read a file to add: " + e.getMessage(), e);
        }
        try {
            Files.writeString(out, TopicRegistry.yaml(model), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Cannot write the registry to " + out + ": " + e.getMessage(), e);
        }
        for (TopicHeader h : added) {
            info.accept("registry: " + (h.topic() ? "topic " + h.id() + " at " : "file ") + h.file());
        }
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) model.get("findings");
        for (Object f : findings) {
            for (TopicHeader h : added) {
                if (String.valueOf(f).startsWith(h.file() + ": ")) {
                    warn.accept("registry: " + f);
                }
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        info.accept("added " + added.size() + " file" + (added.size() == 1 ? "" : "s")
                + " to " + shown(base, out) + " without rescanning (" + millis + " ms)");
    }

    private static String shown(Path base, Path out) {
        return out.toAbsolutePath().startsWith(base.toAbsolutePath())
                ? base.toAbsolutePath().relativize(out.toAbsolutePath()).toString()
                : out.toString();
    }
}

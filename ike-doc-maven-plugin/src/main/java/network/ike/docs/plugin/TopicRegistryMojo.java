package network.ike.docs.plugin;

import network.ike.docs.plugin.packages.AdocLayout;
import network.ike.docs.plugin.packages.AdocPackages;
import network.ike.docs.plugin.registry.BlobIndex;
import network.ike.docs.plugin.registry.GitBlobIndex;
import network.ike.docs.plugin.registry.IncrementalRegistry;
import network.ike.docs.plugin.registry.TermIndex;
import network.ike.docs.plugin.registry.TopicHeader;
import network.ike.docs.plugin.registry.TopicRegistry;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generate the topic registry: every AsciiDoc file under the module's source
 * root, with its storage location and header metadata (the {@code :topic-*:}
 * block of a topic fragment; the title, document attributes and include count
 * of any file), written to {@code target/topic-registry.yaml} in the shape
 * {@code IKE-TOPIC-REGISTRY.md} gives the registry: topics grouped into
 * domains by the prefix of their id. The same structure is printed to the
 * build log as a tree. It applies to every module the doc pipeline renders:
 * topic libraries, assemblies, doc-only and hybrid projects.
 *
 * <p>This is the topic registry's build-time revision: the registry keeps its
 * purpose and shape and is now generated from the files on every run instead
 * of kept by hand at {@code src/docs/asciidoc/topic-registry.yaml}. It is
 * never committed and never edits a source file. Header findings (a
 * missing required attribute, an anchor that does not match the id, an
 * unknown type or status, the same id in two files) are reported in the
 * registry and the log and do not fail the build.
 *
 * <p>A module without {@code src/docs/asciidoc} is skipped with one log
 * line, so the goal can run from a reactor root. Usage:
 *
 * <pre>
 *   mvn idoc:topic-registry                                          # the module's src/docs/asciidoc
 *   mvn idoc:topic-registry -Dike.topic-registry.roots=a,b           # other roots, comma-separated
 *   mvn idoc:topic-registry -Dike.topic-registry.output=/tmp/r.yaml  # write elsewhere
 *   mvn idoc:topic-registry -Dike.topic-registry.add=topics/x/new.adoc   # add one ingested file, no rescan
 *   mvn idoc:topic-registry -Dike.topic-registry.rootFile=src/docs/asciidoc/index.adoc  # only what index.adoc includes
 * </pre>
 *
 * <p>With {@code -Dike.topic-registry.rootFile} the registry holds that file
 * and the {@code .adoc} files it reaches through {@code include::} lines,
 * recursively; other files under the root are left out. The file must lie
 * under one of the roots, and paths stay relative to that root. Includes that
 * cannot be followed (an attribute reference such as {@code {topicsdir}/},
 * an absolute path, a URI, a missing file) are findings. It cannot be combined
 * with {@code add}.
 *
 * <p>With {@code -Dike.topic-registry.add} the named files are parsed alone
 * and merged into the existing registry: placed in their domain, counted, and
 * checked for a duplicate id against the registry. An entry for the same file
 * is replaced, so re-ingesting is safe. Deleted files are not noticed that
 * way; a run without {@code add} re-derives everything.
 *
 * <p>The default phase is {@code validate}: the goal reads sources and
 * writes only under {@code target/}, so an execution declared without a
 * phase (as {@code ike-parent} does for {@code lint-site}) refreshes the
 * registry on every build, including a bare {@code mvn validate}.
 *
 * <p>With the POM property {@code ike.adoc.layout=packages}
 * ({@code dev-adoc-packages}, IKE-Network/ike-issues#1341) the registry is
 * blob-keyed and incremental: {@link GitBlobIndex} lists every file with
 * the git blob id of its content without opening it, and
 * {@link IncrementalRegistry} keeps the entry of a file whose path and
 * blob are unchanged, re-keys the entry of a blob that moved, parses only
 * a new or changed blob and drops a path that is gone. A tree without git
 * falls back to hashing every file in Java, the full scan's cost. The
 * registry and the term index are then written to
 * {@code target/adoc-meta/META-INF/ike/<groupId>/<artifactId>/topic-registry.yaml}
 * and {@code term-index.yaml}, which the parent POM ships in the adoc zip,
 * with every {@code file:} relative to the AsciiDoc root; {@code add} is a
 * special case of the blob rule and needs no separate path. Without the
 * property nothing above changes.
 *
 * <p>Skip with {@code -Dike.skip.topic-registry=true}.
 *
 * @since 109
 */
@Mojo(name = "topic-registry", defaultPhase = "validate")
public class TopicRegistryMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Directories to scan, comma-separated. Defaults to the module's
     * {@code src/docs/asciidoc}; when that default directory does not exist
     * the goal is skipped, while a named directory that does not exist
     * fails the build.
     */
    @Parameter(property = "ike.topic-registry.roots",
               defaultValue = "${project.basedir}/src/docs/asciidoc")
    String roots;

    /** Where the generated registry is written. */
    @Parameter(property = "ike.topic-registry.output",
               defaultValue = "${project.build.directory}/topic-registry.yaml")
    File output;

    /** The most {@code .adoc} files one root may hold before the scan fails. */
    @Parameter(property = "ike.topic-registry.maxFiles", defaultValue = "5000")
    int maxFiles;

    /**
     * Files to add to the existing registry without rescanning, comma-separated,
     * relative to the module base directory or absolute. Each must be an
     * {@code .adoc} file under one of the registry's roots. When no generated
     * registry exists yet, the full scan runs instead.
     */
    @Parameter(property = "ike.topic-registry.add")
    String add;

    /**
     * An {@code .adoc} file to build the registry from instead of scanning
     * every file: the file and what it reaches through {@code include::}
     * lines. Relative to the module base directory or absolute; it must lie
     * under one of the {@link #roots}. Unset, the whole root is scanned.
     */
    @Parameter(property = "ike.topic-registry.rootFile")
    String rootFile;

    /**
     * Fail when a topic still has placeholder keywords and summary, that is
     * a header with {@code :topic-metadata: placeholder}. Without this the
     * placeholder is a finding in the log and the registry only.
     */
    @Parameter(property = "ike.ingest.requireMetadata", defaultValue = "false")
    boolean requireMetadata;

    /** Skip the goal. */
    @Parameter(property = "ike.skip.topic-registry", defaultValue = "false")
    boolean skip;

    /**
     * The layout the library declares: with {@code packages} the registry is
     * blob-keyed, incremental, and written with the term index under
     * {@code META-INF/ike/<groupId>/<artifactId>/} in {@link #metaDirectory}.
     */
    @Parameter(property = AdocLayout.PROPERTY)
    String layout;

    /**
     * Where the packages layout's metadata tree is written; the parent POM
     * zips it with the sources. Used only with {@code ike.adoc.layout=packages}.
     */
    @Parameter(property = "ike.adoc.meta-directory", defaultValue = "${project.build.directory}/adoc-meta")
    File metaDirectory;

    /** The build directory, to tell the default {@link #output} from one the user chose. */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    File buildDirectory;

    /** The project this goal runs in; registry paths are shown relative to its base directory. */
    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.Project project;

    /** Creates this goal instance. */
    public TopicRegistryMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().debug("idoc:topic-registry — skipped");
            return;
        }
        long started = System.nanoTime();
        Path base = project.getBasedir();
        boolean fromRootFile = rootFile != null && !rootFile.isBlank();
        AdocLayout adocLayout;
        try {
            adocLayout = AdocLayout.of(layout);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        }
        if (adocLayout == AdocLayout.PACKAGES) {
            if (fromRootFile) {
                throw new MojoException("-Dike.topic-registry.rootFile is not supported with "
                        + AdocLayout.PROPERTY + "=packages; the registry of a packaged library"
                        + " covers every file under its root");
            }
            executePackages(base, started);
            return;
        }
        if (fromRootFile && add != null && !add.isBlank()) {
            throw new MojoException("-Dike.topic-registry.rootFile and -Dike.topic-registry.add"
                    + " cannot be combined; run with one or the other");
        }
        if (add != null && !add.isBlank()) {
            if (Files.isRegularFile(output.toPath())) {
                addToRegistry(base, output.toPath(), started);
                return;
            }
            getLog().info("idoc:topic-registry — no generated registry at " + output
                    + " yet, running the full scan");
        }
        Path defaultRoot = base.resolve("src/docs/asciidoc");
        List<Path> rootPaths = new ArrayList<>();
        for (String r : roots.split(",")) {
            if (!r.isBlank()) {
                rootPaths.add(Path.of(r.strip()));
            }
        }
        if (!fromRootFile && rootPaths.size() == 1 && rootPaths.get(0).toAbsolutePath().normalize()
                .equals(defaultRoot.toAbsolutePath().normalize())
                && !Files.isDirectory(defaultRoot)) {
            getLog().info("idoc:topic-registry — no src/docs/asciidoc in " + base.getFileName()
                    + ", skipping");
            return;
        }

        List<TopicRegistry.Scan> scans = new ArrayList<>();
        if (fromRootFile) {
            scans.add(scanFromRootFile(base, rootPaths));
        } else {
            for (Path root : rootPaths) {
                if (!Files.isDirectory(root)) {
                    throw new MojoException("Registry root does not exist: " + root
                            + " (check -Dike.topic-registry.roots)");
                }
                try {
                    scans.add(TopicRegistry.scan(root, maxFiles));
                } catch (IOException e) {
                    throw new MojoException("Cannot scan " + root + ": " + e.getMessage(), e);
                } catch (IllegalStateException e) {
                    throw new MojoException(e.getMessage(), e);
                }
            }
        }

        String yaml = TopicRegistry.yaml(TopicRegistry.model(scans, base, Instant.now()));
        Path out = output.toPath();
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Cannot write the registry to " + out + ": " + e.getMessage(), e);
        }

        int topics = 0;
        int others = 0;
        int findings = 0;
        for (TopicRegistry.Scan scan : scans) {
            for (String line : TopicRegistry.tree(scan, base)) {
                getLog().info(line);
            }
            topics += scan.topics().size();
            others += scan.others().size();
            findings += scan.findings().size();
            for (String finding : scan.findings()) {
                getLog().warn("registry: " + finding);
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        getLog().info(topics + " topic" + (topics == 1 ? "" : "s") + ", " + others
                + " other file" + (others == 1 ? "" : "s") + ", " + findings
                + " finding" + (findings == 1 ? "" : "s") + "; topic registry written to "
                + shown(base, out) + " (" + millis + " ms)");
        requireMetadataFilled(out);
    }

    /**
     * The packages layout's run: one root, the blob index from git or by
     * hashing, the incremental update against the registry written last
     * time, the registry and the term index under {@code META-INF}.
     */
    private void executePackages(Path base, long started) throws MojoException {
        List<Path> rootPaths = new ArrayList<>();
        for (String r : roots.split(",")) {
            if (!r.isBlank()) {
                rootPaths.add(Path.of(r.strip()));
            }
        }
        if (rootPaths.size() != 1) {
            throw new MojoException(AdocLayout.PROPERTY + "=packages takes exactly one registry root,"
                    + " the library's AsciiDoc root; got " + rootPaths + " (check -Dike.topic-registry.roots)");
        }
        Path root = rootPaths.get(0);
        if (!Files.isDirectory(root)) {
            if (root.toAbsolutePath().normalize().equals(base.resolve("src/docs/asciidoc").toAbsolutePath().normalize())) {
                getLog().info("idoc:topic-registry — no src/docs/asciidoc in " + base.getFileName() + ", skipping");
                return;
            }
            throw new MojoException("Registry root does not exist: " + root + " (check -Dike.topic-registry.roots)");
        }
        Path metaDir = metaDirectory.toPath().resolve(AdocPackages.metaDirectory(project.getGroupId(),
                project.getArtifactId()));
        Path defaultOutput = buildDirectory.toPath().resolve("topic-registry.yaml").toAbsolutePath().normalize();
        Path out = output.toPath().toAbsolutePath().normalize().equals(defaultOutput)
                ? metaDir.resolve("topic-registry.yaml") : output.toPath();
        Path termsOut = out.resolveSibling("term-index.yaml");

        Map<String, Object> previous = null;
        if (Files.isRegularFile(out)) {
            try {
                previous = TopicRegistry.load(out);
            } catch (IOException e) {
                getLog().warn("idoc:topic-registry — cannot read the previous registry " + shown(base, out)
                        + " (" + e.getMessage() + "); parsing every file");
            }
        }
        if (add != null && !add.isBlank()) {
            getLog().info("idoc:topic-registry — -Dike.topic-registry.add is a special case of the blob rule"
                    + " under " + AdocLayout.PROPERTY + "=packages: changed files are parsed, the rest kept");
        }
        BlobIndex index;
        IncrementalRegistry.Outcome outcome;
        try {
            index = GitBlobIndex.read(root).orElse(null);
            if (index == null) {
                getLog().info("idoc:topic-registry — " + shown(base, root) + " is not in a git working tree;"
                        + " hashing every file instead");
                index = BlobIndex.hashed(root);
            }
            if (index.blobs().size() > maxFiles) {
                throw new MojoException("More than " + maxFiles + " .adoc files under " + root
                        + "; raise -Dike.topic-registry.maxFiles");
            }
            outcome = IncrementalRegistry.update(previous, root, index, base, Instant.now());
        } catch (IOException e) {
            throw new MojoException("Cannot index " + root + ": " + e.getMessage(), e);
        }
        Map<String, Object> model = outcome.model();
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, TopicRegistry.yaml(model), StandardCharsets.UTF_8);
            Files.createDirectories(termsOut.getParent());
            Files.writeString(termsOut, TermIndex.yaml(TermIndex.model(model, Instant.now())), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Cannot write the registry to " + out + ": " + e.getMessage(), e);
        }
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) model.get("findings");
        for (Object f : findings) {
            getLog().warn("registry: " + f);
        }
        int topics = (Integer) model.get("topic-count");
        int others = (Integer) model.get("file-count") - topics;
        long millis = (System.nanoTime() - started) / 1_000_000;
        getLog().info(topics + " topic" + (topics == 1 ? "" : "s") + ", " + others
                + " other file" + (others == 1 ? "" : "s") + ", " + findings.size()
                + " finding" + (findings.size() == 1 ? "" : "s") + "; " + outcome.kept() + " kept, "
                + outcome.rekeyed() + " re-keyed, " + outcome.parsed() + " parsed, " + outcome.dropped()
                + " dropped by " + (index.source() == BlobIndex.Source.GIT ? "git blob" : "hashed blob")
                + "; topic registry and term index written to " + shown(base, out.getParent())
                + "/ (" + millis + " ms)");
        requireMetadataFilled(out);
    }

    private TopicRegistry.Scan scanFromRootFile(Path base, List<Path> rootPaths) throws MojoException {
        Path file = base.resolve(rootFile.strip());
        if (!Files.isRegularFile(file)) {
            throw new MojoException("Root file does not exist: " + file
                    + " (check -Dike.topic-registry.rootFile)");
        }
        try {
            Path realFile = file.toRealPath();
            for (Path root : rootPaths) {
                if (Files.isDirectory(root) && realFile.startsWith(root.toRealPath())) {
                    getLog().info("idoc:topic-registry — from root file " + shown(base, file));
                    return TopicRegistry.scanFrom(root, file);
                }
            }
        } catch (IOException e) {
            throw new MojoException("Cannot scan from " + file + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        }
        throw new MojoException("Root file " + file + " is under none of the registry roots "
                + rootPaths + " (check -Dike.topic-registry.roots)");
    }

    private void addToRegistry(Path base, Path out, long started) throws MojoException {
        List<Path> files = new ArrayList<>();
        for (String f : add.split(",")) {
            if (!f.isBlank()) {
                files.add(Path.of(f.strip()));
            }
        }
        Map<String, Object> model;
        try {
            model = TopicRegistry.load(out);
        } catch (IOException e) {
            throw new MojoException("Cannot read the generated registry " + out + ": "
                    + e.getMessage(), e);
        }
        List<TopicHeader> added;
        try {
            added = TopicRegistry.add(model, base, files, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoException("Cannot read a file to add: " + e.getMessage(), e);
        }
        try {
            Files.writeString(out, TopicRegistry.yaml(model), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Cannot write the registry to " + out + ": " + e.getMessage(), e);
        }
        for (TopicHeader h : added) {
            getLog().info("registry: " + (h.topic() ? "topic " + h.id() + " at " : "file ") + h.file());
        }
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) model.get("findings");
        for (Object f : findings) {
            for (TopicHeader h : added) {
                if (String.valueOf(f).startsWith(h.file() + ": ")) {
                    getLog().warn("registry: " + f);
                }
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        getLog().info("added " + added.size() + " file" + (added.size() == 1 ? "" : "s")
                + " to " + shown(base, out) + " without rescanning (" + millis + " ms)");
        requireMetadataFilled(out);
    }

    /**
     * With {@code requireMetadata}, fail when the written registry still has a
     * topic whose header marks keywords and summary as placeholders. The
     * registry file is already written, so it can be inspected.
     */
    void requireMetadataFilled(Path registryFile) throws MojoException {
        if (!requireMetadata) {
            return;
        }
        List<String> placeholders;
        try {
            placeholders = TopicRegistry.placeholderTopics(TopicRegistry.load(registryFile));
        } catch (IOException e) {
            throw new MojoException("Cannot read the registry " + registryFile + " to check metadata: "
                    + e.getMessage(), e);
        }
        if (!placeholders.isEmpty()) {
            String shown = String.join(", ", placeholders.stream().limit(5).toList())
                    + (placeholders.size() > 5 ? " and " + (placeholders.size() - 5) + " more" : "");
            throw new MojoException(placeholders.size() + " topic(s) still have placeholder keywords and summary "
                    + "(:topic-metadata: placeholder): " + shown + "; write them and remove the line, or set "
                    + "-Dike.ingest.requireMetadata=false");
        }
    }

    private static String shown(Path base, Path out) {
        return out.toAbsolutePath().startsWith(base.toAbsolutePath())
                ? base.toAbsolutePath().relativize(out.toAbsolutePath()).toString()
                : out.toString();
    }
}

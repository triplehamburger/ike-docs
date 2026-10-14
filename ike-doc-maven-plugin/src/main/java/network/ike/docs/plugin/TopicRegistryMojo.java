package network.ike.docs.plugin;

import network.ike.docs.plugin.registry.TopicRegistryMain;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Skip the goal. */
    @Parameter(property = "ike.skip.topic-registry", defaultValue = "false")
    boolean skip;

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
        Path base = project.getBasedir();
        TopicRegistryMain.Options options = new TopicRegistryMain.Options(base,
                paths(base, roots), output.toPath(), maxFiles, paths(base, add),
                rootFile == null || rootFile.isBlank() ? null : base.resolve(rootFile.strip()));
        try {
            TopicRegistryMain.run(options, getLog()::info, getLog()::warn);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    private static List<Path> paths(Path base, String commaSeparated) {
        List<Path> paths = new ArrayList<>();
        if (commaSeparated != null) {
            for (String p : commaSeparated.split(",")) {
                if (!p.isBlank()) {
                    paths.add(base.resolve(p.strip()));
                }
            }
        }
        return paths;
    }
}

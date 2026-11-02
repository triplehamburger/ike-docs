package network.ike.docs.plugin;

import network.ike.plugin.support.GoalRef;
import org.apache.maven.api.plugin.Mojo;

import java.util.Optional;

/**
 * Compile-time identity for every {@code idoc:*} goal in this plugin. Each
 * value wraps the bare goal name, the mojo class that implements it, and
 * a short human description.
 *
 * <p>Parallels {@code network.ike.plugin.IkeGoal} (ike-maven-plugin) and
 * {@code network.ike.plugin.ws.WsGoal} (ike-workspace-maven-plugin). All
 * three implement {@link GoalRef} so a shared {@code GoalReport} writer
 * can address goals across plugins without stringly-typed coupling.
 *
 * <p>Introduced by ike-issues #215, which split the asciidoc/render
 * mojos out of {@code ike-maven-plugin} to dissolve the reverse-dep
 * cycle on {@code koncept-asciidoc-extension}.
 */
public enum IdocGoal implements GoalRef {

    /** {@code idoc:adocstudio} — generate Adoc Studio sidecar projects. */
    ADOCSTUDIO("adocstudio", AdocStudioMojo.class,
            "Generate Adoc Studio sidecar projects for assembly modules."),
    /** {@code idoc:asciidoc} — render AsciiDoc to HTML, DocBook, and Prawn PDF. */
    ASCIIDOC("asciidoc", AsciidocMojo.class,
            "Render AsciiDoc to HTML, DocBook, and Prawn PDF."),
    /** {@code idoc:copy-default-pdf} — copy the default-renderer PDF to the site. */
    COPY_DEFAULT_PDF("copy-default-pdf", CopyDefaultPdfMojo.class,
            "Copy the project's default-renderer PDF to the site."),
    /** {@code idoc:diff} — generate and render a doc-diff review packet (#648). */
    DIFF("diff", DocDiffMojo.class,
            "Generate and render a review packet: changed topics between "
                    + "two refs (or a ref and the working tree) marked with "
                    + "diff roles, plus record of changes, change glossary, "
                    + "change index, and registry delta."),
    /** {@code idoc:copy-docs} — copy rendered docs into the site. */
    COPY_DOCS("copy-docs", CopyDocsToSiteMojo.class,
            "Copy rendered HTML docs and assets into the Maven site."),
    /** {@code idoc:fix-svg} — post-process SVGs for PDF renderer compatibility. */
    FIX_SVG("fix-svg", FixSvgMojo.class,
            "Post-process generated SVGs to work in all PDF renderers."),
    /** {@code idoc:inject-breadcrumb} — inject breadcrumbs into rendered HTML. */
    INJECT_BREADCRUMB("inject-breadcrumb", InjectBreadcrumbMojo.class,
            "Inject breadcrumb navigation into rendered HTML."),
    /** {@code idoc:ingest-coverage} — check that topics still hold what their source PDF says. */
    INGEST_COVERAGE("ingest-coverage", IngestCoverageMojo.class,
            "Compare a source PDF's words with the topics written from it, "
                    + "page by page, and check each decision-summary section "
                    + "title appears. Writes coverage.yaml."),
    /** {@code idoc:ingest-detect} — classify a source PDF as a 510(k) decision summary or not. */
    INGEST_DETECT("ingest-detect", IngestDetectMojo.class,
            "Classify a source PDF as an FDA 510(k) decision summary (layout, "
                    + "template, K-number) or not, and run the AI transcription "
                    + "fallback for scanned PDFs. Writes detect.yaml and needs-ai.yaml."),
    /** {@code idoc:ingest-fragments} — split a converted decision summary into fragments and an assembly. */
    INGEST_FRAGMENTS("ingest-fragments", IngestFragmentsMojo.class,
            "Split the single AsciiDoc file of a 510(k) decision summary, IFU or "
                    + "GUDID record into fragments, with generated header blocks and ids, "
                    + "and build the record as an assembly that includes them. Writes "
                    + "fragments.yaml."),
    /** {@code idoc:lint-packages} — lint the packages layout of a topic library (#1341). */
    LINT_PACKAGES("lint-packages", LintPackagesMojo.class,
            "Lint a topic library laid out as packages (ike.adoc.layout="
                    + "packages): the root holds only dotted-group directories "
                    + "and META-INF, every package begins with the module's "
                    + "group, a topic's directory equals its id's domain "
                    + "prefix, images are inside packages; lines still using "
                    + "{topics} are warnings. Fails the build with every "
                    + "finding listed."),
    /** {@code idoc:lint-site} — lint src/site/site.xml for IKE theme/breadcrumb drift (#319). */
    LINT_SITE("lint-site", LintSiteMojo.class,
            "Lint src/site/site.xml for IKE Network theme + "
                    + "breadcrumb conventions: <bodyClass> drift "
                    + "(Forest vs Horizon), stale breadcrumb names "
                    + "(IKE Pipeline post-#216), and skin GAV drift."),
    /** {@code idoc:manifest} — generate the package manifest of a topic library (#1341). */
    MANIFEST("manifest", ManifestMojo.class,
            "Write META-INF/ike/<groupId>/<artifactId>/manifest.yaml for a "
                    + "topic library laid out as packages (ike.adoc.layout="
                    + "packages): its coordinates, the packages it ships "
                    + "with their kind, and the path of its registry; "
                    + "generated from the pom and the tree, never kept by hand."),
    /** {@code idoc:package-doc} — package rendered docs as an ike-doc artifact. */
    PACKAGE_DOC("package-doc", PackageDocMojo.class,
            "Package rendered docs as an ike-doc artifact."),
    /** {@code idoc:patch-docbook} — apply local patches to DocBook XSL output. */
    PATCH_DOCBOOK("patch-docbook", PatchDocbookMojo.class,
            "Apply local patches to the DocBook XSL output."),
    /** {@code idoc:prepare-renderer-output} — prepare per-renderer output dirs. */
    PREPARE_RENDERER_OUTPUT("prepare-renderer-output", PrepareRendererOutputMojo.class,
            "Prepare per-renderer output directories."),
    /** {@code idoc:render-pdf} — render AsciiDoc to PDF via a configured renderer. */
    RENDER_PDF("render-pdf", RenderPdfMojo.class,
            "Render AsciiDoc to PDF via a configured renderer."),
    /** {@code idoc:scan-logs} — scan renderer logs for warnings and errors. */
    SCAN_LOGS("scan-logs", ScanRendererLogsMojo.class,
            "Scan renderer logs for warnings and errors."),
    /** {@code idoc:topic-registry} — generate the topic registry from the files. */
    TOPIC_REGISTRY("topic-registry", TopicRegistryMojo.class,
            "Read the :topic-*: header of every AsciiDoc file under the "
                    + "module's source root and write target/topic-registry.yaml: "
                    + "the topic registry, generated from the headers in "
                    + "IKE-TOPIC-REGISTRY.md's shape instead of kept by hand. "
                    + "With ike.adoc.layout=packages the registry is blob-keyed "
                    + "and incremental, and written with the term index under "
                    + "META-INF/ike/<groupId>/<artifactId>/.");

    /** Shared {@code idoc:} prefix for all goals in this plugin. */
    public static final String PLUGIN_PREFIX = "idoc";

    private final String goalName;
    private final Class<? extends Mojo> mojoClass;
    private final String description;

    IdocGoal(String goalName,
             Class<? extends Mojo> mojoClass,
             String description) {
        this.goalName = goalName;
        this.mojoClass = mojoClass;
        this.description = description;
    }

    /**
     * The bare goal name as it appears in {@code @Mojo(name = ...)}.
     *
     * @return the bare goal name
     */
    @Override
    public String goalName() {
        return goalName;
    }

    /**
     * The {@code idoc} plugin prefix — shared by every goal in this enum.
     *
     * @return {@link #PLUGIN_PREFIX}
     */
    @Override
    public String pluginPrefix() {
        return PLUGIN_PREFIX;
    }

    /**
     * The fully-qualified goal invocation, e.g. {@code "idoc:render-pdf"}.
     *
     * @return the fully-qualified goal invocation
     */
    @Override
    public String qualified() {
        return PLUGIN_PREFIX + ":" + goalName;
    }

    /**
     * The mojo class that implements this goal.
     *
     * @return the mojo class
     */
    public Class<? extends Mojo> mojoClass() {
        return mojoClass;
    }

    /**
     * One-line human description of what this goal does.
     *
     * @return the human description
     */
    @Override
    public String description() {
        return description;
    }

    /**
     * Look up a goal by its bare name (e.g. {@code "render-pdf"}).
     *
     * @param goalName the bare goal name, without the {@code idoc:} prefix
     * @return the matching goal, or empty if none
     */
    public static Optional<IdocGoal> byName(String goalName) {
        for (IdocGoal g : values()) {
            if (g.goalName.equals(goalName)) return Optional.of(g);
        }
        return Optional.empty();
    }
}

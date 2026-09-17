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
    /** {@code idoc:ledger} — write the document ledger from topic headers. */
    LEDGER("ledger", LedgerMojo.class,
            "Read the :topic-*: header of every AsciiDoc file under the "
                    + "module's source root and write target/doc-ledger.yaml: "
                    + "the storage structure and header metadata of the corpus, "
                    + "shaped like topic-registry.yaml."),
    /** {@code idoc:lint-site} — lint src/site/site.xml for IKE theme/breadcrumb drift (#319). */
    LINT_SITE("lint-site", LintSiteMojo.class,
            "Lint src/site/site.xml for IKE Network theme + "
                    + "breadcrumb conventions: <bodyClass> drift "
                    + "(Forest vs Horizon), stale breadcrumb names "
                    + "(IKE Pipeline post-#216), and skin GAV drift."),
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
            "Scan renderer logs for warnings and errors.");

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

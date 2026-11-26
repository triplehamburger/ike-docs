package network.ike.docs.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ingest a source PDF: run the three stages in order from one set of
 * parameters and stop at the first one that fails.
 *
 * <pre>
 *   idoc:ingest
 *   ├─ convert  idoc:ingest-convert  the converter, then the fidelity check
 *   ├─ split    idoc:ingest-split    fragments and an assembly, then the coverage check
 *   └─ build    idoc:ingest-build    place in a DeX project, registry check, render
 * </pre>
 *
 * <p>The {@code build} stage runs only when {@code ike.ingest.project}, the DeX
 * project folder, is set. {@code ike.ingest.stages} runs some stages alone;
 * each stage's own goal can also be run on its own and takes the settings
 * listed in its documentation, which this goal passes on.
 *
 * <p>Everything is written under {@code ike.ingest.outputDir}: the converted
 * {@code K.adoc} and {@code convert.yaml}, the {@code fragments/} folder with
 * {@code fragments.yaml} and {@code coverage.yaml}, and the build step's
 * {@code placed.yaml}, {@code topic-registry.yaml} and Maven logs.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest -N -Dike.ingest.source=K230440.pdf -Dike.ingest.project=../dex
 * </pre>
 *
 * @since 145
 */
@Mojo(name = "ingest")
public class IngestMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /** The AsciiDoc file the convert stage writes and the split stage reads; unset, {@code K.adoc} in the output directory. */
    @Parameter(property = "ike.ingest.adoc")
    File adoc;

    /** Where every report, the converted file and the fragments are written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** The DeX project to place into and render; unset, the build stage does not run. */
    @Parameter(property = "ike.ingest.project")
    File project;

    /** The assembly module to create in the project; unset, it is named from the assembly file. */
    @Parameter(property = "ike.ingest.assembly")
    String assembly;

    /** The stages to run, comma-separated, from {@code convert}, {@code split} and {@code build}; unset, all that apply. */
    @Parameter(property = "ike.ingest.stages")
    String stages;

    /** The converter command, run as {@code COMMAND PDF -o ADOC}. */
    @Parameter(property = "ike.convert.command", defaultValue = "ike-convert")
    String convertCommand;

    /** The fidelity check command, run as {@code COMMAND PDF ADOC}. */
    @Parameter(property = "ike.convert.fidelityCommand", defaultValue = "ike-fidelity")
    String fidelityCommand;

    /** Whether to run the fidelity check after converting. */
    @Parameter(property = "ike.convert.fidelity", defaultValue = "true")
    boolean fidelity;

    /** Whether a failed fidelity check stops the run; false makes it a warning. */
    @Parameter(property = "ike.convert.strictFidelity", defaultValue = "false")
    boolean strictFidelity;

    /** The citation URL to record in the fragments. */
    @Parameter(property = "ike.fragments.sourceUrl")
    String sourceUrl;

    /** The heading level that starts a fragment, from 1 to 5; 0 chooses it. */
    @Parameter(property = "ike.fragments.sectionLevel", defaultValue = "0")
    int sectionLevel;

    /** The id prefix for a document with no 510(k) number or device identifier. */
    @Parameter(property = "ike.fragments.documentId")
    String documentId;

    /** The character count above which a section is split at its subsections. */
    @Parameter(property = "ike.fragments.sizeLimit", defaultValue = "5000")
    int sizeLimit;

    /** Whether to split an oversize section. */
    @Parameter(property = "ike.fragments.split", defaultValue = "true")
    boolean split;

    /**
     * Directories of existing topics that fragment ids are checked against,
     * comma-separated; unset, the project's topics source folder when a project
     * is set, and no check otherwise.
     */
    @Parameter(property = "ike.fragments.existingRoots")
    String existingRoots;

    /** The share of the PDF's words the fragments may lack, from 0 to 1. */
    @Parameter(property = "ike.ingest.maxMissing", defaultValue = "0.10")
    double maxMissing;

    /** Report what the build stage would place and write nothing there. */
    @Parameter(property = "ike.build.dryRun", defaultValue = "false")
    boolean dryRun;

    /** Place into a DeX project that has uncommitted changes; they are listed as a warning instead of stopping the build stage. */
    @Parameter(property = "ike.build.allowDirty", defaultValue = "false")
    boolean allowDirty;

    /** The Maven command for the build stage's child runs; unset, the running Maven's launcher. */
    @Parameter(property = "ike.build.mavenCommand")
    String mavenCommand;

    /** Extra arguments for the build stage's child Maven runs. */
    @Parameter(property = "ike.build.mavenArgs")
    String mavenArgs;

    /** The plugin whose {@code topic-registry} goal the build stage runs. */
    @Parameter(property = "ike.build.plugin", defaultValue = "network.ike.docs:ike-doc-maven-plugin")
    String plugin;

    /** The minutes each external command may run before it is killed. */
    @Parameter(property = "ike.ingest.timeoutMinutes", defaultValue = "30")
    int timeoutMinutes;

    /** The stages, in run order. */
    static final List<String> STAGES = List.of("convert", "split", "build");

    /** Creates this goal instance. */
    public IngestMojo() {}

    @Override
    public void execute() throws MojoException {
        List<String> toRun = stagesToRun(stages, project != null);
        Path out = outputDir.getAbsoluteFile().toPath();
        Path adocFile = adoc != null ? adoc.getAbsoluteFile().toPath() : out.resolve(stem() + ".adoc");
        Path fragments = out.resolve("fragments");
        if (toRun.contains("build") && project == null) {
            throw new MojoException("idoc:ingest needs ike.ingest.project, the DeX project folder, to run the "
                    + "build stage");
        }
        for (String stage : toRun) {
            log.info("idoc:ingest: stage " + stage);
            try {
                switch (stage) {
                    case "convert" -> convert(adocFile, out);
                    case "split" -> split(adocFile, fragments);
                    case "build" -> build(fragments, out);
                    default -> throw new MojoException("idoc:ingest: unknown stage " + stage);
                }
            } catch (MojoException e) {
                throw new MojoException("idoc:ingest stopped at the " + stage + " stage: " + e.getMessage(), e);
            }
        }
        log.info("idoc:ingest: " + String.join(", ", toRun) + " passed; reports under " + out);
    }

    /**
     * The stages to run for a {@code stages} value, in run order whatever order
     * they were named in.
     *
     * @param stages     the requested stages, comma-separated, or null or blank for all that apply
     * @param hasProject whether a DeX project is set; without one the build stage is left out by default
     * @return the stages in run order
     * @throws MojoException when a name is not a stage
     */
    static List<String> stagesToRun(String stages, boolean hasProject) throws MojoException {
        if (stages == null || stages.isBlank()) {
            return hasProject ? STAGES : STAGES.subList(0, 2);
        }
        List<String> named = new ArrayList<>();
        for (String part : stages.split(",")) {
            String stage = part.strip().toLowerCase(Locale.ROOT);
            if (!STAGES.contains(stage)) {
                throw new MojoException("idoc:ingest: ike.ingest.stages must name stages from " + STAGES
                        + ", not '" + part.strip() + "'");
            }
            named.add(stage);
        }
        return STAGES.stream().filter(named::contains).toList();
    }

    private String stem() {
        return source.getName().replaceFirst("\\.[^.]*$", "");
    }

    private void convert(Path adocFile, Path out) throws MojoException {
        IngestConvertMojo mojo = new IngestConvertMojo();
        mojo.log = log;
        mojo.source = source;
        mojo.adoc = adocFile.toFile();
        mojo.outputDir = out.toFile();
        mojo.command = convertCommand;
        mojo.fidelityCommand = fidelityCommand;
        mojo.fidelity = fidelity;
        mojo.strictFidelity = strictFidelity;
        mojo.timeoutMinutes = timeoutMinutes;
        mojo.execute();
    }

    private void split(Path adocFile, Path fragments) throws MojoException {
        IngestSplitMojo mojo = new IngestSplitMojo();
        mojo.log = log;
        mojo.source = adocFile.toFile();
        mojo.pdf = source;
        mojo.outputDir = fragments.toFile();
        mojo.sourceUrl = sourceUrl;
        mojo.sectionLevel = sectionLevel;
        mojo.documentId = documentId;
        mojo.sizeLimit = sizeLimit;
        mojo.split = split;
        mojo.existingRoots = existingRoots != null ? existingRoots : project == null ? null
                : project.getAbsoluteFile().toPath().resolve("topics/src/docs/asciidoc").toString();
        mojo.maxMissing = maxMissing;
        mojo.execute();
    }

    private void build(Path fragments, Path out) throws MojoException {
        IngestBuildMojo mojo = new IngestBuildMojo();
        mojo.log = log;
        mojo.fragments = fragments.toFile();
        mojo.projectDir = project;
        mojo.module = assembly;
        mojo.topicsAttribute = "{generated}/topics-asciidoc";
        mojo.dryRun = dryRun;
        mojo.allowDirty = allowDirty;
        mojo.outputDir = out.toFile();
        mojo.mavenCommand = mavenCommand;
        mojo.mavenArgs = mavenArgs;
        mojo.plugin = plugin;
        mojo.timeoutMinutes = timeoutMinutes;
        mojo.execute();
    }
}

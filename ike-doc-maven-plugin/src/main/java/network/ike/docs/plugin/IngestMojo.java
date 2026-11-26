package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestHash;
import network.ike.docs.ingest.Manifest;
import network.ike.docs.ingest.ManifestRun;
import network.ike.docs.ingest.SourceKind;
import network.ike.docs.ingest.ToolIdentity;
import network.ike.docs.ingest.ToolRunner;
import network.ike.docs.ingest.ToolTrustPolicy;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
 * <p>{@code ingest.yaml} in the output directory records the run: the source
 * with its hash and, for each stage, how it ended, the hash of every input
 * and output, the tool that ran and the parameters that matter. A stage is
 * skipped when it ended well before and its inputs, tool and parameters are
 * unchanged and its outputs still match, so a second run of the same
 * document does nothing and leaves every file as it was.
 * {@code ike.ingest.force=true} runs every stage again. A manifest for
 * another document, or another version of it, is replaced.
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

    /** Run every stage again even when its inputs, tool and parameters are unchanged. */
    @Parameter(property = "ike.ingest.force", defaultValue = "false")
    boolean force;

    /** The minutes each external command may run before it is killed. */
    @Parameter(property = "ike.ingest.timeoutMinutes", defaultValue = "30")
    int timeoutMinutes;

    /** The run record, in the output directory. */
    static final String MANIFEST = "ingest.yaml";

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
        Manifest manifest = openManifest(toRun, out);
        for (String stage : toRun) {
            try {
                manifest = runStage(stage, manifest, out, adocFile, fragments);
            } catch (MojoException e) {
                throw new MojoException("idoc:ingest stopped at the " + stage + " stage: " + e.getMessage(), e);
            }
        }
        log.info("idoc:ingest: " + String.join(", ", toRun) + " passed; reports under " + out);
    }

    /** The manifest to continue from; checks that the source is a PDF when the convert stage will run. */
    private Manifest openManifest(List<String> toRun, Path out) throws MojoException {
        try {
            SourceKind.Detection detection = SourceKind.detect(source.toPath());
            if (toRun.contains("convert") && !detection.usable()) {
                String why = detection.findings().stream().filter(Finding::isError).map(Finding::message)
                        .findFirst().orElse("not a supported source");
                throw new MojoException("idoc:ingest cannot ingest " + source + ": " + why);
            }
            SourceKind kind = detection.kind() == null ? SourceKind.PDF : detection.kind();
            return ManifestRun.open(out.resolve(MANIFEST), new Manifest.Source(source.getName(),
                    IngestHash.sha256Hex(source.toPath()), kind));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest cannot read the source " + source + ": " + e.getMessage(), e);
        }
    }

    /** What decides whether a stage can be skipped, and where its recorded outputs are found. */
    private record StageKey(Map<String, String> inputs, ToolIdentity tool, Map<String, String> parameters,
                            Path outputRoot, List<Finding> findings, List<String> relaxedGates) {
    }

    /** Run one stage unless the manifest shows it is up to date; record how it ended. */
    private Manifest runStage(String stage, Manifest manifest, Path out, Path adocFile, Path fragments)
            throws MojoException {
        StageKey key;
        try {
            key = keyFor(stage, out, adocFile, fragments);
            boolean never = force || dryRun && stage.equals("build");
            if (!never && manifest.canSkip(stage, key.inputs(), key.tool(), key.parameters(), key.outputRoot())) {
                log.info("idoc:ingest: stage " + stage + " skipped: inputs, tool and parameters are unchanged "
                        + "and its outputs match " + MANIFEST);
                return manifest;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new MojoException("idoc:ingest cannot prepare the " + stage + " stage: " + e.getMessage(), e);
        }
        log.info("idoc:ingest: stage " + stage);
        IngestBuildMojo built = null;
        try {
            switch (stage) {
                case "convert" -> convert(adocFile, out);
                case "split" -> split(adocFile, fragments);
                case "build" -> built = build(fragments, out);
                default -> throw new MojoException("idoc:ingest: unknown stage " + stage);
            }
        } catch (MojoException e) {
            record(manifest, stage, new Manifest.Stage(Manifest.Status.FAILED, key.inputs(), Map.of(), key.tool(),
                    key.parameters(), withFinding(key.findings(), Finding.error("stage-failed", stage,
                    String.valueOf(e.getMessage()))), key.relaxedGates()), out);
            throw e;
        }
        if (stage.equals("build") && dryRun) {
            return manifest;
        }
        try {
            Map<String, String> outputs = new TreeMap<>();
            switch (stage) {
                case "convert" -> {
                    outputs.putAll(ManifestRun.hashFiles(out, List.of(adocFile, out.resolve("convert.yaml"))));
                    outputs.putAll(ManifestRun.hashTree(out, adocFile.toAbsolutePath().getParent().resolve("images")));
                }
                case "split" -> outputs.putAll(ManifestRun.hashTree(out, fragments));
                default -> outputs.putAll(ManifestRun.hashFiles(project.getAbsoluteFile().toPath(),
                        built.placedFiles));
            }
            return record(manifest, stage, new Manifest.Stage(Manifest.Status.OK, key.inputs(), outputs, key.tool(),
                    key.parameters(), key.findings(), key.relaxedGates()), out);
        } catch (IOException e) {
            throw new MojoException("idoc:ingest cannot record the " + stage + " stage in " + MANIFEST + ": "
                    + e.getMessage(), e);
        }
    }

    private static List<Finding> withFinding(List<Finding> findings, Finding extra) {
        List<Finding> all = new ArrayList<>(findings);
        all.add(extra);
        return all;
    }

    private Manifest record(Manifest manifest, String stage, Manifest.Stage result, Path out) throws MojoException {
        Manifest changed = manifest.withStage(stage, result);
        try {
            changed.write(out.resolve(MANIFEST));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest cannot write " + out.resolve(MANIFEST) + ": " + e.getMessage(), e);
        }
        return changed;
    }

    /** The inputs, tool and parameters of a stage as they are now. */
    private StageKey keyFor(String stage, Path out, Path adocFile, Path fragments) throws IOException {
        Map<String, String> inputs = new TreeMap<>();
        Map<String, String> parameters = new TreeMap<>();
        ToolIdentity tool = null;
        List<Finding> findings = new ArrayList<>();
        List<String> relaxed = List.of();
        Path outputRoot = out;
        switch (stage) {
            case "convert" -> {
                inputs.put(source.getName(), IngestHash.sha256Hex(source.toPath()));
                tool = ToolIdentity.resolve(ToolRunner.split(convertCommand), null, null, null);
                findings.addAll(ToolTrustPolicy.tier0().decide(tool).findings());
                put(parameters, "fidelity", fidelity);
                put(parameters, "strictFidelity", strictFidelity);
                if (fidelity) {
                    ToolIdentity check = ToolIdentity.resolve(ToolRunner.split(fidelityCommand), null, null, null);
                    findings.addAll(ToolTrustPolicy.tier0().decide(check).findings());
                    put(parameters, "fidelityCommand", fidelityCommand);
                    put(parameters, "fidelityProgramSha256", check.programSha256());
                    put(parameters, "fidelityScriptSha256", check.scriptSha256());
                    relaxed = strictFidelity ? List.of() : List.of("fidelity");
                }
            }
            case "split" -> {
                inputs.put(adocFile.getFileName().toString(), IngestHash.sha256Hex(adocFile));
                inputs.put(source.getName(), IngestHash.sha256Hex(source.toPath()));
                put(parameters, "sourceUrl", sourceUrl);
                put(parameters, "sectionLevel", sectionLevel);
                put(parameters, "documentId", documentId);
                put(parameters, "sizeLimit", sizeLimit);
                put(parameters, "split", split);
                put(parameters, "maxMissing", maxMissing);
                put(parameters, "existingRoots", existingRoots != null ? existingRoots : project == null ? null
                        : project.getAbsoluteFile().toPath().resolve("topics/src/docs/asciidoc").toString());
            }
            default -> {
                inputs.putAll(ManifestRun.hashTree(out, fragments));
                outputRoot = project.getAbsoluteFile().toPath();
                put(parameters, "project", outputRoot.toString());
                put(parameters, "assembly", assembly);
                put(parameters, "plugin", plugin);
                put(parameters, "mavenCommand", mavenCommand);
                put(parameters, "mavenArgs", mavenArgs);
            }
        }
        return new StageKey(inputs, tool, parameters, outputRoot, findings, relaxed);
    }

    private static void put(Map<String, String> parameters, String name, Object value) {
        if (value != null) {
            parameters.put(name, String.valueOf(value));
        }
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

    private IngestBuildMojo build(Path fragments, Path out) throws MojoException {
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
        return mojo;
    }
}

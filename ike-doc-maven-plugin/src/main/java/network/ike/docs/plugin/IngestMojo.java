package network.ike.docs.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Run the checks of an ingestion in order: {@code idoc:ingest-detect} on the
 * source PDF, {@code idoc:ingest-fragments} on the AsciiDoc file converted from
 * it, then {@code idoc:ingest-coverage} comparing the fragments with the PDF.
 * The three goals stay public and unchanged; this goal only sets their
 * parameters from one set and stops at the first that fails.
 *
 * <p>Everything is written under {@code ike.ingest.outputDir}:
 * {@code detect.yaml}, {@code fragments/} (the fragments, the assembly and
 * {@code fragments.yaml}) and {@code coverage.yaml}. Converting the PDF to
 * AsciiDoc is not part of this goal; it is done first by the converter
 * ({@code ike-convert}).
 *
 * <p>Set {@code ike.ingest.only} to {@code detect}, {@code fragments} or
 * {@code coverage} to run one step with the same parameters.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest -Dike.ingest.source=K230440.pdf -Dike.ingest.adoc=K230440.adoc
 * </pre>
 *
 * @since 145
 */
@Mojo(name = "ingest")
public class IngestMojo implements org.apache.maven.api.plugin.Mojo {

    /** The steps, in the order they run. */
    static final List<String> STEPS = List.of("detect", "fragments", "coverage");

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /** The single AsciiDoc file converted from the PDF; not needed with {@code only=detect}. */
    @Parameter(property = "ike.ingest.adoc")
    File adoc;

    /** Where {@code detect.yaml}, {@code fragments/} and {@code coverage.yaml} are written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** Run one step only: {@code detect}, {@code fragments} or {@code coverage}. Unset runs all three. */
    @Parameter(property = "ike.ingest.only")
    String only;

    /** The citation URL to record. When unset it is derived from the K-number and flagged as unchecked. */
    @Parameter(property = "ike.ingest.sourceUrl")
    String sourceUrl;

    /** The heading level that starts a fragment; 0 chooses it from the document. */
    @Parameter(property = "ike.fragments.sectionLevel", defaultValue = "0")
    int sectionLevel;

    /** The id prefix for a document with no 510(k) number or device identifier. */
    @Parameter(property = "ike.fragments.documentId")
    String documentId;

    /** The character count above which a section is split. */
    @Parameter(property = "ike.fragments.sizeLimit", defaultValue = "5000")
    int sizeLimit;

    /** Whether to split an oversize section. */
    @Parameter(property = "ike.fragments.split", defaultValue = "true")
    boolean split;

    /** Directories of existing topics, comma-separated, that fragment ids are checked against. */
    @Parameter(property = "ike.fragments.existingRoots", defaultValue = "${project.basedir}/src/docs/asciidoc")
    String existingRoots;

    /** The share of the PDF's words the fragments may lack, from 0 to 1. */
    @Parameter(property = "ike.ingest.maxMissing", defaultValue = "0.10")
    double maxMissing;

    /** Creates this goal instance. */
    public IngestMojo() {}

    @Override
    public void execute() throws MojoException {
        List<String> steps = stepsToRun(only);
        if (steps.contains("fragments") && adoc == null) {
            throw new MojoException("idoc:ingest needs ike.ingest.adoc, the AsciiDoc file converted from the PDF, "
                    + "to run the fragments and coverage steps; convert the PDF first (ike-convert)");
        }
        Path out = outputDir.getAbsoluteFile().toPath();
        Path fragments = out.resolve("fragments");
        for (String step : steps) {
            log.info("idoc:ingest: step " + step);
            switch (step) {
                case "detect" -> detect(out);
                case "fragments" -> fragments(fragments);
                case "coverage" -> coverage(out, fragments);
                default -> throw new MojoException("idoc:ingest: unknown step " + step);
            }
        }
        log.info("idoc:ingest: " + String.join(", ", steps) + " passed; reports under " + out);
    }

    /**
     * The steps to run for an {@code only} value.
     *
     * @param only the requested single step, or null or blank for all
     * @return the steps in run order
     * @throws MojoException when {@code only} names no step
     */
    static List<String> stepsToRun(String only) throws MojoException {
        if (only == null || only.isBlank()) {
            return STEPS;
        }
        String step = only.strip().toLowerCase(Locale.ROOT);
        if (!STEPS.contains(step)) {
            throw new MojoException("idoc:ingest: ike.ingest.only must be one of " + STEPS + ", not '" + only + "'");
        }
        return List.of(step);
    }

    private void detect(Path out) throws MojoException {
        IngestDetectMojo mojo = new IngestDetectMojo();
        mojo.log = log;
        mojo.source = source;
        mojo.sourceUrl = sourceUrl;
        mojo.outputDir = out.toFile();
        mojo.execute();
    }

    private void fragments(Path fragments) throws MojoException {
        IngestFragmentsMojo mojo = new IngestFragmentsMojo();
        mojo.log = log;
        mojo.source = adoc;
        mojo.outputDir = fragments.toFile();
        mojo.sourceUrl = sourceUrl;
        mojo.sectionLevel = sectionLevel;
        mojo.documentId = documentId;
        mojo.sizeLimit = sizeLimit;
        mojo.split = split;
        mojo.existingRoots = existingRoots;
        mojo.execute();
    }

    private void coverage(Path out, Path fragments) throws MojoException {
        IngestCoverageMojo mojo = new IngestCoverageMojo();
        mojo.log = log;
        mojo.source = source;
        mojo.topics = fragments.resolve("topics").toFile();
        mojo.assembly = fragments.resolve("assembly").toFile();
        mojo.maxMissing = maxMissing;
        mojo.outputDir = out.toFile();
        mojo.execute();
    }
}

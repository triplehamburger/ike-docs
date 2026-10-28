package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestDetect;
import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.ingest.NeedsAiFile;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classify a source PDF as an FDA 510(k) Substantial Equivalence
 * Determination Decision Summary (a DeX source) or not, without touching the
 * project. Writes {@code detect.yaml} (a report of what was found and every
 * problem met) and, when work remains for an AI, {@code needs-ai.yaml}.
 *
 * <p>A PDF with a text layer is classified directly from its text. A scanned
 * PDF goes through a short AI transcription step: page 1 is rendered to an
 * image and listed as a work order; if the transcript in {@code answers.yaml}
 * shows a decision summary, the remaining pages are requested, and once every
 * page is transcribed detection continues exactly as for a text PDF.
 *
 * <p>The goal fails the build only when the report holds an error: a missing,
 * corrupt, encrypted or non-PDF source, a decision summary whose sections do
 * not add up, a K-number that disagrees with the page footers, or an answer
 * that was rejected. A pending transcription is a warning, not an error.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-detect -Dike.ingest.source=K230440.pdf
 * </pre>
 *
 * <p>Relative paths resolve against the directory Maven was started in.
 *
 * @since 124
 */
@Mojo(name = "ingest-detect")
public class IngestDetectMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF to classify. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /**
     * The citation URL to record for a decision summary. When unset it is
     * derived from the K-number and flagged as unverified.
     */
    @Parameter(property = "ike.ingest.sourceUrl")
    String sourceUrl;

    /** Treat the PDF as scanned even if it has a text layer. */
    @Parameter(property = "ike.ingest.forceScan", defaultValue = "false")
    boolean forceScan;

    /** Where {@code detect.yaml}, {@code needs-ai.yaml} and the page images are written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** The answers to earlier work orders. Defaults to {@code answers.yaml} in the output directory. */
    @Parameter(property = "ike.ingest.answers")
    File answers;

    /** Creates this goal instance. */
    public IngestDetectMojo() {}

    @Override
    public void execute() throws MojoException {
        Path out = outputDir.getAbsoluteFile().toPath();
        Path answersFile = answers == null ? out.resolve("answers.yaml") : answers.getAbsoluteFile().toPath();
        Path report = out.resolve("detect.yaml");
        Path needsAi = out.resolve("needs-ai.yaml");

        IngestDetect.Result result = IngestDetect.run(source.getAbsoluteFile().toPath(),
                new IngestDetect.Options(out.resolve("pages"), forceScan, sourceUrl), readAnswers(answersFile));

        try {
            IngestYaml.writeAtomic(report, IngestYaml.reportToYaml(result.report()));
            if (result.needsAi().isPresent()) {
                NeedsAiFile file = result.needsAi().get();
                IngestYaml.writeAtomic(needsAi, IngestYaml.needsAiToYaml(file));
            } else {
                Files.deleteIfExists(needsAi);
            }
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-detect could not write its output under " + out + ": "
                    + e.getMessage(), e);
        }

        summarize(result);
        if (result.report().hasErrors()) {
            long errors = result.report().findings().stream().filter(Finding::isError).count();
            throw new MojoException("idoc:ingest-detect found " + errors + " error(s); see " + report);
        }
        if (result.needsAi().isPresent()) {
            log.warn("idoc:ingest-detect: " + result.needsAi().get().items().size()
                    + " page transcript(s) needed; answer " + needsAi + " into " + answersFile
                    + ", then run the goal again");
        }
    }

    private String readAnswers(Path file) throws MojoException {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-detect cannot read the answers file " + file + ": "
                    + e.getMessage(), e);
        }
    }

    private void summarize(IngestDetect.Result result) {
        var details = result.report().details();
        log.info("idoc:ingest-detect: " + details.get("kind")
                + (details.containsKey("layout") ? ", layout " + details.get("layout") : "")
                + (details.containsKey("template") ? ", template " + details.get("template") : "")
                + (details.containsKey("k-number") ? ", " + details.get("k-number") : "")
                + (details.containsKey("guess") ? ", looks like " + details.get("guess") : ""));
        for (Finding f : result.report().findings()) {
            String line = "[" + f.check() + "] " + f.component() + ": " + f.message();
            switch (f.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                case INFO -> log.info(line);
            }
        }
    }
}

package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestDetect;
import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.ingest.IngestReport;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classify a source PDF as an FDA 510(k) Substantial Equivalence
 * Determination Decision Summary (a DeX source) or not, without touching the
 * project. Writes {@code detect.yaml}, a report of what was found and every
 * problem met.
 *
 * <p>A PDF with a text layer is classified directly from its text. A scanned
 * PDF is reported as an error: transcribing scans is not supported yet.
 *
 * <p>The goal fails the build only when the report holds an error: a missing,
 * corrupt, encrypted, scanned or non-PDF source, a decision summary whose
 * sections do not add up, or a K-number that disagrees with the page footers.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-detect -Dike.ingest.source=K230440.pdf
 * </pre>
 *
 * <p>Relative paths resolve against the directory Maven was started in.
 *
 * <p>When the source is a directory, every {@code .pdf} directly in it is
 * detected in turn, each writing to its own subfolder of the output directory
 * (named after the PDF). A PDF with errors does not stop the others; the goal
 * fails at the end naming them.
 *
 * @since 124
 */
@Mojo(name = "ingest-detect")
public class IngestDetectMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF to classify, or a directory of PDFs to classify one by one. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /**
     * The citation URL to record for a decision summary. When unset it is
     * derived from the K-number and flagged as unverified.
     */
    @Parameter(property = "ike.ingest.sourceUrl")
    String sourceUrl;

    /** Where {@code detect.yaml} is written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** Creates this goal instance. */
    public IngestDetectMojo() {}

    @Override
    public void execute() throws MojoException {
        Path src = source.getAbsoluteFile().toPath();
        if (!Files.isDirectory(src)) {
            Path out = outputDir.getAbsoluteFile().toPath();
            long errors = detect(src, out);
            if (errors > 0) {
                throw new MojoException("idoc:ingest-detect found " + errors + " error(s); see "
                        + out.resolve("detect.yaml"));
            }
            return;
        }
        java.util.List<Path> pdfs;
        try (var list = Files.list(src)) {
            pdfs = list.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".pdf")).sorted().toList();
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-detect cannot list " + src + ": " + e.getMessage(), e);
        }
        if (pdfs.isEmpty()) {
            throw new MojoException("idoc:ingest-detect found no .pdf files in " + src);
        }
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (Path pdf : pdfs) {
            String name = pdf.getFileName().toString();
            Path out = outputDir.getAbsoluteFile().toPath().resolve(name.substring(0, name.length() - 4));
            log.info("idoc:ingest-detect: " + name + " -> " + out);
            if (detect(pdf, out) > 0) {
                failed.add(name);
            }
        }
        if (!failed.isEmpty()) {
            throw new MojoException("idoc:ingest-detect found errors in " + failed.size() + " of " + pdfs.size()
                    + " PDF(s): " + String.join(", ", failed));
        }
    }

    /** Detects one PDF and writes its report; returns how many errors the report holds. */
    private long detect(Path pdf, Path out) throws MojoException {
        IngestReport result = IngestDetect.run(pdf, sourceUrl);
        try {
            IngestYaml.writeAtomic(out.resolve("detect.yaml"), IngestYaml.reportToYaml(result));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-detect could not write its output under " + out + ": "
                    + e.getMessage(), e);
        }
        summarize(result);
        return result.findings().stream().filter(Finding::isError).count();
    }

    private void summarize(IngestReport result) {
        var details = result.details();
        log.info("idoc:ingest-detect: " + details.get("kind")
                + (details.containsKey("layout") ? ", layout " + details.get("layout") : "")
                + (details.containsKey("template") ? ", template " + details.get("template") : "")
                + (details.containsKey("k-number") ? ", " + details.get("k-number") : "")
                + (details.containsKey("guess") ? ", looks like " + details.get("guess") : ""));
        for (Finding f : result.findings()) {
            String line = "[" + f.check() + "] " + f.component() + ": " + f.message();
            switch (f.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                case INFO -> log.info(line);
            }
        }
    }
}

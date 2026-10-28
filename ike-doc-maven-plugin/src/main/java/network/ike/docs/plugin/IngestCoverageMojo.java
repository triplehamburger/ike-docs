package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestCoverage;
import network.ike.docs.ingest.IngestYaml;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Check that the topics written from a source PDF still hold what the PDF
 * says. Every word and number of the PDF text is counted and the words the
 * topics lack are reported, page by page, so a dropped section shows as a
 * run of weak pages. A 510(k) decision summary is also checked section by
 * section. Writes {@code coverage.yaml}.
 *
 * <p>The goal fails the build when the topics lack more of the PDF's words
 * than {@code ike.ingest.maxMissing} allows, when a decision-summary
 * section title is absent from the topics, or when the PDF is scanned (there
 * is no text to compare; run {@code idoc:ingest-detect} first) or unreadable.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-coverage -Dike.ingest.source=K230440.pdf -Dike.ingest.topics=topics
 * </pre>
 *
 * @since 136
 */
@Mojo(name = "ingest-coverage")
public class IngestCoverageMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF the topics were written from. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /** The folder holding the topic {@code .adoc} files, searched recursively. */
    @Parameter(property = "ike.ingest.topics", required = true)
    File topics;

    /** The share of the PDF's words the topics may lack, from 0 to 1. */
    @Parameter(property = "ike.ingest.maxMissing", defaultValue = "0.10")
    double maxMissing;

    /** Where {@code coverage.yaml} is written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** Creates this goal instance. */
    public IngestCoverageMojo() {}

    @Override
    public void execute() throws MojoException {
        Path out = outputDir.getAbsoluteFile().toPath();
        Path report = out.resolve("coverage.yaml");
        IngestCoverage.Result result = IngestCoverage.run(source.getAbsoluteFile().toPath(),
                topics.getAbsoluteFile().toPath(), maxMissing);
        try {
            IngestYaml.writeAtomic(report, IngestYaml.reportToYaml(result.report()));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-coverage could not write " + report + ": " + e.getMessage(), e);
        }
        var details = result.report().details();
        if (details.containsKey("source-words")) {
            log.info("idoc:ingest-coverage: " + details.get("missing-words") + " of " + details.get("source-words")
                    + " words missing (" + details.get("missing-share") + "), "
                    + ((java.util.List<?>) details.get("weak-pages")).size() + " weak page(s)");
        }
        for (Finding f : result.report().findings()) {
            String line = "[" + f.check() + "] " + f.component() + ": " + f.message();
            switch (f.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                case INFO -> log.info(line);
            }
        }
        if (result.report().hasErrors()) {
            long errors = result.report().findings().stream().filter(Finding::isError).count();
            throw new MojoException("idoc:ingest-coverage found " + errors + " error(s); see " + report);
        }
    }
}

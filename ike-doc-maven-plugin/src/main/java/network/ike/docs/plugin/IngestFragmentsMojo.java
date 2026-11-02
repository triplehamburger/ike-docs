package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestFragments;
import network.ike.docs.ingest.IngestYaml;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Split the single AsciiDoc file of a 510(k) decision summary into one
 * fragment per top-level section, and build the record as an assembly that
 * includes them in order. Writes {@code topics/dex/*.adoc}, the assembly
 * {@code assembly/DeXRecord_K######.adoc} and {@code fragments.yaml}.
 *
 * <p>Each fragment is verbatim source text with a generated header block and
 * the id {@code dex-{k-number}-{slug}}. A section over {@code ike.fragments.sizeLimit}
 * is split at its lettered subsections, and a subsection still over the limit
 * at its top-level numbered studies. The goal fails, and writes no fragment,
 * when the 510(k) number or the sections cannot be found, when two headings
 * give the same id, or when the split would change any word.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-fragments -Dike.fragments.source=K230440.adoc
 * </pre>
 *
 * @since 136
 */
@Mojo(name = "ingest-fragments")
public class IngestFragmentsMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The single AsciiDoc file produced from the decision summary PDF. */
    @Parameter(property = "ike.fragments.source", required = true)
    File source;

    /** Where the fragments, the assembly and {@code fragments.yaml} are written. */
    @Parameter(property = "ike.fragments.outputDir", defaultValue = "${project.build.directory}/fragments")
    File outputDir;

    /** The citation URL to record. When unset it is derived from the K-number and flagged as unchecked. */
    @Parameter(property = "ike.fragments.sourceUrl")
    String sourceUrl;

    /** The heading level that starts a fragment: 2 for the converted layout, from 1 to 5. */
    @Parameter(property = "ike.fragments.sectionLevel", defaultValue = "2")
    int sectionLevel;

    /** The character count above which a section is split at its subsections, then at its numbered studies. */
    @Parameter(property = "ike.fragments.sizeLimit", defaultValue = "5000")
    int sizeLimit;

    /** Whether to split an oversize section; false keeps one fragment per top-level section. */
    @Parameter(property = "ike.fragments.split", defaultValue = "true")
    boolean split;

    /** Creates this goal instance. */
    public IngestFragmentsMojo() {}

    @Override
    public void execute() throws MojoException {
        Path out = outputDir.getAbsoluteFile().toPath();
        Path report = out.resolve("fragments.yaml");
        IngestFragments.Result result = IngestFragments.run(source.getAbsoluteFile().toPath(),
                new IngestFragments.Options(sectionLevel, sourceUrl, sizeLimit, split));
        try {
            if (!result.report().hasErrors()) {
                for (IngestFragments.Fragment f : result.fragments()) {
                    IngestYaml.writeAtomic(out.resolve("topics").resolve("dex").resolve(f.fileName()), f.content());
                }
                IngestYaml.writeAtomic(out.resolve("assembly").resolve(result.assemblyName()), result.assembly());
            }
            IngestYaml.writeAtomic(report, IngestYaml.reportToYaml(result.report()));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-fragments could not write its output under " + out + ": "
                    + e.getMessage(), e);
        }
        if (!result.report().hasErrors()) {
            log.info("idoc:ingest-fragments: " + result.fragments().size() + " fragments and " + result.assemblyName()
                    + " written under " + out);
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
            throw new MojoException("idoc:ingest-fragments found " + errors + " error(s); see " + report);
        }
    }
}

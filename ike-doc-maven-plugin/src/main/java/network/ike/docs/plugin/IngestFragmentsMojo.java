package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestFragments;
import network.ike.docs.ingest.IngestReport;
import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.plugin.registry.TopicHeader;
import network.ike.docs.plugin.registry.TopicRegistry;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Split the single AsciiDoc file of a 510(k) decision summary, IFU or GUDID
 * record into fragments, and build the record as an assembly that includes
 * them in order. Writes {@code topics/dex/*.adoc}, the assembly under
 * {@code assembly/} and {@code fragments.yaml}.
 *
 * <p>Each fragment is verbatim source text with a generated header block and
 * the id {@code dex-{document}-{slug}}, where the document is the 510(k)
 * number, the GUDID primary DI number, {@code ike.fragments.documentId} or the
 * title. A section over {@code ike.fragments.sizeLimit} is split at its
 * lettered subsections, and a subsection still over the limit at its
 * top-level numbered studies. The goal fails, and writes no fragment, when no
 * document id or sections can be found, when a heading sits above the split
 * level, or when the split would change any word.
 *
 * <p>The goal also checks every fragment id against the topics already under
 * {@code ike.fragments.existingRoots} (by default the module's
 * {@code src/docs/asciidoc}), so a duplicate id is caught before the
 * fragments are placed there and not only by a later
 * {@code idoc:topic-registry}. A fragment whose id and file name match an
 * existing topic replaces it and is noted; a fragment whose id is declared by
 * a differently named file is an error and nothing is written.
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

    /** The heading level that starts a fragment, from 1 to 5; 0 chooses the lowest level with two or more headings. */
    @Parameter(property = "ike.fragments.sectionLevel", defaultValue = "0")
    int sectionLevel;

    /** The id prefix for a document with no 510(k) number or device identifier; unset, it is built from the title. */
    @Parameter(property = "ike.fragments.documentId")
    String documentId;

    /** The character count above which a section is split at its subsections, then at its numbered studies. */
    @Parameter(property = "ike.fragments.sizeLimit", defaultValue = "5000")
    int sizeLimit;

    /** Whether to split an oversize section; false keeps one fragment per top-level section. */
    @Parameter(property = "ike.fragments.split", defaultValue = "true")
    boolean split;

    /**
     * Directories of existing topics, comma-separated, that fragment ids are
     * checked against. A directory that does not exist is skipped with a note;
     * a blank value turns the check off.
     */
    @Parameter(property = "ike.fragments.existingRoots",
               defaultValue = "${project.basedir}/src/docs/asciidoc")
    String existingRoots;

    /** The most {@code .adoc} files one existing root may hold before the check fails. */
    private static final int MAX_EXISTING_FILES = 5000;

    /** Creates this goal instance. */
    public IngestFragmentsMojo() {}

    @Override
    public void execute() throws MojoException {
        Path out = outputDir.getAbsoluteFile().toPath();
        Path report = out.resolve("fragments.yaml");
        IngestFragments.Result result = IngestFragments.run(source.getAbsoluteFile().toPath(),
                new IngestFragments.Options(sectionLevel, sourceUrl, sizeLimit, split, documentId));
        IngestReport finalReport = checkExistingTopics(result);
        result = new IngestFragments.Result(finalReport, result.fragments(), result.assemblyName(),
                result.assembly());
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

    /**
     * Add the findings of the existing-topics check to the run's report. The
     * check is skipped when the run already failed or no existing root is set.
     */
    private IngestReport checkExistingTopics(IngestFragments.Result result) {
        IngestReport report = result.report();
        if (report.hasErrors() || existingRoots == null || existingRoots.isBlank()) {
            return report;
        }
        List<Finding> extra = new ArrayList<>();
        List<TopicRegistry.Scan> scans = new ArrayList<>();
        for (String r : existingRoots.split(",")) {
            if (r.isBlank()) {
                continue;
            }
            Path root = Path.of(r.strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                extra.add(Finding.info("existing-topics-skipped", root.toString(),
                        "no such directory, so fragment ids were not checked against existing topics; set "
                                + "-Dike.fragments.existingRoots to the topics to check"));
                continue;
            }
            try {
                scans.add(TopicRegistry.scan(root, MAX_EXISTING_FILES));
            } catch (IOException | IllegalStateException e) {
                extra.add(Finding.error("existing-topics-unreadable", root.toString(),
                        "cannot read the existing topics to check fragment ids against: " + e.getMessage()));
            }
        }
        extra.addAll(duplicateIds(result.fragments(), scans));
        if (extra.isEmpty()) {
            return report;
        }
        List<Finding> findings = new ArrayList<>(report.findings());
        findings.addAll(extra);
        Map<String, Object> details = new LinkedHashMap<>(report.details());
        details.put("existing-topics-checked", scans.stream().mapToInt(s -> s.topics().size()).sum());
        return new IngestReport(report.goal(), report.sources(), details, findings, report.plan());
    }

    /**
     * The findings for fragments whose id an existing topic already declares.
     * A fragment is written as {@code topics/dex/{fileName}}: when the topic
     * that holds the id has the same file name, the fragment replaces it (a
     * re-ingest) and is only counted; when the file name differs, placing the
     * fragment would give two files one id, which is an error.
     *
     * @param fragments the fragments about to be written
     * @param scans     the scans of the existing topic roots
     * @return the findings, empty when no fragment id is already taken
     */
    static List<Finding> duplicateIds(List<IngestFragments.Fragment> fragments, List<TopicRegistry.Scan> scans) {
        Map<String, String> declaredBy = new HashMap<>();
        for (TopicRegistry.Scan scan : scans) {
            for (TopicHeader topic : scan.topics()) {
                declaredBy.putIfAbsent(topic.id(), scan.root().getFileName() + "/" + topic.file());
            }
        }
        List<Finding> findings = new ArrayList<>();
        int replaced = 0;
        for (IngestFragments.Fragment fragment : fragments) {
            String existing = declaredBy.get(fragment.id());
            if (existing == null) {
                continue;
            }
            String existingName = existing.substring(existing.lastIndexOf('/') + 1);
            if (existingName.equals(fragment.fileName())) {
                replaced++;
            } else {
                findings.add(Finding.error("duplicate-id", fragment.id(),
                        "an existing topic already declares this id: " + existing + "; the fragment would be "
                                + "written as topics/dex/" + fragment.fileName() + " and give two files one id"));
            }
        }
        if (replaced > 0) {
            findings.add(Finding.info("existing-topics-replaced", "fragments", replaced
                    + " fragment(s) have the id and file name of an existing topic and replace it when placed"));
        }
        return findings;
    }
}

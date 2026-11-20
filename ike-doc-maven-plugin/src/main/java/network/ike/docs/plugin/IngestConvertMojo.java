package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestHash;
import network.ike.docs.ingest.IngestReport;
import network.ike.docs.ingest.IngestYaml;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convert a source PDF to one AsciiDoc file by running the converter, then
 * check the result against the PDF with the fidelity check. Stage 1 of the
 * ingestion.
 *
 * <p>The converter and the fidelity check are Python commands outside Maven
 * ({@code ike-convert} and {@code ike-fidelity}, set with
 * {@code ike.convert.command} and {@code ike.convert.fidelityCommand}; each may
 * carry arguments, such as {@code python utilities/document_to_asciidoc.py}).
 * They are started from an argument list, never through a shell, with a time
 * limit. The converter is called as {@code COMMAND PDF -o ADOC}, the fidelity
 * check as {@code COMMAND PDF ADOC}.
 *
 * <p>A failed fidelity check is a warning and the AsciiDoc is kept, because the
 * check is stricter than the next stage needs; set
 * {@code ike.convert.strictFidelity=true} to make it an error. The goal fails
 * when the converter cannot be started, exits non-zero or writes no file.
 * Writes {@code convert.yaml} beside the AsciiDoc file.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-convert -Dike.ingest.source=K230440.pdf
 * </pre>
 *
 * @since 145
 */
@Mojo(name = "ingest-convert")
public class IngestConvertMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The source PDF. */
    @Parameter(property = "ike.ingest.source", required = true)
    File source;

    /** The AsciiDoc file to write; unset, it is the PDF's name with {@code .adoc} under the output directory. */
    @Parameter(property = "ike.ingest.adoc")
    File adoc;

    /** Where the AsciiDoc file (unless {@code ike.ingest.adoc} says otherwise) and {@code convert.yaml} are written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** The converter command, run as {@code COMMAND PDF -o ADOC}. */
    @Parameter(property = "ike.convert.command", defaultValue = "ike-convert")
    String command;

    /** The fidelity check command, run as {@code COMMAND PDF ADOC}. */
    @Parameter(property = "ike.convert.fidelityCommand", defaultValue = "ike-fidelity")
    String fidelityCommand;

    /** Whether to run the fidelity check after converting. */
    @Parameter(property = "ike.convert.fidelity", defaultValue = "true")
    boolean fidelity;

    /** Whether a failed fidelity check fails the goal; false makes it a warning. */
    @Parameter(property = "ike.convert.strictFidelity", defaultValue = "false")
    boolean strictFidelity;

    /** The minutes each command may run before it is killed. */
    @Parameter(property = "ike.convert.timeoutMinutes", defaultValue = "30")
    int timeoutMinutes;

    /** Creates this goal instance. */
    public IngestConvertMojo() {}

    @Override
    public void execute() throws MojoException {
        Path pdf = source.getAbsoluteFile().toPath();
        Path out = outputDir.getAbsoluteFile().toPath();
        String stem = pdf.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        Path target = adoc != null ? adoc.getAbsoluteFile().toPath() : out.resolve(stem + ".adoc");
        Path reportFile = target.resolveSibling("convert.yaml");

        List<Finding> findings = new ArrayList<>();
        List<IngestReport.Source> sources = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        run(pdf, target, findings, sources, details);

        IngestReport report = new IngestReport("ingest-convert", sources, details, findings, List.of());
        try {
            IngestYaml.writeAtomic(reportFile, IngestYaml.reportToYaml(report));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-convert could not write " + reportFile + ": " + e.getMessage(), e);
        }
        for (Finding f : findings) {
            String line = "[" + f.check() + "] " + f.component() + ": " + f.message();
            switch (f.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                case INFO -> log.info(line);
            }
        }
        if (report.hasErrors()) {
            long errors = findings.stream().filter(Finding::isError).count();
            throw new MojoException("idoc:ingest-convert found " + errors + " error(s); see " + reportFile);
        }
        log.info("idoc:ingest-convert: " + target + " written");
    }

    private void run(Path pdf, Path target, List<Finding> findings, List<IngestReport.Source> sources,
                     Map<String, Object> details) {
        String name = pdf.getFileName().toString();
        if (!Files.isRegularFile(pdf)) {
            findings.add(Finding.error("source-missing", name, "the source PDF does not exist: " + pdf));
            return;
        }
        if (timeoutMinutes < 1) {
            findings.add(Finding.error("timeout-invalid", "options",
                    "ike.convert.timeoutMinutes must be at least 1, not " + timeoutMinutes));
            return;
        }
        try {
            sources.add(new IngestReport.Source(name, IngestHash.sha256Hex(pdf)));
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            findings.add(Finding.error("source-unreadable", name, "cannot read the PDF: " + e.getMessage()));
            return;
        }

        List<String> convert = new ArrayList<>(ToolRunner.split(command));
        convert.addAll(List.of(pdf.toString(), "-o", target.toString()));
        details.put("converter", String.join(" ", convert));
        ToolRunner.Result converted = launch("converter", "ike.convert.command", convert, findings);
        if (converted == null) {
            return;
        }
        details.put("converter-exit", converted.exitCode());
        if (converted.exitCode() != 0) {
            findings.add(Finding.error("convert-failed", name, "the converter " + describe(converted)
                    + "; last output: " + tail(converted.output())));
            return;
        }
        try {
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                findings.add(Finding.error("no-output", name, "the converter finished but wrote no AsciiDoc at "
                        + target));
                return;
            }
            details.put("adoc", target.toString());
            details.put("adoc-sha256", IngestHash.sha256Hex(target));
        } catch (IOException e) {
            findings.add(Finding.error("no-output", name, "cannot read the converted file: " + e.getMessage()));
            return;
        }

        if (!fidelity) {
            details.put("fidelity", "skipped");
            return;
        }
        List<String> check = new ArrayList<>(ToolRunner.split(fidelityCommand));
        check.addAll(List.of(pdf.toString(), target.toString()));
        details.put("fidelity-check", String.join(" ", check));
        ToolRunner.Result checked = launch("fidelity check", "ike.convert.fidelityCommand", check, findings);
        if (checked == null) {
            details.put("fidelity", "not run");
            return;
        }
        details.put("fidelity-exit", checked.exitCode());
        if (checked.exitCode() == 0) {
            details.put("fidelity", "passed");
            return;
        }
        details.put("fidelity", "failed");
        String message = "the fidelity check " + describe(checked) + (strictFidelity
                ? "" : "; the AsciiDoc is kept, review the report above") + "; last output: " + tail(checked.output());
        findings.add(strictFidelity ? Finding.error("fidelity-failed", name, message)
                : Finding.warning("fidelity-failed", name, message));
    }

    /** Run a command; null, with a finding added, when it cannot be started or is interrupted. */
    private ToolRunner.Result launch(String what, String property, List<String> argv, List<Finding> findings) {
        if (argv.isEmpty() || argv.get(0).isBlank()) {
            findings.add(Finding.error("tool-not-set", what, "no " + what + " command is set; set -D" + property));
            return null;
        }
        log.info("idoc:ingest-convert: running " + String.join(" ", argv));
        try {
            return ToolRunner.run(argv, null, Map.of("PYTHONUTF8", "1"), Duration.ofMinutes(timeoutMinutes),
                    line -> log.info("  " + line));
        } catch (IOException e) {
            findings.add(Finding.error("tool-not-found", what, "cannot start the " + what + " '" + argv.get(0)
                    + "': " + e.getMessage() + "; install it or set -D" + property));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            findings.add(Finding.error("interrupted", what, "the " + what + " was interrupted"));
        }
        return null;
    }

    private String describe(ToolRunner.Result r) {
        return r.timedOut() ? "ran longer than " + timeoutMinutes + " minute(s) and was stopped"
                : "exited with code " + r.exitCode();
    }

    /** The last few lines of a command's output, for a finding message. */
    private static String tail(String output) {
        String[] lines = output.strip().split("\\R");
        int from = Math.max(0, lines.length - 3);
        String text = String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
        return text.isBlank() ? "(none)" : text.length() > 400 ? text.substring(text.length() - 400) : text;
    }
}

package network.ike.docs.plugin;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The root `idoc:ingest` goal: stage selection, the settings it passes on to
/// each stage, and the stop at the first failing stage. External commands are
/// stood in for by small Java programs run as single-file sources.
class IngestMojoTest {

    private static final String FAKE_TOOL = """
            import java.nio.file.*;

            public class FakeTool {
                public static void main(String[] a) throws Exception {
                    switch (a[0]) {
                        case "convert" -> {
                            for (int i = 1; i < a.length - 1; i++) {
                                if (a[i].equals("-o")) {
                                    Files.writeString(Path.of(a[i + 1]), "= Title\\n\\nBody text.\\n");
                                    Files.writeString(Path.of(a[i + 1] + ".runs"), "x",
                                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                }
                            }
                        }
                        case "fail" -> { System.out.println("boom: no tags"); System.exit(3); }
                        case "pass" -> System.out.println("fidelity ok");
                        default -> System.exit(9);
                    }
                }
            }
            """;

    private static final String FAKE_MAVEN = """
            import java.nio.file.*;

            public class FakeMvn {
                public static void main(String[] a) throws Exception {
                    String line = System.getProperty("user.dir") + " >";
                    for (int i = 1; i < a.length; i++) {
                        line += " " + a[i];
                    }
                    Files.writeString(Path.of(a[0]), line + "\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (line.contains("rootFile")) {
                        for (int i = 1; i < a.length; i++) {
                            if (a[i].startsWith("-Dike.topic-registry.output=")) {
                                Path out = Path.of(a[i].substring("-Dike.topic-registry.output=".length()));
                                Files.createDirectories(out.getParent());
                                Files.writeString(out, "domains:\\n- id: dex\\n  topics:\\n  - id: dex-k1-one\\n");
                            }
                        }
                    }
                }
            }
            """;

    private static final String RECORD = """
            = DeXRecord_K999999

            == I Background Information:

            === A 510(k) Number

            K999999

            === B Applicant

            Acme Diagnostics

            == II Conclusion:

            The device is substantially equivalent.
            """;

    private static final String ASSEMBLIES_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0">
                <modelVersion>4.1.0</modelVersion>
                <parent>
                    <groupId>dev.example.dex</groupId>
                    <artifactId>root</artifactId>
                </parent>
                <artifactId>assemblies</artifactId>
                <packaging>pom</packaging>
                <subprojects>
                    <subproject>compendium</subproject>
                </subprojects>
            </project>
            """;

    @TempDir
    Path dir;

    private Path pdf;
    private String java;


    private final List<String> logged = new java.util.ArrayList<>();

    /** A log that keeps the info lines, to see which stages were skipped. */
    private Log recordingLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("info") && args != null && args[0] instanceof CharSequence text) {
                        logged.add(text.toString());
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    private long runs() throws IOException {
        Path file = dir.resolve("out/K999999.adoc.runs");
        return Files.isRegularFile(file) ? Files.readString(file).length() : 0;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void writePdf(Path file, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 9);
                cs.setLeading(11);
                cs.newLineAtOffset(30, 770);
                for (String line : text.split("\n")) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        pdf = dir.resolve("K999999.pdf");
        Files.write(pdf, "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        java = "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\"";
    }

    private String tool(String mode) throws IOException {
        Path source = dir.resolve("FakeTool.java");
        write(source, FAKE_TOOL);
        return java + " \"" + source + "\" " + mode;
    }

    private IngestMojo mojo() throws IOException {
        IngestMojo mojo = new IngestMojo();
        mojo.log = recordingLog();
        mojo.source = pdf.toFile();
        mojo.outputDir = dir.resolve("out").toFile();
        mojo.convertCommand = tool("convert");
        mojo.fidelityCommand = tool("pass");
        mojo.fidelity = true;
        mojo.sizeLimit = 5000;
        mojo.split = true;
        mojo.maxMissing = 0.10;
        mojo.plugin = "network.ike.docs:ike-doc-maven-plugin";
        mojo.timeoutMinutes = 5;
        return mojo;
    }

    @Test
    void withoutAProjectTheBuildStageIsLeftOutAndWithOneAllStagesRun() throws Exception {
        assertThat(IngestMojo.stagesToRun(null, false)).containsExactly("convert", "split");
        assertThat(IngestMojo.stagesToRun(" ", true)).containsExactly("convert", "split", "build");
    }

    @Test
    void namedStagesRunInRunOrderAndAnUnknownOneIsRejected() throws Exception {
        assertThat(IngestMojo.stagesToRun("Split", false)).isEqualTo(List.of("split"));
        assertThat(IngestMojo.stagesToRun("build, convert", false)).containsExactly("convert", "build");
        assertThatThrownBy(() -> IngestMojo.stagesToRun("render", false))
                .isInstanceOf(MojoException.class).hasMessageContaining("convert").hasMessageContaining("render");
    }

    @Test
    void theConvertStageWritesTheAsciiDocUnderTheOutputDirectory() throws Exception {
        IngestMojo mojo = mojo();
        mojo.stages = "convert";

        mojo.execute();

        assertThat(dir.resolve("out/K999999.adoc")).exists();
        assertThat(dir.resolve("out/convert.yaml")).exists();
        assertThat(dir.resolve("out/fragments")).doesNotExist();
    }

    @Test
    void aFailingStageStopsTheRunAndNamesTheStage() throws Exception {
        IngestMojo mojo = mojo();
        mojo.convertCommand = tool("fail");

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("stopped at the convert stage");

        assertThat(dir.resolve("out/fragments")).doesNotExist();
    }

    @Test
    void theSplitStageSplitsTheConvertedFileAndChecksItAgainstThePdf() throws Exception {
        writePdf(pdf, RECORD.replace("\n\n", "\n"));
        write(dir.resolve("out/K999999.adoc"), RECORD);
        IngestMojo mojo = mojo();
        mojo.stages = "split";

        mojo.execute();

        assertThat(dir.resolve("out/fragments/fragments.yaml")).exists();
        assertThat(dir.resolve("out/fragments/coverage.yaml")).exists();
        assertThat(dir.resolve("out/fragments/topics/dex")).isNotEmptyDirectory();
    }

    @Test
    void theBuildStageNeedsAProject() throws Exception {
        IngestMojo mojo = mojo();
        mojo.stages = "build";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.ingest.project");
    }

    @Test
    void theBuildStagePlacesIntoTheProjectAndRunsTheChildMaven() throws Exception {
        Path fragments = dir.resolve("out/fragments");
        write(fragments.resolve("fragments.yaml"), "schema-version: 1\ngoal: ingest-split\nfindings: []\n");
        write(fragments.resolve("topics/dex/k1-one.adoc"),
                ":topic-id: dex-k1-one\n:topic-type: reference\n\n[[dex-k1-one]]\n= T\n\nText.\n");
        write(fragments.resolve("assembly/DeXRecord_K1.adoc"),
                "= K1\n\ninclude::{topics}/topics/dex/k1-one.adoc[leveloffset=+1]\n");
        Path project = dir.resolve("project");
        write(project.resolve("assemblies/pom.xml"), ASSEMBLIES_POM);
        Files.createDirectories(project.resolve("topics/src/docs/asciidoc/topics/dex"));
        Path mvn = dir.resolve("FakeMvn.java");
        write(mvn, FAKE_MAVEN);
        Path calls = dir.resolve("calls.txt");

        IngestMojo mojo = mojo();
        mojo.stages = "build";
        mojo.project = project.toFile();
        mojo.mavenCommand = java + " \"" + mvn + "\" \"" + calls + "\"";
        mojo.mavenArgs = "-o";
        mojo.execute();

        assertThat(project.resolve("topics/src/docs/asciidoc/topics/dex/k1-one.adoc")).exists();
        assertThat(project.resolve("assemblies/DeXRecord_K1/pom.xml")).exists();
        List<String> lines = Files.readAllLines(calls);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith(project + " >").contains(":topic-registry -pl topics").endsWith("-o");
        assertThat(lines.get(1)).contains("rootFile=").contains("index-dex-records.adoc");
        assertThat(lines.get(2)).contains("clean verify -pl assemblies/DeXRecord_K1 -am");
    }

    private String manifest() throws IOException {
        return Files.readString(dir.resolve("out/ingest.yaml"));
    }

    @Test
    void aSecondRunSkipsAStageWhoseInputsToolAndParametersAreUnchanged() throws Exception {
        IngestMojo first = mojo();
        first.stages = "convert";
        first.execute();
        String recorded = manifest();
        byte[] adoc = Files.readAllBytes(dir.resolve("out/K999999.adoc"));

        IngestMojo second = mojo();
        second.stages = "convert";
        second.execute();

        assertThat(runs()).isEqualTo(1);
        assertThat(logged).anyMatch(l -> l.contains("stage convert skipped"));
        assertThat(manifest()).isEqualTo(recorded);
        assertThat(Files.readAllBytes(dir.resolve("out/K999999.adoc"))).isEqualTo(adoc);
        assertThat(recorded).contains("convert:").contains("status: OK").contains("K999999.pdf").contains("kind: PDF")
                .contains("fidelity").doesNotContain("timestamp");
    }

    @Test
    void aChangedParameterRunsTheStageAgainAndRecordsTheNewValue() throws Exception {
        IngestMojo first = mojo();
        first.stages = "convert";
        first.execute();

        IngestMojo second = mojo();
        second.stages = "convert";
        second.strictFidelity = true;
        second.execute();

        assertThat(runs()).isEqualTo(2);
        assertThat(manifest()).contains("strictFidelity: 'true'");
    }

    @Test
    void forceRunsAStageThatCouldBeSkipped() throws Exception {
        IngestMojo first = mojo();
        first.stages = "convert";
        first.execute();

        IngestMojo second = mojo();
        second.stages = "convert";
        second.force = true;
        second.execute();

        assertThat(runs()).isEqualTo(2);
    }

    @Test
    void anOutputThatWasDeletedOrChangedRunsTheStageAgain() throws Exception {
        IngestMojo first = mojo();
        first.stages = "convert";
        first.execute();
        Files.writeString(dir.resolve("out/K999999.adoc"), "tampered");

        IngestMojo second = mojo();
        second.stages = "convert";
        second.execute();

        assertThat(runs()).isEqualTo(2);
        assertThat(dir.resolve("out/K999999.adoc")).content().startsWith("= Title");
    }

    @Test
    void aChangedSourceStartsFromAFreshManifest() throws Exception {
        IngestMojo first = mojo();
        first.stages = "convert";
        first.execute();
        Files.writeString(pdf, "%PDF-1.4\nchanged\n");

        IngestMojo second = mojo();
        second.stages = "convert";
        second.execute();

        assertThat(runs()).isEqualTo(2);
    }

    @Test
    void aFailedStageIsRecordedAsFailedAndRunsAgainNextTime() throws Exception {
        IngestMojo failing = mojo();
        failing.stages = "convert";
        failing.convertCommand = tool("fail");

        assertThatThrownBy(failing::execute).isInstanceOf(MojoException.class);

        assertThat(manifest()).contains("status: FAILED").contains("stage-failed");

        IngestMojo fixed = mojo();
        fixed.stages = "convert";
        fixed.execute();

        assertThat(runs()).isEqualTo(1);
        assertThat(manifest()).contains("status: OK").doesNotContain("stage-failed");
    }

    @Test
    void aRelaxedFidelityGateIsRecordedAsRelaxed() throws Exception {
        IngestMojo relaxed = mojo();
        relaxed.stages = "convert";
        relaxed.execute();
        assertThat(manifest()).contains("relaxed-gates:\n    - fidelity");

        IngestMojo strict = mojo();
        strict.stages = "convert";
        strict.strictFidelity = true;
        strict.execute();
        assertThat(manifest()).contains("relaxed-gates: []");
    }

    @Test
    void aWordDocumentIsRefusedBecauseOnlyPdfIsSupportedYet() throws Exception {
        Path docx = dir.resolve("K1.docx");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(docx))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IngestMojo mojo = mojo();
        mojo.source = docx.toFile();
        mojo.stages = "convert";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("not supported yet");

        assertThat(dir.resolve("out/K1.adoc")).doesNotExist();
    }

    @Test
    void theSplitStageIsSkippedOnAnIdenticalSecondRunAndRerunsWhenItsParameterChanges() throws Exception {
        writePdf(pdf, RECORD.replace("\n\n", "\n"));
        write(dir.resolve("out/K999999.adoc"), RECORD);
        IngestMojo first = mojo();
        first.stages = "split";
        first.execute();
        String recorded = manifest();

        IngestMojo again = mojo();
        again.stages = "split";
        again.execute();
        assertThat(logged).anyMatch(l -> l.contains("stage split skipped"));
        assertThat(manifest()).isEqualTo(recorded);

        logged.clear();
        IngestMojo changed = mojo();
        changed.stages = "split";
        changed.sizeLimit = 4000;
        changed.execute();
        assertThat(logged).noneMatch(l -> l.contains("stage split skipped"));
        assertThat(manifest()).contains("sizeLimit: '4000'");
    }
}

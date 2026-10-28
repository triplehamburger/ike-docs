package network.ike.docs.plugin;

import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.ingest.NeedsAi;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestDetectMojoTest {

    @TempDir
    Path dir;

    private static final List<String> TITLES = List.of("510(k) Number:", "Purpose for Submission:", "Measurand:",
            "Type of Test:", "Applicant:", "Proprietary and Established Names:", "Regulatory Information:",
            "Intended Use:", "Device Description:", "Substantial Equivalence Information:",
            "Standard/Guidance Document Referenced (if applicable):", "Test Principle:",
            "Performance Characteristics (if/when applicable):", "Proposed Labeling:", "Conclusion:");

    private static Log silentLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    private IngestDetectMojo mojo(Path source) {
        IngestDetectMojo mojo = new IngestDetectMojo();
        mojo.log = silentLog();
        mojo.source = source.toFile();
        mojo.outputDir = dir.resolve("out").toFile();
        return mojo;
    }

    private static String assayOnlyText() {
        StringBuilder text = new StringBuilder("510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION\nDECISION SUMMARY\n"
                + "ASSAY ONLY TEMPLATE\n");
        for (int i = 0; i < TITLES.size(); i++) {
            text.append((char) ('A' + i)).append(". ").append(TITLES.get(i)).append('\n');
            text.append(i == 0 ? "K162200\n" : "Body text for this section.\n");
        }
        return text.toString();
    }

    private void writePdf(Path file, String... pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
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
            }
            doc.save(file.toFile());
        }
    }

    private void writeBlankPdf(Path file, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.addRect(50, 50, 200, 100);
                    cs.fill();
                }
            }
            doc.save(file.toFile());
        }
    }

    @Test
    void aDecisionSummaryWritesAReportAndNoWorkOrders() throws Exception {
        Path pdf = dir.resolve("K162200.pdf");
        writePdf(pdf, assayOnlyText());

        mojo(pdf).execute();
        String first = Files.readString(dir.resolve("out/detect.yaml"), StandardCharsets.UTF_8);
        mojo(pdf).execute();
        String second = Files.readString(dir.resolve("out/detect.yaml"), StandardCharsets.UTF_8);

        assertThat(first).contains("kind: DEX_510K").contains("template: ASSAY_ONLY").contains("k-number: K162200")
                .contains("layout: LEGACY_LETTERED");
        assertThat(second).isEqualTo(first);
        assertThat(dir.resolve("out/needs-ai.yaml")).doesNotExist();
    }

    @Test
    void aMissingSourceFailsTheGoalButStillWritesTheReport() {
        assertThatThrownBy(() -> mojo(dir.resolve("nope.pdf")).execute())
                .isInstanceOf(MojoException.class).hasMessageContaining("error(s)").hasMessageContaining("detect.yaml");

        assertThat(dir.resolve("out/detect.yaml")).content().contains("kind: UNREADABLE")
                .contains("check: source-missing");
    }

    @Test
    void aScannedPdfWritesAWorkOrderAndAnImageAndAFinishedAnswerClearsIt() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        writeBlankPdf(pdf, 2);

        mojo(pdf).execute();

        Path needsAi = dir.resolve("out/needs-ai.yaml");
        assertThat(needsAi).exists();
        assertThat(dir.resolve("out/pages/page-001.png")).exists();
        NeedsAi item = IngestYaml.needsAiFromYaml(Files.readString(needsAi)).items().get(0);
        assertThat(item.kind()).isEqualTo("transcribe-page");

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", item.id());
        entry.put("value", "Xpert Xpress CoV-2 plus\nInstructions for Use\nFor Use with GeneXpert Dx System");
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(entry);
        Files.writeString(dir.resolve("out/answers.yaml"), IngestYaml.dump(Map.of("answers", list)));

        mojo(pdf).execute();

        assertThat(needsAi).doesNotExist();
        assertThat(dir.resolve("out/detect.yaml")).content().contains("kind: NOT_DEX")
                .contains("guess: manufacturer-ifu");
    }

    @Test
    void aRejectedAnswerFailsTheGoal() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        writeBlankPdf(pdf, 2);
        mojo(pdf).execute();
        NeedsAi item = IngestYaml.needsAiFromYaml(Files.readString(dir.resolve("out/needs-ai.yaml"))).items().get(0);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", item.id());
        entry.put("value", "tiny");
        Files.writeString(dir.resolve("out/answers.yaml"),
                IngestYaml.dump(Map.of("answers", List.of(entry))));

        assertThatThrownBy(() -> mojo(pdf).execute()).isInstanceOf(MojoException.class);

        assertThat(dir.resolve("out/detect.yaml")).content().contains("check: answer-invalid");
        assertThat(dir.resolve("out/needs-ai.yaml")).exists();
    }
}

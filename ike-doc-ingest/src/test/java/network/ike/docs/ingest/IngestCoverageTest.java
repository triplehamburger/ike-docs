package network.ike.docs.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IngestCoverageTest {

    @TempDir
    Path dir;

    private static final List<String> TITLES = List.of("510(k) Number:", "Purpose for Submission:", "Measurand:",
            "Type of Test:", "Applicant:", "Proprietary and Established Names:", "Regulatory Information:",
            "Intended Use:", "Device Description:", "Substantial Equivalence Information:",
            "Standard/Guidance Document Referenced (if applicable):", "Test Principle:",
            "Performance Characteristics (if/when applicable):", "Proposed Labeling:", "Conclusion:");

    private void writePdf(Path file, List<String> pages) throws IOException {
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

    /// A page of distinct prose: every page has its own words.
    private static String prose(int page) {
        return IntStream.range(0, 12)
                .mapToObj(line -> "Sentence " + line + " of page " + page + " describes alpha" + page + "x" + line
                        + " and beta" + page + "y" + line + " in the validated assay.")
                .collect(Collectors.joining("\n"));
    }

    private Path topics(String... texts) throws IOException {
        Path topics = Files.createDirectories(dir.resolve("topics"));
        for (int i = 0; i < texts.length; i++) {
            Files.writeString(topics.resolve("t" + i + ".adoc"), texts[i], StandardCharsets.UTF_8);
        }
        return topics;
    }

    private static String checks(IngestCoverage.Result r) {
        return r.report().findings().stream().map(Finding::check).sorted().collect(Collectors.joining(","));
    }

    @Test
    void topicsThatHoldEverythingPass() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1), prose(2), prose(3), prose(4)));

        IngestCoverage.Result r = IngestCoverage.run(pdf,
                topics(prose(1) + "\n" + prose(2), prose(3) + "\n" + prose(4)), IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.report().details()).containsEntry("missing-words", 0).containsEntry("source-pages", 4);
        assertThat(r.report().details().get("weak-pages")).isEqualTo(List.of());
    }

    @Test
    void topicsThatDroppedPagesFailAndNameTheWeakPages() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1), prose(2), prose(3), prose(4)));

        IngestCoverage.Result r = IngestCoverage.run(pdf, topics(prose(1), prose(2)), IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(r.report().hasErrors()).isTrue();
        assertThat(checks(r)).contains("coverage-low");
        assertThat(r.report().details().get("weak-pages")).isEqualTo(List.of(3, 4));
        assertThat(r.report().findings()).filteredOn(f -> f.check().equals("coverage-page"))
                .extracting(Finding::component).containsExactly("source.pdf page 3", "source.pdf page 4");
    }

    @Test
    void aSmallLossUnderTheAllowanceStillPasses() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1), prose(2), prose(3), prose(4)));
        String shortened = prose(4).lines().limit(11).collect(Collectors.joining("\n"));

        IngestCoverage.Result r = IngestCoverage.run(pdf,
                topics(prose(1), prose(2), prose(3), shortened), IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat((Integer) r.report().details().get("missing-words")).isPositive();
    }

    @Test
    void aMissingTopicsFolderIsAnError() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1)));

        IngestCoverage.Result r = IngestCoverage.run(pdf, dir.resolve("nope"), IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(checks(r)).isEqualTo("topics-missing");
    }

    @Test
    void aFolderWithNoTopicsIsAnError() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1)));

        IngestCoverage.Result r = IngestCoverage.run(pdf, Files.createDirectories(dir.resolve("empty")),
                IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(checks(r)).isEqualTo("topics-missing");
    }

    @Test
    void aScannedPdfCannotBeCompared() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.addRect(50, 50, 200, 100);
                    cs.fill();
                }
            }
            doc.save(pdf.toFile());
        }

        IngestCoverage.Result r = IngestCoverage.run(pdf, topics("anything"), IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(checks(r)).isEqualTo("coverage-needs-text");
    }

    @Test
    void aMissingPdfIsReportedNotThrown() throws Exception {
        IngestCoverage.Result r = IngestCoverage.run(dir.resolve("nope.pdf"), topics("x"),
                IngestCoverage.DEFAULT_MAX_MISSING);

        assertThat(r.report().hasErrors()).isTrue();
    }

    @Test
    void anInvalidAllowanceIsAnError() throws Exception {
        Path pdf = dir.resolve("source.pdf");
        writePdf(pdf, List.of(prose(1)));

        assertThat(checks(IngestCoverage.run(pdf, topics(prose(1)), 1.5))).isEqualTo("max-missing-invalid");
        assertThat(checks(IngestCoverage.run(pdf, topics(prose(1)), Double.NaN))).isEqualTo("max-missing-invalid");
    }

    @Test
    void aDecisionSummarySectionTitleAbsentFromTheTopicsIsAnError() throws Exception {
        StringBuilder text = new StringBuilder("510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION\nDECISION SUMMARY\n"
                + "ASSAY ONLY TEMPLATE\n");
        for (int i = 0; i < TITLES.size(); i++) {
            text.append((char) ('A' + i)).append(". ").append(TITLES.get(i)).append('\n');
            text.append(i == 0 ? "K162200\n" : "Body text for this section.\n");
        }
        Path pdf = dir.resolve("K162200.pdf");
        writePdf(pdf, List.of(text.toString()));
        String whole = text.toString();

        IngestCoverage.Result whole1 = IngestCoverage.run(pdf, topics(whole), IngestCoverage.DEFAULT_MAX_MISSING);
        assertThat(whole1.report().hasErrors()).as(checks(whole1)).isFalse();
        assertThat(whole1.report().details()).containsEntry("sections-checked", 15);

        Path trimmed = Files.createDirectories(dir.resolve("trimmed"));
        Files.writeString(trimmed.resolve("t.adoc"), whole.replace("Measurand:", "Something else"));
        IngestCoverage.Result r = IngestCoverage.run(pdf, trimmed, 1.0);

        assertThat(checks(r)).contains("section-missing");
        assertThat(r.report().details().get("sections-missing")).isEqualTo(List.of("C Measurand"));
    }
}

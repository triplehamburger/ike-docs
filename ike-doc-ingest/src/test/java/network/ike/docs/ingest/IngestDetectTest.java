package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDetectTest {

    @TempDir
    Path dir;

    private static List<String> checks(IngestReport report) {
        return report.findings().stream().map(Finding::check).toList();
    }

    private Path textPdf(List<String> pages) throws IOException {
        Path pdf = dir.resolve("K230440.pdf");
        DexTexts.writePdf(pdf, pages);
        return pdf;
    }

    @Test
    void aTextDecisionSummaryIsClassifiedWithItsProvenance() throws IOException {
        IngestReport r = IngestDetect.run(textPdf(DexTexts.currentRoman()), null);
        Map<String, Object> d = r.details();

        assertThat(d).containsEntry("kind", "DEX_510K").containsEntry("layout", "CURRENT_ROMAN")
                .containsEntry("template", "ASSAY_AND_INSTRUMENT").containsEntry("k-number", "K230440")
                .containsEntry("pages", 3).containsEntry("text-layer", true)
                .containsEntry("citation-url-origin", "derived")
                .containsEntry("citation-url", "https://www.accessdata.fda.gov/cdrh_docs/reviews/K230440.pdf")
                .containsEntry("license", "Public domain — US federal government work.");
        assertThat(r.sources()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("K230440.pdf");
            assertThat(s.sha256()).hasSize(64);
        });
        assertThat(checks(r)).containsExactly("citation-url-derived");
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void theSameInputAlwaysProducesIdenticalBytes() throws IOException {
        Path pdf = textPdf(DexTexts.currentRoman());

        String first = IngestYaml.reportToYaml(IngestDetect.run(pdf, null));
        String second = IngestYaml.reportToYaml(IngestDetect.run(pdf, null));

        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain(dir.toString());
    }

    @Test
    void aSuppliedCitationUrlIsCheckedNotTrusted() throws IOException {
        Path pdf = textPdf(DexTexts.currentRoman());
        String good = "https://www.accessdata.fda.gov/cdrh_docs/pdf23/K230440.pdf";

        IngestReport ok = IngestDetect.run(pdf, good);
        assertThat(ok.details()).containsEntry("citation-url", good)
                .containsEntry("citation-url-origin", "supplied");
        assertThat(checks(ok)).isEmpty();

        IngestReport insecure = IngestDetect.run(pdf, "http://example.com/K230440.pdf");
        assertThat(checks(insecure)).containsExactly("citation-url-invalid");
        assertThat(insecure.hasErrors()).isTrue();

        IngestReport wrongK = IngestDetect.run(pdf, "https://example.com/other.pdf");
        assertThat(checks(wrongK)).containsExactly("citation-url-mismatch");
    }

    @Test
    void aGudidRecordAndAnIfuAreReportedAsDexCompanions() throws IOException {
        Path gudid = dir.resolve("gudid.pdf");
        DexTexts.writePdf(gudid, List.of("AccessGUDID - DEVICE: GeneXpert\nDEVICE IDENTIFIER (DI) INFORMATION\n"
                + "Primary DI Number: 07332940008581\nBrand Name: GeneXpert and more words to avoid sparse text"));
        Path ifu = dir.resolve("ifu.pdf");
        DexTexts.writePdf(ifu, List.of("Xpert Xpress CoV-2 plus\nInstructions for Use\n"
                + "For Use with GeneXpert Dx System and some more words to avoid sparse text"));

        IngestReport g = IngestDetect.run(gudid, null);
        IngestReport i = IngestDetect.run(ifu, null);

        assertThat(g.details()).containsEntry("kind", "DEX_GUDID").containsEntry("domain", "dex")
                .containsEntry("companion", "gudid-record").containsEntry("di-number", "07332940008581");
        assertThat(i.details()).containsEntry("kind", "DEX_IFU").containsEntry("domain", "dex")
                .containsEntry("companion", "manufacturer-ifu").doesNotContainKey("di-number");
        assertThat(g.details()).doesNotContainKey("k-number");
        assertThat(checks(g)).contains("companion-document");
        assertThat(g.hasErrors()).isFalse();
        assertThat(i.hasErrors()).isFalse();
    }

    @Test
    void aClearanceLetterIsReportedNotDexWithAGuess() throws IOException {
        Path pdf = dir.resolve("letter.pdf");
        DexTexts.writePdf(pdf, List.of("February 6, 2026\nRe: K251543\nDear Kelly Scuderi:\n"
                + "We have reviewed your section 510(k) premarket notification and more words to avoid sparse text"));

        IngestReport r = IngestDetect.run(pdf, null);

        assertThat(r.details()).containsEntry("kind", "NOT_DEX").containsEntry("guess", "fda-clearance-letter")
                .doesNotContainKey("domain");
        assertThat(r.details()).doesNotContainKey("k-number");
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void unreadableInputsEndAsACleanErrorReport() throws IOException {
        IngestReport missing = IngestDetect.run(dir.resolve("nope.pdf"), null);
        assertThat(missing.details()).containsEntry("kind", "UNREADABLE");
        assertThat(checks(missing)).containsExactly("source-missing");
        assertThat(missing.hasErrors()).isTrue();

        Path text = Files.writeString(dir.resolve("notes.pdf"), "plain text pretending to be a pdf");
        IngestReport fake = IngestDetect.run(text, null);
        assertThat(fake.details()).containsEntry("kind", "UNREADABLE");
        assertThat(checks(fake)).containsExactly("not-a-pdf");
    }

    @Test
    void aScannedPdfEndsAsAnErrorNamingTheLimit() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 3);

        IngestReport r = IngestDetect.run(pdf, null);

        assertThat(r.details()).containsEntry("kind", "SCANNED").containsEntry("text-layer", false)
                .containsEntry("pages", 3);
        assertThat(checks(r)).containsExactly("scanned-pdf");
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.findings().get(0).message()).contains("not supported yet");
    }
}

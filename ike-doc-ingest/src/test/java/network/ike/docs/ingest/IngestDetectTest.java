package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestDetectTest {

    @TempDir
    Path dir;

    private IngestDetect.Options options() {
        return new IngestDetect.Options(dir.resolve("out/pages"), false, null);
    }

    private IngestDetect.Result run(Path pdf, IngestDetect.Options options, String answers) {
        return IngestDetect.run(pdf, options, answers);
    }

    private static List<String> checks(IngestReport report) {
        return report.findings().stream().map(Finding::check).toList();
    }

    private static String answers(Map<String, String> byId) {
        List<Map<String, Object>> list = new ArrayList<>();
        byId.forEach((id, value) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("value", value);
            list.add(m);
        });
        return IngestYaml.dump(Map.of("answers", list));
    }

    private Path textPdf(List<String> pages) throws IOException {
        Path pdf = dir.resolve("K230440.pdf");
        DexTexts.writePdf(pdf, pages);
        return pdf;
    }

    @Test
    void aTextDecisionSummaryIsClassifiedWithItsProvenance() throws IOException {
        IngestDetect.Result r = run(textPdf(DexTexts.currentRoman()), options(), null);
        Map<String, Object> d = r.report().details();

        assertThat(d).containsEntry("kind", "DEX_510K").containsEntry("layout", "CURRENT_ROMAN")
                .containsEntry("template", "ASSAY_AND_INSTRUMENT").containsEntry("k-number", "K230440")
                .containsEntry("pages", 3).containsEntry("text-layer", true)
                .containsEntry("citation-url-origin", "derived")
                .containsEntry("citation-url", "https://www.accessdata.fda.gov/cdrh_docs/reviews/K230440.pdf")
                .containsEntry("license", "Public domain — US federal government work.");
        assertThat(r.report().sources()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("K230440.pdf");
            assertThat(s.sha256()).hasSize(64);
        });
        assertThat(checks(r.report())).containsExactly("citation-url-derived");
        assertThat(r.report().hasErrors()).isFalse();
        assertThat(r.needsAi()).isEmpty();
    }

    @Test
    void theSameInputAlwaysProducesIdenticalBytes() throws IOException {
        Path pdf = textPdf(DexTexts.currentRoman());

        String first = IngestYaml.reportToYaml(run(pdf, options(), null).report());
        String second = IngestYaml.reportToYaml(run(pdf, options(), null).report());

        assertThat(second).isEqualTo(first);
        assertThat(first).doesNotContain(dir.toString());
    }

    @Test
    void aSuppliedCitationUrlIsCheckedNotTrusted() throws IOException {
        Path pdf = textPdf(DexTexts.currentRoman());
        String good = "https://www.accessdata.fda.gov/cdrh_docs/pdf23/K230440.pdf";

        IngestDetect.Result ok = run(pdf, new IngestDetect.Options(dir.resolve("p"), false, good), null);
        assertThat(ok.report().details()).containsEntry("citation-url", good)
                .containsEntry("citation-url-origin", "supplied");
        assertThat(checks(ok.report())).isEmpty();

        IngestDetect.Result insecure = run(pdf, new IngestDetect.Options(dir.resolve("p"), false,
                "http://example.com/K230440.pdf"), null);
        assertThat(checks(insecure.report())).containsExactly("citation-url-invalid");
        assertThat(insecure.report().hasErrors()).isTrue();

        IngestDetect.Result wrongK = run(pdf, new IngestDetect.Options(dir.resolve("p"), false,
                "https://example.com/other.pdf"), null);
        assertThat(checks(wrongK.report())).containsExactly("citation-url-mismatch");
    }

    @Test
    void aGudidRecordAndAnIfuAreReportedAsDexCompanions() throws IOException {
        Path gudid = dir.resolve("gudid.pdf");
        DexTexts.writePdf(gudid, List.of("AccessGUDID - DEVICE: GeneXpert\nDEVICE IDENTIFIER (DI) INFORMATION\n"
                + "Primary DI Number: 07332940008581\nBrand Name: GeneXpert and more words to avoid sparse text"));
        Path ifu = dir.resolve("ifu.pdf");
        DexTexts.writePdf(ifu, List.of("Xpert Xpress CoV-2 plus\nInstructions for Use\n"
                + "For Use with GeneXpert Dx System and some more words to avoid sparse text"));

        IngestDetect.Result g = run(gudid, options(), null);
        IngestDetect.Result i = run(ifu, options(), null);

        assertThat(g.report().details()).containsEntry("kind", "DEX_GUDID").containsEntry("domain", "dex")
                .containsEntry("companion", "gudid-record").containsEntry("di-number", "07332940008581");
        assertThat(i.report().details()).containsEntry("kind", "DEX_IFU").containsEntry("domain", "dex")
                .containsEntry("companion", "manufacturer-ifu").doesNotContainKey("di-number");
        assertThat(g.report().details()).doesNotContainKey("k-number");
        assertThat(checks(g.report())).contains("companion-document");
        assertThat(g.report().hasErrors()).isFalse();
        assertThat(i.report().hasErrors()).isFalse();
    }

    @Test
    void aClearanceLetterIsReportedNotDexWithAGuess() throws IOException {
        Path pdf = dir.resolve("letter.pdf");
        DexTexts.writePdf(pdf, List.of("February 6, 2026\nRe: K251543\nDear Kelly Scuderi:\n"
                + "We have reviewed your section 510(k) premarket notification and more words to avoid sparse text"));

        IngestDetect.Result r = run(pdf, options(), null);

        assertThat(r.report().details()).containsEntry("kind", "NOT_DEX").containsEntry("guess", "fda-clearance-letter")
                .doesNotContainKey("domain");
        assertThat(r.report().details()).doesNotContainKey("k-number");
        assertThat(r.report().hasErrors()).isFalse();
    }

    @Test
    void unreadableInputsEndAsACleanErrorReport() throws IOException {
        IngestDetect.Result missing = run(dir.resolve("nope.pdf"), options(), null);
        assertThat(missing.report().details()).containsEntry("kind", "UNREADABLE");
        assertThat(checks(missing.report())).containsExactly("source-missing");
        assertThat(missing.report().hasErrors()).isTrue();

        Path text = Files.writeString(dir.resolve("notes.pdf"), "plain text pretending to be a pdf");
        IngestDetect.Result fake = run(text, options(), null);
        assertThat(fake.report().details()).containsEntry("kind", "UNREADABLE");
        assertThat(checks(fake.report())).containsExactly("not-a-pdf");
    }

    @Test
    void aScannedPdfStartsWithOnlyPageOneAndAnImageForIt() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 3);

        IngestDetect.Result r = run(pdf, options(), null);

        assertThat(r.report().details()).containsEntry("kind", "SCANNED_PENDING").containsEntry("text-layer", false)
                .containsEntry("transcribed", true);
        assertThat(r.needsAi()).isPresent();
        List<NeedsAi> items = r.needsAi().get().items();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).kind()).isEqualTo("transcribe-page");
        assertThat(items.get(0).target()).isEqualTo("page-001");
        assertThat(items.get(0).blocking()).isTrue();
        assertThat(items.get(0).context()).contains("pages/page-001.png").contains("page 1 of 3");
        assertThat(dir.resolve("out/pages/page-001.png")).exists();
        assertThat(dir.resolve("out/pages/page-002.png")).doesNotExist();
        assertThat(checks(r.report())).contains("scanned-pdf", "transcription-pending");
        assertThat(r.needsAi().get().instructions()).contains("<<TABLE>>");
    }

    @Test
    void aDecisionSummaryTranscriptOfPageOneUnlocksTheRemainingPages() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 3);
        List<String> pages = DexTexts.currentRoman();
        NeedsAi pageOne = run(pdf, options(), null).needsAi().orElseThrow().items().get(0);

        IngestDetect.Result r = run(pdf, options(), answers(Map.of(pageOne.id(), pages.get(0))));

        assertThat(r.report().details()).containsEntry("kind", "SCANNED_PENDING");
        List<NeedsAi> items = r.needsAi().orElseThrow().items();
        assertThat(items).extracting(NeedsAi::target).containsExactly("page-002", "page-003");
        assertThat(dir.resolve("out/pages/page-003.png")).exists();
    }

    @Test
    void aNonDecisionSummaryTranscriptStopsAfterOnePage() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 40);
        NeedsAi pageOne = run(pdf, options(), null).needsAi().orElseThrow().items().get(0);

        IngestDetect.Result r = run(pdf, options(), answers(Map.of(pageOne.id(),
                "February 6, 2026\nRe: K251543\nDear Kelly Scuderi:\nWe have reviewed your section 510(k)")));

        assertThat(r.report().details()).containsEntry("kind", "NOT_DEX").containsEntry("pages", 40);
        assertThat(r.needsAi()).isEmpty();
        assertThat(dir.resolve("out/pages/page-002.png")).doesNotExist();
    }

    @Test
    void aScannedIfuUnlocksTheRemainingPagesLikeADecisionSummary() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 3);
        NeedsAi pageOne = run(pdf, options(), null).needsAi().orElseThrow().items().get(0);

        IngestDetect.Result r = run(pdf, options(), answers(Map.of(pageOne.id(),
                "Xpert Xpress CoV-2 plus\nInstructions for Use\nFor Use with GeneXpert Dx System")));

        assertThat(r.needsAi().orElseThrow().items()).extracting(NeedsAi::target)
                .containsExactly("page-002", "page-003");
    }

    @Test
    void whenEveryPageIsTranscribedDetectionContinuesExactlyAsForText() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 3);
        List<String> pages = DexTexts.currentRoman();
        NeedsAi pageOne = run(pdf, options(), null).needsAi().orElseThrow().items().get(0);
        List<NeedsAi> rest = run(pdf, options(), answers(Map.of(pageOne.id(), pages.get(0))))
                .needsAi().orElseThrow().items();

        Map<String, String> all = new LinkedHashMap<>();
        all.put(pageOne.id(), pages.get(0));
        all.put(rest.get(0).id(), pages.get(1));
        all.put(rest.get(1).id(), pages.get(2));
        IngestDetect.Result r = run(pdf, options(), answers(all));

        assertThat(r.needsAi()).isEmpty();
        assertThat(r.report().details()).containsEntry("kind", "DEX_510K").containsEntry("layout", "CURRENT_ROMAN")
                .containsEntry("k-number", "K230440").containsEntry("transcribed", true)
                .containsEntry("text-layer", false);
        assertThat(checks(r.report())).contains("transcribed-source");
        assertThat(r.report().findings()).noneMatch(Finding::isError);
    }

    @Test
    void aBadTranscriptIsRejectedWithItsFaultsAndTheItemStaysOpen() throws IOException {
        Path pdf = dir.resolve("scan.pdf");
        DexTexts.writeBlankPdf(pdf, 2);
        NeedsAi pageOne = run(pdf, options(), null).needsAi().orElseThrow().items().get(0);
        String bad = "510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION DECISION SUMMARY A. 510(k) Number: K111111\n"
                + "<<TABLE>>\na | b | c\nd | e\n";

        IngestDetect.Result r = run(pdf, options(), answers(Map.of(pageOne.id(), bad)));

        assertThat(r.report().details()).containsEntry("kind", "SCANNED_PENDING");
        assertThat(r.needsAi().orElseThrow().items()).extracting(NeedsAi::id).containsExactly(pageOne.id());
        List<Finding> invalid = r.report().findings().stream().filter(f -> f.check().equals("answer-invalid")).toList();
        assertThat(invalid).extracting(Finding::message).anySatisfy(m -> assertThat(m).contains("has 2 cells"))
                .anySatisfy(m -> assertThat(m).contains("never closed"));
        assertThat(r.report().hasErrors()).isTrue();
    }

    @Test
    void forcingAScanOverridesAnExistingTextLayer() throws IOException {
        IngestDetect.Result r = run(textPdf(DexTexts.currentRoman()),
                new IngestDetect.Options(dir.resolve("out/pages"), true, null), null);

        assertThat(r.report().details()).containsEntry("kind", "SCANNED_PENDING").containsEntry("text-layer", false);
        assertThat(r.needsAi().orElseThrow().items()).hasSize(1);
    }

    @Test
    void transcriptChecksCatchStrayAndNestedTableMarkers() {
        NeedsAi item = NeedsAi.of("transcribe-page", "page-001", true, "c", "e");
        List<String> problems = IngestDetect.CHECKS.get("transcribe-page").validate(item,
                "plenty of ordinary page text here\n<<END TABLE>>\n<<TABLE>>\n<<TABLE>>\nx | y\n<<END TABLE>>\n");

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("<<END TABLE>> with no <<TABLE>>"))
                .anySatisfy(p -> assertThat(p).contains("inside the table"));
        assertThat(IngestDetect.CHECKS.get("transcribe-page").validate(item, "tiny")).singleElement()
                .satisfies(p -> assertThat(p).contains("nearly empty"));
    }
}

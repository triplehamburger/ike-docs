package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DexDetectorTest {

    private static DexDetector.Detection detect(List<String> pages) {
        return DexDetector.detect(pages, "sample.pdf");
    }

    private static List<String> checks(DexDetector.Detection d) {
        return d.findings().stream().map(Finding::check).toList();
    }

    @Test
    void legacyDeviceAndInstrumentIsRecognizedByLetteredSections() {
        DexDetector.Detection d = detect(DexTexts.legacyDeviceAndInstrument());

        assertThat(d.kind()).isEqualTo(DexDetector.Kind.DEX_510K);
        assertThat(d.layout()).isEqualTo(DexDetector.Layout.LEGACY_LETTERED);
        assertThat(d.template()).isEqualTo("DEVICE_AND_INSTRUMENT");
        assertThat(d.kNumber()).isEqualTo("K031739");
        assertThat(d.sections()).hasSize(16);
        assertThat(d.sections().get(6)).isEqualTo(new DexDetector.Section("G", "Intended Use", 2));
        assertThat(d.sections().get(15).title()).isEqualTo("Conclusion");
        assertThat(d.findings()).noneMatch(Finding::isError);
    }

    @Test
    void legacyAssayOnlyNormalizesALowerCaseKNumberAndKeysSectionsByTitle() {
        DexDetector.Detection d = detect(DexTexts.legacyAssayOnly());

        assertThat(d.kind()).isEqualTo(DexDetector.Kind.DEX_510K);
        assertThat(d.template()).isEqualTo("ASSAY_ONLY");
        assertThat(d.kNumber()).isEqualTo("K162200");
        assertThat(d.sections()).hasSize(15);
        // letters differ by template: Intended Use is H here, G in the Device and Instrument template
        assertThat(d.sections().get(7)).isEqualTo(new DexDetector.Section("H", "Intended Use", 2));
        assertThat(d.findings()).noneMatch(Finding::isError);
    }

    @Test
    void currentLayoutIsRecognizedAndSubsectionLettersAndStrayNumeralsAreIgnored() {
        DexDetector.Detection d = detect(DexTexts.currentRoman());

        assertThat(d.layout()).isEqualTo(DexDetector.Layout.CURRENT_ROMAN);
        assertThat(d.template()).isEqualTo("ASSAY_AND_INSTRUMENT");
        assertThat(d.kNumber()).isEqualTo("K230440");
        assertThat(d.sections()).extracting(DexDetector.Section::label)
                .containsExactly("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX");
        assertThat(d.sections().get(8).title()).isEqualTo("Conclusion");
        assertThat(d.findings()).isEmpty();
    }

    @Test
    void aFooterKNumberThatDisagreesIsAnError() {
        DexDetector.Detection d = detect(DexTexts.currentRoman("K999999", 3, true));

        assertThat(checks(d)).containsExactly("k-number-mismatch");
        assertThat(d.findings().get(0).isError()).isTrue();
    }

    @Test
    void missingFootersAndAWrongPageTotalAreWarnings() {
        assertThat(checks(detect(DexTexts.currentRoman("K230440", 3, false)))).containsExactly("footer-not-found");

        DexDetector.Detection d = detect(DexTexts.currentRoman("K230440", 24, true));
        assertThat(checks(d)).containsExactly("page-count-mismatch");
        assertThat(d.findings()).noneMatch(Finding::isError);
    }

    @Test
    void aGapInTheSectionSequenceIsAnErrorThatNamesWhereItStops() {
        List<String> pages = new ArrayList<>(DexTexts.currentRoman());
        pages.set(1, pages.get(1).replace("IV Device/System Characteristics:", "Device/System Characteristics:"));

        DexDetector.Detection d = detect(pages);

        assertThat(checks(d)).contains("sections-incomplete");
        assertThat(d.findings().stream().filter(f -> f.check().equals("sections-incomplete")).findFirst().orElseThrow()
                .message()).contains("stops after III").contains("expected IV");
    }

    @Test
    void aDecisionSummaryWithNoRecognizableSectionsIsAnError() {
        String page = """
                510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION
                DECISION SUMMARY
                ASSAY ONLY TEMPLATE
                A. 510(k) Number:
                K111111
                Some prose with no section headings at all.
                """;

        DexDetector.Detection d = detect(List.of(page));

        assertThat(d.kind()).isEqualTo(DexDetector.Kind.DEX_510K);
        assertThat(d.layout()).isEqualTo(DexDetector.Layout.UNKNOWN);
        assertThat(checks(d)).contains("layout-unknown");
    }

    @Test
    void anUnnamedTemplateIsAWarning() {
        List<String> pages = new ArrayList<>(DexTexts.legacyAssayOnly());
        pages.set(0, pages.get(0).replace("ASSAY ONLY TEMPLATE", "SOMETHING ELSE"));

        DexDetector.Detection d = detect(pages);

        assertThat(d.template()).isEqualTo("UNKNOWN");
        assertThat(checks(d)).containsExactly("template-unrecognized");
    }

    @Test
    void sourcesThatAreNotDecisionSummariesAreGuessedAndNeverAnError() {
        String letter = "February 6, 2026\nRe: K251543\nDear Kelly Scuderi:\nWe have reviewed your section 510(k)";

        assertThat(detect(List.of(letter)).guess()).isEqualTo("fda-clearance-letter");
        assertThat(detect(List.of("Some other document with enough words to not be sparse. ".repeat(10))).guess())
                .isEqualTo("unknown");
        assertThat(detect(List.of(letter)).kind()).isEqualTo(DexDetector.Kind.NOT_DEX);
        assertThat(detect(List.of(letter)).findings()).noneMatch(Finding::isError);
    }

    @Test
    void aGudidRecordAndAnIfuAreAlwaysDexCompanionDocuments() {
        String gudid = "AccessGUDID - DEVICE: GeneXpert\nDEVICE IDENTIFIER (DI) INFORMATION\nPrimary DI Number: 07332940008581";
        String ifu = "Xpert Xpress CoV-2 plus\nInstructions for Use\nFor Use with GeneXpert Dx System";

        DexDetector.Detection g = detect(List.of(gudid));
        DexDetector.Detection i = detect(List.of(ifu));

        assertThat(g.kind()).isEqualTo(DexDetector.Kind.DEX_GUDID);
        assertThat(g.guess()).isEqualTo("gudid-record");
        assertThat(i.kind()).isEqualTo(DexDetector.Kind.DEX_IFU);
        assertThat(i.guess()).isEqualTo("manufacturer-ifu");
        assertThat(g.findings()).extracting(Finding::check).contains("companion-document");
        assertThat(g.findings()).noneMatch(Finding::isError);
        assertThat(DexDetector.isCompanion(gudid)).isTrue();
        assertThat(DexDetector.isCompanion(ifu)).isTrue();
        assertThat(DexDetector.isCompanion("Re: K251543 Dear Kelly Instructions for Use")).isFalse();
        assertThat(DexDetector.deviceIdentifier(gudid)).isEqualTo("07332940008581");
        assertThat(DexDetector.deviceIdentifier(ifu)).isEmpty();
    }

    @Test
    void aClearanceLetterThatMentionsInstructionsForUseIsStillNotDex() {
        String letter = "February 6, 2026\nRe: K251543\nDear Kelly Scuderi:\nSee the Instructions for Use enclosed.";

        assertThat(detect(List.of(letter)).kind()).isEqualTo(DexDetector.Kind.NOT_DEX);
    }

    @Test
    void partialSignalsAreFlaggedSoADamagedTextLayerIsNotSilentlyIgnored() {
        String page = "510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION\nDECISION SUMMARY\nASSAY ONLY TEMPLATE\n"
                + "A. 510(k) Numbr: K111111\n";

        DexDetector.Detection d = detect(List.of(page));

        assertThat(d.kind()).isEqualTo(DexDetector.Kind.NOT_DEX);
        assertThat(checks(d)).contains("partial-signals");
        assertThat(d.findings().stream().filter(f -> f.check().equals("partial-signals")).findFirst().orElseThrow()
                .message()).contains("k-number-field").contains("forceScan");
    }

    @Test
    void aTitleOnALaterPageIsFlaggedButNotAccepted() {
        DexDetector.Detection d = detect(List.of("Cover letter text. ".repeat(20),
                "510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION\nDECISION SUMMARY\n"));

        assertThat(d.kind()).isEqualTo(DexDetector.Kind.NOT_DEX);
        assertThat(checks(d)).contains("title-not-on-first-page");
    }

    @Test
    void aSparseFirstPageSuggestsForcingAScan() {
        DexDetector.Detection d = detect(List.of("x"));

        assertThat(checks(d)).contains("sparse-first-page");
    }

    @Test
    void hasAllSignalsNeedsTheTitleTheHeadingAndTheKField() {
        assertThat(DexDetector.hasAllSignals(DexTexts.currentRoman().get(0))).isTrue();
        assertThat(DexDetector.hasAllSignals("510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION DECISION SUMMARY"))
                .isFalse();
        assertThat(DexDetector.hasAllSignals("")).isFalse();
    }
}

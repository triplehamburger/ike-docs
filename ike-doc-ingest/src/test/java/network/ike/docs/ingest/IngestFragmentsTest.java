package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IngestFragmentsTest {

    @TempDir
    Path dir;

    private static final IngestFragments.Options NO_SPLIT = new IngestFragments.Options(2, null, 5000, false);

    private static final String RECORD = """
            // dex-k999999
            :topic-id: dex-k999999
            = DeXRecord_K999999

            [.text-center]
            *510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION*

            == I Background Information:

            === A 510(k) Number

            K999999

            === B Applicant

            Acme Diagnostics

            == II Performance Characteristics (if/when applicable):

            === A Analytical Performance

            . [.underline]#Linearity:#
            The assay was linear.

            ----
            == this line is inside a listing block
            ----

            == III Conclusion:

            The device is substantially equivalent.
            """;

    private Path write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String checks(IngestFragments.Result r) {
        return r.report().findings().stream().map(Finding::check).sorted().collect(Collectors.joining(","));
    }

    private static String filler(int words) {
        return "alpha beta gamma delta ".repeat(words / 4 + 1);
    }

    private static String studies() {
        return "510(k) Number: K888888\n\n"
                + "== I Background:\n\ntext\n\n"
                + "== II Performance Characteristics (if/when applicable):\n\n"
                + "=== A Analytical Performance:\n\n"
                + ". Precision Study:\n" + filler(500) + "\n\n"
                + ". Linearity: +\nNot applicable.\n"
                + ". Reactivity:\n" + filler(500) + "\n\n"
                + "[start=4]\n. Cut-Off:\n" + filler(500) + "\n";
    }

    @Test
    void eachTopLevelSectionBecomesAFragmentWithAGeneratedHeader() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", RECORD), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-k999999-background-information",
                "dex-k999999-performance-characteristics",
                "dex-k999999-conclusion");
        assertThat(r.fragments().get(0).fileName()).isEqualTo("k999999-background-information.adoc");
        String first = r.fragments().get(0).content();
        assertThat(first).contains(":topic-id: dex-k999999-background-information")
                .contains(":topic-type: reference").contains(":topic-status: review")
                .contains(":topic-provenance: external").contains(":topic-license: Public domain")
                .contains("https://www.accessdata.fda.gov/cdrh_docs/reviews/K999999.pdf")
                .contains("\n[[dex-k999999-background-information]]\n= I Background Information:\n")
                .contains("\n== A 510(k) Number\n").contains("\n== B Applicant\n")
                .doesNotContain(":topic-related:");
        assertThat(first).doesNotContain("=== A");
    }

    @Test
    void theAssemblyKeepsTheTitleBlockAndIncludesEveryFragmentInOrder() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", RECORD), IngestFragments.Options.defaults());

        assertThat(r.assemblyName()).isEqualTo("DeXRecord_K999999.adoc");
        assertThat(r.assembly()).startsWith("// dex-k999999").contains("*510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION*");
        assertThat(r.assembly().lines().filter(l -> l.startsWith("include::")).toList()).containsExactly(
                "include::{topics}/topics/dex/k999999-background-information.adoc[leveloffset=+1]",
                "include::{topics}/topics/dex/k999999-performance-characteristics.adoc[leveloffset=+1]",
                "include::{topics}/topics/dex/k999999-conclusion.adoc[leveloffset=+1]");
    }

    @Test
    void aHeadingInsideAListingBlockDoesNotStartAFragment() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", RECORD), IngestFragments.Options.defaults());

        assertThat(r.fragments()).hasSize(3);
        assertThat(r.fragments().get(1).content()).contains("== this line is inside a listing block");
    }

    @Test
    void noWordIsAddedOrLostAndTheReportSaysWhatIsPlaceholder() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", RECORD), IngestFragments.Options.defaults());

        assertThat(checks(r)).contains("metadata-placeholder").contains("citation-url-derived")
                .doesNotContain("content-changed");
        assertThat(r.report().details()).containsEntry("k-number", "K999999").containsEntry("section-level", 2)
                .containsEntry("fragment-count", 3);
    }

    @Test
    void aSuppliedCitationUrlIsUsedAndNotFlagged() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", RECORD),
                new IngestFragments.Options(2, "https://example.org/K999999.pdf", 5000, true));

        assertThat(checks(r)).doesNotContain("citation-url-derived");
        assertThat(r.fragments().get(0).content()).contains("https://example.org/K999999.pdf");
    }

    @Test
    void withSplittingOffAnOversizeSectionIsKeptWholeAndNotedInItsHeader() throws Exception {
        String big = RECORD.replace("The assay was linear.", "word ".repeat(1200));

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", big), NO_SPLIT);

        assertThat(r.report().hasErrors()).isFalse();
        assertThat(checks(r)).contains("fragment-oversize");
        assertThat(r.fragments()).hasSize(3);
        assertThat(r.fragments().get(1).content()).contains("Kept whole as a reference exception");
        assertThat(r.fragments().get(0).content()).doesNotContain("reference exception");
    }

    @Test
    void anOversizeSectionIsSplitAtItsLetteredSubsections() throws Exception {
        String text = "510(k) Number: K777777\n\n= Title\n\n"
                + "== I Intro:\n\ntext\n\n"
                + "== II Device Characteristics:\n\nSection introduction.\n\n"
                + "=== A Device Description:\n\n" + filler(700) + "\n\n"
                + "=== B Principle of Operation:\n\n" + filler(700) + "\n\n"
                + "=== C Instrument Information:\n\n" + filler(700) + "\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-k777777-intro",
                "dex-k777777-device-characteristics-device-description",
                "dex-k777777-device-characteristics-principle-of-operation",
                "dex-k777777-device-characteristics-instrument-information");
        assertThat(r.fragments().get(1).kind()).isEqualTo("subsection");
        assertThat(r.fragments().get(1).content()).contains("\n[[dex-k777777-device-characteristics-device-description]]\n= A Device Description:\n")
                .contains(":topic-related: dex-k777777-device-characteristics-principle-of-operation, "
                        + "dex-k777777-device-characteristics-instrument-information");
        assertThat(r.fragments().get(0).content()).doesNotContain(":topic-related:");
        assertThat(r.assembly()).contains("\n== II Device Characteristics:\n\nSection introduction.\n")
                .contains("include::{topics}/topics/dex/k777777-device-characteristics-device-description.adoc[leveloffset=+2]")
                .doesNotContain("== A Device Description");
    }

    @Test
    void aSubsectionStillOverTheLimitIsSplitAtItsNumberedStudiesAndSmallOnesMerge() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", studies()), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-k888888-background",
                "dex-k888888-performance-characteristics-precision-study",
                "dex-k888888-performance-characteristics-reactivity",
                "dex-k888888-performance-characteristics-cut-off");
        IngestFragments.Fragment precision = r.fragments().get(1);
        assertThat(precision.kind()).isEqualTo("study");
        assertThat(precision.content()).contains("= Precision Study:\n\n// tag::body[]\n")
                .contains("\n. Linearity:\nNot applicable.\n").contains("// end::body[]");
        assertThat(r.assembly()).contains("\n=== A Analytical Performance:\n")
                .contains(". Precision Study:\n+\ninclude::{topics}/topics/dex/k888888-performance-characteristics-precision-study.adoc[tag=body]")
                .contains("[start=4]\n. Cut-Off:\n+\ninclude::{topics}/topics/dex/k888888-performance-characteristics-cut-off.adoc[tag=body]")
                .doesNotContain(". Linearity:\n+\ninclude");
    }

    @Test
    void aDocumentWithNoNumberTakesItsIdFromTheTitleAndItsLicenseIsUnconfirmed() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc",
                "= Acme Respiratory Panel IFU\n\n== 1 Intended Use\n\ntext\n\n== 2 Procedure\n\nmore\n"),
                IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(checks(r)).contains("document-id-derived").contains("license-unconfirmed");
        assertThat(r.report().details()).containsEntry("document-kind", "document")
                .containsEntry("document-id", "acme-respiratory-panel-ifu");
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-acme-respiratory-panel-ifu-intended-use", "dex-acme-respiratory-panel-ifu-procedure");
        assertThat(r.fragments().get(0).content()).contains(":topic-license: License not confirmed")
                .contains("Source document: Acme Respiratory Panel IFU.");
        assertThat(r.assemblyName()).isEqualTo("acme-respiratory-panel-ifu.adoc");
    }

    @Test
    void headingsAllAtOneLevelAreMovedToTheLevelTheirLabelsImply() throws Exception {
        String text = "= Title\n\n510(k) Number: K555555\n\n"
                + "== I Background Information:\n\nintro\n\n== A Applicant\n\nAcme\n\n== Names\n\nPanel\n\n"
                + "== II Performance:\n\n== B Study\n\nbody\n\n== 1 Detail\n\nmore\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(checks(r)).contains("levels-inferred");
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-k555555-background-information", "dex-k555555-performance");
        assertThat(r.fragments().get(0).content()).contains("\n= I Background Information:\n")
                .contains("\n== A Applicant\n").contains("\n== Names\n");
        assertThat(r.fragments().get(1).content()).contains("\n== B Study\n").contains("\n=== 1 Detail\n");
    }

    @Test
    void aLoneRomanOneAmongLetteredHeadingsIsALetter() throws Exception {
        StringBuilder text = new StringBuilder("= Title\n\n510(k) Number: K444444\n\n");
        for (char c = 'A'; c <= 'L'; c++) {
            text.append("== ").append(c).append(". Heading ").append(c).append(":\n\nbody ").append(c).append("\n\n");
        }

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text.toString()),
                IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(checks(r)).doesNotContain("levels-inferred");
        assertThat(r.fragments()).hasSize(12);
    }

    @Test
    void aHeadingAboveTheSplitLevelBetweenSectionsIsAnError() throws Exception {
        String text = "510(k) Number: K111111\n\n= Title\n\n=== A One\n\ntext\n\n== B Two\n\ntext\n\n=== C Three\n\nmore\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(checks(r)).isEqualTo("heading-above-section-level");
        assertThat(r.report().findings().get(0).message()).contains("sectionLevel=2");
    }

    @Test
    void aLongTitleGivesAShortIdPrefix() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc",
                "= Xpert Xpress CoV-2 plus ENGLISH Package Insert 303-0272 Rev A1_3\n\n== One\n\na\n\n== Two\n\nb\n"),
                IngestFragments.Options.defaults());

        assertThat(r.report().details()).containsEntry("document-id", "xpert-xpress-cov-2-plus-english");
        assertThat(r.fragments().get(0).id()).isEqualTo("dex-xpert-xpress-cov-2-plus-english-one");
    }

    @Test
    void aSuppliedDocumentIdOverridesTheTitle() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", "= T\n\n== One\n\na\n\n== Two\n\nb\n"),
                new IngestFragments.Options(0, null, 5000, true, "xpert-ifu"));

        assertThat(checks(r)).doesNotContain("document-id-derived");
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-xpert-ifu-one", "dex-xpert-ifu-two");
    }

    @Test
    void aGudidRecordTakesItsIdFromTheDeviceIdentifier() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc",
                "= AccessGUDID - DEVICE: Acme\n\n== Device Identifiers\n\nPrimary DI Number: 00812345678905\n\n"
                        + "== Contacts\n\nPhone\n"), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.report().details()).containsEntry("document-kind", "gudid")
                .containsEntry("document-id", "gudid-00812345678905");
        assertThat(r.fragments().get(0).content())
                .contains("https://accessgudid.nlm.nih.gov/devices/00812345678905");
        assertThat(checks(r)).contains("citation-url-derived").contains("license-unconfirmed");
    }

    @Test
    void aDocumentWithOnlyATitleIsKeptAsOneFragment() throws Exception {
        String text = "= AccessGUDID - DEVICE: Acme\n\n*Primary DI Number:*\n\n00812345678905\n\n*Brand Name:*\n\nAcme\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(checks(r)).contains("single-fragment");
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly("dex-gudid-00812345678905");
        assertThat(r.fragments().get(0).content()).contains("\n[[dex-gudid-00812345678905]]\n= AccessGUDID - DEVICE: Acme\n");
        assertThat(r.assembly()).contains("include::{topics}/topics/dex/gudid-00812345678905.adoc[leveloffset=+0]");
    }

    @Test
    void aScannedGudidRecordTakesItsIdentifierFromTheDeviceHeading() throws Exception {
        String text = "= scan\n\nimage:file:///C:/tmp/a.png[]\n\n== DEVICE: GeneXperte (07332940008581)\n\ntext\n\n"
                + "== DEVICE IDENTIFIER (DI) INFORMATION\n\nmore\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(r.report().details()).containsEntry("document-id", "gudid-07332940008581");
        assertThat(checks(r)).contains("image-local-path");
    }

    @Test
    void noNumberAndNoTitleIsAnError() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", "== One\n\na\n\n== Two\n\nb\n"),
                IngestFragments.Options.defaults());

        assertThat(checks(r)).isEqualTo("document-id-missing");
        assertThat(r.fragments()).isEmpty();
    }

    @Test
    void theSectionLevelIsChosenFromTheHeadingsWhenNotGiven() throws Exception {
        IngestFragments.Result flat = IngestFragments.run(write("flat.adoc",
                "510(k) Number: K111111\n\n= One\n\na\n\n= Two\n\nb\n"), IngestFragments.Options.defaults());
        IngestFragments.Result deeper = IngestFragments.run(write("deep.adoc",
                "510(k) Number: K111111\n\n= Title\n\n== One\n\na\n\n== Two\n\nb\n"), IngestFragments.Options.defaults());

        assertThat(flat.report().details()).containsEntry("section-level", 1);
        assertThat(deeper.report().details()).containsEntry("section-level", 2);
        assertThat(flat.fragments()).hasSize(2);
        assertThat(flat.assembly()).contains("[leveloffset=+0]");
    }

    @Test
    void noSectionHeadingsIsAnError() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", "510(k) Number: K111111\n\njust text\n"),
                IngestFragments.Options.defaults());

        assertThat(checks(r)).isEqualTo("no-sections");
    }

    @Test
    void twoHeadingsThatGiveTheSameIdGetANumberedSuffixAndAWarning() throws Exception {
        String text = "510(k) Number: K111111\n\n== I Study:\n\na\n\n== II Study\n\nb\n\n== III Study.\n\nc\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(r.report().hasErrors()).as(checks(r)).isFalse();
        assertThat(checks(r)).contains("id-suffixed");
        assertThat(r.fragments()).extracting(IngestFragments.Fragment::id).containsExactly(
                "dex-k111111-study", "dex-k111111-study-2", "dex-k111111-study-3");
        assertThat(r.fragments().get(1).fileName()).isEqualTo("k111111-study-2.adoc");
    }

    @Test
    void aMissingFileAndInvalidSettingsAreReportedNotThrown() throws Exception {
        assertThat(checks(IngestFragments.run(dir.resolve("nope.adoc"), IngestFragments.Options.defaults())))
                .isEqualTo("source-unreadable");
        assertThat(checks(IngestFragments.run(write("k.adoc", RECORD), new IngestFragments.Options(9, null, 5000, true))))
                .isEqualTo("section-level-invalid");
        assertThat(checks(IngestFragments.run(write("k.adoc", RECORD), new IngestFragments.Options(2, null, 10, true))))
                .isEqualTo("size-limit-invalid");
    }

    @Test
    void theSlugDropsTheLabelLongQualifiersAndPunctuation() {
        assertThat(IngestFragments.slug("I Background Information:")).isEqualTo("background-information");
        assertThat(IngestFragments.slug("VII Performance Characteristics (if/when applicable):"))
                .isEqualTo("performance-characteristics");
        assertThat(IngestFragments.slug("A. 510(k) Number:")).isEqualTo("510-k-number");
        assertThat(IngestFragments.slug("Conclusion")).isEqualTo("conclusion");
        assertThat(IngestFragments.shorten("a-very-long-slug-indeed", 12)).isEqualTo("a-very-long");
    }
}

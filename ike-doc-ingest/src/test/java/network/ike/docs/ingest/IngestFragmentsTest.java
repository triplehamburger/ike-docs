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
    void aMissingKNumberIsAnError() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", "= T\n\n== I One\n\ntext\n"),
                IngestFragments.Options.defaults());

        assertThat(checks(r)).isEqualTo("k-number-missing");
        assertThat(r.fragments()).isEmpty();
    }

    @Test
    void noSectionHeadingsIsAnError() throws Exception {
        IngestFragments.Result r = IngestFragments.run(write("k.adoc", "510(k) Number: K111111\n\njust text\n"),
                IngestFragments.Options.defaults());

        assertThat(checks(r)).isEqualTo("no-sections");
    }

    @Test
    void twoHeadingsThatGiveTheSameIdAreAnError() throws Exception {
        String text = "510(k) Number: K111111\n\n== I Study:\n\na\n\n== II Study\n\nb\n";

        IngestFragments.Result r = IngestFragments.run(write("k.adoc", text), IngestFragments.Options.defaults());

        assertThat(checks(r)).contains("id-duplicate");
        assertThat(r.fragments()).isEmpty();
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

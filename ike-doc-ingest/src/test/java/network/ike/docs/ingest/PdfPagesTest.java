package network.ike.docs.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPagesTest {

    @TempDir
    Path dir;

    private static List<String> checks(PdfPages.Loaded loaded) {
        return loaded.findings().stream().map(Finding::check).toList();
    }

    @Test
    void textIsExtractedPerPageInOrder() throws IOException {
        Path pdf = dir.resolve("two.pdf");
        DexTexts.writePdf(pdf, List.of("First page line one\nFirst page line two",
                "Second page only, with enough words to count as a page of text"));

        PdfPages.Loaded loaded = PdfPages.extractText(pdf);

        assertThat(loaded.findings()).isEmpty();
        assertThat(loaded.pageTexts()).hasSize(2);
        assertThat(loaded.pageTexts().get(0)).contains("First page line one").contains("First page line two");
        assertThat(loaded.pageTexts().get(1)).contains("Second page only").doesNotContain("First");
        assertThat(PdfPages.isScanned(loaded.pageTexts())).isFalse();
    }

    @Test
    void pagesWithoutTextAreTreatedAsScanned() throws IOException {
        Path pdf = dir.resolve("blank.pdf");
        DexTexts.writeBlankPdf(pdf, 3);

        PdfPages.Loaded loaded = PdfPages.extractText(pdf);

        assertThat(loaded.failed()).isFalse();
        assertThat(loaded.pageTexts()).hasSize(3);
        assertThat(PdfPages.isScanned(loaded.pageTexts())).isTrue();
    }

    @Test
    void scannedNeedsAtLeastHalfOfTheFirstPagesToBeBare() {
        String text = "enough visible characters on this page to count as text";

        assertThat(PdfPages.isScanned(List.of(text, text, text, "", ""))).isFalse();
        assertThat(PdfPages.isScanned(List.of(text, text, "", "", ""))).isTrue();
        assertThat(PdfPages.isScanned(List.of(text, ""))).isTrue();
        assertThat(PdfPages.isScanned(List.of())).isFalse();
    }

    @Test
    void aMissingFileIsAFindingNotAnException() {
        PdfPages.Loaded loaded = PdfPages.extractText(dir.resolve("nope.pdf"));

        assertThat(checks(loaded)).containsExactly("source-missing");
        assertThat(loaded.failed()).isTrue();
    }

    @Test
    void anEmptyFileAndATextFileAreRefusedByName() throws IOException {
        Path empty = Files.createFile(dir.resolve("empty.pdf"));
        Path text = Files.writeString(dir.resolve("notes.pdf"), "this is not a pdf at all");

        assertThat(checks(PdfPages.extractText(empty))).containsExactly("pdf-empty");
        assertThat(checks(PdfPages.extractText(text))).containsExactly("not-a-pdf");
    }

    @Test
    void aTruncatedPdfIsReportedAsCorrupt() throws IOException {
        Path good = dir.resolve("good.pdf");
        DexTexts.writePdf(good, List.of("some text on a page"));
        byte[] bytes = Files.readAllBytes(good);
        Path cut = dir.resolve("cut.pdf");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        PdfPages.Loaded loaded = PdfPages.extractText(cut);

        assertThat(loaded.failed()).isTrue();
        assertThat(checks(loaded)).containsAnyOf("pdf-corrupt", "pdf-empty");
    }

    @Test
    void aPasswordProtectedPdfIsReportedAsEncrypted() throws IOException {
        Path locked = dir.resolve("locked.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw",
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(locked.toFile());
        } catch (IOException | RuntimeException e) {
            Assumptions.abort("cannot create an encrypted test PDF here: " + e);
        }

        assertThat(checks(PdfPages.extractText(locked))).containsExactly("pdf-encrypted");
    }

    @Test
    void aPageRendersToAPngAndABadPageIsAFinding() throws IOException {
        Path pdf = dir.resolve("two.pdf");
        DexTexts.writePdf(pdf, List.of("page one", "page two"));
        Path png = dir.resolve("pages/page-002.png");

        assertThat(PdfPages.renderPage(pdf, 1, png)).isEmpty();
        BufferedImage image = ImageIO.read(png.toFile());
        assertThat(image.getWidth()).isGreaterThan(100);
        assertThat(image.getHeight()).isGreaterThan(100);

        List<Finding> bad = PdfPages.renderPage(pdf, 7, dir.resolve("pages/page-008.png"));
        assertThat(bad).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("page-render-failed");
            assertThat(f.isError()).isTrue();
        });
    }
}

package network.ike.docs.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The only class that touches PDFBox: page text for detection and page
/// images for the scanned fallback.
///
/// Nothing here throws for a bad PDF. Every failure is a {@link Finding}
/// naming the check, so a damaged, encrypted, oversized or non-PDF input
/// ends as a clean report instead of a stack trace.
final class PdfPages {

    /// Largest PDF accepted, in bytes.
    static final long MAX_BYTES = 300L * 1024 * 1024;

    /// Most pages accepted.
    static final int MAX_PAGES = 1000;

    /// A page with fewer non-whitespace characters than this has no usable text.
    static final int MIN_PAGE_CHARS = 25;

    /// How many leading pages are sampled to decide whether a PDF is scanned.
    static final int SAMPLE_PAGES = 5;

    private static final int RENDER_DPI = 150;

    private PdfPages() {
    }

    /// Extracted text of a PDF.
    ///
    /// @param pageTexts one string per page, in page order
    /// @param findings  every problem met while reading; an error means the text is unusable
    record Loaded(List<String> pageTexts, List<Finding> findings) {
        boolean failed() {
            return findings.stream().anyMatch(Finding::isError);
        }
    }

    /// Read the text of every page.
    ///
    /// @param pdf the PDF file
    /// @return the page texts, or findings explaining why there are none
    static Loaded extractText(Path pdf) {
        String name = pdf.getFileName().toString();
        List<Finding> findings = new ArrayList<>();
        Finding unusable = checkFile(pdf, name);
        if (unusable != null) {
            return new Loaded(List.of(), List.of(unusable));
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                return new Loaded(List.of(), List.of(Finding.error("pdf-empty", name, "the PDF has no pages")));
            }
            if (pages > MAX_PAGES) {
                return new Loaded(List.of(), List.of(Finding.error("pdf-too-long", name,
                        "the PDF has " + pages + " pages; the limit is " + MAX_PAGES)));
            }
            List<String> texts = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            for (int page = 1; page <= pages; page++) {
                try {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    texts.add(stripper.getText(doc));
                } catch (IOException | RuntimeException e) {
                    texts.add("");
                    findings.add(Finding.error("pdf-text-failed", name + " page " + page,
                            "text extraction failed: " + describe(e)));
                }
            }
            return new Loaded(texts, findings);
        } catch (InvalidPasswordException e) {
            return new Loaded(List.of(), List.of(Finding.error("pdf-encrypted", name,
                    "the PDF is password protected; remove the protection and try again")));
        } catch (IOException | RuntimeException e) {
            return new Loaded(List.of(), List.of(Finding.error("pdf-corrupt", name,
                    "the PDF could not be read: " + describe(e))));
        }
    }

    /// Whether a PDF has no usable text layer: at least half of its first
    /// pages have almost no text.
    ///
    /// @param pageTexts the extracted page texts
    /// @return true if the PDF should be treated as scanned
    static boolean isScanned(List<String> pageTexts) {
        int sample = Math.min(SAMPLE_PAGES, pageTexts.size());
        int bare = 0;
        for (int i = 0; i < sample; i++) {
            if (visibleChars(pageTexts.get(i)) < MIN_PAGE_CHARS) {
                bare++;
            }
        }
        return sample > 0 && bare * 2 >= sample;
    }

    /// Count the non-whitespace characters of a page.
    ///
    /// @param text page text
    /// @return the count
    static int visibleChars(String text) {
        return DexDetector.visibleChars(text);
    }

    /// Render one page to a PNG for an AI to read.
    ///
    /// @param pdf       the PDF file
    /// @param pageIndex zero-based page number
    /// @param png       where to write the image
    /// @return empty if rendered, otherwise the finding explaining the failure
    static List<Finding> renderPage(Path pdf, int pageIndex, Path png) {
        String where = pdf.getFileName() + " page " + (pageIndex + 1);
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.GRAY);
            Files.createDirectories(png.toAbsolutePath().getParent());
            if (!ImageIO.write(image, "png", png.toFile())) {
                return List.of(Finding.error("page-render-failed", where, "no PNG writer is available"));
            }
            return List.of();
        } catch (IOException | RuntimeException e) {
            return List.of(Finding.error("page-render-failed", where,
                    "the page could not be rendered to an image (scans in JBIG2 or JPEG 2000 may need an "
                            + "extra image plugin): " + describe(e)));
        }
    }

    private static Finding checkFile(Path pdf, String name) {
        if (!Files.isRegularFile(pdf)) {
            return Finding.error("source-missing", name, "the source file does not exist or is not a file");
        }
        try {
            long size = Files.size(pdf);
            if (size == 0) {
                return Finding.error("pdf-empty", name, "the file is empty");
            }
            if (size > MAX_BYTES) {
                return Finding.error("pdf-too-large", name,
                        "the file is " + size + " bytes; the limit is " + MAX_BYTES);
            }
            byte[] head = new byte[1024];
            int read;
            try (InputStream in = Files.newInputStream(pdf)) {
                read = in.readNBytes(head, 0, head.length);
            }
            if (!new String(head, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                return Finding.error("not-a-pdf", name, "the file does not start like a PDF (no %PDF- header)");
            }
            return null;
        } catch (IOException e) {
            return Finding.error("source-unreadable", name, "the file cannot be read: " + describe(e));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : message.lines().findFirst().orElse(message);
    }
}

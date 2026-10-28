package network.ike.docs.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Synthetic decision-summary text and PDFs shaped like the real FDA
/// layouts, so tests run without committing any FDA file.
final class DexTexts {

    private DexTexts() {
    }

    /// Legacy "Device and Instrument" layout: sections A. to P.
    static List<String> legacyDeviceAndInstrument() {
        String p1 = """
                510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION
                DECISION SUMMARY
                DEVICE AND INSTRUMENT TEMPLATE
                A. 510(k) Number:
                K031739
                B. Analyte:
                Troponin I
                C. Type of Test:
                Electrochemical two site ELISA
                D. Applicant:
                Example Corp
                E. Proprietary and Established Names:
                Example Assay
                F. Regulatory Information:
                21 CFR 862.1215
                """;
        String p2 = """
                G. Intended Use:
                For the quantitative determination of troponin I.
                H. Device Description
                A cartridge based assay.
                I. Substantial Equivalence Information:
                Predicate device name: Example Predicate
                J. Standard/Guidance Document Referenced (if applicable):
                EP5-A2
                K. Test Principle:
                Two site sandwich.
                L. Performance Characteristics (if/when applicable):
                Precision was acceptable.
                M. Instrument Name:
                Example Analyzer
                N. System Descriptions:
                Modules and software.
                O. Other Supportive Instrument Performance Characteristics Data Not Covered In
                the Performance Characteristics Section above.
                P. Conclusion:
                The submitted information supports a substantial equivalence decision.
                """;
        return List.of(p1, p2);
    }

    /// Legacy "Assay Only" layout with a lower-case K-number: sections A. to O.
    static List<String> legacyAssayOnly() {
        String p1 = """
                510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION
                DECISION SUMMARY
                ASSAY ONLY TEMPLATE
                A. 510(k) Number:
                k162200
                B. Purpose for Submission:
                New Device
                C. Measurand:
                Magnesium
                D. Type of Test:
                Quantitative photometric assay
                E. Applicant:
                Example Corp
                F. Proprietary and Established Names:
                Example Magnesium
                G. Regulatory Information:
                21 CFR 862.1495
                """;
        String p2 = """
                H. Intended Use:
                Magnesium measurement.
                I. Device Description:
                Reagent kit.
                J. Substantial Equivalence Information:
                Predicate: Example.
                K. Standard/Guidance Document Referenced (if applicable):
                EP6-A
                L. Test Principle:
                Colorimetric.
                M. Performance Characteristics (if/when applicable):
                Linearity acceptable.
                N. Proposed Labeling:
                Labeling is acceptable.
                O. Conclusion:
                The submitted information supports a substantial equivalence decision.
                """;
        return List.of(p1, p2);
    }

    /// Current layout: sections I to IX, unpunctuated lettered subsections, K-number footers.
    static List<String> currentRoman() {
        return currentRoman("K230440", 3, true);
    }

    /// Current layout with chosen footer K-number and total, optionally without footers.
    static List<String> currentRoman(String footerK, int footerTotal, boolean footers) {
        String p1 = """
                510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION
                DECISION SUMMARY
                ASSAY AND INSTRUMENT
                I Background Information:
                A 510(k) Number
                K230440
                B Applicant
                Cepheid
                C Proprietary and Established Names
                Xpert Xpress CoV-2 plus
                V Notice printed before the real fifth section
                II Submission/Device Overview:
                A Purpose for Submission:
                To obtain a substantial equivalence determination.
                B Measurand:
                Nucleic acid
                C Type of Test:
                Real-time RT-PCR
                III Intended Use/Indications for Use:
                A Intended Use(s):
                Qualitative detection.
                """;
        String p2 = """
                IV Device/System Characteristics:
                A Device Description:
                A cartridge.
                B Principle of Operation:
                PCR.
                V Substantial Equivalence Information:
                A Predicate Device Name(s):
                Example predicate.
                VI Standards/Guidance Documents Referenced:
                ISO 14971
                VII Performance Characteristics (if/when applicable):
                A Analytical Performance:
                Reproducibility was acceptable.
                B Comparison Studies:
                Agreement was acceptable.
                """;
        String p3 = """
                VIII Proposed Labeling:
                The labeling supports the finding of substantial equivalence.
                IX Conclusion:
                The submitted information supports a substantial equivalence decision.
                """;
        List<String> pages = new ArrayList<>(List.of(p1, p2, p3));
        if (footers) {
            for (int i = 1; i < pages.size(); i++) {
                pages.set(i, pages.get(i) + footerK + " - Page " + (i + 1) + " of " + footerTotal + "\n");
            }
        }
        return pages;
    }

    /// Write a text PDF with one page per string.
    static void writePdf(Path file, List<String> pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 10);
                    cs.setLeading(12);
                    cs.newLineAtOffset(40, 760);
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

    /// Write a PDF whose pages have no text, like a scan with nothing recognizable as text.
    static void writeBlankPdf(Path file, int pages) throws IOException {
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
}

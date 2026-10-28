package network.ike.docs.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Decides whether page text is an FDA 510(k) Substantial Equivalence
/// Determination Decision Summary (a DeX source), and if so which layout
/// and template it uses.
///
/// Detection works on text only, so it runs the same on a PDF's text layer
/// and on an AI transcript of a scan. Two layouts exist:
///
/// - **Legacy:** top-level sections lettered `A.`, `B.` … (the Device and
///   Instrument and the Assay Only templates). Letters differ by template,
///   so sections are recorded by title, never assumed by letter.
/// - **Current:** top-level sections numbered `I`, `II` … `IX`, with
///   unpunctuated lettered subsections (`A Purpose for Submission:`).
///
/// Every doubtful case becomes a finding; nothing is guessed silently.
public final class DexDetector {

    /// What the source is.
    public enum Kind {
        /// A 510(k) decision summary.
        DEX_510K,
        /// Anything else.
        NOT_DEX
    }

    /// Which section layout a decision summary uses.
    public enum Layout {
        /// Sections lettered A., B., ….
        LEGACY_LETTERED,
        /// Sections numbered I … IX.
        CURRENT_ROMAN,
        /// No layout could be established.
        UNKNOWN
    }

    /// One piece of evidence for the classification.
    ///
    /// @param name  the signal, e.g. `title`
    /// @param page  the one-based page searched
    /// @param found whether it was found
    public record Signal(String name, int page, boolean found) {
    }

    /// A top-level section found in the document.
    ///
    /// @param label the section label as printed, e.g. `VII` or `L`
    /// @param title the section title without trailing colon
    /// @param page  the one-based page where it starts
    public record Section(String label, String title, int page) {
    }

    /// The outcome of detection.
    ///
    /// @param kind     decision summary or not
    /// @param layout   the section layout, or UNKNOWN
    /// @param template the template name, or UNKNOWN
    /// @param kNumber  the upper-case K-number, or empty
    /// @param guess    for a non-DeX source, a best guess of what it is
    /// @param signals  the signals checked on page 1
    /// @param sections the top-level sections found, in order
    /// @param findings every problem found
    public record Detection(Kind kind, Layout layout, String template, String kNumber, String guess,
                            List<Signal> signals, List<Section> sections, List<Finding> findings) {
        /// Copy the lists so a detection cannot change after it is built.
        public Detection {
            signals = List.copyOf(signals);
            sections = List.copyOf(sections);
            findings = List.copyOf(findings);
        }
    }

    private static final Pattern TITLE =
            Pattern.compile("510\\(k\\)\\s+SUBSTANTIAL\\s+EQUIVALENCE\\s+DETERMINATION", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARY =
            Pattern.compile("\\bDECISION\\s+SUMMARY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern K_FIELD =
            Pattern.compile("\\bA\\.?\\s+510\\(k\\)\\s+Number:?\\s+(K\\d{6})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOTER =
            Pattern.compile("\\b(K\\d{6})\\s*[-–]\\s*Page\\s+\\d+\\s+of\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final List<String> ROMAN = List.of("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX",
            "X", "XI", "XII");
    private static final Pattern ROMAN_LINE = Pattern.compile(
            "^\\s{0,8}(I|II|III|IV|V|VI|VII|VIII|IX|X|XI|XII)\\s+([A-Z]\\S.*?)\\s*$");
    private static final Pattern LETTER_LINE = Pattern.compile("^\\s{0,8}([A-Z])\\.\\s+(\\S.*?)\\s*$");

    private static final int MIN_SEQUENCE = 3;

    private DexDetector() {
    }

    /// Whether page 1 carries all three decision-summary signals.
    ///
    /// @param firstPage the text of page 1
    /// @return true if the title, the decision-summary heading and a
    ///         K-number field are all present
    public static boolean hasAllSignals(String firstPage) {
        String page = collapse(firstPage);
        return TITLE.matcher(page).find() && SUMMARY.matcher(page).find() && K_FIELD.matcher(page).find();
    }

    /// Detect from all pages of text.
    ///
    /// @param pages  the text of each page, in order; must not be empty
    /// @param source the source name, used as the component of findings
    /// @return the detection
    public static Detection detect(List<String> pages, String source) {
        List<Finding> findings = new ArrayList<>();
        String first = collapse(pages.get(0));
        boolean title = TITLE.matcher(first).find();
        boolean summary = SUMMARY.matcher(first).find();
        Matcher k = K_FIELD.matcher(first);
        boolean kField = k.find();
        List<Signal> signals = List.of(new Signal("title", 1, title), new Signal("decision-summary", 1, summary),
                new Signal("k-number-field", 1, kField));

        if (!(title && summary && kField)) {
            return notDex(first, pages, source, signals, findings);
        }

        String kNumber = k.group(1).toUpperCase(Locale.ROOT);
        String template = template(first);
        if (template.equals("UNKNOWN")) {
            findings.add(Finding.warning("template-unrecognized", source,
                    "page 1 names no known template (Device and Instrument, Assay Only, Assay and Instrument)"));
        }

        List<Section> roman = sequence(pages, ROMAN_LINE, ROMAN);
        Layout layout;
        List<Section> sections;
        if (roman.size() >= MIN_SEQUENCE) {
            layout = Layout.CURRENT_ROMAN;
            sections = roman;
        } else {
            List<String> letters = new ArrayList<>();
            for (char c = 'A'; c <= 'Z'; c++) {
                letters.add(String.valueOf(c));
            }
            List<Section> lettered = sequence(pages, LETTER_LINE, letters);
            if (lettered.size() >= MIN_SEQUENCE) {
                layout = Layout.LEGACY_LETTERED;
                sections = lettered;
            } else {
                layout = Layout.UNKNOWN;
                sections = List.of();
                findings.add(Finding.error("layout-unknown", source,
                        "this is a decision summary but its section headings match neither the lettered "
                                + "(A., B., …) nor the numbered (I, II, …) layout"));
            }
        }

        if (layout != Layout.UNKNOWN) {
            checkComplete(layout, sections, source, findings);
        }
        if (layout == Layout.CURRENT_ROMAN) {
            checkFooters(pages, kNumber, source, findings);
        }
        return new Detection(Kind.DEX_510K, layout, template, kNumber, "", signals, sections, findings);
    }

    private static Detection notDex(String first, List<String> pages, String source, List<Signal> signals,
                                    List<Finding> findings) {
        List<String> found = signals.stream().filter(Signal::found).map(Signal::name).toList();
        List<String> missing = signals.stream().filter(s -> !s.found()).map(Signal::name).toList();
        if (!found.isEmpty()) {
            findings.add(Finding.warning("partial-signals", source,
                    "page 1 has " + found + " but not " + missing + "; if this is a decision summary the text "
                            + "layer may be damaged, so try -Dike.ingest.forceScan=true"));
        }
        for (int i = 1; i < Math.min(3, pages.size()); i++) {
            if (TITLE.matcher(collapse(pages.get(i))).find()) {
                findings.add(Finding.warning("title-not-on-first-page", source,
                        "the decision-summary title appears on page " + (i + 1) + ", not page 1; "
                                + "a cover letter may precede it, which is not supported"));
                break;
            }
        }
        String guess = guess(first);
        if (visibleChars(first) < 200) {
            findings.add(Finding.info("sparse-first-page", source,
                    "page 1 has very little text; if this is a scan, use -Dike.ingest.forceScan=true"));
        }
        findings.add(Finding.info("not-dex", source,
                "not a 510(k) decision summary (best guess: " + guess + ")"));
        return new Detection(Kind.NOT_DEX, Layout.UNKNOWN, "UNKNOWN", "", guess, signals, List.of(), findings);
    }

    private static String guess(String firstPage) {
        if (Pattern.compile("\\bRe:\\s*K\\d{6}", Pattern.CASE_INSENSITIVE).matcher(firstPage).find()
                && Pattern.compile("\\bDear\\b", Pattern.CASE_INSENSITIVE).matcher(firstPage).find()) {
            return "fda-clearance-letter";
        }
        if (firstPage.contains("AccessGUDID")) {
            return "gudid-record";
        }
        if (Pattern.compile("Instructions\\s+for\\s+Use", Pattern.CASE_INSENSITIVE).matcher(firstPage).find()) {
            return "manufacturer-ifu";
        }
        return "unknown";
    }

    private static String template(String firstPage) {
        if (Pattern.compile("ASSAY\\s+AND\\s+INSTRUMENT\\s+COMBINATION\\s+TEMPLATE", Pattern.CASE_INSENSITIVE)
                .matcher(firstPage).find()) {
            return "ASSAY_AND_INSTRUMENT_COMBINATION";
        }
        if (Pattern.compile("DEVICE\\s+AND\\s+INSTRUMENT\\s+TEMPLATE", Pattern.CASE_INSENSITIVE)
                .matcher(firstPage).find()) {
            return "DEVICE_AND_INSTRUMENT";
        }
        if (Pattern.compile("ASSAY\\s+ONLY\\s+TEMPLATE", Pattern.CASE_INSENSITIVE).matcher(firstPage).find()) {
            return "ASSAY_ONLY";
        }
        if (Pattern.compile("ASSAY\\s+AND\\s+INSTRUMENT", Pattern.CASE_INSENSITIVE).matcher(firstPage).find()) {
            return "ASSAY_AND_INSTRUMENT";
        }
        return "UNKNOWN";
    }

    /// Walk the lines once and take each label in order. A line that is not
    /// the next expected label is ignored, so body text cannot derail it.
    private static List<Section> sequence(List<String> pages, Pattern line, List<String> labels) {
        List<Section> found = new ArrayList<>();
        int next = 0;
        for (int p = 0; p < pages.size() && next < labels.size(); p++) {
            for (String text : pages.get(p).split("\n")) {
                Matcher m = line.matcher(text);
                if (next < labels.size() && m.matches() && m.group(1).equals(labels.get(next))) {
                    found.add(new Section(m.group(1), cleanTitle(m.group(2)), p + 1));
                    next++;
                }
            }
        }
        return found;
    }

    private static void checkComplete(Layout layout, List<Section> sections, String source, List<Finding> findings) {
        Section last = sections.get(sections.size() - 1);
        if (!last.title().toLowerCase(Locale.ROOT).startsWith("conclusion")) {
            List<String> labels = layout == Layout.CURRENT_ROMAN ? ROMAN : alphabet();
            String expected = sections.size() < labels.size() ? labels.get(sections.size()) : "(none)";
            findings.add(Finding.error("sections-incomplete", source,
                    "the section sequence stops after " + last.label() + " '" + last.title()
                            + "' (page " + last.page() + "); expected " + expected
                            + " next, and the last section should be the Conclusion"));
        }
    }

    private static List<String> alphabet() {
        List<String> letters = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) {
            letters.add(String.valueOf(c));
        }
        return letters;
    }

    private static void checkFooters(List<String> pages, String kNumber, String source, List<Finding> findings) {
        Set<String> kNumbers = new LinkedHashSet<>();
        Set<String> totals = new LinkedHashSet<>();
        for (String page : pages) {
            Matcher m = FOOTER.matcher(page);
            while (m.find()) {
                kNumbers.add(m.group(1).toUpperCase(Locale.ROOT));
                totals.add(m.group(2));
            }
        }
        if (kNumbers.isEmpty()) {
            findings.add(Finding.warning("footer-not-found", source,
                    "no 'K###### - Page n of N' footers were found to cross-check the K-number"));
            return;
        }
        if (!kNumbers.equals(Set.of(kNumber))) {
            findings.add(Finding.error("k-number-mismatch", source,
                    "the K-number field says " + kNumber + " but page footers say " + kNumbers));
        }
        if (totals.size() > 1 || !totals.contains(String.valueOf(pages.size()))) {
            findings.add(Finding.warning("page-count-mismatch", source,
                    "footers say the document has " + totals + " page(s) but the text covers " + pages.size()));
        }
    }

    private static String cleanTitle(String raw) {
        return raw.replaceAll("\\s+", " ").replaceAll("[:\\s]+$", "").strip();
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /// Count the non-whitespace characters of a page.
    ///
    /// @param text page text
    /// @return the count
    static int visibleChars(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }
}

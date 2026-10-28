package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The `ingest-detect` stage: classify a source PDF as a 510(k) decision
/// summary or not, and for a scanned PDF run the AI transcription fallback
/// until the pages are text the deterministic detector can read.
///
/// A scanned PDF costs the least AI work that can answer the question:
/// page 1 is transcribed first, and only if it carries the decision-summary
/// signals are the remaining pages requested. Once every page has an
/// accepted transcript, detection continues exactly as for a text PDF.
///
/// Nothing here throws for a bad input: every failure is a finding.
public final class IngestDetect {

    /// The goal name written into the report.
    public static final String GOAL = "ingest-detect";

    /// The work-order kind for one page of a scanned source.
    public static final String TRANSCRIBE_PAGE = "transcribe-page";

    /// How an answerer should transcribe a page. Stated once in `needs-ai.yaml`.
    public static final String INSTRUCTIONS = """
            For each item, read the page image named in its context and transcribe the page exactly.
            Copy every character as printed, including numbers, symbols, units and running headers and
            footers. Do not correct, reword, summarize or add anything. Keep one printed line per line.
            Write each table between a line <<TABLE>> and a line <<END TABLE>>, one table row per line,
            with cells separated by ' | ' and an empty cell for each spanned column, so every row of a
            table has the same number of cells.
            Answer in answers.yaml as a list named answers; each entry has the item id and the page
            text as a | block:
              answers:
                - id: transcribe-page-0123456789ab
                  value: |
                    the page text""";

    /// Per-kind answer validation for the work orders this stage issues.
    public static final Map<String, AnswerCheck> CHECKS = Map.of(TRANSCRIBE_PAGE, IngestDetect::checkTranscript);

    private static final int MAX_TRANSCRIPT_CHARS = 200_000;
    private static final int MIN_TRANSCRIPT_CHARS = 20;
    private static final int MAX_PROBLEMS = 10;
    private static final String ACCESSDATA = "https://www.accessdata.fda.gov/cdrh_docs/reviews/";

    private IngestDetect() {
    }

    /// Settings for a run.
    ///
    /// @param pagesDir   where page images for the scanned fallback are written
    /// @param forceScan  treat the PDF as scanned even if it has a text layer
    /// @param sourceUrl  the citation URL to use, or null to derive it from the K-number
    public record Options(Path pagesDir, boolean forceScan, String sourceUrl) {
        /// Validate the options and treat a blank URL as absent.
        ///
        /// @throws NullPointerException if pagesDir is null
        public Options {
            Objects.requireNonNull(pagesDir, "pagesDir");
            sourceUrl = sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.strip();
        }
    }

    /// What a run produced.
    ///
    /// @param report  the report, for `detect.yaml`
    /// @param needsAi the open work orders for `needs-ai.yaml`, or empty if none
    public record Result(IngestReport report, Optional<NeedsAiFile> needsAi) {
    }

    /// Classify a source PDF.
    ///
    /// @param pdf         the source PDF
    /// @param options     run settings
    /// @param answersYaml the contents of `answers.yaml`, or null if there is none
    /// @return the report and any open work orders
    public static Result run(Path pdf, Options options, String answersYaml) {
        String name = pdf.getFileName() == null ? pdf.toString() : pdf.getFileName().toString();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        List<IngestReport.Source> sources = new ArrayList<>();

        PdfPages.Loaded loaded = PdfPages.extractText(pdf);
        findings.addAll(loaded.findings());
        if (loaded.failed()) {
            return finish(sources, details, findings, "UNREADABLE", List.of());
        }
        String sourceHash;
        try {
            sourceHash = IngestHash.sha256Hex(pdf);
        } catch (IOException e) {
            findings.add(Finding.error("source-unreadable", name, "cannot hash the source: " + e.getMessage()));
            return finish(sources, details, findings, "UNREADABLE", List.of());
        }
        sources.add(new IngestReport.Source(name, sourceHash));

        int pages = loaded.pageTexts().size();
        boolean scanned = options.forceScan() || PdfPages.isScanned(loaded.pageTexts());
        details.put("pages", pages);
        details.put("text-layer", !scanned);

        List<String> texts = loaded.pageTexts();
        List<NeedsAi> open = List.of();
        if (scanned) {
            findings.add(Finding.info("scanned-pdf", name,
                    "no usable text layer; pages are transcribed by AI and then detected like text"));
            details.put("transcribed", true);
            Scan scan = transcribe(pdf, name, sourceHash, pages, options, answersYaml, findings);
            if (scan.failed) {
                return finish(sources, details, findings, "UNREADABLE", List.of());
            }
            texts = scan.texts;
            open = scan.open;
            if (texts == null) {
                findings.add(Finding.warning("transcription-pending", name,
                        open.size() + " page transcript(s) still needed; answer needs-ai.yaml into answers.yaml "
                                + "and run again"));
                return finish(sources, details, findings, "SCANNED_PENDING", open);
            }
        }

        DexDetector.Detection detection = DexDetector.detect(texts, name);
        findings.addAll(detection.findings());
        details.put("layout", detection.layout().name());
        details.put("template", detection.template());
        details.put("signals", detection.signals().stream().map(s -> orderedMap(
                "name", s.name(), "page", s.page(), "found", s.found())).toList());
        details.put("sections", detection.sections().stream().map(s -> orderedMap(
                "label", s.label(), "title", s.title(), "page", s.page())).toList());
        if (detection.kind() == DexDetector.Kind.DEX_510K) {
            details.put("k-number", detection.kNumber());
            describeProvenance(detection.kNumber(), name, options, details, findings);
            if (scanned) {
                findings.add(Finding.warning("transcribed-source", name,
                        "the pages were transcribed by AI from a scan, so topics built from them must be "
                                + "marked draft and verified against the source"));
            }
        } else {
            details.put("guess", detection.guess());
        }
        return finish(sources, details, findings, detection.kind().name(), open);
    }

    private static Result finish(List<IngestReport.Source> sources, Map<String, Object> details,
                                 List<Finding> findings, String kind, List<NeedsAi> open) {
        details.put("kind", kind);
        IngestReport report = new IngestReport(GOAL, sources, details, findings, List.of());
        Optional<NeedsAiFile> file = open.isEmpty() ? Optional.empty()
                : Optional.of(new NeedsAiFile(INSTRUCTIONS, open));
        return new Result(report, file);
    }

    private static void describeProvenance(String kNumber, String name, Options options,
                                           Map<String, Object> details, List<Finding> findings) {
        String url;
        if (options.sourceUrl() == null) {
            url = ACCESSDATA + kNumber + ".pdf";
            details.put("citation-url-origin", "derived");
            findings.add(Finding.warning("citation-url-derived", name,
                    "the citation URL " + url + " was derived from the K-number and not checked; "
                            + "pass -Dike.ingest.sourceUrl to set it"));
        } else {
            url = options.sourceUrl();
            details.put("citation-url-origin", "supplied");
            if (!url.startsWith("https://")) {
                findings.add(Finding.error("citation-url-invalid", name, "the citation URL must start with https://"));
            } else if (!url.toUpperCase(Locale.ROOT).contains(kNumber)) {
                findings.add(Finding.warning("citation-url-mismatch", name,
                        "the citation URL does not contain the K-number " + kNumber));
            }
        }
        details.put("citation-url", url);
        details.put("license", ProvenanceAttributes.externalFederalPublicDomain(url).license());
    }

    private record Scan(List<String> texts, List<NeedsAi> open, boolean failed) {
    }

    private static Scan transcribe(Path pdf, String name, String sourceHash, int pages, Options options,
                                   String answersYaml, List<Finding> findings) {
        NeedsAi first = workOrder(pdf, name, sourceHash, 0, pages, options, findings);
        if (first == null) {
            return new Scan(null, List.of(), true);
        }
        String firstText = Answers.resolve(List.of(first), answersYaml, CHECKS).accepted().get(first.id());
        boolean dex = firstText != null && DexDetector.hasAllSignals(firstText);

        List<NeedsAi> items = new ArrayList<>(List.of(first));
        if (dex) {
            for (int i = 1; i < pages; i++) {
                NeedsAi item = workOrder(pdf, name, sourceHash, i, pages, options, findings);
                if (item == null) {
                    return new Scan(null, List.of(), true);
                }
                items.add(item);
            }
        }
        Answers.Resolution resolution = Answers.resolve(items, answersYaml, CHECKS);
        findings.addAll(resolution.findings());

        if (firstText != null && !dex) {
            return new Scan(List.of(firstText), List.of(), false);
        }
        if (!resolution.remaining().isEmpty()) {
            return new Scan(null, resolution.remaining(), false);
        }
        List<String> texts = new ArrayList<>();
        for (NeedsAi item : items) {
            texts.add(resolution.accepted().get(item.id()));
        }
        return new Scan(texts, List.of(), false);
    }

    private static NeedsAi workOrder(Path pdf, String name, String sourceHash, int index, int pages,
                                     Options options, List<Finding> findings) {
        String file = String.format("page-%03d.png", index + 1);
        List<Finding> failure = PdfPages.renderPage(pdf, index, options.pagesDir().resolve(file));
        if (!failure.isEmpty()) {
            findings.addAll(failure);
            return null;
        }
        String context = "Transcribe page " + (index + 1) + " of " + pages + " of " + name
                + " (source sha256 " + sourceHash + "). Image: " + options.pagesDir().getFileName() + "/" + file;
        return NeedsAi.of(TRANSCRIBE_PAGE, String.format("page-%03d", index + 1), true, context,
                "the exact text of this page, tables between <<TABLE>> and <<END TABLE>>");
    }

    private static List<String> checkTranscript(NeedsAi item, String value) {
        List<String> problems = new ArrayList<>();
        int visible = PdfPages.visibleChars(value);
        if (visible < MIN_TRANSCRIPT_CHARS) {
            problems.add("the transcript is nearly empty (" + visible + " visible characters)");
        }
        if (value.length() > MAX_TRANSCRIPT_CHARS) {
            problems.add("the transcript is longer than " + MAX_TRANSCRIPT_CHARS + " characters; it must be one page");
        }
        String[] lines = value.split("\n", -1);
        int tableStart = -1;
        int expectedCells = -1;
        for (int i = 0; i < lines.length && problems.size() < MAX_PROBLEMS; i++) {
            String line = lines[i].strip();
            int number = i + 1;
            if (line.equals("<<TABLE>>")) {
                if (tableStart >= 0) {
                    problems.add("line " + number + ": <<TABLE>> inside the table that began on line " + tableStart);
                }
                tableStart = number;
                expectedCells = -1;
            } else if (line.equals("<<END TABLE>>")) {
                if (tableStart < 0) {
                    problems.add("line " + number + ": <<END TABLE>> with no <<TABLE>> before it");
                }
                tableStart = -1;
            } else if (tableStart >= 0 && !line.isEmpty()) {
                int cells = line.split("\\s\\|\\s", -1).length;
                if (expectedCells < 0) {
                    expectedCells = cells;
                } else if (cells != expectedCells) {
                    problems.add("line " + number + ": the table row has " + cells + " cells but the first row of "
                            + "the table on line " + tableStart + " has " + expectedCells);
                }
            }
        }
        if (tableStart >= 0 && problems.size() < MAX_PROBLEMS) {
            problems.add("the <<TABLE>> on line " + tableStart + " is never closed with <<END TABLE>>");
        }
        return problems;
    }

    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}

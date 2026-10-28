package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Checks that the topics written from a source PDF still hold what the PDF
/// says. The check is on words, not layout: every word and number of the
/// PDF text is counted, and the words the topics are short of are reported,
/// page by page, so a dropped section shows as a run of weak pages.
///
/// A decision summary is also checked section by section: each section title
/// found by detection must appear in the topics.
///
/// Nothing here throws for bad input; every problem is a {@link Finding}.
public final class IngestCoverage {

    /// The largest share of the PDF's words the topics may lack before the check fails.
    public static final double DEFAULT_MAX_MISSING = 0.10;

    /// A page whose distinct words are covered less than this is reported.
    static final double WEAK_PAGE = 0.60;

    /// Pages with fewer words than this are not judged.
    static final int MIN_PAGE_WORDS = 20;

    /// Most weak pages named in the report before the rest are only counted.
    private static final int MAX_PAGE_FINDINGS = 25;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private IngestCoverage() {
    }

    /// The outcome of a coverage check.
    ///
    /// @param report what was compared and every problem found
    public record Result(IngestReport report) {
    }

    /// Compare a source PDF with the topics written from it.
    ///
    /// @param pdf        the source PDF; it must have a text layer
    /// @param topicsDir  the folder holding the topic `.adoc` files, searched recursively
    /// @param maxMissing the share of the PDF's words the topics may lack, from 0 to 1
    /// @return the report; an error finding means coverage is not good enough or could not be judged
    public static Result run(Path pdf, Path topicsDir, double maxMissing) {
        String name = pdf.getFileName() == null ? pdf.toString() : pdf.getFileName().toString();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        List<IngestReport.Source> sources = new ArrayList<>();

        if (Double.isNaN(maxMissing) || maxMissing < 0 || maxMissing > 1) {
            findings.add(Finding.error("max-missing-invalid", "options",
                    "the allowed missing share must be between 0 and 1, not " + maxMissing));
            return done(sources, details, findings);
        }

        PdfPages.Loaded loaded = PdfPages.extractText(pdf);
        findings.addAll(loaded.findings());
        if (loaded.failed()) {
            return done(sources, details, findings);
        }
        if (PdfPages.isScanned(loaded.pageTexts())) {
            findings.add(Finding.error("coverage-needs-text", name,
                    "the PDF is scanned, so there is no text to compare; transcribe it with idoc:ingest-detect first"));
            return done(sources, details, findings);
        }
        addSource(sources, findings, pdf, name);

        List<Path> files = topicFiles(topicsDir, findings);
        if (files.isEmpty()) {
            if (findings.stream().noneMatch(Finding::isError)) {
                findings.add(Finding.error("topics-missing", topicsDir.toString(),
                        "no topic .adoc files were found"));
            }
            return done(sources, details, findings);
        }
        StringBuilder topicText = new StringBuilder();
        for (Path file : files) {
            try {
                topicText.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                findings.add(Finding.error("topic-unreadable", file.toString(),
                        "cannot read the topic: " + e.getMessage()));
            }
        }
        if (findings.stream().anyMatch(Finding::isError)) {
            return done(sources, details, findings);
        }

        List<String> topicWords = words(topicText.toString());
        Map<String, Integer> topicCounts = count(topicWords);
        Set<String> topicSet = topicCounts.keySet();

        List<List<String>> pages = pageWords(loaded.pageTexts());
        int total = 0;
        int missing = 0;
        Map<String, Integer> sourceCounts = new HashMap<>();
        for (List<String> page : pages) {
            total += page.size();
            for (String w : page) {
                sourceCounts.merge(w, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : sourceCounts.entrySet()) {
            missing += Math.max(0, e.getValue() - topicCounts.getOrDefault(e.getKey(), 0));
        }
        double share = total == 0 ? 0 : (double) missing / total;

        List<Integer> weak = weakPages(pages, topicSet);
        details.put("source-pages", pages.size());
        details.put("topic-files", files.size());
        details.put("source-words", total);
        details.put("missing-words", missing);
        details.put("missing-share", Math.round(share * 10000) / 10000.0);
        details.put("allowed-share", maxMissing);
        details.put("weak-pages", weak);

        if (share > maxMissing) {
            findings.add(Finding.error("coverage-low", name, String.format(Locale.ROOT,
                    "the topics lack %d of the PDF's %d words (%.1f%%); %.1f%% is allowed",
                    missing, total, share * 100, maxMissing * 100)));
        }
        int named = 0;
        for (int page : weak) {
            if (named++ < MAX_PAGE_FINDINGS) {
                findings.add(Finding.warning("coverage-page", name + " page " + page,
                        "less than " + Math.round(WEAK_PAGE * 100) + "% of this page's words are in the topics"));
            }
        }
        if (weak.size() > MAX_PAGE_FINDINGS) {
            findings.add(Finding.warning("coverage-page", name,
                    (weak.size() - MAX_PAGE_FINDINGS) + " more weak page(s) not listed"));
        }
        checkSections(pdf, String.join(" ", topicWords), details, findings);
        return done(sources, details, findings);
    }

    private static void checkSections(Path pdf, String topicNormalized, Map<String, Object> details,
                                      List<Finding> findings) {
        IngestDetect.Result detected = IngestDetect.run(pdf,
                new IngestDetect.Options(Path.of(System.getProperty("java.io.tmpdir"), "ike-coverage-pages"), false, null),
                null);
        Map<String, Object> d = detected.report().details();
        details.put("kind", d.get("kind"));
        if (!"DEX_510K".equals(String.valueOf(d.get("kind"))) || !(d.get("sections") instanceof List<?> sections)) {
            return;
        }
        List<String> absent = new ArrayList<>();
        for (Object o : sections) {
            if (!(o instanceof Map<?, ?> section)) {
                continue;
            }
            String title = String.valueOf(section.get("title"));
            String normalized = String.join(" ", words(title));
            if (!normalized.isEmpty() && !topicNormalized.contains(normalized)) {
                absent.add(section.get("label") + " " + title);
                findings.add(Finding.error("section-missing", pdf.getFileName() + " section " + section.get("label"),
                        "the section \"" + title + "\" does not appear in the topics"));
            }
        }
        details.put("sections-checked", sections.size());
        details.put("sections-missing", absent);
    }

    private static List<Integer> weakPages(List<List<String>> pages, Set<String> topicSet) {
        List<Integer> weak = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Set<String> distinct = new HashSet<>(pages.get(i));
            if (pages.get(i).size() < MIN_PAGE_WORDS || distinct.isEmpty()) {
                continue;
            }
            long covered = distinct.stream().filter(topicSet::contains).count();
            if ((double) covered / distinct.size() < WEAK_PAGE) {
                weak.add(i + 1);
            }
        }
        return weak;
    }

    /// Words of each page, leaving out lines that repeat on many pages
    /// (running headers, footers and page numbers), which topics never carry.
    private static List<List<String>> pageWords(List<String> pageTexts) {
        Map<String, Integer> lineCounts = new HashMap<>();
        for (String page : pageTexts) {
            for (String line : new HashSet<>(lines(page))) {
                lineCounts.merge(line, 1, Integer::sum);
            }
        }
        int furniture = Math.max(3, pageTexts.size() / 3);
        List<List<String>> pages = new ArrayList<>();
        for (String page : pageTexts) {
            StringBuilder kept = new StringBuilder();
            for (String line : lines(page)) {
                if (lineCounts.getOrDefault(line, 0) < furniture && !isPageNumber(line)) {
                    kept.append(line).append('\n');
                }
            }
            pages.add(words(kept.toString()));
        }
        return pages;
    }

    private static boolean isPageNumber(String line) {
        return line.matches("(?i)(page\\s*)?\\d+(\\s*(of|/)\\s*\\d+)?")
                || line.matches("(?i).*\\bpage\\s+\\d+\\s+of\\s+\\d+\\s*$");
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static Map<String, Integer> count(List<String> words) {
        Map<String, Integer> counts = new HashMap<>();
        for (String w : words) {
            counts.merge(w, 1, Integer::sum);
        }
        return counts;
    }

    private static List<Path> topicFiles(Path dir, List<Finding> findings) {
        if (!Files.isDirectory(dir)) {
            findings.add(Finding.error("topics-missing", dir.toString(), "the topics folder does not exist"));
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".adoc")
                    && !p.getFileName().toString().startsWith("_")).sorted().toList();
        } catch (IOException e) {
            findings.add(Finding.error("topics-unreadable", dir.toString(), "cannot list the topics: " + e.getMessage()));
            return List.of();
        }
    }

    private static void addSource(List<IngestReport.Source> sources, List<Finding> findings, Path pdf, String name) {
        try {
            sources.add(new IngestReport.Source(name, IngestHash.sha256Hex(pdf)));
        } catch (IOException e) {
            findings.add(Finding.error("source-unreadable", name, "cannot hash the PDF: " + e.getMessage()));
        }
    }

    private static Result done(List<IngestReport.Source> sources, Map<String, Object> details, List<Finding> findings) {
        return new Result(new IngestReport("ingest-coverage", sources, details, findings, List.of()));
    }
}

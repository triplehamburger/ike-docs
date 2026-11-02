package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Cuts the single AsciiDoc file of a 510(k) decision summary into fragments
/// and builds the record as an assembly that includes them in order.
///
/// Each fragment is verbatim text from the source. What the splitter adds is
/// derived from the text and the 510(k) number only: the header block, a
/// literal anchor, and an id of the form `dex-{k-number}-{slug}`.
///
/// A top-level section is one fragment. A section over the size limit is split
/// at its lettered subsections; a subsection (or a section with none) still
/// over the limit is split at its top-level numbered items, the studies. A
/// study fragment keeps its text between `tag::body[]` and `end::body[]`, and
/// the assembly includes only that region under a list item that repeats the
/// study title, so the rendered record keeps the source's list. A study too
/// small to stand alone is merged into the study before it.
///
/// A heading that is split keeps its place in the assembly, with any text
/// between it and its first part; every other word lives in a fragment.
///
/// The summary, keywords and index terms are placeholders built from the
/// heading; who writes the real ones is not decided.
///
/// Nothing here throws for bad input; every problem is a {@link Finding}, and
/// when any is an error no fragment is returned.
public final class IngestFragments {

    /// The goal name written into the report.
    public static final String GOAL = "ingest-fragments";

    /// Fragments over this many characters are split, or noted as a size exception.
    public static final int SIZE_LIMIT = 5000;

    /// Fragments under this many characters are reported, and a small study is merged into the one before.
    public static final int MIN_SIZE = 500;

    /// The heading level that starts a fragment when none is given.
    public static final int DEFAULT_SECTION_LEVEL = 2;

    private static final String LICENSE = "Public domain — US federal government work.";
    private static final String ACCESSDATA = "https://www.accessdata.fda.gov/cdrh_docs/reviews/";
    private static final Pattern K_NUMBER =
            Pattern.compile("510\\(k\\)\\s+Number:?\\s*(K\\d{6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("^(={1,6}) (\\S.*?)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^(-{4,}|\\.{4,}|\\+{4,}|/{4,}|={4,}|\\|={3,})\\s*$");
    private static final Pattern LABEL = Pattern.compile("^([IVX]{1,4}|[A-Z])\\.?\\s+(\\S.*)$");
    private static final Pattern ITEM = Pattern.compile("^\\. (\\S.*?)\\s*$");
    private static final Pattern START_ATTR = Pattern.compile("^\\[start=\\d+\\]\\s*$");
    private static final Pattern QUALIFIER = Pattern.compile("\\s*\\([^)]*[\\s/][^)]*\\)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private IngestFragments() {
    }

    /// Settings for a run.
    ///
    /// @param sectionLevel the heading level that starts a fragment, from 1 to 5
    /// @param sourceUrl    the citation URL to record, or null to derive it from the K-number
    /// @param sizeLimit    the character count above which a fragment is split, at least 1000
    /// @param split        whether to split an oversize fragment; false keeps one fragment per section
    public record Options(int sectionLevel, String sourceUrl, int sizeLimit, boolean split) {
        /// Treat a blank URL as absent.
        public Options {
            sourceUrl = sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.strip();
        }

        /// The default options: sections start at level 2, the URL is derived and oversize fragments are split.
        ///
        /// @return the default options
        public static Options defaults() {
            return new Options(DEFAULT_SECTION_LEVEL, null, SIZE_LIMIT, true);
        }
    }

    /// One fragment ready to write.
    ///
    /// @param id       the topic id, `dex-{k-number}-{slug}`
    /// @param fileName the file name, the id without its `dex-` domain prefix, plus `.adoc`
    /// @param title    the heading as printed
    /// @param kind     `section`, `subsection` or `study`
    /// @param section  the heading of the top-level section it belongs to
    /// @param chars    the length of the fragment body in characters
    /// @param content  the whole file: header block, anchor and body
    public record Fragment(String id, String fileName, String title, String kind, String section, int chars,
                           String content) {
    }

    /// The outcome of a run.
    ///
    /// @param report       what was found and every problem
    /// @param fragments    the fragments in source order; empty when the report has an error
    /// @param assemblyName the file name of the assembly, or empty
    /// @param assembly     the assembly text, or empty
    public record Result(IngestReport report, List<Fragment> fragments, String assemblyName, String assembly) {
    }

    private record Heading(int line, int level, String title) {
    }

    /// A fragment before its header is written.
    private record Draft(String id, String title, String kind, int sectionIndex, String sectionTitle, String body,
                         boolean tagged) {
    }

    /// One top-level numbered item.
    private record Item(int start, int titleLine, String title, String startAttr) {
    }

    /// Consecutive items that form one study fragment.
    private record Group(List<Item> items, int from, int to) {
    }

    /// Split a converted decision summary into fragments.
    ///
    /// @param adoc    the single AsciiDoc file from the converter
    /// @param options run settings
    /// @return the fragments, the assembly and the report
    public static Result run(Path adoc, Options options) {
        Objects.requireNonNull(options, "options");
        String name = adoc.getFileName() == null ? adoc.toString() : adoc.getFileName().toString();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        List<IngestReport.Source> sources = new ArrayList<>();

        if (options.sectionLevel() < 1 || options.sectionLevel() > 5) {
            findings.add(Finding.error("section-level-invalid", "options",
                    "the section level must be from 1 to 5, not " + options.sectionLevel()));
            return failed(sources, details, findings);
        }
        if (options.sizeLimit() < 1000) {
            findings.add(Finding.error("size-limit-invalid", "options",
                    "the size limit must be at least 1000 characters, not " + options.sizeLimit()));
            return failed(sources, details, findings);
        }
        String text;
        try {
            text = Files.readString(adoc, StandardCharsets.UTF_8).replace("\r\n", "\n");
            sources.add(new IngestReport.Source(name, IngestHash.sha256Hex(adoc)));
        } catch (IOException e) {
            findings.add(Finding.error("source-unreadable", name, "cannot read the AsciiDoc file: " + e.getMessage()));
            return failed(sources, details, findings);
        }

        Matcher k = K_NUMBER.matcher(text);
        if (!k.find()) {
            findings.add(Finding.error("k-number-missing", name,
                    "no '510(k) Number: K######' was found; the K-number comes from section I A"));
            return failed(sources, details, findings);
        }
        String kNumber = k.group(1).toUpperCase(Locale.ROOT);
        String kLower = kNumber.toLowerCase(Locale.ROOT);

        List<String> lines = List.of(text.split("\n", -1));
        List<Heading> headings = headings(lines);
        int level = options.sectionLevel();
        List<Heading> sections = headings.stream().filter(h -> h.level() == level).toList();
        if (sections.isEmpty()) {
            findings.add(Finding.error("no-sections", name, "no level-" + level + " headings were found to split on"));
            return failed(sources, details, findings);
        }

        String url = options.sourceUrl() == null ? ACCESSDATA + kNumber + ".pdf" : options.sourceUrl();
        if (options.sourceUrl() == null) {
            findings.add(Finding.warning("citation-url-derived", name,
                    "the citation URL " + url + " was derived from the K-number and not checked; "
                            + "pass -Dike.fragments.sourceUrl to set it"));
        } else if (!url.startsWith("https://")) {
            findings.add(Finding.error("citation-url-invalid", name, "the citation URL must start with https://"));
        }

        String preamble = join(lines.subList(0, sections.get(0).line()));
        List<Draft> drafts = new ArrayList<>();
        StringBuilder assembly = new StringBuilder(preamble.replaceAll("\\n+$", "\n"));
        long ownWords = words(preamble).size();
        Planner planner = new Planner(lines, headings, level, options, kLower, drafts, assembly, findings, name);
        for (int i = 0; i < sections.size(); i++) {
            ownWords += planner.section(i, sections.get(i));
        }
        if (findings.stream().anyMatch(Finding::isError)) {
            return failed(sources, details, findings);
        }

        long fragmentWords = 0;
        for (Draft d : drafts) {
            fragmentWords += words(d.body()).size() + (d.tagged() ? words(d.title()).size() : 0);
        }
        fragmentWords += planner.attrWords;
        long sourceWords = words(text).size();
        if (sourceWords != ownWords + fragmentWords) {
            findings.add(Finding.error("content-changed", name, "the fragments and assembly hold "
                    + (ownWords + fragmentWords) + " words but the source has " + sourceWords));
            return failed(sources, details, findings);
        }

        Map<Integer, List<String>> bySection = new LinkedHashMap<>();
        for (Draft d : drafts) {
            bySection.computeIfAbsent(d.sectionIndex(), x -> new ArrayList<>()).add(d.id());
        }
        List<Fragment> fragments = new ArrayList<>();
        List<String> small = new ArrayList<>();
        List<Object> detailList = new ArrayList<>();
        for (Draft d : drafts) {
            boolean oversize = d.body().length() > options.sizeLimit();
            if (oversize) {
                findings.add(Finding.warning("fragment-oversize", d.id(), "the fragment has " + d.body().length()
                        + " characters, over " + options.sizeLimit() + "; kept whole and noted in :topic-notes:"));
            }
            if (d.body().length() < MIN_SIZE) {
                small.add(d.id());
            }
            List<String> related = new ArrayList<>(bySection.get(d.sectionIndex()));
            related.remove(d.id());
            String file = d.id().substring("dex-".length()) + ".adoc";
            String content = header(d, kNumber, url, oversize, related) + "\n[[" + d.id() + "]]\n" + wrap(d);
            fragments.add(new Fragment(d.id(), file, d.title(), d.kind(), d.sectionTitle(), d.body().length(),
                    content));
            detailList.add(orderedMap("id", d.id(), "file", file, "kind", d.kind(), "title", d.title(),
                    "chars", d.body().length()));
        }
        if (!small.isEmpty()) {
            findings.add(Finding.info("fragment-undersize", name, small.size() + " fragment(s) have under "
                    + MIN_SIZE + " characters and are kept as printed: " + String.join(", ", small)));
        }
        findings.add(Finding.warning("metadata-placeholder", name,
                ":topic-summary:, :topic-keywords: and index terms are placeholders built from the heading; "
                        + "who writes the real ones is not decided"));

        String assemblyName = "DeXRecord_" + kNumber + ".adoc";
        details.put("k-number", kNumber);
        details.put("citation-url", url);
        details.put("section-level", level);
        details.put("size-limit", options.sizeLimit());
        details.put("source-words", sourceWords);
        details.put("assembly", assemblyName);
        details.put("fragment-count", fragments.size());
        details.put("fragments", detailList);
        return new Result(new IngestReport(GOAL, sources, details, findings, List.of()), List.copyOf(fragments),
                assemblyName, assembly.toString());
    }

    /// Plans the split of each section, appending fragments and assembly text as it goes.
    private static final class Planner {
        private final List<String> lines;
        private final List<Heading> headings;
        private final int level;
        private final Options options;
        private final String kLower;
        private final List<Draft> drafts;
        private final StringBuilder assembly;
        private final List<Finding> findings;
        private final String source;
        private final Map<String, String> seen = new HashMap<>();
        long attrWords;
        private int sectionIndex;
        private String sectionTitle;
        private String sectionPart;

        Planner(List<String> lines, List<Heading> headings, int level, Options options, String kLower,
                List<Draft> drafts, StringBuilder assembly, List<Finding> findings, String source) {
            this.lines = lines;
            this.headings = headings;
            this.level = level;
            this.options = options;
            this.kLower = kLower;
            this.drafts = drafts;
            this.assembly = assembly;
            this.findings = findings;
            this.source = source;
        }

        /// Plan one section; returns the words the assembly holds itself for it.
        long section(int index, Heading heading) {
            sectionIndex = index;
            sectionTitle = heading.title();
            sectionPart = shorten(slug(heading.title()), 32);
            int end = end(heading);
            String body = shift(lines.subList(heading.line(), end), level);
            if (!options.split || body.length() <= options.sizeLimit()) {
                whole(heading, "section", slug(heading.title()), body, level - 1);
                return 0;
            }
            List<Heading> subs = headings.stream().filter(h -> h.level() == level + 1
                    && h.line() > heading.line() && h.line() < end).toList();
            if (!subs.isEmpty()) {
                long own = container(heading, subs.get(0).line());
                for (int i = 0; i < subs.size(); i++) {
                    Heading sub = subs.get(i);
                    int subEnd = i + 1 < subs.size() ? subs.get(i + 1).line() : end;
                    String subBody = shift(lines.subList(sub.line(), subEnd), level + 1);
                    if (subBody.length() <= options.sizeLimit()) {
                        whole(sub, "subsection", sectionPart + "-" + shorten(slug(sub.title()), 48), subBody, level);
                    } else {
                        own += studiesOrWhole(sub, sub.line() + 1, subEnd, subBody, level);
                    }
                }
                return own;
            }
            return studiesOrWhole(heading, heading.line() + 1, end, body, level - 1);
        }

        private long studiesOrWhole(Heading heading, int from, int to, String wholeBody, int offset) {
            List<Group> groups = groups(from, to);
            if (groups.size() < 2) {
                whole(heading, heading.level() == level ? "section" : "subsection",
                        heading.level() == level ? slug(heading.title())
                                : sectionPart + "-" + shorten(slug(heading.title()), 48), wholeBody, offset);
                return 0;
            }
            long own = container(heading, groups.get(0).from());
            for (Group g : groups) {
                Item first = g.items().get(0);
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < g.items().size(); i++) {
                    Item item = g.items().get(i);
                    int bodyEnd = i + 1 < g.items().size() ? g.items().get(i + 1).start() : g.to();
                    String itemBody = trimBody(lines.subList(item.titleLine() + 1, bodyEnd));
                    if (i > 0) {
                        if (item.startAttr() != null) {
                            body.append(item.startAttr()).append('\n');
                        }
                        body.append(". ").append(item.title()).append('\n');
                    }
                    body.append(itemBody);
                    if (i + 1 < g.items().size() && !itemBody.isEmpty()) {
                        body.append('\n');
                    }
                }
                String id = "dex-" + kLower + "-" + sectionPart + "-" + shorten(slug(first.title()), 48);
                String text = body.toString().replaceAll("\\n+$", "\n");
                add(new Draft(unique(id, first.title()), first.title(), "study", sectionIndex, sectionTitle, text,
                        true));
                assembly.append('\n');
                if (first.startAttr() != null) {
                    assembly.append(first.startAttr()).append('\n');
                    attrWords += words(first.startAttr()).size();
                }
                assembly.append(". ").append(first.title()).append("\n+\ninclude::{topics}/topics/dex/")
                        .append(drafts.get(drafts.size() - 1).id().substring("dex-".length()))
                        .append(".adoc[tag=body]\n");
            }
            return own;
        }

        /// Write a split heading and its introduction into the assembly; returns the words written.
        private long container(Heading heading, int firstChild) {
            String intro = join(lines.subList(heading.line() + 1, firstChild)).strip();
            assembly.append('\n').append(lines.get(heading.line())).append('\n');
            long own = words(lines.get(heading.line())).size();
            if (!intro.isEmpty()) {
                assembly.append('\n').append(intro).append('\n');
                own += words(intro).size();
            }
            return own;
        }

        private void whole(Heading heading, String kind, String slug, String body, int offset) {
            String id = "dex-" + kLower + "-" + (kind.equals("section") ? shorten(slug, 60) : slug);
            if (slug.isEmpty() || slug.endsWith("-") || slug.startsWith("-")) {
                findings.add(Finding.error("slug-empty", source + " line " + (heading.line() + 1),
                        "the heading \"" + heading.title() + "\" has no letters or digits to build an id from"));
                return;
            }
            String unique = unique(id, heading.title());
            add(new Draft(unique, heading.title(), kind, sectionIndex, sectionTitle, body, false));
            assembly.append('\n').append("include::{topics}/topics/dex/").append(unique.substring("dex-".length()))
                    .append(".adoc[leveloffset=+").append(offset).append("]\n");
        }

        private void add(Draft draft) {
            drafts.add(draft);
        }

        private String unique(String id, String title) {
            String previous = seen.putIfAbsent(id, title);
            if (previous != null) {
                findings.add(Finding.error("id-duplicate", source,
                        "the headings \"" + previous + "\" and \"" + title + "\" both give the id " + id));
            }
            return id;
        }

        private int end(Heading heading) {
            return headings.stream().filter(h -> h.line() > heading.line() && h.level() <= heading.level())
                    .mapToInt(Heading::line).findFirst().orElse(lines.size());
        }

        /// The study groups of lines [from, to): top-level numbered items, small ones merged into the one before.
        private List<Group> groups(int from, int to) {
            List<Item> items = items(from, to);
            if (items.size() < 2) {
                return List.of();
            }
            List<Group> groups = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                int itemEnd = i + 1 < items.size() ? items.get(i + 1).start() : to;
                int size = join(lines.subList(item.titleLine(), itemEnd)).length();
                if (size < MIN_SIZE && !groups.isEmpty()) {
                    Group last = groups.remove(groups.size() - 1);
                    List<Item> merged = new ArrayList<>(last.items());
                    merged.add(item);
                    groups.add(new Group(merged, last.from(), itemEnd));
                } else {
                    groups.add(new Group(new ArrayList<>(List.of(item)), item.start(), itemEnd));
                }
            }
            return groups;
        }

        private List<Item> items(int from, int to) {
            List<Item> items = new ArrayList<>();
            boolean inBlock = false;
            for (int i = from; i < to; i++) {
                String line = lines.get(i);
                if (FENCE.matcher(line).matches()) {
                    inBlock = !inBlock;
                } else if (!inBlock) {
                    Matcher m = ITEM.matcher(line);
                    if (m.matches()) {
                        String attr = i > from && START_ATTR.matcher(lines.get(i - 1)).matches()
                                ? lines.get(i - 1) : null;
                        items.add(new Item(attr == null ? i : i - 1, i, m.group(1).replaceAll("\\s*\\+$", ""),
                                attr));
                    }
                }
            }
            return items;
        }

        private static String trimBody(List<String> body) {
            int start = 0;
            while (start < body.size() && (body.get(start).isBlank() || body.get(start).equals("+"))) {
                start++;
            }
            int end = body.size();
            while (end > start && body.get(end - 1).isBlank()) {
                end--;
            }
            return start >= end ? "" : String.join("\n", body.subList(start, end)) + "\n";
        }
    }

    private static List<Heading> headings(List<String> lines) {
        List<Heading> headings = new ArrayList<>();
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (FENCE.matcher(line).matches()) {
                inBlock = !inBlock;
            } else if (!inBlock) {
                Matcher m = HEADING.matcher(line);
                if (m.matches()) {
                    headings.add(new Heading(i, m.group(1).length(), m.group(2)));
                }
            }
        }
        return headings;
    }

    /// Move every heading up so a heading of the given level becomes level 1.
    private static String shift(List<String> section, int fromLevel) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        for (String line : section) {
            String result = line;
            if (FENCE.matcher(line).matches()) {
                inBlock = !inBlock;
            } else if (!inBlock) {
                Matcher m = HEADING.matcher(line);
                if (m.matches() && m.group(1).length() >= fromLevel) {
                    result = "=".repeat(m.group(1).length() - (fromLevel - 1)) + " " + m.group(2);
                }
            }
            out.append(result).append('\n');
        }
        return out.toString().replaceAll("\\n+$", "\n");
    }

    private static Result failed(List<IngestReport.Source> sources, Map<String, Object> details,
                                 List<Finding> findings) {
        return new Result(new IngestReport(GOAL, sources, details, findings, List.of()), List.of(), "", "");
    }

    /// The text after the anchor: a heading and body, or for a study a heading and the tagged body.
    private static String wrap(Draft d) {
        if (!d.tagged()) {
            return d.body();
        }
        return "= " + d.title() + "\n\n// tag::body[]\n" + d.body() + "// end::body[]\n";
    }

    private static String header(Draft d, String kNumber, String url, boolean oversize, List<String> related) {
        String rest = stripLabel(d.title());
        String label = d.title().substring(0, d.title().length() - rest.length()).strip();
        String plain = rest.replaceAll("[:.]+$", "");
        String notes = "DeX fragment, verbatim from the FDA decision summary " + kNumber
                + ". Public domain US federal work."
                + (oversize ? " Kept whole as a reference exception per IKE-TOPIC-DECOMPOSITION." : "");
        return "// " + d.id() + "\n"
                + "// Topic: " + d.title() + "\n"
                + "// Type: reference\n"
                + "// Status: review\n"
                + ":topic-id: " + d.id() + "\n"
                + ":topic-type: reference\n"
                + ":topic-status: review\n"
                + ":topic-keywords: 510(k), " + kNumber + ", substantial equivalence, "
                + plain.toLowerCase(Locale.ROOT) + "\n"
                + ":topic-summary: " + (label.isEmpty() ? "Part" : "Part " + label) + " (" + plain
                + ") of the FDA 510(k) decision summary " + kNumber + ", reproduced verbatim.\n"
                + (related.isEmpty() ? "" : ":topic-related: " + String.join(", ", related) + "\n")
                + ":topic-notes: " + notes + "\n"
                + ":topic-provenance: external\n"
                + ":topic-citation: U.S. Food and Drug Administration, Center for Devices and Radiological Health. "
                + "510(k) Substantial Equivalence Determination Decision Summary: " + kNumber + ". " + url + "\n"
                + ":topic-license: " + LICENSE + "\n";
    }

    /// The heading with its printed label (a Roman numeral or a letter) removed.
    private static String stripLabel(String title) {
        Matcher m = LABEL.matcher(title);
        return m.matches() ? m.group(2) : title;
    }

    /// The id slug: the title without its label or a long parenthetical, lowercase, other runs as one hyphen.
    static String slug(String title) {
        String plain = QUALIFIER.matcher(stripLabel(title)).replaceAll("");
        String slug = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return slug.replaceAll("^-+|-+$", "");
    }

    /// Cut a slug to at most max characters at a hyphen, unless the first word alone is longer.
    static String shorten(String slug, int max) {
        if (slug.length() <= max) {
            return slug;
        }
        int cut = slug.lastIndexOf('-', max);
        return cut > 0 ? slug.substring(0, cut) : slug.substring(0, max);
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    private static Map<String, Object> orderedMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}

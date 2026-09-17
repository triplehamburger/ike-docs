package network.ike.docs.plugin.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed header of one AsciiDoc file: the {@code :topic-*:} attribute
 * block, the anchor and the level-1 heading, read line by line without
 * AsciidoctorJ. The layout is the one {@code IKE-ASCIIDOC-FRAGMENT.md}
 * prescribes: an optional comment header, the attribute block (values may
 * continue on the next line with a trailing backslash), a literal
 * {@code [[anchor]]} equal to the topic id, then {@code = Title}.
 *
 * <p>A file with no {@code :topic-id:} before its heading is not a topic;
 * it is recorded as an assembly (when it carries {@code include::} lines)
 * or a plain file, with its title and document attributes, and gets no
 * header findings. Every kind of module the doc pipeline renders is
 * covered: topic libraries, assemblies, doc-only and hybrid projects.
 *
 * @param file       the file, relative to the scan root, with {@code /} separators
 * @param topic      whether the file declares a {@code :topic-id:} before its heading
 * @param id         the topic id, or {@code null} for a file that is not a topic
 * @param title      the first heading's text: the level-1 heading for a topic;
 *                   for any other file the first heading of any level, so a
 *                   chapter file that starts at {@code ==} still has a title
 * @param level      the heading level of {@link #title()} (1 for {@code =}),
 *                   or 0 when the file has no heading
 * @param attributes every {@code :topic-*:} attribute in declaration order,
 *                   keyed without the {@code topic-} prefix
 * @param documentAttributes every other {@code :name: value} attribute in the
 *                   header, before the heading or in the contiguous block
 *                   after it ({@code doctype}, {@code toc}, ...), in order
 * @param anchor     {@code present}, {@code missing} or {@code mismatched}
 *                   for a topic; {@code null} for other files
 * @param charCount  characters from the anchor line (or the heading, when
 *                   there is no anchor) to the end of the file
 * @param includes   the number of {@code include::} lines in the file
 * @param findings   what is wrong with the header, empty when nothing is
 * @since 109
 */
public record TopicHeader(String file, boolean topic, String id, String title, int level,
                          Map<String, String> attributes, Map<String, String> documentAttributes,
                          String anchor, long charCount, int includes, List<String> findings) {

    /** Attributes every topic must declare, without the {@code topic-} prefix. */
    public static final List<String> REQUIRED = List.of("id", "type", "status", "keywords");

    /**
     * The topic types {@code IKE-TOPIC-REGISTRY.md} allows, plus
     * {@code procedure}, which {@code ike-doc-ingest} writes for task topics.
     */
    public static final Set<String> TYPES =
            Set.of("concept", "task", "procedure", "reference", "dialog");

    /** The topic statuses {@code IKE-TOPIC-REGISTRY.md} allows. */
    public static final Set<String> STATUSES =
            Set.of("draft", "proposed", "review", "published", "deprecated");

    /**
     * Parse one file.
     *
     * @param file the file to read, UTF-8
     * @param root the scan root the file lies under; {@link #file()} is
     *             expressed relative to it
     * @return the parsed header
     * @throws IOException if the file cannot be read
     */
    public static TopicHeader parse(Path file, Path root) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return parse(relative, Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parse file content already in memory.
     *
     * @param relative the file's path relative to its scan root
     * @param content  the file content
     * @return the parsed header
     */
    public static TopicHeader parse(String relative, String content) {
        List<String> lines = content.lines().toList();
        Map<String, String> attributes = new LinkedHashMap<>();
        Map<String, String> documentAttributes = new LinkedHashMap<>();
        String pendingName = null;
        StringBuilder pendingValue = null;
        String anchorText = null;
        int anchorIndex = -1;
        int headingIndex = -1;
        String title = null;
        int level = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (pendingName != null) {
                String text = line.strip();
                boolean continues = text.endsWith("\\");
                String piece = continues ? text.substring(0, text.length() - 1).strip() : text;
                if (!piece.isEmpty()) {
                    pendingValue.append(' ').append(piece);
                }
                if (!continues) {
                    attributes.put(pendingName, pendingValue.toString().strip());
                    pendingName = null;
                }
                continue;
            }
            if (line.startsWith("= ")) {
                headingIndex = i;
                title = line.substring(2).strip();
                level = 1;
                break;
            }
            if (line.startsWith("==") && headingIndex < 0 && !attributes.containsKey("id")) {
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '=') {
                    depth++;
                }
                if (depth < line.length() && line.charAt(depth) == ' ') {
                    headingIndex = i;
                    title = line.substring(depth + 1).strip();
                    level = depth;
                    break;
                }
            }
            if (line.startsWith("//")) {
                continue;
            }
            String stripped = line.strip();
            if (stripped.startsWith("[[") && stripped.endsWith("]]") && stripped.length() > 4) {
                anchorText = stripped.substring(2, stripped.length() - 2).strip();
                anchorIndex = i;
                continue;
            }
            if (line.startsWith(":")) {
                int close = line.indexOf(':', 1);
                if (close > 1) {
                    String name = line.substring(1, close);
                    String value = line.substring(close + 1).strip();
                    if (name.startsWith("topic-")) {
                        String key = name.substring("topic-".length());
                        if (value.endsWith("\\")) {
                            pendingName = key;
                            pendingValue = new StringBuilder(
                                    value.substring(0, value.length() - 1).strip());
                        } else {
                            attributes.put(key, value);
                        }
                    } else if (!name.startsWith("!") && !name.endsWith("!")) {
                        documentAttributes.put(name, value);
                    }
                }
            }
        }
        if (pendingName != null) {
            attributes.put(pendingName, pendingValue.toString().strip());
        }
        // The AsciiDoc document header: attribute lines contiguous with the title.
        for (int i = headingIndex + 1; headingIndex >= 0 && i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(":")) {
                break;
            }
            int close = line.indexOf(':', 1);
            if (close > 1) {
                String name = line.substring(1, close);
                if (!name.startsWith("topic-") && !name.startsWith("!") && !name.endsWith("!")) {
                    documentAttributes.put(name, line.substring(close + 1).strip());
                }
            }
        }

        int includes = 0;
        for (String line : lines) {
            if (line.startsWith("include::")) {
                includes++;
            }
        }
        int start = anchorIndex >= 0 ? anchorIndex : Math.max(headingIndex, 0);
        long charCount = 0;
        for (int i = start; i < lines.size(); i++) {
            charCount += lines.get(i).length() + 1;
        }

        boolean topic = attributes.containsKey("id");
        if (!topic) {
            return new TopicHeader(relative, false, null, title, level, attributes,
                    documentAttributes, null, charCount, includes, List.of());
        }
        String id = attributes.get("id");
        List<String> findings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!attributes.containsKey(required) || attributes.get(required).isBlank()) {
                missing.add(required);
            }
        }
        for (String m : missing) {
            findings.add("missing required attribute :topic-" + m + ":");
        }
        String type = attributes.get("type");
        if (type != null && !type.isBlank() && !TYPES.contains(type)) {
            findings.add("unknown type '" + type + "' (expected one of " + sorted(TYPES) + ")");
        }
        String status = attributes.get("status");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            findings.add("unknown status '" + status + "' (expected one of " + sorted(STATUSES) + ")");
        }
        String anchor;
        if (anchorText == null) {
            anchor = "missing";
            findings.add("no [[" + id + "]] anchor before the heading");
        } else if (anchorText.equals(id)) {
            anchor = "present";
        } else {
            anchor = "mismatched";
            findings.add("anchor [[" + anchorText + "]] does not match :topic-id: " + id);
        }
        if (headingIndex < 0) {
            findings.add("no level-1 heading (= Title)");
        }
        return new TopicHeader(relative, true, id, title, level, attributes, documentAttributes,
                anchor, charCount, includes, List.copyOf(findings));
    }

    /**
     * The required attributes this header lacks, in the order of {@link #REQUIRED}.
     *
     * @return the missing attribute names without the {@code topic-} prefix
     */
    public List<String> missingRequired() {
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!attributes.containsKey(required) || attributes.get(required).isBlank()) {
                missing.add(required);
            }
        }
        return missing;
    }

    /**
     * The {@code :topic-keywords:} value split on commas and trimmed.
     *
     * @return the keywords, empty when the attribute is absent
     */
    public List<String> keywords() {
        String raw = attributes.get("keywords");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String k : raw.split(",")) {
            String t = k.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}

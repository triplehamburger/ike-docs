package network.ike.docs.plugin.ledger;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The document ledger: every AsciiDoc file under a scan root, parsed by
 * {@link TopicHeader}, grouped by directory, with the findings the headers
 * raise. Topic fragments carry their {@code :topic-*:} metadata; every file,
 * topic or not, carries its title, document attributes and include count. Pure Java with no Maven dependency, so it is testable without a
 * build; {@code LedgerMojo} is the wiring around it.
 *
 * <p>The YAML the ledger produces uses {@code topic-registry.yaml}'s field
 * names ({@code id}, {@code file}, {@code title}, {@code type},
 * {@code status}, {@code keywords}, {@code char-count}, {@code related},
 * {@code summary}) so the two can be compared field by field.
 *
 * @since 109
 */
public final class Ledger {

    /** Directory names never entered by the scan. */
    public static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", "node_modules");

    /**
     * One scan root's result.
     *
     * @param root     the root that was scanned
     * @param topics   the files that declare a {@code :topic-id:}, in path order
     * @param others   the files that do not, in path order
     * @param findings every finding, each prefixed with the file it concerns
     */
    public record Scan(Path root, List<TopicHeader> topics, List<TopicHeader> others,
                       List<String> findings) {
    }

    private Ledger() {
    }

    /**
     * Scan one root.
     *
     * @param root     the directory to scan
     * @param maxFiles the most {@code .adoc} files the scan will accept
     * @return the scan result
     * @throws IOException           if the tree cannot be read
     * @throws IllegalStateException if the root holds more than {@code maxFiles}
     *                               {@code .adoc} files
     */
    public static Scan scan(Path root, int maxFiles) throws IOException {
        Path real = root.toRealPath();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(real, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(real) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".adoc")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (files.size() > maxFiles) {
            throw new IllegalStateException("More than " + maxFiles + " .adoc files under "
                    + real + "; raise -Dike.ledger.maxFiles or narrow -Dike.ledger.roots");
        }
        files.sort((a, b) -> real.relativize(a).toString().compareTo(real.relativize(b).toString()));

        List<TopicHeader> topics = new ArrayList<>();
        List<TopicHeader> others = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        Map<String, String> firstFileById = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = real.relativize(file).toString().replace('\\', '/');
            if (!file.toRealPath().startsWith(real)) {
                findings.add(relative + ": resolves outside the root (symbolic link), skipped");
                continue;
            }
            TopicHeader header = TopicHeader.parse(file, real);
            if (header.topic()) {
                topics.add(header);
                String earlier = firstFileById.putIfAbsent(header.id(), relative);
                if (earlier != null) {
                    findings.add(relative + ": duplicate id '" + header.id()
                            + "', already declared by " + earlier);
                }
            } else {
                others.add(header);
            }
            for (String f : header.findings()) {
                findings.add(relative + ": " + f);
            }
        }
        return new Scan(real, List.copyOf(topics), List.copyOf(others), List.copyOf(findings));
    }

    /**
     * The ledger as an ordered map, ready for YAML.
     *
     * @param scans the scanned roots
     * @param base  the directory paths in the ledger are made relative to
     *              (the module base directory)
     * @param now   the generation instant
     * @return the ledger model
     */
    public static Map<String, Object> model(List<Scan> scans, Path base, Instant now) {
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("generated", now.truncatedTo(ChronoUnit.SECONDS).toString());
        ledger.put("scanned-from", base.toAbsolutePath().normalize().toString());
        List<Object> roots = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        for (Scan scan : scans) {
            Map<String, Object> rootEntry = new LinkedHashMap<>();
            rootEntry.put("root", relativeTo(base, scan.root()));
            rootEntry.put("files", scan.topics().size() + scan.others().size());
            rootEntry.put("topics", scan.topics().size());
            Map<String, List<TopicHeader>> byDir = new TreeMap<>();
            for (TopicHeader t : scan.topics()) {
                byDir.computeIfAbsent(directoryOf(t.file()), k -> new ArrayList<>()).add(t);
            }
            List<Object> directories = new ArrayList<>();
            for (Map.Entry<String, List<TopicHeader>> e : byDir.entrySet()) {
                Map<String, Object> dir = new LinkedHashMap<>();
                dir.put("dir", e.getKey().isEmpty() ? "." : e.getKey());
                List<Object> entries = new ArrayList<>();
                for (TopicHeader t : e.getValue()) {
                    entries.add(topicEntry(t));
                }
                dir.put("topics", entries);
                directories.add(dir);
            }
            rootEntry.put("directories", directories);
            List<Object> otherFiles = new ArrayList<>();
            for (TopicHeader o : scan.others()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", o.file());
                entry.put("kind", o.includes() > 0 ? "assembly" : "plain");
                if (o.title() != null) {
                    entry.put("title", o.title());
                    if (o.level() != 1) {
                        entry.put("level", o.level());
                    }
                }
                if (!o.documentAttributes().isEmpty()) {
                    entry.put("attributes", new LinkedHashMap<>(o.documentAttributes()));
                }
                entry.put("includes", o.includes());
                otherFiles.add(entry);
            }
            rootEntry.put("other-files", otherFiles);
            roots.add(rootEntry);
            findings.addAll(scan.findings());
        }
        ledger.put("roots", roots);
        ledger.put("findings", findings);
        return ledger;
    }

    /**
     * Render the ledger model as YAML in the registry's block style.
     *
     * @param model the ledger model from {@link #model}
     * @return the YAML text
     */
    public static String yaml(Map<String, Object> model) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setWidth(120);
        return "# doc-ledger.yaml, generated by idoc:ledger. Do not edit or commit.\n"
                + new Yaml(options).dump(model);
    }

    /**
     * The scan as an indented tree for the build log: directories, then
     * each file with its id, type and status, and the files that are not
     * topics with their include counts.
     *
     * @param scan the scan
     * @param base the directory the root is shown relative to
     * @return the lines, without newlines
     */
    public static List<String> tree(Scan scan, Path base) {
        List<String> lines = new ArrayList<>();
        lines.add(relativeTo(base, scan.root()));
        Map<String, List<TopicHeader>> byDir = new TreeMap<>();
        for (TopicHeader t : scan.topics()) {
            byDir.computeIfAbsent(directoryOf(t.file()), k -> new ArrayList<>()).add(t);
        }
        for (TopicHeader o : scan.others()) {
            byDir.computeIfAbsent(directoryOf(o.file()), k -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<String, List<TopicHeader>> e : byDir.entrySet()) {
            String indent = "  ";
            if (!e.getKey().isEmpty()) {
                lines.add("  " + e.getKey() + "/");
                indent = "    ";
            }
            for (TopicHeader h : e.getValue()) {
                String name = h.file().substring(h.file().lastIndexOf('/') + 1);
                if (h.topic()) {
                    lines.add(indent + pad(name, 40) + pad(h.id(), 38)
                            + pad(h.attributes().getOrDefault("type", "-"), 11)
                            + h.attributes().getOrDefault("status", "-"));
                } else {
                    lines.add(indent + pad(name, 40)
                            + (h.includes() > 0 ? "assembly, " + h.includes() + " includes" : "plain"));
                }
            }
        }
        return lines;
    }

    private static Map<String, Object> topicEntry(TopicHeader t) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", t.id());
        entry.put("file", t.file());
        if (t.title() != null) {
            entry.put("title", t.title());
        }
        putIfPresent(entry, "type", t.attributes().get("type"));
        putIfPresent(entry, "status", t.attributes().get("status"));
        putIfPresent(entry, "provenance", t.attributes().get("provenance"));
        entry.put("keywords", t.keywords());
        putIfPresent(entry, "summary", t.attributes().get("summary"));
        putIfPresent(entry, "scope-note", t.attributes().get("scope-note"));
        String related = t.attributes().get("related");
        if (related != null && !related.isBlank()) {
            entry.put("related", split(related));
        }
        putIfPresent(entry, "notes", t.attributes().get("notes"));
        putIfPresent(entry, "citation", t.attributes().get("citation"));
        putIfPresent(entry, "license", t.attributes().get("license"));
        entry.put("char-count", t.charCount());
        entry.put("anchor", t.anchor());
        List<String> missing = t.missingRequired();
        entry.put("header", missing.isEmpty() ? "complete" : "missing: " + String.join(", ", missing));
        Map<String, String> extra = new LinkedHashMap<>();
        Set<String> known = Set.of("id", "type", "status", "provenance", "keywords", "summary",
                "scope-note", "related", "notes", "citation", "license");
        for (Map.Entry<String, String> a : t.attributes().entrySet()) {
            if (!known.contains(a.getKey())) {
                extra.put(a.getKey(), a.getValue());
            }
        }
        if (!extra.isEmpty()) {
            entry.put("extra", extra);
        }
        if (!t.documentAttributes().isEmpty()) {
            entry.put("attributes", new LinkedHashMap<>(t.documentAttributes()));
        }
        if (t.includes() > 0) {
            entry.put("includes", t.includes());
        }
        return entry;
    }

    private static void putIfPresent(Map<String, Object> entry, String key, String value) {
        if (value != null && !value.isBlank()) {
            entry.put(key, value);
        }
    }

    private static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String v : value.split(",")) {
            String t = v.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String directoryOf(String relativeFile) {
        int slash = relativeFile.lastIndexOf('/');
        return slash < 0 ? "" : relativeFile.substring(0, slash);
    }

    private static String relativeTo(Path base, Path path) {
        try {
            Path b = base.toRealPath();
            if (path.startsWith(b)) {
                String relative = b.relativize(path).toString().replace('\\', '/');
                return relative.isEmpty() ? "." : relative;
            }
        } catch (IOException e) {
            // fall through to the absolute form
        }
        return path.toString();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}

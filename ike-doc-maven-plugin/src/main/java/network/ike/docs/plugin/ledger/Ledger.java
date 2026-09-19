package network.ike.docs.plugin.ledger;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
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
        List<String> linkFindings = new ArrayList<>();
        // Links are never followed, so containment holds without resolving each
        // file's real path (which cost as much as parsing); a linked .adoc is
        // reported and skipped instead.
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
                if (!file.getFileName().toString().endsWith(".adoc")) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    linkFindings.add(real.relativize(file).toString().replace('\\', '/')
                            + ": symbolic link, skipped");
                } else if (attrs.isRegularFile()) {
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
        linkFindings.sort(null);
        findings.addAll(linkFindings);
        Map<String, String> firstFileById = new LinkedHashMap<>();
        for (Path file : files) {
            String relative = real.relativize(file).toString().replace('\\', '/');
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
                otherFiles.add(otherEntry(o));
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
     * Render the ledger model as YAML in the registry's block style: two-space
     * indent, sequences indented under their key, one scalar per line. Written
     * directly rather than through SnakeYAML's emitter, which cost 40 percent
     * of a large run and about 45 ms of class loading on every small one; the
     * output round-trips through {@link #load} unchanged.
     *
     * @param model the ledger model from {@link #model}
     * @return the YAML text
     */
    public static String yaml(Map<String, Object> model) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("# doc-ledger.yaml, generated by idoc:ledger. Do not edit or commit.\n");
        emitMap(sb, model, 0);
        return sb.toString();
    }

    private static void emitMap(StringBuilder sb, Map<?, ?> map, int indent) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            pad(sb, indent).append(scalar(String.valueOf(e.getKey()))).append(':');
            emitValue(sb, e.getValue(), indent);
        }
    }

    private static void emitValue(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append(" {}\n");
            } else {
                sb.append('\n');
                emitMap(sb, m, indent + 2);
            }
        } else if (value instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append(" []\n");
                return;
            }
            sb.append('\n');
            for (Object item : l) {
                pad(sb, indent + 2).append('-');
                if (item instanceof Map<?, ?> m && !m.isEmpty()) {
                    boolean first = true;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (first) {
                            sb.append(' ');
                            first = false;
                        } else {
                            pad(sb, indent + 4);
                        }
                        sb.append(scalar(String.valueOf(e.getKey()))).append(':');
                        emitValue(sb, e.getValue(), indent + 4);
                    }
                } else {
                    sb.append(' ');
                    emitScalarOrEmpty(sb, item);
                    sb.append('\n');
                }
            }
        } else {
            sb.append(' ');
            emitScalarOrEmpty(sb, value);
            sb.append('\n');
        }
    }

    private static void emitScalarOrEmpty(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?>) {
            sb.append("{}");
        } else if (value instanceof List<?>) {
            sb.append("[]");
        } else {
            sb.append(scalar(value));
        }
    }

    /** Words YAML 1.1 reads as booleans or null when unquoted. */
    private static final Set<String> RESERVED = Set.of("true", "false", "yes", "no", "on", "off",
            "y", "n", "null", "~", ".inf", "-.inf", ".nan");

    /**
     * A scalar as YAML: numbers and booleans plain; strings plain when SnakeYAML
     * would read them back as the same string, otherwise single-quoted (or
     * double-quoted when they hold control characters).
     */
    static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String s = String.valueOf(value);
        if (s.isEmpty()) {
            return "''";
        }
        boolean control = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                control = true;
                break;
            }
        }
        if (control) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20 || c == 0x7f) {
                            sb.append(String.format("\\x%02x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
        if (isPlain(s)) {
            return s;
        }
        return "'" + s.replace("'", "''") + "'";
    }

    private static boolean isPlain(String s) {
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (first == ' ' || last == ' ' || last == ':') {
            return false;
        }
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(first) >= 0) {
            return false;
        }
        if (s.contains(": ") || s.contains(" #") || s.contains("\t")) {
            return false;
        }
        if (RESERVED.contains(s.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        // Anything SnakeYAML could take for a number, a timestamp or a sexagesimal;
        // only strings that start like one need the regex.
        if ((Character.isDigit(first) || first == '.' || first == '+') && LOOKS_TYPED.matcher(s).matches()) {
            return false;
        }
        return true;
    }

    private static final java.util.regex.Pattern LOOKS_TYPED = java.util.regex.Pattern.compile(
            "[-+]?(\\d[\\d_]*)?(\\.\\d[\\d_]*)?([eE][-+]?\\d+)?|0x[0-9a-fA-F_]+|0o?[0-7_]+|0b[01_]+"
                    + "|[-+]?\\d[\\d_]*(:[0-5]?\\d)+(\\.\\d+)?|\\d{4}-\\d\\d?-\\d\\d?([Tt ].*)?");

    private static StringBuilder pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb;
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

    /**
     * Read a ledger written by {@link #yaml(Map)} back into its model.
     *
     * @param ledgerFile the ledger file
     * @return the model, with the same shape {@link #model} produces
     * @throws IOException if the file cannot be read or is not a ledger
     */
    public static Map<String, Object> load(Path ledgerFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // SnakeYAML refuses documents over 3 MB by default; a ledger of a few
        // thousand topics is larger than that. 256 MB is far above any corpus.
        options.setCodePointLimit(256 * 1024 * 1024);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (!(loaded instanceof Map<?, ?> map) || !(map.get("roots") instanceof List<?>)) {
                throw new IOException("Not a ledger: " + ledgerFile);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) map;
            return model;
        }
    }

    /**
     * Add files to an existing ledger, or refresh their entries, without
     * rescanning the roots: each file is parsed alone, placed in its directory
     * group in path order, counted, and checked for a duplicate id against the
     * ids the ledger already holds. An entry for the same file is replaced, so
     * a re-ingested document is safe to add again. Files the ledger lists but
     * that no longer exist are not noticed here; a full scan is.
     *
     * @param model the ledger model, from {@link #model} or {@link #load};
     *              updated in place
     * @param base  the module base directory the ledger's roots are relative to
     * @param files the files to add, relative to {@code base} or absolute; each
     *              must be an {@code .adoc} file under one of the ledger's roots
     * @param now   the update instant, recorded as {@code generated}
     * @return the parsed headers of the added files, in the order given
     * @throws IOException              if a file cannot be read
     * @throws IllegalArgumentException if a file is missing, is not AsciiDoc,
     *                                  or lies under none of the ledger's roots
     */
    @SuppressWarnings("unchecked")
    public static List<TopicHeader> add(Map<String, Object> model, Path base, List<Path> files,
                                        Instant now) throws IOException {
        List<Map<String, Object>> roots = (List<Map<String, Object>>) model.get("roots");
        List<String> findings = new ArrayList<>();
        for (Object f : (List<Object>) model.getOrDefault("findings", List.of())) {
            findings.add(String.valueOf(f));
        }
        List<TopicHeader> added = new ArrayList<>();
        for (Path file : files) {
            Path abs = base.resolve(file);
            if (!Files.isRegularFile(abs)) {
                throw new IllegalArgumentException("Not a file: " + abs);
            }
            if (!abs.getFileName().toString().endsWith(".adoc")) {
                throw new IllegalArgumentException("Not an AsciiDoc file: " + abs);
            }
            Path real = abs.toRealPath();
            Map<String, Object> rootEntry = null;
            Path rootPath = null;
            List<String> rootNames = new ArrayList<>();
            for (Map<String, Object> r : roots) {
                String name = String.valueOf(r.get("root"));
                rootNames.add(name);
                Path candidate = base.resolve(name);
                if (Files.isDirectory(candidate) && real.startsWith(candidate.toRealPath())) {
                    rootEntry = r;
                    rootPath = candidate.toRealPath();
                    break;
                }
            }
            if (rootEntry == null) {
                throw new IllegalArgumentException(abs + " is under none of the ledger's roots "
                        + rootNames + "; run a full idoc:ledger instead");
            }
            TopicHeader header = TopicHeader.parse(real, rootPath);
            String relative = header.file();
            remove(rootEntry, relative);
            findings.removeIf(f -> f.startsWith(relative + ": "));
            if (header.topic()) {
                String earlier = fileDeclaring(roots, header.id());
                if (earlier != null) {
                    findings.add(relative + ": duplicate id '" + header.id()
                            + "', already declared by " + earlier);
                }
                insertTopic(rootEntry, header);
            } else {
                insertOther(rootEntry, header);
            }
            for (String f : header.findings()) {
                findings.add(relative + ": " + f);
            }
            recount(rootEntry);
            added.add(header);
        }
        model.put("generated", now.truncatedTo(ChronoUnit.SECONDS).toString());
        model.put("findings", findings);
        return added;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> holder, String key) {
        return (List<Map<String, Object>>) holder.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private static void remove(Map<String, Object> rootEntry, String relative) {
        for (Iterator<Map<String, Object>> it = listOf(rootEntry, "directories").iterator(); it.hasNext();) {
            Map<String, Object> dir = it.next();
            List<Map<String, Object>> topics = listOf(dir, "topics");
            topics.removeIf(t -> relative.equals(t.get("file")));
            if (topics.isEmpty()) {
                it.remove();
            }
        }
        listOf(rootEntry, "other-files").removeIf(o -> relative.equals(o.get("file")));
    }

    private static String fileDeclaring(List<Map<String, Object>> roots, String id) {
        for (Map<String, Object> r : roots) {
            for (Map<String, Object> dir : listOf(r, "directories")) {
                for (Map<String, Object> t : listOf(dir, "topics")) {
                    if (id.equals(t.get("id"))) {
                        return String.valueOf(t.get("file"));
                    }
                }
            }
        }
        return null;
    }

    private static void insertTopic(Map<String, Object> rootEntry, TopicHeader header) {
        List<Map<String, Object>> dirs = listOf(rootEntry, "directories");
        String dirName = directoryOf(header.file());
        String key = dirName.isEmpty() ? "." : dirName;
        Map<String, Object> group = null;
        int position = 0;
        for (int i = 0; i < dirs.size(); i++) {
            String d = String.valueOf(dirs.get(i).get("dir"));
            if (d.equals(key)) {
                group = dirs.get(i);
                break;
            }
            if (d.compareTo(key) < 0) {
                position = i + 1;
            }
        }
        if (group == null) {
            group = new LinkedHashMap<>();
            group.put("dir", key);
            group.put("topics", new ArrayList<>());
            dirs.add(position, group);
        }
        List<Map<String, Object>> topics = listOf(group, "topics");
        int at = 0;
        while (at < topics.size()
                && String.valueOf(topics.get(at).get("file")).compareTo(header.file()) < 0) {
            at++;
        }
        topics.add(at, topicEntry(header));
    }

    private static void insertOther(Map<String, Object> rootEntry, TopicHeader header) {
        List<Map<String, Object>> others = listOf(rootEntry, "other-files");
        int at = 0;
        while (at < others.size()
                && String.valueOf(others.get(at).get("file")).compareTo(header.file()) < 0) {
            at++;
        }
        others.add(at, otherEntry(header));
    }

    private static void recount(Map<String, Object> rootEntry) {
        int topics = 0;
        for (Map<String, Object> dir : listOf(rootEntry, "directories")) {
            topics += listOf(dir, "topics").size();
        }
        int others = listOf(rootEntry, "other-files").size();
        rootEntry.put("files", topics + others);
        rootEntry.put("topics", topics);
    }

    private static Map<String, Object> otherEntry(TopicHeader o) {
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
        return entry;
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

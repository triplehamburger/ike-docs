package network.ike.docs.plugin.registry;

import network.ike.docs.plugin.diff.PacketAssembler;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The generated topic registry: every AsciiDoc file under a scan root, parsed
 * by {@link TopicHeader}, in the shape {@code IKE-TOPIC-REGISTRY.md} gives
 * {@code topic-registry.yaml}: topics grouped into domains by the prefix of
 * their id, each with the registry's field names ({@code id}, {@code file},
 * {@code title}, {@code type}, {@code keywords}, {@code status},
 * {@code char-count}, {@code related}, {@code summary}), then what a build can
 * add on top: assemblies and plain files with their document attributes, the
 * findings the headers raise, and a generation stamp. This is the registry's
 * build-time revision: generated from the files on every run where, before it,
 * the same registry was kept by hand at {@code src/docs/asciidoc/topic-registry.yaml}.
 * Pure Java with no Maven dependency, so it is testable without a build;
 * {@code TopicRegistryMojo} is the wiring around it.
 *
 * @since 109
 */
public final class TopicRegistry {

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

    private TopicRegistry() {
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
                    + real + "; raise -Dike.topic-registry.maxFiles or narrow -Dike.topic-registry.roots");
        }
        files.sort((a, b) -> real.relativize(a).toString().compareTo(real.relativize(b).toString()));

        // Parse in parallel; toList() keeps the sorted encounter order.
        List<TopicHeader> headers;
        try {
            headers = files.parallelStream().map(file -> {
                try {
                    return TopicHeader.parse(file, real);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return collect(real, headers, linkFindings);
    }

    /**
     * Scan from one root file: the file and every {@code .adoc} file it
     * reaches through {@code include::} lines, followed recursively and
     * resolved relative to the including file as the AsciiDoc preprocessor
     * does. Files under the root that are not reached are left out. An
     * include whose target cannot be resolved statically (an attribute
     * reference, an absolute path, a URI, a path above the root) or that
     * names a missing file is a finding; a target that is not an
     * {@code .adoc} file (a diagram, a CSV) is passed over. Each file is read
     * once, so an include cycle ends.
     *
     * @param root     the scan root; registry paths are relative to it
     * @param rootFile the {@code .adoc} file to start from, under {@code root}
     * @return the scan result
     * @throws IOException              if a file cannot be read
     * @throws IllegalArgumentException if {@code rootFile} is not an
     *                                  {@code .adoc} file under {@code root}
     */
    public static Scan scanFrom(Path root, Path rootFile) throws IOException {
        Path real = root.toRealPath();
        if (!Files.isRegularFile(rootFile) || !rootFile.getFileName().toString().endsWith(".adoc")) {
            throw new IllegalArgumentException("Not an AsciiDoc file: " + rootFile);
        }
        Path start = rootFile.toRealPath();
        if (!start.startsWith(real)) {
            throw new IllegalArgumentException(rootFile + " is not under the registry root " + real);
        }
        String first = real.relativize(start).toString().replace(File.separatorChar, '/');
        Set<String> seen = new HashSet<>(List.of(first));
        Deque<String> queue = new ArrayDeque<>(List.of(first));
        List<TopicHeader> headers = new ArrayList<>();
        List<String> includeFindings = new ArrayList<>();
        while (!queue.isEmpty()) {
            String relative = queue.removeFirst();
            String content = Files.readString(real.resolve(relative), StandardCharsets.UTF_8);
            headers.add(TopicHeader.parse(relative, content));
            for (String line : content.lines().toList()) {
                int bracket = line.indexOf('[');
                if (!line.startsWith("include::") || bracket < 0) {
                    continue;
                }
                String target = line.substring("include::".length(), bracket).strip();
                String resolved = PacketAssembler.resolveInclude(relative, target);
                if (resolved == null) {
                    includeFindings.add(relative + ": include::" + target
                            + "[] not followed (attribute reference, absolute path, URI or above the root)");
                    continue;
                }
                if (!resolved.endsWith(".adoc") || !seen.add(resolved)) {
                    continue;
                }
                Path file = real.resolve(resolved);
                if (Files.isSymbolicLink(file)) {
                    includeFindings.add(resolved + ": symbolic link, skipped");
                } else if (!Files.isRegularFile(file)) {
                    includeFindings.add(relative + ": include::" + target + "[] does not resolve to a file");
                } else {
                    queue.addLast(resolved);
                }
            }
        }
        headers.sort((a, b) -> a.file().compareTo(b.file()));
        return collect(real, headers, includeFindings);
    }

    /**
     * Group parsed headers into topics and other files and gather their
     * findings, the part of a scan shared by {@link #scan} and
     * {@link #scanFrom}.
     */
    private static Scan collect(Path real, List<TopicHeader> headers, List<String> earlyFindings) {
        List<TopicHeader> topics = new ArrayList<>();
        List<TopicHeader> others = new ArrayList<>();
        List<String> findings = new ArrayList<>(earlyFindings);
        findings.sort(null);
        Map<String, String> firstFileById = new LinkedHashMap<>();
        for (TopicHeader header : headers) {
            String relative = header.file();
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
     * The domain a topic belongs to: the part of its id before the first
     * hyphen, the {@code {domain-prefix}-{slug}} rule of
     * {@code IKE-TOPIC-REGISTRY.md}.
     *
     * @param id the topic id
     * @return the domain id, or the whole id when it has no hyphen
     */
    public static String domainOf(String id) {
        int i = id.indexOf('-');
        return i <= 0 ? id : id.substring(0, i);
    }

    /**
     * The registry as an ordered map, ready for YAML: the schema's keys first
     * ({@code registry-version}, {@code generated}, {@code topic-count},
     * {@code domains} with their {@code topics}), then {@code assemblies},
     * {@code other-files} and {@code findings}. Topics are grouped by
     * {@link #domainOf domain} and sorted by id. A file path is relative to
     * its root, prefixed with the root when several roots were scanned.
     *
     * @param scans the scanned roots
     * @param base  the directory root paths are made relative to (the module
     *              base directory)
     * @param now   the generation instant
     * @return the registry model
     */
    public static Map<String, Object> model(List<Scan> scans, Path base, Instant now) {
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("registry-version", "1.2");
        registry.put("generated", now.truncatedTo(ChronoUnit.SECONDS).toString());
        registry.put("scanned-from", base.toAbsolutePath().normalize().toString());
        List<String> roots = new ArrayList<>();
        for (Scan scan : scans) {
            roots.add(relativeTo(base, scan.root()));
        }
        registry.put("roots", roots);
        boolean prefix = scans.size() > 1;
        TreeMap<String, List<Map<String, Object>>> domains = new TreeMap<>();
        List<Object> assemblies = new ArrayList<>();
        List<Object> otherFiles = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        int files = 0;
        int topics = 0;
        for (int i = 0; i < scans.size(); i++) {
            Scan scan = scans.get(i);
            String rootPrefix = prefix ? roots.get(i) + "/" : "";
            files += scan.topics().size() + scan.others().size();
            topics += scan.topics().size();
            for (TopicHeader t : scan.topics()) {
                domains.computeIfAbsent(domainOf(t.id()), k -> new ArrayList<>())
                        .add(topicEntry(t, rootPrefix + t.file()));
            }
            for (TopicHeader o : scan.others()) {
                (o.includes() > 0 ? assemblies : otherFiles).add(otherEntry(o, rootPrefix + o.file()));
            }
            for (String f : scan.findings()) {
                findings.add(rootPrefix + f);
            }
        }
        registry.put("topic-count", topics);
        registry.put("file-count", files);
        List<Object> domainList = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : domains.entrySet()) {
            Map<String, Object> domain = new LinkedHashMap<>();
            domain.put("id", e.getKey());
            e.getValue().sort((x, y) -> String.valueOf(x.get("id")).compareTo(String.valueOf(y.get("id"))));
            domain.put("topics", e.getValue());
            domainList.add(domain);
        }
        registry.put("domains", domainList);
        registry.put("assemblies", assemblies);
        registry.put("other-files", otherFiles);
        registry.put("findings", findings);
        return registry;
    }

    /**
     * Render the registry model as YAML in the registry's block style: two-space
     * indent, sequences indented under their key, one scalar per line. Written
     * directly rather than through SnakeYAML's emitter, which cost 40 percent
     * of a large run and about 45 ms of class loading on every small one; the
     * output round-trips through {@link #load} unchanged.
     *
     * @param model the registry model from {@link #model}
     * @return the YAML text
     */
    public static String yaml(Map<String, Object> model) {
        return yaml(model, "topic-registry.yaml, generated by idoc:topic-registry from the files. Do not edit.");
    }

    /**
     * Render a model as YAML in the registry's block style under a given
     * header comment, the shape shared by the registry, the term index and
     * the package manifest.
     *
     * @param model   the model, an ordered map of scalars, lists and maps
     * @param comment the header comment, without the leading {@code #}
     * @return the YAML text
     */
    public static String yaml(Map<String, Object> model, String comment) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("# ").append(comment).append('\n');
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
     * The ids of the topics whose header marks keywords and summary as
     * placeholders ({@code :topic-metadata: placeholder}), in registry order.
     *
     * @param model a registry model, as {@link #load} returns it
     * @return the topic ids, empty when none is marked
     */
    public static List<String> placeholderTopics(Map<String, Object> model) {
        List<String> ids = new ArrayList<>();
        if (model.get("domains") instanceof List<?> domains) {
            for (Object d : domains) {
                if (d instanceof Map<?, ?> domain && domain.get("topics") instanceof List<?> topics) {
                    for (Object t : topics) {
                        if (t instanceof Map<?, ?> topic && topic.get("extra") instanceof Map<?, ?> extra
                                && TopicHeader.PLACEHOLDER.equalsIgnoreCase(
                                        String.valueOf(extra.get("metadata")).strip())) {
                            ids.add(String.valueOf(topic.get("id")));
                        }
                    }
                }
            }
        }
        return ids;
    }

    /**
     * Read a registry written by {@link #yaml(Map)} back into its model.
     *
     * @param registryFile the generated registry file
     * @return the model, with the same shape {@link #model} produces
     * @throws IOException if the file cannot be read or is not a generated registry
     */
    public static Map<String, Object> load(Path registryFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // SnakeYAML refuses documents over 3 MB by default; a registry of a few
        // thousand topics is larger than that. 256 MB is far above any corpus.
        options.setCodePointLimit(256 * 1024 * 1024);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(registryFile, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            if (!(loaded instanceof Map<?, ?> map) || !(map.get("domains") instanceof List<?>)
                    || !(map.get("roots") instanceof List<?>)) {
                throw new IOException("Not a generated topic registry: " + registryFile);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) map;
            return model;
        }
    }

    /**
     * Add files to an existing registry, or refresh their entries, without
     * rescanning the roots: each file is parsed alone, placed in its domain in
     * id order, counted, and checked for a duplicate id against the ids the
     * registry already holds. An entry for the same file is replaced, so a
     * re-ingested document is safe to add again. Files the registry lists but
     * that no longer exist are not noticed here; a full scan is.
     *
     * @param model the registry model, from {@link #model} or {@link #load};
     *              updated in place
     * @param base  the module base directory the registry's roots are relative to
     * @param files the files to add, relative to {@code base} or absolute; each
     *              must be an {@code .adoc} file under one of the registry's roots
     * @param now   the update instant, recorded as {@code generated}
     * @return the parsed headers of the added files, in the order given
     * @throws IOException              if a file cannot be read
     * @throws IllegalArgumentException if a file is missing, is not AsciiDoc,
     *                                  or lies under none of the registry's roots
     */
    @SuppressWarnings("unchecked")
    public static List<TopicHeader> add(Map<String, Object> model, Path base, List<Path> files,
                                        Instant now) throws IOException {
        List<String> roots = new ArrayList<>();
        for (Object r : (List<Object>) model.get("roots")) {
            roots.add(String.valueOf(r));
        }
        boolean prefix = roots.size() > 1;
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
            String rootName = null;
            Path rootPath = null;
            for (String name : roots) {
                Path candidate = base.resolve(name);
                if (Files.isDirectory(candidate) && real.startsWith(candidate.toRealPath())) {
                    rootName = name;
                    rootPath = candidate.toRealPath();
                    break;
                }
            }
            if (rootName == null) {
                throw new IllegalArgumentException(abs + " is under none of the registry's roots "
                        + roots + "; run a full idoc:topic-registry instead");
            }
            TopicHeader header = TopicHeader.parse(real, rootPath);
            String fileValue = (prefix ? rootName + "/" : "") + header.file();
            remove(model, fileValue);
            findings.removeIf(f -> f.startsWith(fileValue + ": "));
            if (header.topic()) {
                String earlier = fileDeclaring(model, header.id());
                if (earlier != null) {
                    findings.add(fileValue + ": duplicate id '" + header.id()
                            + "', already declared by " + earlier);
                }
                insertTopic(model, header, fileValue);
            } else {
                insertOther(model, header, fileValue);
            }
            for (String f : header.findings()) {
                findings.add(fileValue + ": " + f);
            }
            added.add(header);
        }
        recount(model);
        model.put("generated", now.truncatedTo(ChronoUnit.SECONDS).toString());
        model.put("findings", findings);
        return added;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> holder, String key) {
        return (List<Map<String, Object>>) holder.computeIfAbsent(key, k -> new ArrayList<>());
    }

    private static void remove(Map<String, Object> model, String fileValue) {
        for (Iterator<Map<String, Object>> it = listOf(model, "domains").iterator(); it.hasNext();) {
            Map<String, Object> domain = it.next();
            List<Map<String, Object>> topics = listOf(domain, "topics");
            topics.removeIf(t -> fileValue.equals(t.get("file")));
            if (topics.isEmpty()) {
                it.remove();
            }
        }
        listOf(model, "assemblies").removeIf(o -> fileValue.equals(o.get("file")));
        listOf(model, "other-files").removeIf(o -> fileValue.equals(o.get("file")));
    }

    private static String fileDeclaring(Map<String, Object> model, String id) {
        for (Map<String, Object> domain : listOf(model, "domains")) {
            for (Map<String, Object> t : listOf(domain, "topics")) {
                if (id.equals(t.get("id"))) {
                    return String.valueOf(t.get("file"));
                }
            }
        }
        return null;
    }

    private static void insertTopic(Map<String, Object> model, TopicHeader header, String fileValue) {
        List<Map<String, Object>> domains = listOf(model, "domains");
        String key = domainOf(header.id());
        Map<String, Object> domain = null;
        int position = 0;
        for (int i = 0; i < domains.size(); i++) {
            String d = String.valueOf(domains.get(i).get("id"));
            if (d.equals(key)) {
                domain = domains.get(i);
                break;
            }
            if (d.compareTo(key) < 0) {
                position = i + 1;
            }
        }
        if (domain == null) {
            domain = new LinkedHashMap<>();
            domain.put("id", key);
            domain.put("topics", new ArrayList<>());
            domains.add(position, domain);
        }
        List<Map<String, Object>> topics = listOf(domain, "topics");
        int at = 0;
        while (at < topics.size()
                && String.valueOf(topics.get(at).get("id")).compareTo(header.id()) <= 0) {
            at++;
        }
        topics.add(at, topicEntry(header, fileValue));
    }

    private static void insertOther(Map<String, Object> model, TopicHeader header, String fileValue) {
        List<Map<String, Object>> list = listOf(model, header.includes() > 0 ? "assemblies" : "other-files");
        int at = 0;
        while (at < list.size()
                && String.valueOf(list.get(at).get("file")).compareTo(fileValue) < 0) {
            at++;
        }
        list.add(at, otherEntry(header, fileValue));
    }

    private static void recount(Map<String, Object> model) {
        int topics = 0;
        for (Map<String, Object> domain : listOf(model, "domains")) {
            topics += listOf(domain, "topics").size();
        }
        model.put("topic-count", topics);
        model.put("file-count", topics + listOf(model, "assemblies").size()
                + listOf(model, "other-files").size());
    }

    /**
     * An assembly or plain file: its path, title, document attributes and
     * include count.
     *
     * @param o         the parsed header of a file that is not a topic
     * @param fileValue the file path to record
     * @return the registry entry
     */
    public static Map<String, Object> otherEntry(TopicHeader o, String fileValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", fileValue);
        if (o.title() != null) {
            entry.put("title", o.title());
            if (o.level() != 1) {
                entry.put("level", o.level());
            }
        }
        if (!o.documentAttributes().isEmpty()) {
            entry.put("attributes", new LinkedHashMap<>(o.documentAttributes()));
        }
        if (o.includes() > 0) {
            entry.put("includes", o.includes());
        }
        return entry;
    }

    /**
     * One topic in the registry's field order ({@code id}, {@code file},
     * {@code title}, {@code type}, {@code keywords}, {@code status},
     * {@code char-count}, {@code dependencies}, {@code related},
     * {@code summary}, {@code supersedes}, {@code notes}), then what the
     * header adds: provenance, scope note, citation, license, the anchor and
     * header verdicts, unknown {@code :topic-*:} attributes under
     * {@code extra}, document attributes, and the include count.
     * Shared with {@code idoc:diff}, which generates the registry for each
     * side of a comparison in this same shape.
     *
     * @param t         the parsed topic header
     * @param fileValue the file path to record
     * @return the registry entry
     */
    public static Map<String, Object> topicEntry(TopicHeader t, String fileValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", t.id());
        entry.put("file", fileValue);
        if (t.title() != null) {
            entry.put("title", t.title());
        }
        putIfPresent(entry, "type", t.attributes().get("type"));
        entry.put("keywords", t.keywords());
        putIfPresent(entry, "status", t.attributes().get("status"));
        entry.put("char-count", t.charCount());
        putListIfPresent(entry, "dependencies", t.attributes().get("dependencies"));
        putListIfPresent(entry, "related", t.attributes().get("related"));
        putIfPresent(entry, "summary", t.attributes().get("summary"));
        putIfPresent(entry, "supersedes", t.attributes().get("supersedes"));
        putIfPresent(entry, "notes", t.attributes().get("notes"));
        putIfPresent(entry, "provenance", t.attributes().get("provenance"));
        putIfPresent(entry, "scope-note", t.attributes().get("scope-note"));
        putIfPresent(entry, "citation", t.attributes().get("citation"));
        putIfPresent(entry, "license", t.attributes().get("license"));
        entry.put("anchor", t.anchor());
        List<String> missing = t.missingRequired();
        entry.put("header", missing.isEmpty() ? "complete" : "missing: " + String.join(", ", missing));
        Map<String, String> extra = new LinkedHashMap<>();
        Set<String> known = Set.of("id", "type", "keywords", "status", "dependencies", "related",
                "summary", "supersedes", "notes", "provenance", "scope-note", "citation", "license");
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

    private static void putListIfPresent(Map<String, Object> entry, String key, String value) {
        if (value != null && !value.isBlank()) {
            entry.put(key, split(value));
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

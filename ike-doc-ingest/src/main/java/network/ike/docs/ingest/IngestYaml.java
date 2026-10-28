package network.ike.docs.ingest;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Deterministic YAML reading and writing for the ingest contract.
///
/// The same input always dumps to the same bytes: block style, two-space
/// indent, LF line endings, no line wrapping, sorted keys inside goal
/// details, and multi-line text as literal blocks. Reading is a safe load
/// that refuses aliases and duplicate keys, and every failure surfaces as
/// an {@link IOException} naming what was wrong.
public final class IngestYaml {

    /// The schema version written to, and required of, every ingest file.
    public static final int SCHEMA_VERSION = 1;

    private static final int MAX_CODE_POINTS = 16 * 1024 * 1024;

    private IngestYaml() {
    }

    /// Dump a value to YAML text.
    ///
    /// Maps are written with sorted keys. Supported values are maps,
    /// collections, strings, numbers, booleans and enums.
    ///
    /// @param value the value to dump
    /// @return the YAML text, ending in a newline
    /// @throws IllegalArgumentException for an unsupported value type
    public static String dump(Object value) {
        String text = newDumper().dump(normalize(value));
        return text.endsWith("\n") ? text : text + "\n";
    }

    /// Safely load YAML text.
    ///
    /// @param text the YAML text
    /// @return the loaded value: a map, list, scalar or null
    /// @throws IOException if the text is not valid YAML
    public static Object load(String text) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_CODE_POINTS);
        Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()),
                new DumperOptions(), options);
        try {
            return yaml.load(text);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("not valid YAML: " + message.lines().findFirst().orElse(message), e);
        }
    }

    /// Render a report as YAML.
    ///
    /// @param report the report
    /// @return the YAML text
    public static String reportToYaml(IngestReport report) {
        Map<String, Object> root = new Fixed();
        root.put("schema-version", SCHEMA_VERSION);
        root.put("goal", report.goal());
        root.put("sources", report.sources().stream()
                .map(s -> ordered("path", s.path(), "sha256", s.sha256())).toList());
        root.put("details", report.details());
        root.put("findings", report.findings().stream()
                .map(f -> ordered("severity", f.severity().name(), "check", f.check(),
                        "component", f.component(), "message", f.message())).toList());
        root.put("plan", report.plan().stream()
                .map(p -> ordered("path", p.path(), "sha256", p.sha256())).toList());
        return dump(root);
    }

    /// Read a report back from YAML.
    ///
    /// @param yaml the YAML text
    /// @return the report
    /// @throws IOException if the text is not a valid report
    public static IngestReport reportFromYaml(String yaml) throws IOException {
        Map<String, Object> root = asMap(load(yaml), "report");
        requireSchema(root, "report");
        try {
            List<IngestReport.Source> sources = new ArrayList<>();
            for (Object o : asList(root.get("sources"), "report sources")) {
                Map<String, Object> m = asMap(o, "report source");
                sources.add(new IngestReport.Source(text(m, "path", "report source"),
                        text(m, "sha256", "report source")));
            }
            List<Finding> findings = new ArrayList<>();
            for (Object o : asList(root.get("findings"), "report findings")) {
                Map<String, Object> m = asMap(o, "report finding");
                findings.add(new Finding(Finding.Severity.valueOf(text(m, "severity", "report finding")),
                        text(m, "check", "report finding"), text(m, "component", "report finding"),
                        text(m, "message", "report finding")));
            }
            List<IngestReport.PlanEntry> plan = new ArrayList<>();
            for (Object o : asList(root.get("plan"), "report plan")) {
                Map<String, Object> m = asMap(o, "report plan entry");
                plan.add(new IngestReport.PlanEntry(text(m, "path", "report plan entry"),
                        text(m, "sha256", "report plan entry")));
            }
            Object details = root.get("details");
            return new IngestReport(text(root, "goal", "report"), sources,
                    details == null ? Map.of() : asMap(details, "report details"), findings, plan);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid report: " + e.getMessage(), e);
        }
    }

    /// Render the AI-facing work-order file as YAML.
    ///
    /// @param file the work orders
    /// @return the YAML text
    public static String needsAiToYaml(NeedsAiFile file) {
        Map<String, Object> root = new Fixed();
        root.put("schema-version", SCHEMA_VERSION);
        root.put("instructions", file.instructions());
        root.put("items", file.items().stream().map(i -> ordered("id", i.id(), "kind", i.kind(),
                "target", i.target(), "blocking", i.blocking(), "context", i.context(),
                "expect", i.expect())).toList());
        return dump(root);
    }

    /// Read the work-order file back from YAML.
    ///
    /// @param yaml the YAML text
    /// @return the work orders
    /// @throws IOException if the text is not a valid work-order file
    public static NeedsAiFile needsAiFromYaml(String yaml) throws IOException {
        Map<String, Object> root = asMap(load(yaml), "needs-ai");
        requireSchema(root, "needs-ai");
        try {
            List<NeedsAi> items = new ArrayList<>();
            for (Object o : asList(root.get("items"), "needs-ai items")) {
                Map<String, Object> m = asMap(o, "needs-ai item");
                Object blocking = m.get("blocking");
                if (!(blocking instanceof Boolean b)) {
                    throw new IOException("needs-ai item blocking must be true or false");
                }
                items.add(new NeedsAi(text(m, "id", "needs-ai item"), text(m, "kind", "needs-ai item"),
                        text(m, "target", "needs-ai item"), b, text(m, "context", "needs-ai item"),
                        text(m, "expect", "needs-ai item")));
            }
            return new NeedsAiFile(text(root, "instructions", "needs-ai"), items);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid needs-ai file: " + e.getMessage(), e);
        }
    }

    /// Write a file atomically: to a sibling temporary file, then moved
    /// into place, so a reader never sees a half-written file.
    ///
    /// @param target  the file to write
    /// @param content the text, written as UTF-8 with LF line endings
    /// @throws IOException if the file cannot be written
    public static void writeAtomic(Path target, String content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), ".ingest-", ".tmp");
        try {
            Files.write(temp, content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Yaml newDumper() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        options.setLineBreak(DumperOptions.LineBreak.UNIX);
        options.setAllowUnicode(true);
        return new Yaml(new LiteralRepresenter(options), options);
    }

    /// Writes multi-line strings as literal blocks so they stay readable.
    private static final class LiteralRepresenter extends Representer {
        LiteralRepresenter(DumperOptions options) {
            super(options);
            Represent standard = this.representers.get(String.class);
            this.representers.put(String.class, data -> {
                String s = (String) data;
                if (s.indexOf('\n') >= 0 && s.indexOf('\r') < 0) {
                    return representScalar(Tag.STR, s, DumperOptions.ScalarStyle.LITERAL);
                }
                return standard.representData(data);
            });
        }
    }

    /// A map whose key order is part of the file's fixed layout. Every
    /// other map is written with sorted keys so goal details cannot
    /// depend on the order a producer built them in.
    private static final class Fixed extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new Fixed();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case Fixed fixed -> {
                Map<String, Object> out = new LinkedHashMap<>();
                fixed.forEach((k, v) -> out.put(k, normalize(v)));
                yield out;
            }
            case Map<?, ?> map -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
                Map<String, Object> out = new LinkedHashMap<>();
                sorted.forEach((k, v) -> out.put(k, normalize(v)));
                yield out;
            }
            case Collection<?> collection -> {
                List<Object> out = new ArrayList<>();
                collection.forEach(v -> out.add(normalize(v)));
                yield out;
            }
            case Enum<?> e -> e.name();
            case CharSequence s -> s.toString();
            case Number n -> n;
            case Boolean b -> b;
            default -> throw new IllegalArgumentException(
                    "unsupported value type for YAML: " + value.getClass().getName());
        };
    }

    private static void requireSchema(Map<String, Object> root, String what) throws IOException {
        if (!Integer.valueOf(SCHEMA_VERSION).equals(root.get("schema-version"))) {
            throw new IOException(what + " schema-version must be " + SCHEMA_VERSION
                    + " but was " + root.get("schema-version"));
        }
    }

    /// Cast a loaded value to a string-keyed map.
    ///
    /// @param value the loaded value
    /// @param where what is being read, for the error message
    /// @return the map
    /// @throws IOException if the value is not a map
    static Map<String, Object> asMap(Object value, String where) throws IOException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IOException(where + " must be a mapping");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /// Cast a loaded value to a list; a missing value is an empty list.
    ///
    /// @param value the loaded value
    /// @param where what is being read, for the error message
    /// @return the list
    /// @throws IOException if the value is present but not a list
    static List<Object> asList(Object value, String where) throws IOException {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IOException(where + " must be a list");
        }
        return new ArrayList<>(list);
    }

    private static String text(Map<String, Object> map, String key, String where) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IOException(where + " is missing text for '" + key + "'");
        }
        return s;
    }
}

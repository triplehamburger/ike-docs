package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// The record of one document's ingestion: `ingest.yaml`.
///
/// It names the source with its hash and, for each stage that ran, the status,
/// the SHA-256 of every input and output, the tool that ran, the parameters
/// that mattered, the findings and the gates that were relaxed. It holds no
/// timestamps, so the same inputs always give the same bytes.
///
/// A manifest is immutable: {@link #withStage} returns a changed copy.
/// {@link #write} replaces the file atomically. {@link #canSkip} is the skip
/// rule: a stage may be skipped when it succeeded before and its inputs, tool
/// and parameters are unchanged and its outputs still match their recorded hashes.
///
/// @param source the document that was ingested
/// @param stages the stages that have run, by name
public record Manifest(Source source, Map<String, Stage> stages) {

    /// The document that was ingested.
    ///
    /// @param file   the file name, without its directory, so a manifest moves with its output
    /// @param sha256 SHA-256 of the source file
    /// @param kind   what the source is
    public record Source(String file, String sha256, SourceKind kind) {

        /// Validate the source.
        ///
        /// @throws IllegalArgumentException when the file name or hash is blank
        public Source {
            if (file == null || file.isBlank() || sha256 == null || sha256.isBlank()) {
                throw new IllegalArgumentException("a manifest source needs a file name and a hash");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /// How a stage ended.
    public enum Status {
        /// The stage finished with no ERROR finding.
        OK,
        /// The stage ended with an ERROR finding or an exception.
        FAILED
    }

    /// What one stage did.
    ///
    /// @param status      how the stage ended
    /// @param inputs      SHA-256 of each input file, by path as the stage names it
    /// @param outputs     SHA-256 of each output file, by path relative to the output folder
    /// @param tool        the tool the stage ran, or null when it ran none
    /// @param parameters  the parameters that change the result, by name
    /// @param findings    what the stage found
    /// @param relaxedGates the gates that were turned down from ERROR for this run
    public record Stage(Status status, Map<String, String> inputs, Map<String, String> outputs,
                        ToolIdentity tool, Map<String, String> parameters, List<Finding> findings,
                        List<String> relaxedGates) {

        /// Validate the stage and copy its collections.
        ///
        /// @throws NullPointerException when the status or a collection is null
        public Stage {
            Objects.requireNonNull(status, "status");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            parameters = Map.copyOf(parameters);
            findings = List.copyOf(findings);
            relaxedGates = List.copyOf(relaxedGates);
        }
    }

    /// Validate the manifest and copy its stages.
    ///
    /// @throws NullPointerException when the source or the stages are null
    public Manifest {
        Objects.requireNonNull(source, "source");
        stages = Map.copyOf(stages);
    }

    /// A manifest with no stage run yet.
    ///
    /// @param source the document
    /// @return the manifest
    public static Manifest of(Source source) {
        return new Manifest(source, Map.of());
    }

    /// A copy with one stage added, or replaced when it ran before.
    ///
    /// @param name  the stage name, such as `convert`
    /// @param stage what it did
    /// @return the changed copy
    /// @throws IllegalArgumentException when the name is blank
    public Manifest withStage(String name, Stage stage) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a stage needs a name");
        }
        Map<String, Stage> copy = new LinkedHashMap<>(stages);
        copy.put(name, Objects.requireNonNull(stage, "stage"));
        return new Manifest(source, copy);
    }

    /// The skip rule. A stage may be skipped when the manifest has it as
    /// {@link Status#OK}, and the inputs, the tool and the parameters equal the
    /// recorded ones, and every recorded output still exists under the output
    /// folder with the recorded hash. Anything else means the stage runs again.
    ///
    /// @param name       the stage name
    /// @param inputs     SHA-256 of each input file now
    /// @param tool       the tool that would run now, or null for none
    /// @param parameters the parameters that change the result now
    /// @param outputRoot the folder the recorded output paths are relative to
    /// @return true when the stage can be skipped
    /// @throws IOException when an output file exists but cannot be read for hashing
    public boolean canSkip(String name, Map<String, String> inputs, ToolIdentity tool,
                           Map<String, String> parameters, Path outputRoot) throws IOException {
        Stage recorded = stages.get(name);
        if (recorded == null || recorded.status() != Status.OK
                || !recorded.inputs().equals(inputs)
                || !Objects.equals(recorded.tool(), tool)
                || !recorded.parameters().equals(parameters)) {
            return false;
        }
        for (Map.Entry<String, String> output : recorded.outputs().entrySet()) {
            Path file = outputRoot.resolve(output.getKey());
            if (!Files.isRegularFile(file) || !output.getValue().equals(IngestHash.sha256Hex(file))) {
                return false;
            }
        }
        return true;
    }

    /// Render the manifest as YAML.
    ///
    /// @return the YAML text
    public String toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema-version", IngestYaml.SCHEMA_VERSION);
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("file", source.file());
        src.put("sha256", source.sha256());
        src.put("kind", source.kind());
        root.put("source", src);
        Map<String, Object> stageMap = new TreeMap<>();
        stages.forEach((name, stage) -> stageMap.put(name, stageToMap(stage)));
        root.put("stages", stageMap);
        return IngestYaml.dump(root);
    }

    /// Read a manifest from YAML.
    ///
    /// @param yaml the YAML text
    /// @return the manifest
    /// @throws IOException when the text is not a valid manifest
    public static Manifest fromYaml(String yaml) throws IOException {
        Map<String, Object> root = IngestYaml.asMap(IngestYaml.load(yaml), "manifest");
        if (!Integer.valueOf(IngestYaml.SCHEMA_VERSION).equals(root.get("schema-version"))) {
            throw new IOException("manifest schema-version must be " + IngestYaml.SCHEMA_VERSION
                    + " but was " + root.get("schema-version"));
        }
        try {
            Map<String, Object> src = IngestYaml.asMap(root.get("source"), "manifest source");
            Source source = new Source(required(src, "file", "manifest source"),
                    required(src, "sha256", "manifest source"),
                    SourceKind.valueOf(required(src, "kind", "manifest source")));
            Map<String, Stage> stages = new LinkedHashMap<>();
            Object stagesValue = root.get("stages");
            if (stagesValue != null) {
                for (Map.Entry<String, Object> e : IngestYaml.asMap(stagesValue, "manifest stages").entrySet()) {
                    stages.put(e.getKey(), stageFromMap(IngestYaml.asMap(e.getValue(), "manifest stage")));
                }
            }
            return new Manifest(source, stages);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("invalid manifest: " + e.getMessage(), e);
        }
    }

    /// Write the manifest atomically.
    ///
    /// @param file the file to write, normally `ingest.yaml` in the output folder
    /// @throws IOException when the file cannot be written
    public void write(Path file) throws IOException {
        IngestYaml.writeAtomic(file, toYaml());
    }

    /// Read a manifest from a file.
    ///
    /// @param file the file to read
    /// @return the manifest
    /// @throws IOException when the file cannot be read or is not a valid manifest
    public static Manifest read(Path file) throws IOException {
        return fromYaml(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Object> stageToMap(Stage stage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", stage.status());
        map.put("inputs", stage.inputs());
        map.put("outputs", stage.outputs());
        if (stage.tool() != null) {
            map.put("tool", stage.tool().toMap());
        }
        map.put("parameters", stage.parameters());
        List<Object> findings = new ArrayList<>();
        for (Finding f : stage.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", f.severity());
            m.put("check", f.check());
            m.put("component", f.component());
            m.put("message", f.message());
            findings.add(m);
        }
        map.put("findings", findings);
        map.put("relaxed-gates", new ArrayList<>(stage.relaxedGates()));
        return map;
    }

    private static Stage stageFromMap(Map<String, Object> map) throws IOException {
        Object tool = map.get("tool");
        List<Finding> findings = new ArrayList<>();
        for (Object o : IngestYaml.asList(map.get("findings"), "stage findings")) {
            Map<String, Object> m = IngestYaml.asMap(o, "stage finding");
            findings.add(new Finding(Finding.Severity.valueOf(required(m, "severity", "stage finding")),
                    required(m, "check", "stage finding"), required(m, "component", "stage finding"),
                    required(m, "message", "stage finding")));
        }
        List<String> relaxed = new ArrayList<>();
        for (Object o : IngestYaml.asList(map.get("relaxed-gates"), "stage relaxed-gates")) {
            relaxed.add(String.valueOf(o));
        }
        return new Stage(Status.valueOf(required(map, "status", "stage")),
                strings(map.get("inputs"), "stage inputs"), strings(map.get("outputs"), "stage outputs"),
                tool == null ? null : ToolIdentity.fromMap(IngestYaml.asMap(tool, "stage tool")),
                strings(map.get("parameters"), "stage parameters"), findings, relaxed);
    }

    private static Map<String, String> strings(Object value, String where) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (value != null) {
            IngestYaml.asMap(value, where).forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    private static String required(Map<String, Object> map, String key, String where) throws IOException {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IOException(where + " is missing '" + key + "'");
        }
        return String.valueOf(value);
    }
}

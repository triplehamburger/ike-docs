package network.ike.docs.ingest;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestTest {

    @TempDir
    Path dir;

    private static final Manifest.Source SOURCE = new Manifest.Source("K1.pdf", "ab".repeat(32), SourceKind.PDF);
    private static final ToolIdentity TOOL = new ToolIdentity("/bin/ike-convert", "aa", "/c.py", "bb", "1.0", "cc");

    private static Manifest.Stage stage(Manifest.Status status, Map<String, String> inputs,
                                        Map<String, String> outputs, ToolIdentity tool,
                                        Map<String, String> parameters) {
        return new Manifest.Stage(status, inputs, outputs, tool, parameters,
                List.of(Finding.warning("fidelity-low", "K1.adoc", "two tables differ")), List.of("fidelity"));
    }

    @Test
    void yaml_roundTripsEveryField() throws IOException {
        Manifest manifest = Manifest.of(SOURCE)
                .withStage("convert", stage(Manifest.Status.OK, Map.of("K1.pdf", "11"), Map.of("K1.adoc", "22"),
                        TOOL, Map.of("strictFidelity", "false")))
                .withStage("split", new Manifest.Stage(Manifest.Status.FAILED, Map.of(), Map.of(), null,
                        Map.of(), List.of(Finding.error("word-check", "x", "a word changed")), List.of()));
        assertThat(Manifest.fromYaml(manifest.toYaml())).isEqualTo(manifest);
    }

    @Test
    void yaml_isDeterministicAndHasNoTimestamps() {
        Manifest manifest = Manifest.of(SOURCE)
                .withStage("split", stage(Manifest.Status.OK, Map.of("b", "2", "a", "1"), Map.of(), null, Map.of()))
                .withStage("convert", stage(Manifest.Status.OK, Map.of(), Map.of(), TOOL, Map.of()));
        String first = manifest.toYaml();
        assertThat(manifest.toYaml()).isEqualTo(first);
        assertThat(first).startsWith("schema-version: 1\n");
        assertThat(first.indexOf("convert:")).isLessThan(first.indexOf("split:"));
        assertThat(first).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void writeAndRead_replaceTheFileAtomically() throws IOException {
        Path file = dir.resolve("out").resolve("ingest.yaml");
        Manifest manifest = Manifest.of(SOURCE);
        manifest.write(file);
        assertThat(Manifest.read(file)).isEqualTo(manifest);
        Manifest changed = manifest.withStage("convert",
                stage(Manifest.Status.OK, Map.of(), Map.of(), null, Map.of()));
        changed.write(file);
        assertThat(Manifest.read(file)).isEqualTo(changed);
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("ingest.yaml");
        }
    }

    @Test
    void withStage_replacesAStageThatRanBefore() {
        Manifest first = Manifest.of(SOURCE).withStage("convert",
                stage(Manifest.Status.FAILED, Map.of(), Map.of(), null, Map.of()));
        Manifest second = first.withStage("convert",
                stage(Manifest.Status.OK, Map.of(), Map.of(), null, Map.of()));
        assertThat(first.stages().get("convert").status()).isEqualTo(Manifest.Status.FAILED);
        assertThat(second.stages()).hasSize(1);
        assertThat(second.stages().get("convert").status()).isEqualTo(Manifest.Status.OK);
        assertThatThrownBy(() -> second.withStage(" ", stage(Manifest.Status.OK, Map.of(), Map.of(), null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromYaml_rejectsAWrongSchemaVersionAndAMissingSource() {
        assertThatThrownBy(() -> Manifest.fromYaml("schema-version: 9\nsource: {}\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("schema-version");
        assertThatThrownBy(() -> Manifest.fromYaml("schema-version: 1\nsource:\n  sha256: ab\n  kind: PDF\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("file");
        assertThatThrownBy(() -> Manifest.fromYaml("not: [valid"))
                .isInstanceOf(IOException.class);
    }

    @Nested
    class SkipRule {

        private Path out;
        private Manifest manifest;
        private final Map<String, String> inputs = Map.of("K1.pdf", "11");
        private final Map<String, String> params = Map.of("limit", "20");

        @org.junit.jupiter.api.BeforeEach
        void setUp() throws IOException {
            out = Files.createDirectories(dir.resolve("run"));
            Files.writeString(out.resolve("K1.adoc"), "converted");
            manifest = Manifest.of(SOURCE).withStage("convert", stage(Manifest.Status.OK, inputs,
                    Map.of("K1.adoc", IngestHash.sha256Hex("converted")), TOOL, params));
        }

        @Test
        void skipsWhenNothingChanged() throws IOException {
            assertThat(manifest.canSkip("convert", inputs, TOOL, params, out)).isTrue();
        }

        @Test
        void runsWhenTheStageIsUnknownOrDidNotSucceed() throws IOException {
            assertThat(manifest.canSkip("split", inputs, TOOL, params, out)).isFalse();
            Manifest failed = manifest.withStage("convert", stage(Manifest.Status.FAILED, inputs,
                    Map.of("K1.adoc", IngestHash.sha256Hex("converted")), TOOL, params));
            assertThat(failed.canSkip("convert", inputs, TOOL, params, out)).isFalse();
        }

        @Test
        void runsWhenAnInputTheToolOrAParameterChanged() throws IOException {
            assertThat(manifest.canSkip("convert", Map.of("K1.pdf", "99"), TOOL, params, out)).isFalse();
            ToolIdentity newer = new ToolIdentity("/bin/ike-convert", "aa", "/c.py", "bb", "2.0", "cc");
            assertThat(manifest.canSkip("convert", inputs, newer, params, out)).isFalse();
            assertThat(manifest.canSkip("convert", inputs, null, params, out)).isFalse();
            assertThat(manifest.canSkip("convert", inputs, TOOL, Map.of("limit", "30"), out)).isFalse();
        }

        @Test
        void runsWhenAnOutputIsMissingOrWasEdited() throws IOException {
            Files.writeString(out.resolve("K1.adoc"), "edited by hand");
            assertThat(manifest.canSkip("convert", inputs, TOOL, params, out)).isFalse();
            Files.delete(out.resolve("K1.adoc"));
            assertThat(manifest.canSkip("convert", inputs, TOOL, params, out)).isFalse();
        }
    }
}

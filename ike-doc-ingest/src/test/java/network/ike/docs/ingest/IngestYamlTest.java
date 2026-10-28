package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestYamlTest {

    private static IngestReport sampleReport() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("zeta", "last");
        details.put("alpha", Map.of("pages", 24, "text-layer", true, "k-number", "K230440"));
        return new IngestReport("ingest-detect",
                List.of(new IngestReport.Source("K230440.pdf", "aa"), new IngestReport.Source("A.pdf", "bb")),
                details,
                List.of(Finding.warning("derived-url", "K230440.pdf", "citation URL derived, not verified"),
                        Finding.error("signal-missing", "K230440.pdf", "no title")),
                List.of(new IngestReport.PlanEntry("topics/dex/b.adoc", "cc"),
                        new IngestReport.PlanEntry("topics/dex/a.adoc", "dd")));
    }

    @Test
    void report_roundTripsAndRedumpsToIdenticalBytes() throws IOException {
        String first = IngestYaml.reportToYaml(sampleReport());
        IngestReport back = IngestYaml.reportFromYaml(first);

        assertThat(IngestYaml.reportToYaml(back)).isEqualTo(first);
        assertThat(back.goal()).isEqualTo("ingest-detect");
        assertThat(back.findings()).hasSize(2);
        assertThat(back.hasErrors()).isTrue();
    }

    @Test
    void report_isIndependentOfInputOrder() {
        IngestReport a = sampleReport();
        IngestReport b = new IngestReport(a.goal(), List.of(a.sources().get(1), a.sources().get(0)),
                a.details(), List.of(a.findings().get(1), a.findings().get(0)),
                List.of(a.plan().get(1), a.plan().get(0)));

        assertThat(IngestYaml.reportToYaml(b)).isEqualTo(IngestYaml.reportToYaml(a));
    }

    @Test
    void report_writesSortedDetailKeysAndStableOutline() {
        String yaml = IngestYaml.reportToYaml(sampleReport());

        assertThat(yaml.indexOf("alpha:")).isLessThan(yaml.indexOf("zeta:"));
        assertThat(yaml).startsWith("schema-version: 1\ngoal: ingest-detect\n");
        assertThat(yaml).doesNotContain("\r").endsWith("\n");
    }

    @Test
    void needsAi_multiLineContextIsALiteralBlockAndRoundTrips() throws IOException {
        String context = "page image: pages/page-001.png\nsha256: abc\n  indented line";
        NeedsAiFile file = new NeedsAiFile("Write plain text per page.\nTables between markers.",
                List.of(NeedsAi.of("transcribe-page", "page-001", true, context, "plain text")));

        String yaml = IngestYaml.needsAiToYaml(file);
        NeedsAiFile back = IngestYaml.needsAiFromYaml(yaml);

        assertThat(yaml).contains("context: |");
        assertThat(back.items().get(0).context()).isEqualTo(context);
        assertThat(back.instructions()).isEqualTo(file.instructions());
        assertThat(IngestYaml.needsAiToYaml(back)).isEqualTo(yaml);
    }

    @Test
    void unicodeSurvivesARoundTrip() throws IOException {
        IngestReport report = new IngestReport("g", List.of(), Map.of("note", "µ ≥ 21 CFR §862 — ®"),
                List.of(), List.of());

        IngestReport back = IngestYaml.reportFromYaml(IngestYaml.reportToYaml(report));

        assertThat(back.details()).containsEntry("note", "µ ≥ 21 CFR §862 — ®");
    }

    @Test
    void load_refusesDuplicateKeysAliasesAndGarbage() {
        assertThatThrownBy(() -> IngestYaml.load("a: 1\na: 2\n")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> IngestYaml.load("a: &x [1]\nb: *x\n")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> IngestYaml.load("a: [unclosed")).isInstanceOf(IOException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void reading_namesTheProblemForABadFile() {
        assertThatThrownBy(() -> IngestYaml.reportFromYaml("schema-version: 2\ngoal: g\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("schema-version");
        assertThatThrownBy(() -> IngestYaml.reportFromYaml("- just\n- a list\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("mapping");
        assertThatThrownBy(() -> IngestYaml.reportFromYaml("schema-version: 1\ngoal: g\nfindings:\n"
                + "  - severity: LOUD\n    check: c\n    component: x\n    message: m\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("invalid report");
        assertThatThrownBy(() -> IngestYaml.needsAiFromYaml("schema-version: 1\ninstructions: i\nitems:\n"
                + "  - id: a-111111111111\n    kind: a\n    target: t\n    blocking: maybe\n"
                + "    context: c\n    expect: e\n"))
                .isInstanceOf(IOException.class).hasMessageContaining("blocking");
    }

    @Test
    void dump_refusesAValueItCannotRepresentDeterministically() {
        assertThatThrownBy(() -> IngestYaml.dump(Map.of("p", Path.of("x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test
    void writeAtomic_writesUtf8WithLfAndLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("deep/er/report.yaml");

        IngestYaml.writeAtomic(target, "a: µ\r\nb: 2\r\n");
        IngestYaml.writeAtomic(target, "a: µ\r\nb: 3\r\n");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("a: µ\nb: 3\n");
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("report.yaml");
        }
    }
}

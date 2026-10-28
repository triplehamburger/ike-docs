package network.ike.docs.ingest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs detection on real FDA and manufacturer PDFs. Skipped unless a
/// directory holding them is named by the `ike.ingest.samples` system
/// property or the `IKE_INGEST_SAMPLES` environment variable, so no FDA file
/// is committed.
class RealSamplesTest {

    @TempDir
    Path out;

    private static Path samples() {
        String dir = System.getProperty("ike.ingest.samples", System.getenv("IKE_INGEST_SAMPLES"));
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "no samples directory configured");
        Path path = Path.of(dir);
        Assumptions.assumeTrue(Files.isDirectory(path), "samples directory does not exist: " + path);
        return path;
    }

    private IngestDetect.Result detect(String prefix) throws Exception {
        Path dir = samples();
        try (var files = Files.list(dir)) {
            Path pdf = files.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".pdf")).findFirst().orElse(null);
            Assumptions.assumeTrue(pdf != null, "sample not present: " + prefix);
            return IngestDetect.run(pdf, new IngestDetect.Options(out.resolve("pages"), false, null), null);
        }
    }

    private static void assertDex(IngestDetect.Result r, String layout, String template, String k, int sections) {
        assertThat(r.report().details()).containsEntry("kind", "DEX_510K").containsEntry("layout", layout)
                .containsEntry("template", template).containsEntry("k-number", k);
        assertThat((List<?>) r.report().details().get("sections")).hasSize(sections);
        assertThat(r.report().findings()).as("findings: %s", r.report().findings()).noneMatch(Finding::isError);
        assertThat(r.needsAi()).isEmpty();
    }

    @Test
    void k031739IsALegacyDeviceAndInstrumentSummary() throws Exception {
        assertDex(detect("K031739"), "LEGACY_LETTERED", "DEVICE_AND_INSTRUMENT", "K031739", 16);
    }

    @Test
    void k162200IsALegacyAssayOnlySummaryWithALowerCaseKNumber() throws Exception {
        assertDex(detect("K162200"), "LEGACY_LETTERED", "ASSAY_ONLY", "K162200", 15);
    }

    @Test
    void k211079IsACurrentLayoutSummary() throws Exception {
        assertDex(detect("K211079"), "CURRENT_ROMAN", "ASSAY_AND_INSTRUMENT", "K211079", 9);
    }

    @Test
    void k230440IsACurrentLayoutSummary() throws Exception {
        assertDex(detect("K230440"), "CURRENT_ROMAN", "ASSAY_AND_INSTRUMENT", "K230440", 9);
    }

    @Test
    void aClearanceLetterIsNotADecisionSummary() throws Exception {
        IngestDetect.Result r = detect("K251543");
        assertThat(r.report().details()).containsEntry("kind", "NOT_DEX").containsEntry("guess", "fda-clearance-letter");
        assertThat(r.report().hasErrors()).isFalse();
    }

    @Test
    void aGudidRecordIsNotADecisionSummary() throws Exception {
        IngestDetect.Result r = detect("AccessGUDID");
        assertThat(r.report().details()).containsEntry("kind", "NOT_DEX").containsEntry("guess", "gudid-record");
    }

    @Test
    void anInstructionsForUseIsNotADecisionSummary() throws Exception {
        IngestDetect.Result r = detect("Xpert Xpress");
        assertThat(r.report().details()).containsEntry("kind", "NOT_DEX").containsEntry("guess", "manufacturer-ifu");
    }
}

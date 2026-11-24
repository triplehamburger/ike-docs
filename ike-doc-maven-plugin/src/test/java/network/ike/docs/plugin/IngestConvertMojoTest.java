package network.ike.docs.plugin;

import network.ike.docs.ingest.ToolRunner;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `idoc:ingest-convert` and the process runner it uses. The converter and the
/// fidelity check are stood in for by one small Java program run as a
/// single-file source, so the tests need no Python.
class IngestConvertMojoTest {

    private static final String FAKE_TOOL = """
            import java.nio.file.*;

            public class FakeTool {
                public static void main(String[] a) throws Exception {
                    switch (a[0]) {
                        case "convert" -> {
                            for (int i = 1; i < a.length - 1; i++) {
                                if (a[i].equals("-o")) {
                                    Files.writeString(Path.of(a[i + 1]), "= Title\\n\\nBody text.\\n");
                                }
                            }
                        }
                        case "empty" -> { }
                        case "fail" -> { System.out.println("boom: no tags"); System.exit(3); }
                        case "pass" -> System.out.println("fidelity ok");
                        case "mismatch" -> { System.out.println("content 98.9% is below the gate"); System.exit(1); }
                        case "slow" -> Thread.sleep(60000);
                        default -> System.exit(9);
                    }
                }
            }
            """;

    @TempDir
    Path dir;

    private Path pdf;
    private String tool;

    private static Log silentLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    @BeforeEach
    void setUp() throws IOException {
        pdf = dir.resolve("K999999.pdf");
        Files.write(pdf, new byte[] {1, 2, 3});
        Path source = dir.resolve("FakeTool.java");
        Files.writeString(source, FAKE_TOOL, StandardCharsets.UTF_8);
        tool = "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\" \"" + source + "\"";
    }

    private IngestConvertMojo mojo(String convertMode, String fidelityMode) {
        IngestConvertMojo mojo = new IngestConvertMojo();
        mojo.log = silentLog();
        mojo.source = pdf.toFile();
        mojo.outputDir = dir.resolve("out").toFile();
        mojo.command = tool + " " + convertMode;
        mojo.fidelityCommand = tool + " " + fidelityMode;
        mojo.fidelity = true;
        mojo.strictFidelity = false;
        mojo.timeoutMinutes = 5;
        return mojo;
    }

    private String report() throws IOException {
        return Files.readString(dir.resolve("out/convert.yaml"), StandardCharsets.UTF_8);
    }

    @Test
    void aConvertedPdfWritesTheAsciiDocAndAPassingReport() throws Exception {
        mojo("convert", "pass").execute();

        assertThat(dir.resolve("out/K999999.adoc")).content().contains("= Title");
        assertThat(report()).contains("goal: ingest-convert").contains("fidelity: passed").contains("adoc-sha256")
                .doesNotContain("severity: ERROR");
    }

    @Test
    void aFailedFidelityCheckOnlyWarnsAndKeepsTheAsciiDoc() throws Exception {
        mojo("convert", "mismatch").execute();

        assertThat(dir.resolve("out/K999999.adoc")).exists();
        assertThat(report()).contains("check: fidelity-failed").contains("severity: WARNING")
                .contains("98.9% is below the gate").contains("fidelity: failed");
    }

    @Test
    void strictFidelityMakesAFailedCheckAnErrorButKeepsTheAsciiDoc() throws Exception {
        IngestConvertMojo mojo = mojo("convert", "mismatch");
        mojo.strictFidelity = true;

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class).hasMessageContaining("convert.yaml");

        assertThat(dir.resolve("out/K999999.adoc")).exists();
        assertThat(report()).contains("check: fidelity-failed").contains("severity: ERROR");
    }

    @Test
    void fidelityCanBeTurnedOffAndTheCheckIsThenNotRun() throws Exception {
        IngestConvertMojo mojo = mojo("convert", "mismatch");
        mojo.fidelity = false;

        mojo.execute();

        assertThat(report()).contains("fidelity: skipped").doesNotContain("fidelity-failed");
    }

    @Test
    void aConverterThatExitsNonZeroFailsTheGoalAndSkipsTheFidelityCheck() throws Exception {
        assertThatThrownBy(() -> mojo("fail", "pass").execute()).isInstanceOf(MojoException.class);

        assertThat(report()).contains("check: convert-failed").contains("exited with code 3")
                .contains("boom: no tags").doesNotContain("fidelity");
        assertThat(dir.resolve("out/K999999.adoc")).doesNotExist();
    }

    @Test
    void aConverterThatWritesNothingIsAnError() throws Exception {
        assertThatThrownBy(() -> mojo("empty", "pass").execute()).isInstanceOf(MojoException.class);

        assertThat(report()).contains("check: no-output");
    }

    @Test
    void aMissingConverterIsAnErrorThatNamesTheSetting() throws Exception {
        IngestConvertMojo mojo = mojo("convert", "pass");
        mojo.command = "no-such-converter-xyz";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);

        assertThat(report()).contains("check: tool-not-found").contains("ike.convert.command");
    }

    @Test
    void aMissingPdfIsAnErrorAndNothingIsRun() throws Exception {
        IngestConvertMojo mojo = mojo("convert", "pass");
        mojo.source = dir.resolve("absent.pdf").toFile();

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);

        assertThat(report()).contains("check: source-missing");
        assertThat(dir.resolve("out/absent.adoc")).doesNotExist();
    }

    @Test
    void anExplicitAsciiDocPathIsUsedAndTheReportSitsBesideIt() throws Exception {
        IngestConvertMojo mojo = mojo("convert", "pass");
        mojo.adoc = dir.resolve("elsewhere/K.adoc").toFile();

        mojo.execute();

        assertThat(dir.resolve("elsewhere/K.adoc")).exists();
        assertThat(dir.resolve("elsewhere/convert.yaml")).exists();
    }

    @Test
    void theRunnerKillsAProcessThatRunsTooLong() throws Exception {
        List<String> argv = ToolRunner.split(tool + " slow");

        ToolRunner.Result result = ToolRunner.run(argv, null, Map.of(), Duration.ofMillis(1500), line -> { });

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void theRunnerCapturesOutputAndTheExitCode() throws Exception {
        ToolRunner.Result result = ToolRunner.run(ToolRunner.split(tool + " fail"), null, Map.of(),
                Duration.ofMinutes(1), line -> { });

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).contains("boom: no tags");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void aCommandSettingSplitsOnSpacesAndKeepsQuotedPartsWhole() {
        assertThat(ToolRunner.split("python utilities/convert.py")).containsExactly("python", "utilities/convert.py");
        assertThat(ToolRunner.split("\"C:\\Program Files\\Python\\python.exe\" run.py"))
                .containsExactly("C:\\Program Files\\Python\\python.exe", "run.py");
        assertThat(ToolRunner.split("  ")).isEmpty();
        assertThat(ToolRunner.split(null)).isEmpty();
    }
}

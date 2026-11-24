package network.ike.docs.ingest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRunnerTest {

    @TempDir
    Path dir;

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private Path source(String name, String body) throws IOException {
        Path file = dir.resolve(name + ".java");
        Files.writeString(file, "public class " + name + " { public static void main(String[] a) throws Exception { "
                + body + " } }");
        return file;
    }

    @Test
    void split_separatesOnWhitespaceAndKeepsQuotedParts() {
        assertThat(ToolRunner.split("python \"C:\\a b\\c.py\" x"))
                .containsExactly("python", "C:\\a b\\c.py", "x");
        assertThat(ToolRunner.split("  one   two ")).containsExactly("one", "two");
    }

    @Test
    void split_blankOrNullGivesNoParts() {
        assertThat(ToolRunner.split(null)).isEmpty();
        assertThat(ToolRunner.split("   ")).isEmpty();
    }

    @Test
    void run_capturesOutputAndExitCode() throws Exception {
        Path hello = source("Hello", "System.out.println(\"hello \" + a[0]); System.exit(3);");
        List<String> lines = new ArrayList<>();
        ToolRunner.Result result = ToolRunner.run(List.of(java(), hello.toString(), "world"), dir, Map.of(),
                Duration.ofSeconds(60), lines::add);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).contains("hello world");
        assertThat(lines).contains("hello world");
    }

    @Test
    void run_killsAProcessThatOutlivesTheTimeout() throws Exception {
        Path sleep = source("Sleep", "Thread.sleep(120000);");
        ToolRunner.Result result = ToolRunner.run(List.of(java(), sleep.toString()), dir, Map.of(),
                Duration.ofSeconds(4), line -> { });
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void run_keepsOnlyTheNewestOutputUpToTheCap() throws Exception {
        Path many = source("Many", "for (int i = 0; i < 200; i++) System.out.println(\"line-\" + i);");
        ToolRunner.Result result = ToolRunner.run(List.of(java(), many.toString()), dir, Map.of(),
                Duration.ofSeconds(60), 60, ToolRunner.INHERIT, line -> { });
        assertThat(result.output().length()).isLessThanOrEqualTo(60);
        assertThat(result.output()).contains("line-199");
        assertThat(result.output()).doesNotContain("line-0\n");
    }

    @Test
    void run_passesTheInheritedEnvironmentThroughTheHookThenAddsExplicitVariables() throws Exception {
        Path env = source("Env", "System.out.println(System.getenv(\"IKE_HOOK\") + \"/\" + System.getenv(\"IKE_ENV\"));");
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        ToolRunner.Result result = ToolRunner.run(List.of(java(), env.toString()), dir, Map.of("IKE_ENV", "two"),
                Duration.ofSeconds(60), ToolRunner.OUTPUT_CAP, inherited -> {
                    seen.set(new LinkedHashMap<>(inherited));
                    Map<String, String> chosen = new LinkedHashMap<>(inherited);
                    chosen.put("IKE_HOOK", "one");
                    return chosen;
                }, line -> { });
        assertThat(seen.get()).isNotEmpty();
        assertThat(result.output()).contains("one/two");
    }

    @Test
    void run_givesTheHookAnEnvironmentThatIgnoresVariableNameCaseOnWindows() throws Exception {
        Assumptions.assumeTrue(ToolRunner.isWindows(), "variable names differ in case on Windows only");
        Path hello = source("Hi", "System.out.println(\"hi\");");
        AtomicReference<Boolean> found = new AtomicReference<>();
        ToolRunner.run(List.of(java(), hello.toString()), dir, Map.of(), Duration.ofSeconds(60),
                ToolRunner.OUTPUT_CAP, inherited -> {
                    found.set(inherited.containsKey("PATH") && inherited.containsKey("path"));
                    return inherited;
                }, line -> { });
        assertThat(found.get()).isTrue();
    }

    @Test
    void run_rejectsAnEmptyCommandAndANullPart() {
        assertThatThrownBy(() -> ToolRunner.run(List.of(), null, Map.of(), Duration.ofSeconds(1), l -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> withNull = new ArrayList<>();
        withNull.add("x");
        withNull.add(null);
        assertThatThrownBy(() -> ToolRunner.run(withNull, null, Map.of(), Duration.ofSeconds(1), l -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void run_reportsAProgramThatCannotBeStarted() {
        assertThatThrownBy(() -> ToolRunner.run(List.of(dir.resolve("no-such-program").toString()), null,
                Map.of(), Duration.ofSeconds(5), l -> { })).isInstanceOf(IOException.class);
    }
}

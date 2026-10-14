package network.ike.docs.plugin.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command-line entry point: its options resolve against {@code --base}
 * as the goal's parameters resolve against the module, and a run writes the
 * registry, then adds a new file to it without rescanning.
 */
class TopicRegistryMainTest {

    private static String topic(String id) {
        return """
                :topic-id: %s
                :topic-type: reference
                :topic-status: review
                :topic-keywords: one, two
                :topic-summary: A topic.

                [[%s]]
                = Title of %s

                Body text.
                """.formatted(id, id, id);
    }

    @Test
    void parse_resolvesDefaultsAndOptionsAgainstBase(@TempDir Path base) {
        TopicRegistryMain.Options defaults = TopicRegistryMain.parse(
                new String[] {"--base", base.toString()});
        assertThat(defaults.roots()).containsExactly(base.resolve("src/docs/asciidoc"));
        assertThat(defaults.output()).isEqualTo(base.resolve("target/topic-registry.yaml"));
        assertThat(defaults.add()).isEmpty();
        assertThat(defaults.rootFile()).isNull();
        assertThat(defaults.maxFiles()).isEqualTo(TopicRegistryMain.DEFAULT_MAX_FILES);

        TopicRegistryMain.Options options = TopicRegistryMain.parse(new String[] {
                "--base", base.toString(), "--roots", "a, b", "--output", "out.yaml",
                "--add", "a/x.adoc,a/y.adoc", "--max-files", "7"});
        assertThat(options.roots()).containsExactly(base.resolve("a"), base.resolve("b"));
        assertThat(options.output()).isEqualTo(base.resolve("out.yaml"));
        assertThat(options.add()).containsExactly(base.resolve("a/x.adoc"), base.resolve("a/y.adoc"));
        assertThat(options.maxFiles()).isEqualTo(7);
    }

    @Test
    void parse_rejectsUnknownOptionsAndMissingValues() {
        assertThatThrownBy(() -> TopicRegistryMain.parse(new String[] {"--bogus", "x"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--bogus");
        assertThatThrownBy(() -> TopicRegistryMain.parse(new String[] {"--add"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--add");
    }

    @Test
    void run_writesTheRegistry_thenAddsWithoutRescanning(@TempDir Path base) throws IOException {
        Path domain = base.resolve("src/docs/asciidoc/topics/arch");
        Files.createDirectories(domain);
        Files.writeString(domain.resolve("a.adoc"), topic("arch-a"));
        List<String> info = new ArrayList<>();
        List<String> warn = new ArrayList<>();

        TopicRegistryMain.run(TopicRegistryMain.parse(new String[] {"--base", base.toString()}),
                info::add, warn::add);

        Path registry = base.resolve("target/topic-registry.yaml");
        assertThat(Files.readString(registry)).contains("arch-a").doesNotContain("arch-b");
        assertThat(info.getLast()).startsWith("1 topic, 0 other files, 0 findings");

        Files.writeString(domain.resolve("b.adoc"), topic("arch-b"));
        TopicRegistryMain.run(TopicRegistryMain.parse(new String[] {"--base", base.toString(),
                "--add", "src/docs/asciidoc/topics/arch/b.adoc"}), info::add, warn::add);

        assertThat(Files.readString(registry)).contains("arch-a", "arch-b");
        assertThat(info.getLast()).startsWith("added 1 file to target/topic-registry.yaml");
        assertThat(warn).isEmpty();
    }

    @Test
    void run_rejectsRootFileWithAdd(@TempDir Path base) {
        TopicRegistryMain.Options options = TopicRegistryMain.parse(new String[] {
                "--base", base.toString(), "--add", "x.adoc", "--root-file", "index.adoc"});
        assertThatThrownBy(() -> TopicRegistryMain.run(options, s -> { }, s -> { }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be combined");
    }
}

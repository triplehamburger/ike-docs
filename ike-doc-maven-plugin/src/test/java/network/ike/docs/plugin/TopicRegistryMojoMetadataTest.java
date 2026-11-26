package network.ike.docs.plugin;

import network.ike.docs.plugin.registry.TopicRegistry;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `ike.ingest.requireMetadata` on `idoc:topic-registry`: a topic whose header
/// says its keywords and summary are placeholders is only a finding, until
/// the switch makes it an error.
class TopicRegistryMojoMetadataTest {

    @TempDir
    Path dir;

    private Path registry(String... markedIds) throws IOException {
        Path root = Files.createDirectories(dir.resolve("topics/dex"));
        for (String id : List.of("dex-a", "dex-b", "dex-c", "dex-d", "dex-e", "dex-f", "dex-g")) {
            String marker = List.of(markedIds).contains(id) ? ":topic-metadata: placeholder\n" : "";
            Files.writeString(root.resolve(id + ".adoc"), ":topic-id: " + id + "\n:topic-type: reference\n"
                    + ":topic-status: review\n:topic-keywords: a\n" + marker + "\n[[" + id + "]]\n= T\n");
        }
        Path file = dir.resolve("registry.yaml");
        Files.writeString(file, TopicRegistry.yaml(TopicRegistry.model(
                List.of(TopicRegistry.scan(dir.resolve("topics"), 100)), dir, Instant.now())));
        return file;
    }

    @Test
    void withoutTheSwitchAPlaceholderIsNotAnError() throws Exception {
        TopicRegistryMojo mojo = new TopicRegistryMojo();

        assertThatCode(() -> mojo.requireMetadataFilled(registry("dex-a"))).doesNotThrowAnyException();
    }

    @Test
    void withTheSwitchAPlaceholderTopicFailsAndNamesIt() throws Exception {
        TopicRegistryMojo mojo = new TopicRegistryMojo();
        mojo.requireMetadata = true;
        Path file = registry("dex-b");

        assertThatThrownBy(() -> mojo.requireMetadataFilled(file)).isInstanceOf(MojoException.class)
                .hasMessageContaining("1 topic(s)").hasMessageContaining("dex-b")
                .hasMessageContaining("ike.ingest.requireMetadata");
        assertThat(file).exists();
    }

    @Test
    void theMessageShowsFiveIdsAndCountsTheRest() throws Exception {
        TopicRegistryMojo mojo = new TopicRegistryMojo();
        mojo.requireMetadata = true;
        Path file = registry("dex-a", "dex-b", "dex-c", "dex-d", "dex-e", "dex-f", "dex-g");

        assertThatThrownBy(() -> mojo.requireMetadataFilled(file))
                .hasMessageContaining("7 topic(s)").hasMessageContaining("dex-e and 2 more");
    }

    @Test
    void withTheSwitchAndNoPlaceholderTheRunPasses() throws Exception {
        TopicRegistryMojo mojo = new TopicRegistryMojo();
        mojo.requireMetadata = true;

        assertThatCode(() -> mojo.requireMetadataFilled(registry())).doesNotThrowAnyException();
    }
}

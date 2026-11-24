package network.ike.docs.ingest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathJailTest {

    @TempDir
    Path dir;

    @Test
    void permissive_allowsEveryPathWithoutFindings() throws IOException {
        List<Finding> findings = new ArrayList<>();
        Path resolved = PathJail.permissive().resolve(Path.of("a", "..", "b"), findings);
        assertThat(resolved).isEqualTo(Path.of("b").toAbsolutePath().normalize());
        assertThat(findings).isEmpty();
    }

    @Test
    void recording_resolvesARelativePathAgainstTheRootAndAcceptsAPathInside() throws IOException {
        Files.createDirectories(dir.resolve("out"));
        List<Finding> findings = new ArrayList<>();
        Path resolved = PathJail.recording(dir).resolve(Path.of("out", "new", "file.adoc"), findings);
        assertThat(resolved).isEqualTo(dir.toRealPath().resolve("out").resolve("new").resolve("file.adoc"));
        assertThat(findings).isEmpty();
    }

    @Test
    void recording_notesAPathThatEscapesTheRootAndStillReturnsIt() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        List<Finding> findings = new ArrayList<>();
        Path resolved = PathJail.recording(root).resolve(Path.of("..", "elsewhere.txt"), findings);
        assertThat(resolved).isEqualTo(dir.toRealPath().resolve("elsewhere.txt"));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("path-outside-root");
            assertThat(f.isError()).isFalse();
        });
    }

    @Test
    void recording_notesAnAbsolutePathOutsideTheRoot() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path other = Files.createDirectories(dir.resolve("other"));
        List<Finding> findings = new ArrayList<>();
        PathJail.recording(root).resolve(other.resolve("x.txt"), findings);
        assertThat(findings).hasSize(1);
    }

    @Test
    void enforcing_refusesAPathThatEscapesTheRoot() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        PathJail jail = PathJail.enforcing(root);
        assertThatThrownBy(() -> jail.resolve(Path.of("..", "elsewhere.txt"), new ArrayList<>()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("outside the allowed root");
        assertThat(jail.resolve(Path.of("fine.txt"), new ArrayList<>()))
                .isEqualTo(root.toRealPath().resolve("fine.txt"));
    }

    @Test
    void recording_followsASymbolicLinkOutOfTheRoot() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links are not available here: " + e.getMessage());
        }
        List<Finding> findings = new ArrayList<>();
        PathJail.recording(root).resolve(Path.of("link", "file.txt"), findings);
        assertThat(findings).singleElement().satisfies(f -> assertThat(f.check()).isEqualTo("path-outside-root"));
    }
}

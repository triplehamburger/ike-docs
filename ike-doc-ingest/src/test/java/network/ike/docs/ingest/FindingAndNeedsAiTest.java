package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingAndNeedsAiTest {

    @Test
    void finding_flattensWhitespaceToOneLine() {
        Finding f = Finding.error("check-x", "file.adoc", "line one\n  line two\t end");
        assertThat(f.message()).isEqualTo("line one line two end");
        assertThat(f.isError()).isTrue();
    }

    @Test
    void finding_warningAndInfoDoNotFailAGoal() {
        assertThat(Finding.warning("c", "x", "m").isError()).isFalse();
        assertThat(Finding.info("c", "x", "m").isError()).isFalse();
    }

    @Test
    void finding_rejectsBlankFields() {
        assertThatThrownBy(() -> Finding.error(" ", "x", "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.error("c", "", "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Finding.error("c", "x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(null, "c", "x", "m")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void needsAi_idIsStableAndTiedToItsInput() {
        NeedsAi a = NeedsAi.of("transcribe-page", "page-001", true, "image sha 1", "plain text");
        NeedsAi same = NeedsAi.of("transcribe-page", "page-001", true, "image sha 1", "plain text");
        NeedsAi changed = NeedsAi.of("transcribe-page", "page-001", true, "image sha 2", "plain text");

        assertThat(a.id()).matches("transcribe-page-[0-9a-f]{12}");
        assertThat(a.id()).isEqualTo(same.id());
        assertThat(changed.id()).isNotEqualTo(a.id());
    }

    @Test
    void needsAi_rejectsBadFields() {
        assertThatThrownBy(() -> NeedsAi.of("Bad Kind", "t", true, "c", "e"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kebab-case");
        assertThatThrownBy(() -> NeedsAi.of("kind", " ", true, "c", "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NeedsAi.of("kind", "t", true, "", "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NeedsAi.of("kind", "t", true, "c", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsAiFile_sortsItemsByTargetSoPagesComeOutInPageOrder() {
        NeedsAi p2 = NeedsAi.of("transcribe-page", "page-002", true, "c2", "e");
        NeedsAi p10 = NeedsAi.of("transcribe-page", "page-010", true, "c10", "e");
        NeedsAi p1 = NeedsAi.of("transcribe-page", "page-001", true, "c1", "e");

        assertThat(new NeedsAiFile("answer", List.of(p10, p1, p2)).items()).containsExactly(p1, p2, p10);
    }

    @Test
    void needsAiFile_sortsItemsById() {
        NeedsAi b = NeedsAi.of("b-kind", "t", true, "c", "e");
        NeedsAi a = NeedsAi.of("a-kind", "t", true, "c", "e");
        assertThat(new NeedsAiFile("answer each", List.of(b, a)).items()).containsExactly(a, b);
    }

    @Test
    void needsAiFile_rejectsDuplicateIdsAndBlankInstructions() {
        NeedsAi a = NeedsAi.of("a-kind", "t", true, "c", "e");
        assertThatThrownBy(() -> new NeedsAiFile("x", List.of(a, a)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new NeedsAiFile(" ", List.of(a))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hash_matchesKnownVectorForStringBytesAndFile(@TempDir Path dir) throws IOException {
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Path file = dir.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(IngestHash.sha256Hex("abc")).isEqualTo(abc);
        assertThat(IngestHash.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(abc);
        assertThat(IngestHash.sha256Hex(file)).isEqualTo(abc);
    }
}

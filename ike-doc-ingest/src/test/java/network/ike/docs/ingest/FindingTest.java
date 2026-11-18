package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingTest {

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
    void hash_matchesKnownVectorForStringBytesAndFile(@TempDir Path dir) throws IOException {
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Path file = dir.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(IngestHash.sha256Hex("abc")).isEqualTo(abc);
        assertThat(IngestHash.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(abc);
        assertThat(IngestHash.sha256Hex(file)).isEqualTo(abc);
    }
}

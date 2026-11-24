package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolIdentityTest {

    @TempDir
    Path dir;

    @Test
    void resolve_hashesTheProgramAndTheFirstExistingFileArgument() throws IOException {
        Path program = Files.writeString(dir.resolve("tool.bin"), "program bytes");
        Path script = Files.writeString(dir.resolve("convert.py"), "print('x')");
        ToolIdentity identity = ToolIdentity.resolve(
                List.of(program.toString(), "--flag", "convert.py", "other.txt"), dir, null, null);
        assertThat(identity.program()).isEqualTo(program.toAbsolutePath().normalize().toString());
        assertThat(identity.programSha256()).isEqualTo(IngestHash.sha256Hex("program bytes"));
        assertThat(identity.script()).isEqualTo(script.toAbsolutePath().normalize().toString());
        assertThat(identity.scriptSha256()).isEqualTo(IngestHash.sha256Hex("print('x')"));
        assertThat(identity.version()).isNull();
        assertThat(identity.lockfileSha256()).isNull();
    }

    @Test
    void resolve_recordsADeclaredVersionAndTheLockfileHash() throws IOException {
        Path program = Files.writeString(dir.resolve("tool.bin"), "p");
        Path lock = Files.writeString(dir.resolve("requirements.lock"), "pdfplumber==1.0");
        ToolIdentity identity = ToolIdentity.resolve(List.of(program.toString()), dir, " 1.2.3 ", lock);
        assertThat(identity.version()).isEqualTo("1.2.3");
        assertThat(identity.lockfileSha256()).isEqualTo(IngestHash.sha256Hex("pdfplumber==1.0"));
        assertThat(identity.script()).isNull();
    }

    @Test
    void resolve_keepsTheGivenNameWhenTheProgramIsNotAFile() throws IOException {
        ToolIdentity identity = ToolIdentity.resolve(List.of("no-such-program-xyz"), dir, "", null);
        assertThat(identity.program()).isEqualTo("no-such-program-xyz");
        assertThat(identity.programSha256()).isNull();
        assertThat(identity.version()).isNull();
    }

    @Test
    void resolve_rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> ToolIdentity.resolve(List.of(), dir, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsABlankProgram() {
        assertThatThrownBy(() -> new ToolIdentity(" ", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toMap_leavesOutAbsentPartsAndRoundTrips() {
        ToolIdentity full = new ToolIdentity("/bin/tool", "aa", "/s.py", "bb", "1.0", "cc");
        ToolIdentity bare = new ToolIdentity("tool", null, null, null, null, null);
        assertThat(bare.toMap()).containsOnlyKeys("program");
        assertThat(ToolIdentity.fromMap(full.toMap())).isEqualTo(full);
        assertThat(ToolIdentity.fromMap(bare.toMap())).isEqualTo(bare);
    }

    @Test
    void tier0_allowsEveryToolAndNotesOneThatWasNotResolved() {
        ToolTrustPolicy policy = ToolTrustPolicy.tier0();
        ToolTrustPolicy.Decision resolved = policy.decide(
                new ToolIdentity("/bin/tool", "aa", null, null, null, null));
        assertThat(resolved.allowed()).isTrue();
        assertThat(resolved.findings()).isEmpty();

        ToolTrustPolicy.Decision unresolved = policy.decide(
                new ToolIdentity("tool", null, null, null, null, null));
        assertThat(unresolved.allowed()).isTrue();
        assertThat(unresolved.findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("tool-unresolved");
            assertThat(f.isError()).isFalse();
        });
    }
}

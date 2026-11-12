package network.ike.docs.plugin;

import network.ike.docs.ingest.IngestFragments;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The existing-topics check of `idoc:ingest-fragments`: a fragment id that an
/// existing topic already declares is caught before the fragments are placed.
class IngestFragmentsMojoTest {

    private static final String RECORD = """
            = DeXRecord_K999999

            == I Background Information:

            === A 510(k) Number

            K999999

            === B Applicant

            Acme Diagnostics

            == II Conclusion:

            The device is substantially equivalent.
            """;

    @TempDir
    Path dir;

    private Path source;
    private Path library;
    private IngestFragments.Fragment first;

    private static Log silentLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    @BeforeEach
    void setUp() throws IOException {
        source = dir.resolve("K999999.adoc");
        Files.writeString(source, RECORD, StandardCharsets.UTF_8);
        library = dir.resolve("library");
        Files.createDirectories(library.resolve("topics/dex"));
        first = IngestFragments.run(source, IngestFragments.Options.defaults()).fragments().get(0);
    }

    private IngestFragmentsMojo mojo(String existingRoots) {
        IngestFragmentsMojo mojo = new IngestFragmentsMojo();
        mojo.log = silentLog();
        mojo.source = source.toFile();
        mojo.outputDir = dir.resolve("out").toFile();
        mojo.sizeLimit = 5000;
        mojo.split = true;
        mojo.existingRoots = existingRoots;
        return mojo;
    }

    private void writeTopic(String relative, String id) throws IOException {
        Path file = library.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ":topic-id: " + id + "\n:topic-type: reference\n:topic-status: review\n"
                + ":topic-keywords: x\n\n[[" + id + "]]\n= Existing\n\nText.\n", StandardCharsets.UTF_8);
    }

    @Test
    void anIdDeclaredByAnotherFileIsAnErrorAndNothingIsWritten() throws Exception {
        writeTopic("topics/other/someone-elses.adoc", first.id());

        assertThatThrownBy(() -> mojo(library.toString()).execute())
                .isInstanceOf(MojoException.class).hasMessageContaining("error(s)");

        assertThat(dir.resolve("out/fragments.yaml")).content()
                .contains("check: duplicate-id").contains(first.id()).contains("someone-elses.adoc");
        assertThat(dir.resolve("out/topics")).doesNotExist();
    }

    @Test
    void anIdDeclaredByTheSameFileNameReplacesItAndIsNoted() throws Exception {
        writeTopic("topics/dex/" + first.fileName(), first.id());

        mojo(library.toString()).execute();

        assertThat(dir.resolve("out/fragments.yaml")).content()
                .contains("check: existing-topics-replaced").doesNotContain("duplicate-id")
                .contains("existing-topics-checked");
        assertThat(dir.resolve("out/topics/dex/" + first.fileName())).exists();
    }

    @Test
    void anUnrelatedExistingTopicChangesNothing() throws Exception {
        writeTopic("topics/other/unrelated.adoc", "other-unrelated");

        mojo(library.toString()).execute();

        assertThat(dir.resolve("out/fragments.yaml")).content()
                .doesNotContain("duplicate-id").doesNotContain("existing-topics-replaced");
        assertThat(dir.resolve("out/topics/dex/" + first.fileName())).exists();
    }

    @Test
    void aMissingRootIsSkippedWithANote() throws Exception {
        mojo(dir.resolve("no-such-root").toString()).execute();

        assertThat(dir.resolve("out/fragments.yaml")).content().contains("check: existing-topics-skipped");
        assertThat(dir.resolve("out/topics/dex/" + first.fileName())).exists();
    }

    @Test
    void aBlankRootTurnsTheCheckOff() throws Exception {
        writeTopic("topics/other/someone-elses.adoc", first.id());

        mojo("").execute();

        assertThat(dir.resolve("out/fragments.yaml")).content().doesNotContain("duplicate-id");
        assertThat(dir.resolve("out/topics/dex/" + first.fileName())).exists();
    }
}

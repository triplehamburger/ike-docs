package network.ike.docs.plugin;

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

/// `idoc:ingest-place`: fragments from `idoc:ingest-fragments` are copied into a
/// DeX project, the assembly gets a module, and nothing is written when the
/// run cannot be placed safely.
class IngestPlaceMojoTest {

    private static final String ASSEMBLIES_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0">
                <modelVersion>4.1.0</modelVersion>
                <parent>
                    <groupId>dev.example.dex</groupId>
                    <artifactId>root</artifactId>
                </parent>
                <artifactId>assemblies</artifactId>
                <packaging>pom</packaging>
                <subprojects>
                    <subproject>compendium</subproject>
                </subprojects>
            </project>
            """;

    @TempDir
    Path dir;

    private Path fragments;
    private Path project;

    private static Log silentLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String topic(String id) {
        return ":topic-id: " + id + "\n:topic-type: reference\n\n[[" + id + "]]\n= T\n\nText.\n";
    }

    @BeforeEach
    void setUp() throws IOException {
        fragments = dir.resolve("frag");
        project = dir.resolve("project");
        write(fragments.resolve("fragments.yaml"), "schema-version: 1\ngoal: ingest-fragments\nfindings: []\n");
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one"));
        write(fragments.resolve("topics/dex/k1-two.adoc"), topic("dex-k1-two"));
        write(fragments.resolve("assembly/DeXRecord_K1.adoc"),
                "= K1\n\ninclude::{topics}/topics/dex/k1-one.adoc[leveloffset=+1]\n");
        write(project.resolve("assemblies/pom.xml"), ASSEMBLIES_POM);
        Files.createDirectories(project.resolve("topics/src/docs/asciidoc/topics/dex"));
    }

    private IngestPlaceMojo mojo() {
        IngestPlaceMojo mojo = new IngestPlaceMojo();
        mojo.log = silentLog();
        mojo.fragments = fragments.toFile();
        mojo.topicsDir = project.resolve("topics/src/docs/asciidoc/topics/dex").toFile();
        mojo.assembliesDir = project.resolve("assemblies").toFile();
        mojo.topicsAttribute = "{generated}/topics-asciidoc";
        mojo.outputDir = dir.resolve("out").toFile();
        return mojo;
    }

    private Path placed(String name) {
        return project.resolve("topics/src/docs/asciidoc/topics/dex/" + name);
    }

    @Test
    void placesTopicsBuildsTheAssemblyModuleAndRegistersIt() throws Exception {
        mojo().execute();

        assertThat(placed("k1-one.adoc")).exists();
        assertThat(placed("k1-two.adoc")).exists();
        Path module = project.resolve("assemblies/K1");
        assertThat(module.resolve("src/docs/asciidoc/DeXRecord_K1.adoc")).content()
                .startsWith("= K1\n:doctype: book").contains(":topics: {generated}/topics-asciidoc")
                .contains("include::{topics}/topics/dex/k1-one.adoc");
        assertThat(module.resolve("pom.xml")).content()
                .contains("<groupId>dev.example.dex</groupId>").contains("<artifactId>assemblies</artifactId>")
                .contains("<artifactId>DeXRecord_K1</artifactId>")
                .contains("<pdf.source.document>DeXRecord_K1</pdf.source.document>").contains("<classifier>adoc");
        assertThat(project.resolve("assemblies/pom.xml")).content()
                .contains("<subproject>compendium</subproject>").contains("<subproject>K1</subproject>");
        assertThat(dir.resolve("out/placed.yaml")).exists();
    }

    @Test
    void aDryRunReportsAndWritesNothing() throws Exception {
        IngestPlaceMojo mojo = mojo();
        mojo.dryRun = true;

        mojo.execute();

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(project.resolve("assemblies/K1")).doesNotExist();
        assertThat(project.resolve("assemblies/pom.xml")).content().doesNotContain("K1");
        assertThat(dir.resolve("out/placed.yaml")).content().contains("dry-run: true");
    }

    @Test
    void placingTwiceReplacesTheSameTopicsAndRegistersTheModuleOnce() throws Exception {
        mojo().execute();
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one") + "More text.\n");

        mojo().execute();

        assertThat(placed("k1-one.adoc")).content().contains("More text.");
        assertThat(dir.resolve("out/placed.yaml")).content().contains("topics-replaced");
        assertThat(project.resolve("assemblies/pom.xml")).content()
                .containsOnlyOnce("<subproject>K1</subproject>");
    }

    @Test
    void aFileNameUsedByADifferentTopicStopsEverythingBeforeAnythingIsWritten() throws Exception {
        write(placed("k1-two.adoc"), topic("dex-someone-else"));

        assertThatThrownBy(() -> mojo().execute())
                .isInstanceOf(MojoException.class).hasMessageContaining("nothing was placed");

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(placed("k1-two.adoc")).content().contains("dex-someone-else");
        assertThat(project.resolve("assemblies/K1")).doesNotExist();
        assertThat(dir.resolve("out/placed.yaml")).content().contains("file-name-clash");
    }

    @Test
    void aFragmentRunThatRecordedAnErrorIsNotPlaced() throws Exception {
        write(fragments.resolve("fragments.yaml"),
                "schema-version: 1\nfindings:\n- severity: ERROR\n  check: duplicate-id\n  component: x\n  message: m\n");

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class);

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(dir.resolve("out/placed.yaml")).content().contains("fragments-failed");
    }

    @Test
    void missingFragmentsAreReportedWithTheGoalToRunFirst() throws Exception {
        Files.delete(fragments.resolve("fragments.yaml"));

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class);

        assertThat(dir.resolve("out/placed.yaml")).content().contains("fragments-missing")
                .contains("idoc:ingest-fragments");
    }

    @Test
    void anAssemblyThatAlreadySetsTheTopicsAttributeIsLeftAlone() {
        String assembly = "= K1\n:topics: custom\n\ntext\n";

        assertThat(mojo().withAttributes(assembly)).isEqualTo(assembly);
    }

    @Test
    void aModuleNameWithPathCharactersIsRejected() {
        IngestPlaceMojo mojo = mojo();
        mojo.module = "../evil";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);

        assertThat(project.resolve("evil")).doesNotExist();
    }
}

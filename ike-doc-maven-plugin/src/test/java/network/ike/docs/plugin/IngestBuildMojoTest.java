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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `idoc:ingest-build`: fragments from `idoc:ingest-split` are copied into a
/// DeX project, the assembly gets a module, and nothing is written when the
/// run cannot be placed safely; the registry and render steps run a child
/// Maven, here a small Java program that records how it was called.
class IngestBuildMojoTest {

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
        write(fragments.resolve("fragments.yaml"), "schema-version: 1\ngoal: ingest-split\nfindings: []\n");
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one"));
        write(fragments.resolve("topics/dex/k1-two.adoc"), topic("dex-k1-two"));
        write(fragments.resolve("assembly/DeXRecord_K1.adoc"),
                "= K1\n\ninclude::{topics}/topics/dex/k1-one.adoc[leveloffset=+1]\n");
        write(project.resolve("assemblies/pom.xml"), ASSEMBLIES_POM);
        Files.createDirectories(project.resolve("topics/src/docs/asciidoc/topics/dex"));
    }

    private IngestBuildMojo mojo() {
        IngestBuildMojo mojo = new IngestBuildMojo();
        mojo.log = silentLog();
        mojo.fragments = fragments.toFile();
        mojo.topicsRoot = project.resolve("topics/src/docs/asciidoc").toFile();
        mojo.topicsDir = project.resolve("topics/src/docs/asciidoc/topics/dex").toFile();
        mojo.assembliesDir = project.resolve("assemblies").toFile();
        mojo.topicsAttribute = "{generated}/topics-asciidoc";
        mojo.outputDir = dir.resolve("out").toFile();
        mojo.projectDir = project.toFile();
        mojo.steps = "place";
        mojo.plugin = "network.ike.docs:ike-doc-maven-plugin";
        mojo.timeoutMinutes = 5;
        return mojo;
    }

    private static final String FAKE_MAVEN = """
            import java.nio.file.*;

            public class FakeMvn {
                public static void main(String[] a) throws Exception {
                    String line = System.getProperty("user.dir") + " >";
                    for (int i = 2; i < a.length; i++) {
                        line += " " + a[i];
                    }
                    Files.writeString(Path.of(a[1]), line + "\\n",
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (line.contains("rootFile")) {
                        for (int i = 2; i < a.length; i++) {
                            if (a[i].startsWith("-Dike.topic-registry.output=")) {
                                Path out = Path.of(a[i].substring("-Dike.topic-registry.output=".length()));
                                Files.createDirectories(out.getParent());
                                Files.writeString(out, a[0].equals("unreached") ? "domains: []\\n"
                                        : "domains:\\n- id: dex\\n  topics:\\n  - id: dex-k1-one\\n  - id: dex-k1-two\\n");
                            }
                        }
                    }
                    if (a[0].equals("fail") && line.contains("topic-registry")) {
                        System.out.println("[ERROR] duplicate id dex-x");
                        System.exit(1);
                    }
                    if (a[0].equals("failrender") && line.contains("verify")) {
                        System.out.println("BUILD FAILURE");
                        System.exit(1);
                    }
                }
            }
            """;

    private Path calls;

    /** A mojo whose child Maven is the fake one; {@code mode} is ok, fail (registry) or failrender. */
    private IngestBuildMojo withFakeMaven(String mode) throws IOException {
        Path source = dir.resolve("FakeMvn.java");
        write(source, FAKE_MAVEN);
        calls = dir.resolve("calls.txt");
        IngestBuildMojo mojo = mojo();
        mojo.steps = null;
        mojo.mavenCommand = "\"" + Path.of(System.getProperty("java.home"), "bin", "java") + "\" \"" + source
                + "\" " + mode + " \"" + calls + "\"";
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
        Path module = project.resolve("assemblies/DeXRecord_K1");
        assertThat(module.resolve("src/docs/asciidoc/DeXRecord_K1.adoc")).content()
                .startsWith("= K1\n:doctype: book").contains(":topics: {generated}/topics-asciidoc")
                .contains("include::{topics}/topics/dex/k1-one.adoc");
        assertThat(module.resolve("pom.xml")).content()
                .contains("<groupId>dev.example.dex</groupId>").contains("<artifactId>assemblies</artifactId>")
                .contains("<artifactId>DeXRecord_K1</artifactId>")
                .contains("<pdf.source.document>DeXRecord_K1</pdf.source.document>").contains("<classifier>adoc");
        assertThat(project.resolve("assemblies/pom.xml")).content()
                .contains("<subproject>compendium</subproject>").contains("<subproject>DeXRecord_K1</subproject>");
        assertThat(dir.resolve("out/placed.yaml")).exists();
    }

    @Test
    void aDryRunReportsAndWritesNothing() throws Exception {
        IngestBuildMojo mojo = mojo();
        mojo.dryRun = true;

        mojo.execute();

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(project.resolve("assemblies/DeXRecord_K1")).doesNotExist();
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
                .containsOnlyOnce("<subproject>DeXRecord_K1</subproject>");
    }

    @Test
    void aFileNameUsedByADifferentTopicStopsEverythingBeforeAnythingIsWritten() throws Exception {
        write(placed("k1-two.adoc"), topic("dex-someone-else"));

        assertThatThrownBy(() -> mojo().execute())
                .isInstanceOf(MojoException.class).hasMessageContaining("nothing was placed");

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(placed("k1-two.adoc")).content().contains("dex-someone-else");
        assertThat(project.resolve("assemblies/DeXRecord_K1")).doesNotExist();
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
                .contains("idoc:ingest-split");
    }

    @Test
    void anAssemblyThatAlreadySetsTheTopicsAttributeIsLeftAlone() {
        String assembly = "= K1\n:topics: custom\n\ntext\n";

        assertThat(mojo().withAttributes(assembly)).isEqualTo(assembly);
    }

    @Test
    void aModuleNameWithPathCharactersIsRejected() {
        IngestBuildMojo mojo = mojo();
        mojo.module = "../evil";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class);

        assertThat(project.resolve("evil")).doesNotExist();
    }

    @Test
    void noStepsValueRunsAllStepsInOrderAndNamedOnesInRunOrder() throws Exception {
        assertThat(IngestBuildMojo.stepsToRun(null)).containsExactly("place", "registry", "render");
        assertThat(IngestBuildMojo.stepsToRun("render, place")).containsExactly("place", "render");
        assertThatThrownBy(() -> IngestBuildMojo.stepsToRun("deploy"))
                .isInstanceOf(MojoException.class).hasMessageContaining("place").hasMessageContaining("deploy");
    }

    @Test
    void allStepsPlaceThenCheckTheRegistryThenRenderInTheProject() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("ok");
        mojo.mavenArgs = "-o -Dfoo=bar";

        mojo.execute();

        assertThat(placed("k1-one.adoc")).exists();
        List<String> lines = Files.readAllLines(calls);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith(project + " >")
                .contains("-B network.ike.docs:ike-doc-maven-plugin:topic-registry -pl topics")
                .contains("-Dike.topic-registry.output=" + dir.resolve("out").resolve("topic-registry.yaml"))
                .endsWith("-o -Dfoo=bar");
        assertThat(lines.get(1)).contains("-Dike.topic-registry.rootFile=")
                .contains("index-dex-records.adoc").endsWith("-o -Dfoo=bar");
        assertThat(lines.get(2)).startsWith(project + " >")
                .contains("-B clean verify -pl assemblies/DeXRecord_K1 -am").endsWith("-o -Dfoo=bar");
        assertThat(dir.resolve("out/build-registry.log")).exists();
        assertThat(dir.resolve("out/build-render.log")).exists();
    }

    @Test
    void requireMetadataIsPassedToBothRegistryRunsAndOnlyThen() throws Exception {
        IngestBuildMojo off = withFakeMaven("ok");
        off.execute();
        assertThat(Files.readAllLines(calls)).noneMatch(l -> l.contains("requireMetadata"));

        Files.delete(calls);
        IngestBuildMojo on = withFakeMaven("ok");
        on.requireMetadata = true;
        on.execute();

        List<String> lines = Files.readAllLines(calls);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains(":topic-registry").contains("-Dike.ingest.requireMetadata=true");
        assertThat(lines.get(1)).contains("rootFile=").contains("-Dike.ingest.requireMetadata=true");
        assertThat(lines.get(2)).doesNotContain("requireMetadata");
    }

    @Test
    void aDryRunPlacesNothingAndRunsNoChildMaven() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("ok");
        mojo.dryRun = true;

        mojo.execute();

        assertThat(placed("k1-one.adoc")).doesNotExist();
        assertThat(calls).doesNotExist();
    }

    @Test
    void aFailedRegistryCheckStopsTheRunBeforeTheRender() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("fail");

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("topic registry check failed with exit code 1")
                .hasMessageContaining("duplicate id dex-x");

        assertThat(Files.readAllLines(calls)).hasSize(1);
        assertThat(dir.resolve("out/build-registry.log")).content().contains("duplicate id dex-x");
        assertThat(placed("k1-one.adoc")).exists();
    }

    @Test
    void aFailedRenderIsAnErrorThatPointsAtTheLog() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("failrender");

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("render failed with exit code 1").hasMessageContaining("build-render.log");
    }

    @Test
    void theRenderStepAloneNeedsTheModuleName() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("ok");
        mojo.steps = "render";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.build.module");
        assertThat(calls).doesNotExist();

        mojo.module = "DeXRecord_K1";
        mojo.execute();
        assertThat(Files.readAllLines(calls)).singleElement().asString().contains("assemblies/DeXRecord_K1");
    }

    @Test
    void aMissingMavenIsAnErrorThatNamesTheSetting() throws Exception {
        IngestBuildMojo mojo = mojo();
        mojo.steps = "registry";
        mojo.mavenCommand = "no-such-maven-xyz";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.build.mavenCommand");
    }

    private Path docRoot() {
        return project.resolve("topics/src/docs/asciidoc");
    }

    private String placedYaml() throws IOException {
        return Files.readString(dir.resolve("out/placed.yaml"));
    }

    @Test
    void theRecordIsRegisteredByGeneratedFilesAndNoHandOwnedFileIsEditedButTheAggregatorPom() throws Exception {
        mojo().execute();

        assertThat(docRoot().resolve("topics/dex/_includes-K1.adoc")).hasContent(
                "// generated by idoc:ingest-build - do not edit\n= K1\n\n"
                        + "include::k1-one.adoc[leveloffset=+1]\n\ninclude::k1-two.adoc[leveloffset=+1]\n");
        assertThat(docRoot().resolve("index-dex-records.adoc")).content()
                .contains("// generated by idoc:ingest-build - do not edit").contains("== DeX Records")
                .contains("include::topics/dex/_includes-K1.adoc[leveloffset=+2]");
        Path compendium = project.resolve("assemblies/DeXRecords");
        assertThat(compendium.resolve("src/docs/asciidoc/DeXRecords.adoc")).content()
                .contains("include::{topics}/topics/dex/_includes-K1.adoc[leveloffset=+2]")
                .endsWith("[index]\n== Index\n");
        assertThat(compendium.resolve("pom.xml")).content().contains("<artifactId>DeXRecords</artifactId>")
                .contains("<pdf.source.document>DeXRecords</pdf.source.document>");
        assertThat(project.resolve("assemblies/pom.xml")).content().contains("<subproject>DeXRecords</subproject>");
        assertThat(placedYaml()).contains("written:").contains("topics/src/docs/asciidoc/index-dex-records.adoc")
                .contains("assemblies/pom.xml").contains("fragment-not-in-assembly");
    }

    @Test
    void textOnlyTheAssemblyHoldsReachesTheGeneratedListsInTheAssemblysOrder() throws Exception {
        write(fragments.resolve("assembly/DeXRecord_K1.adoc"), "= Decision summary\n\nPreamble words.\n\n"
                + "== Split section\n\nIntro words.\n\n"
                + "include::{topics}/topics/dex/k1-two.adoc[leveloffset=+1]\n\n"
                + "include::{topics}/topics/dex/k1-one.adoc[leveloffset=+1]\n");

        mojo().execute();

        assertThat(docRoot().resolve("topics/dex/_includes-K1.adoc")).hasContent(
                "// generated by idoc:ingest-build - do not edit\n= K1\n\nDecision summary\n\nPreamble words.\n\n"
                        + "== Split section\n\nIntro words.\n\n"
                        + "include::k1-two.adoc[leveloffset=+1]\n\ninclude::k1-one.adoc[leveloffset=+1]\n");
        assertThat(placedYaml()).doesNotContain("fragment-not-in-assembly");
    }

    @Test
    void aSecondRecordIsAddedToTheGeneratedListsAndTheFirstIsKept() throws Exception {
        mojo().execute();
        Path second = dir.resolve("frag2");
        write(second.resolve("fragments.yaml"), "schema-version: 1\ngoal: ingest-split\nfindings: []\n");
        write(second.resolve("topics/dex/k2-one.adoc"), topic("dex-k2-one"));
        write(second.resolve("assembly/DeXRecord_K2.adoc"),
                "= K2\n\ninclude::{topics}/topics/dex/k2-one.adoc[leveloffset=+1]\n");
        IngestBuildMojo mojo = mojo();
        mojo.fragments = second.toFile();

        mojo.execute();

        assertThat(docRoot().resolve("index-dex-records.adoc")).content()
                .containsSubsequence("_includes-K1.adoc", "_includes-K2.adoc");
        assertThat(project.resolve("assemblies/DeXRecords/src/docs/asciidoc/DeXRecords.adoc")).content()
                .containsSubsequence("_includes-K1.adoc", "_includes-K2.adoc");
        assertThat(project.resolve("assemblies/pom.xml")).content()
                .containsOnlyOnce("<subproject>DeXRecords</subproject>")
                .contains("<subproject>DeXRecord_K1</subproject>").contains("<subproject>DeXRecord_K2</subproject>");
    }

    @Test
    void theGeneratedFilesAreRewrittenFromTheFragmentsOnEveryRun() throws Exception {
        mojo().execute();
        write(docRoot().resolve("index-dex-records.adoc"), "stale\n");
        write(docRoot().resolve("topics/dex/_includes-K1.adoc"), "stale\n");

        mojo().execute();

        assertThat(docRoot().resolve("index-dex-records.adoc")).content().contains("_includes-K1.adoc");
        assertThat(docRoot().resolve("topics/dex/_includes-K1.adoc")).content().contains("k1-one.adoc");
    }

    @Test
    void aModuleNameThatIsNotTheAssemblyNameIsRefusedBecauseTheFourNamesMustAgree() throws Exception {
        IngestBuildMojo mojo = mojo();
        mojo.module = "K1";

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class).hasMessageContaining("nothing was placed");

        assertThat(placedYaml()).contains("module-name-mismatch").contains("DeXRecord_K1");
        assertThat(project.resolve("assemblies/K1")).doesNotExist();
        assertThat(placed("k1-one.adoc")).doesNotExist();
    }

    @Test
    void imagesAreCopiedAndTheirReferencesRewrittenInThePlacedCopiesOnly() throws Exception {
        write(fragments.resolve("assembly/DeXRecord_K1.adoc"), "= K1\n\nimage::images/k1-image-01.jpg[\"Picture\",width=255]\n\n"
                + "include::{topics}/topics/dex/k1-one.adoc[leveloffset=+1]\n");
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one") + "\nimage::images/k1-fig.png[Fig]\n");
        write(dir.resolve("images/k1-image-01.jpg"), "jpg");
        write(dir.resolve("images/k1-fig.png"), "png");

        mojo().execute();

        Path module = project.resolve("assemblies/DeXRecord_K1/src/docs/asciidoc");
        assertThat(docRoot().resolve("images/dex/K1/k1-image-01.jpg")).hasContent("jpg");
        assertThat(module.resolve("images")).doesNotExist();
        assertThat(module.resolve("DeXRecord_K1.adoc")).content().contains(":imagesdir: {topics}/images")
                .contains("image::dex/K1/k1-image-01.jpg[").doesNotContain("images/k1-image-01");
        assertThat(docRoot().resolve("topics/dex/_includes-K1.adoc")).content()
                .contains("image::dex/K1/k1-image-01.jpg[");
        assertThat(docRoot().resolve("images/dex/K1/k1-fig.png")).hasContent("png");
        assertThat(placed("k1-one.adoc")).content().contains("image::dex/K1/k1-fig.png[Fig]");
        assertThat(fragments.resolve("topics/dex/k1-one.adoc")).content().contains("image::images/k1-fig.png");
        assertThat(placedYaml()).contains("images: 2");
    }

    @Test
    void anImageThatIsNotThereStopsEverythingBeforeAnythingIsWritten() throws Exception {
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one") + "\nimage::images/gone.png[x]\n");

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class)
                .hasMessageContaining("nothing was placed");

        assertThat(placedYaml()).contains("image-missing").contains("gone.png");
        assertThat(placed("k1-two.adoc")).doesNotExist();
        assertThat(project.resolve("assemblies/DeXRecord_K1")).doesNotExist();
    }

    @Test
    void anImagePathThatLeavesTheSourceFolderIsRefused() throws Exception {
        write(fragments.resolve("topics/dex/k1-one.adoc"), topic("dex-k1-one") + "\nimage::../secret.png[x]\n");

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class);

        assertThat(placedYaml()).contains("image-path-escapes");
        assertThat(placed("k1-one.adoc")).doesNotExist();
    }

    @Test
    void aReferenceByUrlOrAttributeIsLeftAlone() throws Exception {
        String text = topic("dex-k1-one") + "\nimage::https://example.org/a.png[x]\n\nimage::{imagesdir}/b.png[y]\n";
        write(fragments.resolve("topics/dex/k1-one.adoc"), text);

        mojo().execute();

        assertThat(placed("k1-one.adoc")).hasContent(text);
    }

    @Test
    void anImageNoFragmentRefersToIsAWarningNotAnError() throws Exception {
        write(dir.resolve("images/extra.jpg"), "x");

        mojo().execute();

        assertThat(placedYaml()).contains("image-unreferenced").contains("extra.jpg");
        assertThat(placed("k1-one.adoc")).exists();
    }

    @Test
    void aTopicIdUsedByAnotherFileStopsEverythingBeforeAnythingIsWritten() throws Exception {
        write(docRoot().resolve("topics/other/x.adoc"), topic("dex-k1-one"));

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class)
                .hasMessageContaining("nothing was placed");

        assertThat(placedYaml()).contains("topic-id-clash").contains("topics/other/x.adoc");
        assertThat(placed("k1-one.adoc")).doesNotExist();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private void git(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t",
                "-c", "user.email=t@example.org", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).as(String.join(" ", args)).isZero();
    }

    @Test
    void aProjectWithUncommittedChangesIsNotPlacedInto() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "git is needed");
        git("init", "-q");

        assertThatThrownBy(() -> mojo().execute()).isInstanceOf(MojoException.class)
                .hasMessageContaining("nothing was placed");

        assertThat(placedYaml()).contains("project-dirty").contains("dirty-before");
        assertThat(placed("k1-one.adoc")).doesNotExist();
    }

    @Test
    void allowDirtyPlacesAndLeavesTheDirtyPathsAsAWarning() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "git is needed");
        git("init", "-q");
        IngestBuildMojo mojo = mojo();
        mojo.allowDirty = true;

        mojo.execute();

        assertThat(placed("k1-one.adoc")).exists();
        assertThat(placedYaml()).contains("project-dirty").contains("severity: WARNING");
    }

    @Test
    void aCleanProjectIsPlacedIntoAndTheFilesAddedAreTheOnesListed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "git is needed");
        git("init", "-q");
        git("add", "-A");
        git("commit", "-q", "-m", "start");

        mojo().execute();

        assertThat(placed("k1-one.adoc")).exists();
        assertThat(placedYaml()).doesNotContain("project-dirty");
    }

    @Test
    void aRegistryRunThatDoesNotReachEveryTopicOfTheRecordIsAnError() throws Exception {
        IngestBuildMojo mojo = withFakeMaven("unreached");

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class)
                .hasMessageContaining("does not reach 2 topic(s)").hasMessageContaining("dex-k1-one");
        assertThat(Files.readAllLines(calls)).hasSize(2);
    }

    @Test
    void theRegistryStepAloneChecksTheTopicsTheIncludesFilesList() throws Exception {
        mojo().execute();
        IngestBuildMojo mojo = withFakeMaven("ok");
        mojo.fragments = null;
        mojo.steps = "registry";

        mojo.execute();

        assertThat(Files.readAllLines(calls)).hasSize(2);
    }
}

package network.ike.docs.plugin;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate `idoc:ingest` goal: step selection, parameter checks and the
/// stop at the first failing step.
class IngestMojoTest {

    @TempDir
    Path dir;

    private static Log silentLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    private IngestMojo mojo() {
        IngestMojo mojo = new IngestMojo();
        mojo.log = silentLog();
        mojo.source = dir.resolve("missing.pdf").toFile();
        mojo.outputDir = dir.resolve("out").toFile();
        mojo.sizeLimit = 5000;
        mojo.split = true;
        mojo.existingRoots = "";
        mojo.maxMissing = 0.10;
        return mojo;
    }

    @Test
    void noOnlyValueRunsBothStepsInOrder() throws Exception {
        assertThat(IngestMojo.stepsToRun(null)).containsExactly("fragments", "coverage");
        assertThat(IngestMojo.stepsToRun(" ")).containsExactly("fragments", "coverage");
    }

    @Test
    void anOnlyValueRunsThatStepAlone() throws Exception {
        assertThat(IngestMojo.stepsToRun("Coverage")).isEqualTo(List.of("coverage"));
    }

    @Test
    void anUnknownOnlyValueIsRejectedAndNamesTheChoices() {
        assertThatThrownBy(() -> IngestMojo.stepsToRun("render"))
                .isInstanceOf(MojoException.class).hasMessageContaining("fragments").hasMessageContaining("render");
    }

    @Test
    void theFragmentsStepNeedsTheConvertedAsciiDoc() {
        IngestMojo mojo = mojo();
        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoException.class).hasMessageContaining("ike.ingest.adoc");
        assertThat(dir.resolve("out")).doesNotExist();
    }

    @Test
    void aFailingStepStopsTheRunBeforeTheNextOne() throws Exception {
        IngestMojo mojo = mojo();
        mojo.adoc = dir.resolve("absent.adoc").toFile();

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoException.class).hasMessageContaining("ingest-fragments");

        assertThat(dir.resolve("out/coverage.yaml")).doesNotExist();
    }
}

package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SourceKindTest {

    @TempDir
    Path dir;

    private Path zip(String name, String entry) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void detect_aPdfByItsHeaderIsUsableWithNoFindings() throws IOException {
        Path pdf = Files.writeString(dir.resolve("a.pdf"), "%PDF-1.7\nbody");
        SourceKind.Detection d = SourceKind.detect(pdf);
        assertThat(d.kind()).isEqualTo(SourceKind.PDF);
        assertThat(d.findings()).isEmpty();
        assertThat(d.usable()).isTrue();
    }

    @Test
    void detect_aPdfWhoseHeaderComesAfterSomeBytesIsStillAPdf() throws IOException {
        Path pdf = Files.writeString(dir.resolve("a.pdf"), "x".repeat(300) + "%PDF-1.4\nbody");
        assertThat(SourceKind.detect(pdf).kind()).isEqualTo(SourceKind.PDF);
    }

    @Test
    void detect_aPdfWithAnotherExtensionWarnsButIsUsable() throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "%PDF-1.7\nbody");
        SourceKind.Detection d = SourceKind.detect(file);
        assertThat(d.kind()).isEqualTo(SourceKind.PDF);
        assertThat(d.findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("source-extension-mismatch");
            assertThat(f.isError()).isFalse();
        });
        assertThat(d.usable()).isTrue();
    }

    @Test
    void detect_aWordFileIsRecognisedButNotSupportedYet() throws IOException {
        SourceKind.Detection d = SourceKind.detect(zip("a.docx", "word/document.xml"));
        assertThat(d.kind()).isEqualTo(SourceKind.DOCX);
        assertThat(d.usable()).isFalse();
        assertThat(d.findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("source-kind-unsupported");
            assertThat(f.isError()).isTrue();
            assertThat(f.message()).contains("not supported yet");
        });
    }

    @Test
    void detect_aZipThatIsNotAWordFileIsUnknown() throws IOException {
        SourceKind.Detection d = SourceKind.detect(zip("a.zip", "other/file.txt"));
        assertThat(d.kind()).isNull();
        assertThat(d.findings()).singleElement().satisfies(f -> assertThat(f.check()).isEqualTo("source-kind-unknown"));
    }

    @Test
    void detect_plainTextIsUnknown() throws IOException {
        Path file = Files.writeString(dir.resolve("a.pdf"), "just words");
        SourceKind.Detection d = SourceKind.detect(file);
        assertThat(d.kind()).isNull();
        assertThat(d.usable()).isFalse();
        assertThat(d.findings()).singleElement().satisfies(f -> assertThat(f.check()).isEqualTo("source-kind-unknown"));
    }

    @Test
    void detect_aMissingFileIsAnError() throws IOException {
        SourceKind.Detection d = SourceKind.detect(dir.resolve("none.pdf"));
        assertThat(d.kind()).isNull();
        assertThat(d.findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("source-missing");
            assertThat(f.isError()).isTrue();
        });
    }

    @Test
    void supported_isTrueOnlyForPdf() {
        assertThat(SourceKind.PDF.supported()).isTrue();
        assertThat(SourceKind.DOCX.supported()).isFalse();
    }
}

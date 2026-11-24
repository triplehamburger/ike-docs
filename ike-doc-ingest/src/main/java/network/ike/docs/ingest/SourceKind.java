package network.ike.docs.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/// The kind of a source document, read from the file's own bytes.
///
/// A PDF starts with `%PDF-` within its first kilobyte; a Word file is a zip
/// archive that holds `word/document.xml`. The file extension is only a hint:
/// when it names the other kind, or none, a WARNING is recorded. {@link #DOCX}
/// is a placeholder: it is recognised but not supported yet, so detection of
/// one reports an ERROR with a clear message.
public enum SourceKind {

    /// A PDF document; the supported kind.
    PDF(true),

    /// A Word document; recognised, not supported yet.
    DOCX(false);

    private final boolean supported;

    SourceKind(boolean supported) {
        this.supported = supported;
    }

    /// Whether the ingestion can read this kind now.
    ///
    /// @return true for {@link #PDF}
    public boolean supported() {
        return supported;
    }

    /// The result of looking at a source file.
    ///
    /// @param kind     the kind found, or null when the file is none of them or missing
    /// @param findings what was found: ERROR `source-missing`, `source-kind-unknown` or
    ///                 `source-kind-unsupported`, WARNING `source-extension-mismatch`
    public record Detection(SourceKind kind, List<Finding> findings) {

        /// Copy the findings so the detection is immutable.
        public Detection {
            findings = List.copyOf(findings);
        }

        /// Whether the source can be ingested now.
        ///
        /// @return true when a supported kind was found and no finding is an ERROR
        public boolean usable() {
            return kind != null && kind.supported() && findings.stream().noneMatch(Finding::isError);
        }
    }

    /// Detect the kind of a source file.
    ///
    /// @param file the source file
    /// @return what was found
    /// @throws IOException when the file exists but cannot be read
    public static Detection detect(Path file) throws IOException {
        List<Finding> findings = new ArrayList<>();
        String name = file.toString();
        if (!Files.isRegularFile(file)) {
            findings.add(Finding.error("source-missing", name, "the source file does not exist"));
            return new Detection(null, findings);
        }
        SourceKind kind = fromBytes(file);
        if (kind == null) {
            findings.add(Finding.error("source-kind-unknown", name,
                    "the file is neither a PDF (no %PDF- header) nor a Word document (no word/document.xml)"));
            return new Detection(null, findings);
        }
        SourceKind hinted = fromExtension(name);
        if (hinted != kind) {
            findings.add(Finding.warning("source-extension-mismatch", name,
                    "the content is " + kind + " but the file extension says "
                            + (hinted == null ? "neither" : hinted.toString())));
        }
        if (!kind.supported()) {
            findings.add(Finding.error("source-kind-unsupported", name,
                    kind + " input is not supported yet; convert it to PDF"));
        }
        return new Detection(kind, findings);
    }

    private static SourceKind fromBytes(Path file) throws IOException {
        byte[] head = new byte[1024];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        String start = new String(head, 0, read, StandardCharsets.ISO_8859_1);
        if (start.contains("%PDF-")) {
            return PDF;
        }
        if (read >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                if (zip.getEntry("word/document.xml") != null) {
                    return DOCX;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static SourceKind fromExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return PDF;
        }
        if (lower.endsWith(".docx")) {
            return DOCX;
        }
        return null;
    }
}

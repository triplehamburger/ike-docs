package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The `ingest-detect` stage: classify a source PDF as a 510(k) decision
/// summary, a companion document or something else.
///
/// A PDF with a text layer is classified from its text. A scanned PDF has no
/// text to classify, so it ends as an error that says so; transcribing scans
/// is not supported yet.
///
/// Nothing here throws for a bad input: every failure is a finding.
public final class IngestDetect {

    /// The goal name written into the report.
    public static final String GOAL = "ingest-detect";

    private static final String ACCESSDATA = "https://www.accessdata.fda.gov/cdrh_docs/reviews/";

    private IngestDetect() {
    }

    /// Classify a source PDF.
    ///
    /// @param pdf       the source PDF
    /// @param sourceUrl the citation URL to use, or null or blank to derive it from the K-number
    /// @return the report, for `detect.yaml`
    public static IngestReport run(Path pdf, String sourceUrl) {
        String citationUrl = sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.strip();
        String name = pdf.getFileName() == null ? pdf.toString() : pdf.getFileName().toString();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        List<IngestReport.Source> sources = new ArrayList<>();

        PdfPages.Loaded loaded = PdfPages.extractText(pdf);
        findings.addAll(loaded.findings());
        if (loaded.failed()) {
            return finish(sources, details, findings, "UNREADABLE");
        }
        String sourceHash;
        try {
            sourceHash = IngestHash.sha256Hex(pdf);
        } catch (IOException e) {
            findings.add(Finding.error("source-unreadable", name, "cannot hash the source: " + e.getMessage()));
            return finish(sources, details, findings, "UNREADABLE");
        }
        sources.add(new IngestReport.Source(name, sourceHash));

        List<String> texts = loaded.pageTexts();
        boolean scanned = PdfPages.isScanned(texts);
        details.put("pages", texts.size());
        details.put("text-layer", !scanned);
        if (scanned) {
            findings.add(Finding.error("scanned-pdf", name,
                    "the PDF has no usable text layer; transcribing scanned PDFs is not supported yet"));
            return finish(sources, details, findings, "SCANNED");
        }

        DexDetector.Detection detection = DexDetector.detect(texts, name);
        findings.addAll(detection.findings());
        details.put("layout", detection.layout().name());
        details.put("template", detection.template());
        details.put("signals", detection.signals().stream().map(s -> orderedMap(
                "name", s.name(), "page", s.page(), "found", s.found())).toList());
        details.put("sections", detection.sections().stream().map(s -> orderedMap(
                "label", s.label(), "title", s.title(), "page", s.page())).toList());
        boolean dex = detection.kind() != DexDetector.Kind.NOT_DEX;
        if (dex) {
            details.put("domain", "dex");
        }
        if (detection.kind() == DexDetector.Kind.DEX_510K) {
            details.put("k-number", detection.kNumber());
            describeProvenance(detection.kNumber(), name, citationUrl, details, findings);
        } else if (dex) {
            details.put("companion", detection.guess());
            if (detection.kind() == DexDetector.Kind.DEX_GUDID) {
                String di = DexDetector.deviceIdentifier(texts.get(0));
                if (!di.isEmpty()) {
                    details.put("di-number", di);
                }
            }
        } else {
            details.put("guess", detection.guess());
        }
        return finish(sources, details, findings, detection.kind().name());
    }

    private static IngestReport finish(List<IngestReport.Source> sources, Map<String, Object> details,
                                       List<Finding> findings, String kind) {
        details.put("kind", kind);
        return new IngestReport(GOAL, sources, details, findings, List.of());
    }

    private static void describeProvenance(String kNumber, String name, String sourceUrl,
                                           Map<String, Object> details, List<Finding> findings) {
        String url;
        if (sourceUrl == null) {
            url = ACCESSDATA + kNumber + ".pdf";
            details.put("citation-url-origin", "derived");
            findings.add(Finding.warning("citation-url-derived", name,
                    "the citation URL " + url + " was derived from the K-number and not checked; "
                            + "pass -Dike.ingest.sourceUrl to set it"));
        } else {
            url = sourceUrl;
            details.put("citation-url-origin", "supplied");
            if (!url.startsWith("https://")) {
                findings.add(Finding.error("citation-url-invalid", name, "the citation URL must start with https://"));
            } else if (!url.toUpperCase(Locale.ROOT).contains(kNumber)) {
                findings.add(Finding.warning("citation-url-mismatch", name,
                        "the citation URL does not contain the K-number " + kNumber));
            }
        }
        details.put("citation-url", url);
        details.put("license", ProvenanceAttributes.externalFederalPublicDomain(url).license());
    }

    private static Map<String, Object> orderedMap(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}

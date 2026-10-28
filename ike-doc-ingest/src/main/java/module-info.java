/// Reusable infrastructure for ingesting external content into IKE
/// documentation corpus projects.
///
/// Source-specific parsers (FHIR NPM packages, FDA IFU PDFs, ISO
/// standards, etc.) live in each corpus project's own ingest module
/// and consume this library for the cross-cutting concerns:
///
/// - {@link network.ike.docs.ingest.TarballDownloader} — HTTP fetch
///   and TGZ extraction
/// - {@link network.ike.docs.ingest.TopicFragmentWriter} — emit
///   AsciiDoc topic fragments with IKE-INGEST §"External Source
///   Ingestion" provenance attributes and any further `:topic-*:`
///   header attributes (summary, notes, related, …)
/// - {@link network.ike.docs.ingest.RegistryShardWriter} — deprecated:
///   the topic registry is now generated from topic headers, so put
///   registry metadata in {@link network.ike.docs.ingest.TopicFragment}
///   attributes instead
/// - {@link network.ike.docs.ingest.IncludesFileWriter} — emit
///   `_includes.adoc` assembly-composition manifests
/// - {@link network.ike.docs.ingest.IngestUtil} — pure helpers
///   (safe slug, YAML escape, truncate, strip markup)
/// - {@link network.ike.docs.ingest.IngestReport},
///   and {@link network.ike.docs.ingest.Finding} — the deterministic
///   ingest contract: every problem is a finding, written with the report
///   as deterministic YAML by {@link network.ike.docs.ingest.IngestYaml}
/// - {@link network.ike.docs.ingest.DexDetector} — decide from page text
///   whether a source is an FDA 510(k) decision summary, and which layout
///   and template it uses
/// - {@link network.ike.docs.ingest.IngestDetect} — classify a source PDF
///   as a decision summary or not
///
/// See IKE-Network/ike-issues#550 for the design proposal that
/// factored this module out of `hl7-ig-corpus-example/ingest/`.
module network.ike.docs.ingest {
    exports network.ike.docs.ingest;

    requires org.apache.commons.compress;
    requires org.yaml.snakeyaml;
    requires org.apache.pdfbox;
    requires java.net.http;
}

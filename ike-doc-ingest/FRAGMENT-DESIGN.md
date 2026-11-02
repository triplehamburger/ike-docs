# Making fragments from a converted document

Design notes for `idoc:ingest-fragments` (`IngestFragments` in this module, `IngestFragmentsMojo` in the plugin).

## Where it fits

The ingestion pipeline turns a source PDF into AsciiDoc fragments with as little AI as possible.

1. A converter (the PDF to AsciiDoc utility in `ike-dex-docs`) writes one `.adoc` file. It reads tags or layout, falls back to Docling for scans and Word files, and rebuilds picture tables with an OCR vote. It is not repeatable and is run by hand.
2. `idoc:ingest-fragments` turns that file into fragments plus an assembly that includes them. It is a pure function of the file: the same input gives the same output, and every problem is a finding in a report.
3. `idoc:topic-registry` then reads the generated headers.

The goal does not invoke the converter and does not read the converter's own fidelity check. Semantic line breaks, a table render check and a batch confirmation are not part of this goal.

## Standards

The fragments follow `IKE-ASCIIDOC-FRAGMENT.md`: a comment header, a `:topic-*:` attribute block, a literal `[[topic-id]]` anchor, a level-1 heading, and the body. Sizes follow `IKE-TOPIC-DECOMPOSITION.md` (5000 characters). `IKE-DEX-INGEST.md` currently describes a 510(k) as one whole-document record; this goal decomposes it instead, so that standard needs updating (decomposition, the id scheme, three heading levels in a fragment, the study fragment convention, converted tables).

## Behaviour

- **Level.** The heading level to split on is chosen from the headings (the lowest level with two or more) unless `ike.fragments.sectionLevel` is set. A document with a title and no sections is one fragment. When every heading has one level, as Docling writes them, levels are inferred from the printed labels: a Roman numeral keeps the level, a letter goes one deeper, a number two deeper. A lone `I`, `V` or `X` counts as a letter unless a longer Roman numeral is present.
- **Split.** A top-level section is one fragment. A section over the size limit is split at its lettered subsections; a subsection (or a section with none) still over the limit is split at its top-level numbered items, the studies. A study under 500 characters merges into the study before it. A fragment that cannot be split further is kept whole and its `:topic-notes:` says so.
- **Ids.** `dex-{document}-{slug}`. The document part is the 510(k) number (read from `510(k) Number: K######`), the GUDID primary DI number, `ike.fragments.documentId`, or the document title (cut at 32 characters). The slug drops the printed label (Roman numeral, letter or number) and a long parenthetical. A repeated id gets `-2`, `-3` and a warning.
- **Headers.** Type `reference`, status `review`, provenance `external`. A 510(k) is recorded as public domain; any other document carries an unconfirmed license and a warning. `:topic-related:` links the fragments of the same top-level section. The summary and keywords are placeholders built from the heading.
- **Assembly.** The source's title block, then each split heading with the text before its first part, then an `include::` for every fragment. A study fragment keeps its text between `tag::body[]` and `end::body[]`; the assembly includes only that region under a list item that repeats the study title, so the rendered record keeps the source's list.
- **Safety net.** The words of the fragments plus the assembly must equal the words of the source, or the goal fails and writes nothing. A heading above the split level between sections is an error, because its text would belong to no fragment.

Parameters: `ike.fragments.source`, `outputDir`, `sourceUrl`, `sectionLevel` (default 0, chosen from the headings), `documentId`, `sizeLimit` (default 5000), `split` (default true).

## Known limits

- The study split only recognises ordered-list markup. A converter that writes numbered labels as bold paragraphs gives sections that cannot be split into studies.
- Scans converted by Docling often give flat, garbled headings and many small fragments.
- Summary, keywords and index terms are placeholders; who writes the real ones is not decided.
- Images referenced by a local file path (`image:file:///...`) are reported with a warning.
- The converter's fidelity check is not enforced by this goal. It can fail the tables of real documents, and it cannot judge a scan.

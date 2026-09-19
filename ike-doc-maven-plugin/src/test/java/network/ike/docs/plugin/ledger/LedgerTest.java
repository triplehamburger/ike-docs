package network.ike.docs.plugin.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scans a small topic library laid out per IKE-INGEST.md: three well-formed
 * topics under one domain directory, an index assembly that includes them,
 * one topic with a missing attribute and a wrong anchor, one duplicate id,
 * and a stale copy under target/ that must be ignored.
 */
class LedgerTest {

    private static String topic(String id, String extraHeader, String anchor) {
        return """
                // %s
                // Topic: Title of %s
                // Type: reference
                // Status: review
                :topic-id: %s
                :topic-type: reference
                %s:topic-keywords: versioning, coordinates, STAMP, temporal
                :topic-scope-note: Covers versioning from the architecture perspective. \\
                  For version management procedures, see ops-version-migration. \\
                  For the coordinate fields, see ref-coordinate-fields.
                :topic-provenance: authored

                [[%s]]
                = Title of %s

                Body text.
                """.formatted(id, id, id, extraHeader, anchor, id);
    }

    private static Path corpus(Path dir) throws IOException {
        Path reg = dir.resolve("topics/architecture");
        Files.createDirectories(reg);
        Files.createDirectories(dir.resolve("topics/broken"));
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(reg.resolve("a.adoc"), topic("arch-a", ":topic-status: published\n", "arch-a"));
        Files.writeString(reg.resolve("b.adoc"), topic("arch-b", ":topic-status: published\n", "arch-b"));
        Files.writeString(reg.resolve("c.adoc"), topic("arch-c", ":topic-status: published\n", "arch-c"));
        Files.writeString(dir.resolve("topics/broken/d.adoc"), topic("arch-d", "", "wrong-anchor"));
        Files.writeString(dir.resolve("topics/broken/e.adoc"), topic("arch-a", ":topic-status: published\n", "arch-a"));
        Files.writeString(dir.resolve("target/stale.adoc"), topic("arch-stale", ":topic-status: published\n", "arch-stale"));
        Files.writeString(dir.resolve("index.adoc"), """
                = Topic Library
                :doctype: book
                :toc: left

                include::topics/architecture/a.adoc[leveloffset=+2]

                include::topics/architecture/b.adoc[leveloffset=+2]

                include::topics/architecture/c.adoc[leveloffset=+2]
                """);
        return dir;
    }

    @Test
    void scan_groupsTopicsAndOthers_andSkipsTarget(@TempDir Path dir) throws IOException {
        Ledger.Scan scan = Ledger.scan(corpus(dir), 100);

        assertThat(scan.topics()).extracting(TopicHeader::id)
                .containsExactly("arch-a", "arch-b", "arch-c", "arch-d", "arch-a");
        assertThat(scan.others()).extracting(TopicHeader::file).containsExactly("index.adoc");
        assertThat(scan.others().get(0).includes()).isEqualTo(3);
        assertThat(scan.others().get(0).title()).isEqualTo("Topic Library");
        assertThat(scan.others().get(0).documentAttributes())
                .containsExactly(Map.entry("doctype", "book"), Map.entry("toc", "left"));
    }

    @Test
    void wellFormedTopic_hasCompleteHeaderAndNoFindings(@TempDir Path dir) throws IOException {
        Ledger.Scan scan = Ledger.scan(corpus(dir), 100);
        TopicHeader a = scan.topics().stream().filter(t -> t.file().endsWith("/a.adoc")).findFirst().orElseThrow();

        assertThat(a.anchor()).isEqualTo("present");
        assertThat(a.findings()).isEmpty();
        assertThat(a.missingRequired()).isEmpty();
        assertThat(a.keywords()).containsExactly("versioning", "coordinates", "STAMP", "temporal");
        assertThat(a.attributes().get("scope-note"))
                .isEqualTo("Covers versioning from the architecture perspective. For version management procedures, see ops-version-migration. For the coordinate fields, see ref-coordinate-fields.");
        assertThat(a.title()).isEqualTo("Title of arch-a");
        assertThat(a.charCount()).isGreaterThan(30);
    }

    @Test
    void brokenTopic_andDuplicateId_areFindingsNotFailures(@TempDir Path dir) throws IOException {
        Ledger.Scan scan = Ledger.scan(corpus(dir), 100);
        TopicHeader d = scan.topics().get(3);

        assertThat(d.file()).isEqualTo("topics/broken/d.adoc");
        assertThat(d.anchor()).isEqualTo("mismatched");
        assertThat(d.missingRequired()).containsExactly("status");
        assertThat(d.findings()).anySatisfy(f -> assertThat(f).contains(":topic-status:"));
        assertThat(d.findings()).anySatisfy(f -> assertThat(f).contains("wrong-anchor"));
        assertThat(scan.findings()).anySatisfy(f -> assertThat(f)
                .contains("topics/broken/e.adoc").contains("duplicate id 'arch-a'"));
    }

    @Test
    void model_andYaml_useRegistryFieldNames(@TempDir Path dir) throws IOException {
        Ledger.Scan scan = Ledger.scan(corpus(dir), 100);
        Map<String, Object> model = Ledger.model(List.of(scan), dir, Instant.parse("2026-09-17T12:00:00Z"));
        String yaml = Ledger.yaml(model);

        assertThat(model).containsKeys("generated", "scanned-from", "roots", "findings");
        assertThat(yaml)
                .startsWith("# doc-ledger.yaml, generated by idoc:ledger.")
                .contains("generated: '2026-09-17T12:00:00Z'")
                .contains("- dir: topics/architecture")
                .contains("- id: arch-a")
                .contains("file: topics/architecture/a.adoc")
                .contains("provenance: authored")
                .contains("doctype: book")
                .contains("anchor: present")
                .contains("header: complete")
                .contains("header: 'missing: status'")
                .contains("kind: assembly")
                .contains("includes: 3")
                .doesNotContain("stale");
    }

    @Test
    void tree_listsDirectoriesFilesAndCounts(@TempDir Path dir) throws IOException {
        Ledger.Scan scan = Ledger.scan(corpus(dir), 100);
        List<String> tree = Ledger.tree(scan, dir);

        assertThat(tree.get(0)).isEqualTo(".");
        assertThat(tree).anySatisfy(l -> assertThat(l).contains("index.adoc").contains("assembly, 3 includes"));
        assertThat(tree).contains("  topics/architecture/");
        assertThat(tree).anySatisfy(l -> assertThat(l).contains("a.adoc").contains("arch-a").contains("reference").contains("published"));
    }

    @Test
    void add_insertsNewFilesIntoAnExistingLedgerWithoutRescanning(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Path ledgerFile = dir.resolve("target/doc-ledger.yaml");
        Files.writeString(ledgerFile, Ledger.yaml(Ledger.model(
                List.of(Ledger.scan(root, 100)), dir, Instant.parse("2026-09-17T12:00:00Z"))));
        Files.writeString(root.resolve("topics/architecture/z.adoc"), topic("arch-z", ":topic-status: draft\n", "arch-z"));
        Files.createDirectories(root.resolve("topics/ops"));
        Files.writeString(root.resolve("topics/ops/m.adoc"), topic("ops-m", ":topic-status: draft\n", "ops-m"));
        Files.writeString(root.resolve("notes.adoc"), "== Working Notes\n\nText.\n");

        Map<String, Object> loaded = Ledger.load(ledgerFile);
        List<TopicHeader> added = Ledger.add(loaded, dir,
                List.of(Path.of("topics/architecture/z.adoc"), Path.of("topics/ops/m.adoc"), root.resolve("notes.adoc")),
                Instant.parse("2026-09-18T09:00:00Z"));
        String yaml = Ledger.yaml(loaded);

        assertThat(added).extracting(TopicHeader::file)
                .containsExactly("topics/architecture/z.adoc", "topics/ops/m.adoc", "notes.adoc");
        assertThat(yaml)
                .contains("generated: '2026-09-18T09:00:00Z'")
                .contains("files: 9").contains("topics: 7")
                .contains("- dir: topics/ops").contains("- id: ops-m")
                .contains("file: notes.adoc").contains("title: Working Notes");
        assertThat(yaml.indexOf("- dir: topics/architecture")).isLessThan(yaml.indexOf("- dir: topics/broken"));
        assertThat(yaml.indexOf("- dir: topics/broken")).isLessThan(yaml.indexOf("- dir: topics/ops"));
        assertThat(yaml.indexOf("file: topics/architecture/c.adoc")).isLessThan(yaml.indexOf("file: topics/architecture/z.adoc"));
        assertThat((List<?>) loaded.get("findings")).hasSize(3);   // the fixture's own three, untouched
    }

    @Test
    void add_replacesTheEntryForARereadFile_andChecksDuplicateIdsAgainstTheLedger(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = Ledger.model(List.of(Ledger.scan(root, 100)), dir, Instant.now());

        Files.writeString(root.resolve("topics/architecture/a.adoc"), topic("arch-a", ":topic-status: review\n", "arch-a"));
        Ledger.add(model, dir, List.of(Path.of("topics/architecture/a.adoc")), Instant.now());
        String yaml = Ledger.yaml(model);
        assertThat(yaml).contains("files: 6").contains("topics: 5").contains("status: review");
        assertThat(yaml.split("file: topics/architecture/a.adoc", -1)).hasSize(2);

        Files.writeString(root.resolve("topics/architecture/b2.adoc"), topic("arch-b", ":topic-status: draft\n", "arch-b"));
        Ledger.add(model, dir, List.of(Path.of("topics/architecture/b2.adoc")), Instant.now());
        assertThat((List<?>) model.get("findings")).anySatisfy(f -> assertThat(String.valueOf(f))
                .isEqualTo("topics/architecture/b2.adoc: duplicate id 'arch-b', already declared by topics/architecture/b.adoc"));
    }

    @Test
    void add_rejectsFilesOutsideTheLedgerRoots(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = Ledger.model(List.of(Ledger.scan(root.resolve("topics"), 100)), dir, Instant.now());
        Path outside = root.resolve("index.adoc");
        assertThatThrownBy(() -> Ledger.add(model, dir, List.of(outside), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none of the ledger's roots");
    }

    @Test
    void yaml_roundTripsThroughSnakeYamlUnchanged(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = Ledger.model(List.of(Ledger.scan(root, 100)), dir, Instant.parse("2026-09-17T12:00:00Z"));
        String once = Ledger.yaml(model);
        Path file = dir.resolve("target/rt.yaml");
        Files.writeString(file, once);
        String twice = Ledger.yaml(Ledger.load(file));
        assertThat(twice).isEqualTo(once);
        assertThat(once).doesNotContain("\t");
    }

    @Test
    void scalar_quotesWhatYamlWouldRetype_andLeavesTheRestPlain(@TempDir Path dir) throws IOException {
        Map<String, Object> odd = new java.util.LinkedHashMap<>();
        odd.put("empty", "");
        odd.put("bool", "yes");
        odd.put("num", "3");
        odd.put("float", "1.0");
        odd.put("date", "2026-09-17");
        odd.put("stamp", "2026-09-17T12:00:00Z");
        odd.put("colon", "K031739 i-STAT Test: Device Overview");
        odd.put("hash", "a # b");
        odd.put("dash", "- leading dash");
        odd.put("quote", "it's");
        odd.put("nl", "two\nlines");
        odd.put("plain", "Public domain \u2014 US federal government work.");
        odd.put("count", 42L);
        odd.put("flag", true);
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        model.put("roots", List.of());
        model.put("odd", odd);
        Path file = dir.resolve("odd.yaml");
        Files.writeString(file, Ledger.yaml(model));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Ledger.load(file).get("odd");
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("empty", "");
        expected.put("bool", "yes");
        expected.put("num", "3");
        expected.put("float", "1.0");
        expected.put("date", "2026-09-17");
        expected.put("stamp", "2026-09-17T12:00:00Z");
        expected.put("colon", "K031739 i-STAT Test: Device Overview");
        expected.put("hash", "a # b");
        expected.put("dash", "- leading dash");
        expected.put("quote", "it's");
        expected.put("nl", "two\nlines");
        expected.put("plain", "Public domain \u2014 US federal government work.");
        expected.put("count", 42);
        expected.put("flag", true);
        assertThat(back).containsExactlyEntriesOf(expected);
        assertThat(Ledger.scalar("Public domain \u2014 US federal government work.")).isEqualTo("Public domain \u2014 US federal government work.");
        assertThat(Ledger.scalar("2026-09-17T12:00:00Z")).isEqualTo("'2026-09-17T12:00:00Z'");
        assertThat(Ledger.scalar("missing: status")).isEqualTo("'missing: status'");
    }

    @Test
    void scan_failsPastMaxFiles(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        assertThatThrownBy(() -> Ledger.scan(root, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("More than 2 .adoc files");
    }

    @Test
    void parse_fileWithoutTopicId_isNotATopic_butKeepsItsHeader() {
        TopicHeader h = TopicHeader.parse("guide.adoc",
                "= A Guide\n:doctype: book\n:imagesdir: images\n\n:late: ignored\ninclude::x.adoc[]\n");
        assertThat(h.topic()).isFalse();
        assertThat(h.id()).isNull();
        assertThat(h.title()).isEqualTo("A Guide");
        assertThat(h.documentAttributes())
                .containsExactly(Map.entry("doctype", "book"), Map.entry("imagesdir", "images"));
        assertThat(h.includes()).isEqualTo(1);
        assertThat(h.findings()).isEmpty();
    }

    @Test
    void parse_chapterFileStartingAtLevelTwo_keepsItsHeadingAsTitle() {
        TopicHeader h = TopicHeader.parse("chapters/build.adoc", "== Build Profiles\n\nText.\n");
        assertThat(h.topic()).isFalse();
        assertThat(h.title()).isEqualTo("Build Profiles");
        assertThat(h.level()).isEqualTo(2);
    }

    @Test
    void parse_acceptsProcedureAsTaskType() {
        TopicHeader h = TopicHeader.parse("p.adoc",
                ":topic-id: ops-x\n:topic-type: procedure\n:topic-status: draft\n:topic-keywords: a\n\n[[ops-x]]\n= X\n");
        assertThat(h.findings()).isEmpty();
    }
}

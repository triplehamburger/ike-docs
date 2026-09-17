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

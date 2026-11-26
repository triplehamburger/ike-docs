package network.ike.docs.plugin.registry;

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
class TopicRegistryTest {

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
        TopicRegistry.Scan scan = TopicRegistry.scan(corpus(dir), 100);

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
        TopicRegistry.Scan scan = TopicRegistry.scan(corpus(dir), 100);
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
        TopicRegistry.Scan scan = TopicRegistry.scan(corpus(dir), 100);
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
    void scanFrom_keepsOnlyWhatTheRootFileReaches(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);

        TopicRegistry.Scan scan = TopicRegistry.scanFrom(root, root.resolve("index.adoc"));

        assertThat(scan.topics()).extracting(TopicHeader::id).containsExactly("arch-a", "arch-b", "arch-c");
        assertThat(scan.others()).extracting(TopicHeader::file).containsExactly("index.adoc");
        assertThat(scan.findings()).isEmpty();
        // the full scan of the same root is unchanged
        assertThat(TopicRegistry.scan(root, 100).topics()).hasSize(5);
    }

    @Test
    void scanFrom_followsNestedIncludes_andStopsAtCycles(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Files.createDirectories(root.resolve("chapters"));
        Files.writeString(root.resolve("book.adoc"), "= Book\n\ninclude::chapters/one.adoc[]\n");
        Files.writeString(root.resolve("chapters/one.adoc"), """
                == One

                include::../topics/architecture/b.adoc[leveloffset=+1]
                include::../book.adoc[]
                include::one.adoc[]
                """);

        TopicRegistry.Scan scan = TopicRegistry.scanFrom(root, root.resolve("book.adoc"));

        assertThat(scan.topics()).extracting(TopicHeader::id).containsExactly("arch-b");
        assertThat(scan.others()).extracting(TopicHeader::file).containsExactly("book.adoc", "chapters/one.adoc");
        assertThat(scan.findings()).isEmpty();
    }

    @Test
    void scanFrom_reportsIncludesItCannotFollow(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Files.writeString(root.resolve("map.adoc"), """
                = Map

                include::{topicsdir}/architecture/a.adoc[]
                include::topics/architecture/gone.adoc[]
                include::diagram.puml[]
                include::topics/architecture/c.adoc[]
                """);

        TopicRegistry.Scan scan = TopicRegistry.scanFrom(root, root.resolve("map.adoc"));

        assertThat(scan.topics()).extracting(TopicHeader::id).containsExactly("arch-c");
        assertThat(scan.findings()).containsExactly(
                "map.adoc: include::topics/architecture/gone.adoc[] does not resolve to a file",
                "map.adoc: include::{topicsdir}/architecture/a.adoc[] not followed"
                        + " (attribute reference, absolute path, URI or above the root)");
    }

    @Test
    void scanFrom_rejectsAFileOutsideTheRoot(@TempDir Path dir) throws IOException {
        Path root = corpus(dir).resolve("topics");

        assertThatThrownBy(() -> TopicRegistry.scanFrom(root, dir.resolve("index.adoc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not under the registry root");
    }

    @Test
    void domainOf_isTheIdPrefixBeforeTheFirstHyphen() {
        assertThat(TopicRegistry.domainOf("arch-coord-versioning")).isEqualTo("arch");
        assertThat(TopicRegistry.domainOf("ext-fda-k031739-device-overview")).isEqualTo("ext");
        assertThat(TopicRegistry.domainOf("orphan")).isEqualTo("orphan");
    }

    @Test
    void model_andYaml_followTheRegistrySchema(@TempDir Path dir) throws IOException {
        TopicRegistry.Scan scan = TopicRegistry.scan(corpus(dir), 100);
        Map<String, Object> model = TopicRegistry.model(List.of(scan), dir, Instant.parse("2026-09-17T12:00:00Z"));
        String yaml = TopicRegistry.yaml(model);

        assertThat(model.keySet()).containsExactly("registry-version", "generated", "scanned-from", "roots",
                "topic-count", "file-count", "domains", "assemblies", "other-files", "findings");
        assertThat(yaml)
                .startsWith("# topic-registry.yaml, generated by idoc:topic-registry")
                .contains("registry-version: '1.2'")
                .contains("generated: '2026-09-17T12:00:00Z'")
                .contains("topic-count: 5")
                .contains("file-count: 6")
                .contains("- id: arch\n")
                .contains("- id: arch-a")
                .contains("file: topics/architecture/a.adoc")
                .contains("provenance: authored")
                .contains("doctype: book")
                .contains("anchor: present")
                .contains("header: complete")
                .contains("header: 'missing: status'")
                .contains("file: index.adoc")
                .contains("includes: 3")
                .doesNotContain("stale");
        // one domain, its topics in id order with the duplicate id kept in path order
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> domains = (List<Map<String, Object>>) model.get("domains");
        assertThat(domains).hasSize(1);
        assertThat(domains.get(0).get("id")).isEqualTo("arch");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topics = (List<Map<String, Object>>) domains.get(0).get("topics");
        assertThat(topics).extracting(t -> t.get("id")).containsExactly("arch-a", "arch-a", "arch-b", "arch-c", "arch-d");
        assertThat(topics.get(0).get("file")).isEqualTo("topics/architecture/a.adoc");
        assertThat(topics.get(1).get("file")).isEqualTo("topics/broken/e.adoc");
        assertThat(topics.get(0).keySet()).startsWith("id", "file", "title", "type", "keywords", "status", "char-count");
        assertThat((List<?>) model.get("assemblies")).hasSize(1);
        assertThat((List<?>) model.get("other-files")).isEmpty();
    }

    @Test
    void model_withSeveralRoots_prefixesFilesWithTheirRoot(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Files.createDirectories(root.resolve("extra"));
        Files.writeString(root.resolve("extra/x.adoc"), topic("ops-x", ":topic-status: draft\n", "ops-x"));
        Map<String, Object> model = TopicRegistry.model(
                List.of(TopicRegistry.scan(root.resolve("topics"), 100), TopicRegistry.scan(root.resolve("extra"), 100)),
                dir, Instant.now());
        String yaml = TopicRegistry.yaml(model);

        assertThat(model.get("roots")).isEqualTo(List.of("topics", "extra"));
        assertThat(yaml).contains("file: topics/architecture/a.adoc").contains("file: extra/x.adoc")
                .contains("- id: arch\n").contains("- id: ops\n").contains("topic-count: 6");
        assertThat((List<?>) model.get("findings")).anySatisfy(f -> assertThat(String.valueOf(f))
                .startsWith("topics/broken/e.adoc: duplicate id 'arch-a'"));
    }

    @Test
    void tree_listsDirectoriesFilesAndCounts(@TempDir Path dir) throws IOException {
        TopicRegistry.Scan scan = TopicRegistry.scan(corpus(dir), 100);
        List<String> tree = TopicRegistry.tree(scan, dir);

        assertThat(tree.get(0)).isEqualTo(".");
        assertThat(tree).anySatisfy(l -> assertThat(l).contains("index.adoc").contains("assembly, 3 includes"));
        assertThat(tree).contains("  topics/architecture/");
        assertThat(tree).anySatisfy(l -> assertThat(l).contains("a.adoc").contains("arch-a").contains("reference").contains("published"));
    }

    @Test
    void add_insertsNewFilesIntoAnExistingRegistryWithoutRescanning(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Path registryFile = dir.resolve("target/topic-registry.yaml");
        Files.writeString(registryFile, TopicRegistry.yaml(TopicRegistry.model(
                List.of(TopicRegistry.scan(root, 100)), dir, Instant.parse("2026-09-17T12:00:00Z"))));
        Files.writeString(root.resolve("topics/architecture/z.adoc"), topic("arch-z", ":topic-status: draft\n", "arch-z"));
        Files.createDirectories(root.resolve("topics/ops"));
        Files.writeString(root.resolve("topics/ops/m.adoc"), topic("ops-m", ":topic-status: draft\n", "ops-m"));
        Files.writeString(root.resolve("notes.adoc"), "== Working Notes\n\nText.\n");

        Map<String, Object> loaded = TopicRegistry.load(registryFile);
        List<TopicHeader> added = TopicRegistry.add(loaded, dir,
                List.of(Path.of("topics/architecture/z.adoc"), Path.of("topics/ops/m.adoc"), root.resolve("notes.adoc")),
                Instant.parse("2026-09-18T09:00:00Z"));
        String yaml = TopicRegistry.yaml(loaded);

        assertThat(added).extracting(TopicHeader::file)
                .containsExactly("topics/architecture/z.adoc", "topics/ops/m.adoc", "notes.adoc");
        assertThat(yaml)
                .contains("generated: '2026-09-18T09:00:00Z'")
                .contains("topic-count: 7").contains("file-count: 9")
                .contains("- id: ops\n").contains("- id: ops-m")
                .contains("file: notes.adoc").contains("title: Working Notes");
        assertThat(yaml.indexOf("- id: arch\n")).isLessThan(yaml.indexOf("- id: ops\n"));
        assertThat(yaml.indexOf("- id: arch-d")).isLessThan(yaml.indexOf("- id: arch-z"));
        assertThat(yaml.indexOf("- id: arch-z")).isLessThan(yaml.indexOf("- id: ops\n"));
        assertThat((List<?>) loaded.get("findings")).hasSize(3);   // the fixture's own three, untouched
    }

    @Test
    void add_replacesTheEntryForARereadFile_andChecksDuplicateIdsAgainstTheRegistry(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = TopicRegistry.model(List.of(TopicRegistry.scan(root, 100)), dir, Instant.now());

        Files.writeString(root.resolve("topics/architecture/a.adoc"), topic("arch-a", ":topic-status: review\n", "arch-a"));
        TopicRegistry.add(model, dir, List.of(Path.of("topics/architecture/a.adoc")), Instant.now());
        String yaml = TopicRegistry.yaml(model);
        assertThat(yaml).contains("topic-count: 5").contains("file-count: 6").contains("status: review");
        assertThat(yaml.split("file: topics/architecture/a.adoc", -1)).hasSize(2);

        Files.writeString(root.resolve("topics/architecture/b2.adoc"), topic("arch-b", ":topic-status: draft\n", "arch-b"));
        TopicRegistry.add(model, dir, List.of(Path.of("topics/architecture/b2.adoc")), Instant.now());
        assertThat((List<?>) model.get("findings")).anySatisfy(f -> assertThat(String.valueOf(f))
                .isEqualTo("topics/architecture/b2.adoc: duplicate id 'arch-b', already declared by topics/architecture/b.adoc"));
    }

    @Test
    void add_rejectsFilesOutsideTheRegistryRoots(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = TopicRegistry.model(List.of(TopicRegistry.scan(root.resolve("topics"), 100)), dir, Instant.now());
        Path outside = root.resolve("index.adoc");
        assertThatThrownBy(() -> TopicRegistry.add(model, dir, List.of(outside), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none of the registry's roots");
    }

    @Test
    void yaml_roundTripsThroughSnakeYamlUnchanged(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        Map<String, Object> model = TopicRegistry.model(List.of(TopicRegistry.scan(root, 100)), dir, Instant.parse("2026-09-17T12:00:00Z"));
        String once = TopicRegistry.yaml(model);
        Path file = dir.resolve("target/rt.yaml");
        Files.writeString(file, once);
        String twice = TopicRegistry.yaml(TopicRegistry.load(file));
        assertThat(twice).isEqualTo(once);
        assertThat(once).doesNotContain("\t");
    }

    @Test
    void load_rejectsAHandKeptRegistryWithoutRoots(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("topic-registry.yaml");
        Files.writeString(file, "registry-version: \"1.1\"\ndomains:\n  - id: arch\n    topics: []\n");
        assertThatThrownBy(() -> TopicRegistry.load(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a generated topic registry");
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
        odd.put("plain", "Public domain — US federal government work.");
        odd.put("count", 42L);
        odd.put("flag", true);
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        model.put("roots", List.of());
        model.put("domains", List.of());
        model.put("odd", odd);
        Path file = dir.resolve("odd.yaml");
        Files.writeString(file, TopicRegistry.yaml(model));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) TopicRegistry.load(file).get("odd");
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
        expected.put("plain", "Public domain — US federal government work.");
        expected.put("count", 42);
        expected.put("flag", true);
        assertThat(back).containsExactlyEntriesOf(expected);
        assertThat(TopicRegistry.scalar("Public domain — US federal government work.")).isEqualTo("Public domain — US federal government work.");
        assertThat(TopicRegistry.scalar("2026-09-17T12:00:00Z")).isEqualTo("'2026-09-17T12:00:00Z'");
        assertThat(TopicRegistry.scalar("missing: status")).isEqualTo("'missing: status'");
    }

    @Test
    void scan_reportsSymbolicLinksAndDoesNotFollowThem(@TempDir Path dir, @TempDir Path outside) throws IOException {
        Path root = corpus(dir);
        Path target = outside.resolve("secret.adoc");
        Files.writeString(target, topic("x-secret", ":topic-status: draft\n", "x-secret"));
        Files.createSymbolicLink(root.resolve("topics/linked.adoc"), target);

        TopicRegistry.Scan scan = TopicRegistry.scan(root, 100);

        assertThat(scan.topics()).extracting(TopicHeader::id).doesNotContain("x-secret");
        assertThat(scan.findings()).contains("topics/linked.adoc: symbolic link, skipped");
    }

    @Test
    void scan_failsPastMaxFiles(@TempDir Path dir) throws IOException {
        Path root = corpus(dir);
        assertThatThrownBy(() -> TopicRegistry.scan(root, 2))
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
    void parse_aPlaceholderMetadataMarkerIsAFindingAndNothingElseIs() {
        String header = ":topic-id: x\n:topic-type: reference\n:topic-status: review\n:topic-keywords: a\n";
        TopicHeader marked = TopicHeader.parse("x.adoc", header + ":topic-metadata: Placeholder\n\n[[x]]\n= X\n");
        assertThat(marked.findings()).singleElement().asString()
                .contains("placeholders").contains(":topic-metadata: placeholder");
        TopicHeader written = TopicHeader.parse("x.adoc", header + ":topic-metadata: reviewed\n\n[[x]]\n= X\n");
        assertThat(written.findings()).isEmpty();
        assertThat(TopicHeader.parse("x.adoc", header + "\n[[x]]\n= X\n").findings()).isEmpty();
    }

    @Test
    void placeholderTopics_listsTheMarkedTopicsOfARegistry(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("topics/dex"));
        String marked = ":topic-id: dex-a\n:topic-type: reference\n:topic-status: review\n:topic-keywords: a\n"
                + ":topic-metadata: placeholder\n\n[[dex-a]]\n= A\n";
        Files.writeString(root.resolve("a.adoc"), marked);
        Files.writeString(root.resolve("b.adoc"), marked.replace("dex-a", "dex-b").replace(
                ":topic-metadata: placeholder\n", ""));
        Path registry = dir.resolve("registry.yaml");
        Files.writeString(registry, TopicRegistry.yaml(TopicRegistry.model(
                List.of(TopicRegistry.scan(dir.resolve("topics"), 100)), dir, Instant.now())));

        assertThat(TopicRegistry.placeholderTopics(TopicRegistry.load(registry))).containsExactly("dex-a");
    }

    @Test
    void parse_acceptsProcedureAsTaskType() {
        TopicHeader h = TopicHeader.parse("p.adoc",
                ":topic-id: ops-x\n:topic-type: procedure\n:topic-status: draft\n:topic-keywords: a\n\n[[ops-x]]\n= X\n");
        assertThat(h.findings()).isEmpty();
    }
}

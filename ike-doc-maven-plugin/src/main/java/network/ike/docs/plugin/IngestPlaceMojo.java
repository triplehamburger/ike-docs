package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestReport;
import network.ike.docs.ingest.IngestYaml;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Place the fragments written by {@code idoc:ingest-fragments} into a DeX
 * project: the topic files go into the topics module, the assembly becomes a
 * new assembly module (its {@code pom.xml}, its {@code .adoc}) and is
 * registered as a {@code <subproject>} of {@code assemblies/pom.xml}.
 *
 * <p>Nothing is placed when the fragment run recorded an error or when a fragment
 * file name is already used by a topic with a different id; the check is made
 * for every file before the first is written. A topic with the same file name
 * and id is replaced, which is how a re-ingest updates it. Run it from the
 * project's root, or point {@code ike.place.topicsDir} and
 * {@code ike.place.assembliesDir} at the right folders.
 *
 * <p>With {@code ike.place.dryRun=true} the goal reports what it would do and
 * writes nothing. {@code idoc:topic-registry} then validates the placed
 * topics, and {@code mvn verify -pl assemblies/MODULE -am} renders them.
 *
 * <p>Typical use, from the root of the project:
 *
 * <pre>
 *   mvn idoc:ingest-place -N -Dike.place.fragments=target/ingest/fragments
 * </pre>
 *
 * <p>Pass {@code -N} (non-recursive) in a multi-module project: a goal run from the command
 * line otherwise runs once in every module, and the defaults only fit the root.
 *
 * @since 145
 */
@Mojo(name = "ingest-place")
public class IngestPlaceMojo implements org.apache.maven.api.plugin.Mojo {

    private static final Pattern MODULE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String ASSEMBLY_PREFIX = "DeXRecord_";

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The output directory of {@code idoc:ingest-fragments}: holds {@code topics/dex}, {@code assembly} and {@code fragments.yaml}. */
    @Parameter(property = "ike.place.fragments", required = true)
    File fragments;

    /** The folder the topic files are copied into. */
    @Parameter(property = "ike.place.topicsDir",
               defaultValue = "${project.basedir}/topics/src/docs/asciidoc/topics/dex")
    File topicsDir;

    /** The folder holding {@code pom.xml} of the assemblies aggregator and one folder per assembly module. */
    @Parameter(property = "ike.place.assembliesDir", defaultValue = "${project.basedir}/assemblies")
    File assembliesDir;

    /** The assembly module folder to create or update; unset, it is the assembly file name without {@code DeXRecord_}. */
    @Parameter(property = "ike.place.module")
    String module;

    /** The value of the {@code :topics:} attribute written into the assembly. */
    @Parameter(property = "ike.place.topicsAttribute", defaultValue = "{generated}/topics-asciidoc")
    String topicsAttribute;

    /** Report what would be placed and write nothing. */
    @Parameter(property = "ike.place.dryRun", defaultValue = "false")
    boolean dryRun;

    /** Where {@code placed.yaml} is written. */
    @Parameter(property = "ike.place.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** Creates this goal instance. */
    public IngestPlaceMojo() {}

    /** What placing would do, worked out before anything is written. */
    private record Plan(List<Path> topicFiles, List<String> replaced, Path assemblySource, String assemblyBase,
                        String moduleName, Path moduleDir, boolean moduleExists, String parentGroup,
                        String parentArtifact) {
    }

    @Override
    public void execute() throws MojoException {
        Path out = outputDir.getAbsoluteFile().toPath();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        Plan plan = plan(findings, details);
        if (plan != null && findings.stream().noneMatch(Finding::isError)) {
            details.put("dry-run", dryRun);
            if (!dryRun) {
                apply(plan, findings);
            }
        }
        IngestReport report = new IngestReport("ingest-place", List.of(), details, findings, List.of());
        try {
            IngestYaml.writeAtomic(out.resolve("placed.yaml"), IngestYaml.reportToYaml(report));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-place could not write placed.yaml under " + out + ": "
                    + e.getMessage(), e);
        }
        for (Finding f : findings) {
            String line = "[" + f.check() + "] " + f.component() + ": " + f.message();
            switch (f.severity()) {
                case ERROR -> log.error(line);
                case WARNING -> log.warn(line);
                case INFO -> log.info(line);
            }
        }
        if (report.hasErrors()) {
            long errors = findings.stream().filter(Finding::isError).count();
            throw new MojoException("idoc:ingest-place found " + errors + " error(s); nothing was placed, see "
                    + out.resolve("placed.yaml"));
        }
        if (plan != null) {
            log.info("idoc:ingest-place: " + (dryRun ? "would place " : "placed ") + plan.topicFiles().size()
                    + " topic(s) into " + topicsDir + " and assembly module " + plan.moduleName() + " under "
                    + assembliesDir);
        }
    }

    /** Check everything that can stop placing and work out what to write; null when the inputs are unusable. */
    private Plan plan(List<Finding> findings, Map<String, Object> details) {
        Path frag = fragments.getAbsoluteFile().toPath();
        Path reportFile = frag.resolve("fragments.yaml");
        if (!Files.isRegularFile(reportFile)) {
            findings.add(Finding.error("fragments-missing", reportFile.toString(),
                    "no fragments.yaml; run idoc:ingest-fragments first and point ike.place.fragments at its output"));
            return null;
        }
        if (fragmentRunHadErrors(reportFile, findings)) {
            return null;
        }
        List<Path> topics = list(frag.resolve("topics").resolve("dex"), findings);
        List<Path> assemblies = list(frag.resolve("assembly"), findings);
        if (topics.isEmpty() || assemblies.size() != 1) {
            if (findings.stream().noneMatch(Finding::isError)) {
                findings.add(Finding.error("fragments-incomplete", frag.toString(),
                        "expected topics/dex/*.adoc and exactly one assembly/*.adoc, found " + topics.size()
                                + " topic(s) and " + assemblies.size() + " assembly file(s)"));
            }
            return null;
        }
        Path assembly = assemblies.get(0);
        String base = assembly.getFileName().toString().replaceFirst("\\.adoc$", "");
        String moduleName = module != null && !module.isBlank() ? module.strip()
                : base.startsWith(ASSEMBLY_PREFIX) ? base.substring(ASSEMBLY_PREFIX.length()) : base;
        if (!MODULE_NAME.matcher(moduleName).matches()) {
            findings.add(Finding.error("module-name-invalid", moduleName,
                    "an assembly module name may hold letters, digits, dots, hyphens and underscores only"));
            return null;
        }
        Path assembliesPath = assembliesDir.getAbsoluteFile().toPath();
        String[] parent = parentCoordinates(assembliesPath.resolve("pom.xml"), findings);
        if (parent == null) {
            return null;
        }
        Path moduleDir = assembliesPath.resolve(moduleName);
        List<String> replaced = new ArrayList<>();
        Path topicsPath = topicsDir.getAbsoluteFile().toPath();
        for (Path file : topics) {
            Path target = topicsPath.resolve(file.getFileName().toString());
            if (!Files.exists(target)) {
                continue;
            }
            String newId = topicId(file);
            String oldId = topicId(target);
            if (newId != null && newId.equals(oldId)) {
                replaced.add(file.getFileName().toString());
            } else {
                findings.add(Finding.error("file-name-clash", target.toString(),
                        "a topic with this file name exists with id " + oldId + ", not " + newId
                                + "; placing would overwrite a different topic"));
            }
        }
        details.put("topics", topics.size());
        details.put("topics-replaced", replaced.size());
        details.put("module", moduleName);
        details.put("topics-dir", topicsPath.toString());
        details.put("assembly-module", moduleDir.toString());
        return new Plan(topics, replaced, assembly, base, moduleName, moduleDir, Files.isDirectory(moduleDir),
                parent[0], parent[1]);
    }

    private void apply(Plan plan, List<Finding> findings) throws MojoException {
        try {
            Path topicsPath = topicsDir.getAbsoluteFile().toPath();
            for (Path file : plan.topicFiles()) {
                IngestYaml.writeAtomic(topicsPath.resolve(file.getFileName().toString()),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
            Path adocDir = plan.moduleDir().resolve("src").resolve("docs").resolve("asciidoc");
            IngestYaml.writeAtomic(adocDir.resolve(plan.assemblyBase() + ".adoc"),
                    withAttributes(Files.readString(plan.assemblySource(), StandardCharsets.UTF_8)));
            Path pom = plan.moduleDir().resolve("pom.xml");
            if (!Files.exists(pom)) {
                IngestYaml.writeAtomic(pom, pomFor(plan));
            }
            register(plan.moduleName(), assembliesDir.getAbsoluteFile().toPath().resolve("pom.xml"), findings);
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-place could not write the project: " + e.getMessage(), e);
        }
        if (!plan.replaced().isEmpty()) {
            findings.add(Finding.info("topics-replaced", "topics", plan.replaced().size()
                    + " topic(s) had the same file name and id and were replaced"));
        }
        if (plan.moduleExists()) {
            findings.add(Finding.info("assembly-module-exists", plan.moduleName(),
                    "the assembly module exists; its assembly .adoc was replaced and its pom.xml kept"));
        }
    }

    private boolean fragmentRunHadErrors(Path reportFile, List<Finding> findings) {
        try {
            Object loaded = new Yaml().load(Files.readString(reportFile, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> map && map.get("findings") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> f && "ERROR".equals(String.valueOf(f.get("severity")))) {
                        findings.add(Finding.error("fragments-failed", reportFile.toString(),
                                "idoc:ingest-fragments recorded an error, so nothing is placed; fix it and run it again"));
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            findings.add(Finding.error("fragments-unreadable", reportFile.toString(),
                    "cannot read fragments.yaml: " + e.getMessage()));
            return true;
        }
    }

    private static List<Path> list(Path dir, List<Finding> findings) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".adoc")).sorted().toList();
        } catch (IOException e) {
            findings.add(Finding.error("fragments-unreadable", dir.toString(), "cannot list: " + e.getMessage()));
            return List.of();
        }
    }

    /** The {@code :topic-id:} of a topic file, or null. */
    static String topicId(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> l.startsWith(":topic-id:")).map(l -> l.substring(":topic-id:".length()).strip())
                    .findFirst().orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The group id and artifact id a new assembly module names as its parent: the assemblies aggregator. */
    private static String[] parentCoordinates(Path pom, List<Finding> findings) {
        if (!Files.isRegularFile(pom)) {
            findings.add(Finding.error("assemblies-pom-missing", pom.toString(),
                    "no pom.xml in the assemblies folder; run from the project root or set ike.place.assembliesDir"));
            return null;
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            Element project = doc.getDocumentElement();
            String artifact = childText(project, "artifactId");
            String group = childText(project, "groupId");
            if (group == null) {
                Element parent = child(project, "parent");
                group = parent == null ? null : childText(parent, "groupId");
            }
            if (artifact == null || group == null) {
                findings.add(Finding.error("assemblies-pom-unreadable", pom.toString(),
                        "cannot find the group id and artifact id of the assemblies pom"));
                return null;
            }
            return new String[] {group, artifact};
        } catch (Exception e) {
            findings.add(Finding.error("assemblies-pom-unreadable", pom.toString(), e.getMessage()));
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName() != null ? e.getLocalName() : e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().strip();
    }

    /** The assembly with the book attributes the project's assemblies use, unless it already sets {@code :topics:}. */
    String withAttributes(String assembly) {
        if (assembly.lines().anyMatch(l -> l.startsWith(":topics:"))) {
            return assembly;
        }
        int eol = assembly.indexOf('\n');
        if (eol < 0) {
            return assembly;
        }
        String attributes = ":doctype: book\n:toc: left\n:toclevels: 2\n:icons: font\n:topics: " + topicsAttribute
                + "\n:imagesdir: images\n";
        return assembly.substring(0, eol + 1) + attributes + assembly.substring(eol + 1);
    }

    /** A {@code pom.xml} for a new assembly module, in the shape of the existing assembly modules. */
    private static String pomFor(Plan plan) {
        String[] lines = {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<project xmlns=\"http://maven.apache.org/POM/4.1.0\"",
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
            "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.1.0",
            "         http://maven.apache.org/xsd/maven-4.1.0.xsd\">",
            "    <modelVersion>4.1.0</modelVersion>",
            "",
            "    <parent>",
            "        <groupId>" + plan.parentGroup() + "</groupId>",
            "        <artifactId>" + plan.parentArtifact() + "</artifactId>",
            "    </parent>",
            "",
            "    <artifactId>" + plan.assemblyBase() + "</artifactId>",
            "    <packaging>pom</packaging>",
            "",
            "    <name>" + plan.moduleName() + "</name>",
            "    <description>Assembly of the topics ingested for " + plan.moduleName() + ".</description>",
            "",
            "    <properties>",
            "        <pdf.source.document>" + plan.assemblyBase() + "</pdf.source.document>",
            "    </properties>",
            "",
            "    <dependencies>",
            "        <dependency>",
            "            <groupId>${project.groupId}</groupId>",
            "            <artifactId>topics</artifactId>",
            "            <version>${project.version}</version>",
            "            <classifier>adoc</classifier>",
            "            <type>zip</type>",
            "        </dependency>",
            "    </dependencies>",
            "",
            "</project>",
            ""};
        return String.join("\n", lines);
    }

    /** Add the module to the aggregator's {@code <subprojects>} (or {@code <modules>}) when it is not listed. */
    static void register(String moduleName, Path assembliesPom, List<Finding> findings) throws IOException {
        String text = Files.readString(assembliesPom, StandardCharsets.UTF_8);
        for (String[] tags : new String[][] {{"subprojects", "subproject"}, {"modules", "module"}}) {
            if (!text.contains("</" + tags[0] + ">")) {
                continue;
            }
            if (text.contains("<" + tags[1] + ">" + moduleName + "</" + tags[1] + ">")) {
                return;
            }
            String updated = Pattern.compile("(\\s*)</" + tags[0] + ">").matcher(text)
                    .replaceFirst(m -> java.util.regex.Matcher.quoteReplacement(
                            m.group(1) + "    <" + tags[1] + ">" + moduleName + "</" + tags[1] + ">"
                                    + m.group(1) + "</" + tags[0] + ">"));
            IngestYaml.writeAtomic(assembliesPom, updated);
            findings.add(Finding.info("module-registered", moduleName,
                    "added to <" + tags[0] + "> of " + assembliesPom.getFileName()));
            return;
        }
        findings.add(Finding.warning("module-not-registered", assembliesPom.toString(),
                "no <subprojects> or <modules> element found; add <subproject>" + moduleName + "</subproject> by hand"));
    }
}

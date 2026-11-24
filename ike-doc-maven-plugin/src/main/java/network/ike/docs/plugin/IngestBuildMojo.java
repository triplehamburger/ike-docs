package network.ike.docs.plugin;

import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.IngestReport;
import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.ingest.ToolRunner;
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
 * Put the fragments written by {@code idoc:ingest-split} into a DeX project,
 * check the topics there, and render the result. Stage 3 of the ingestion.
 *
 * <p>Three steps run in order:
 *
 * <ol>
 *   <li>{@code place}: the topic files go into the topics module, the assembly
 *       becomes a new assembly module (its {@code pom.xml}, its {@code .adoc})
 *       and is registered as a {@code <subproject>} of
 *       {@code assemblies/pom.xml}. Nothing is placed when the split recorded
 *       an error or when a fragment file name is already used by a topic with
 *       a different id; the check is made for every file before the first is
 *       written. A topic with the same file name and id is replaced, which is
 *       how a re-ingest updates it. {@code ike.build.dryRun=true} reports what
 *       would be placed, writes nothing and skips the other steps.</li>
 *   <li>{@code registry}: {@code idoc:topic-registry} reads every topic header
 *       of the project and fails on findings. It runs as a child Maven in the
 *       project, and its registry is written under the ingest output
 *       directory, not under the project's {@code target}, so the clean of the
 *       next step cannot remove it.</li>
 *   <li>{@code render}: {@code mvn clean verify -pl assemblies/MODULE -am} in
 *       the project, as a child Maven.</li>
 * </ol>
 *
 * <p>A child Maven is the running Maven's own launcher unless
 * {@code ike.build.mavenCommand} says otherwise; {@code ike.build.mavenArgs}
 * adds arguments to both runs. Child processes start from an argument list,
 * never through a shell, with a time limit; their full output is written to
 * {@code build-registry.log} and {@code build-render.log} under the output
 * directory. {@code ike.ingest.steps} runs some steps alone.
 *
 * <p>Typical use:
 *
 * <pre>
 *   mvn idoc:ingest-build -N -Dike.build.project=../dex -Dike.build.fragments=target/fragments
 * </pre>
 *
 * <p>Pass {@code -N} (non-recursive) in a multi-module project: a goal run from the command
 * line otherwise runs once in every module.
 *
 * @since 145
 */
@Mojo(name = "ingest-build")
public class IngestBuildMojo implements org.apache.maven.api.plugin.Mojo {

    private static final Pattern MODULE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String ASSEMBLY_PREFIX = "DeXRecord_";

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The output directory of {@code idoc:ingest-split}: holds {@code topics/dex}, {@code assembly} and {@code fragments.yaml}. */
    @Parameter(property = "ike.build.fragments")
    File fragments;

    /** The DeX project to place into and render; its root holds {@code topics} and {@code assemblies}. */
    @Parameter(property = "ike.build.project", defaultValue = "${project.basedir}")
    File projectDir;

    /** The folder the topic files are copied into; unset, {@code topics/src/docs/asciidoc/topics/dex} in the project. */
    @Parameter(property = "ike.build.topicsDir")
    File topicsDir;

    /** The folder holding {@code pom.xml} of the assemblies aggregator and one folder per assembly module; unset, {@code assemblies} in the project. */
    @Parameter(property = "ike.build.assembliesDir")
    File assembliesDir;

    /** The assembly module folder to create or update; unset, it is the assembly file name without {@code DeXRecord_}. */
    @Parameter(property = "ike.build.module")
    String module;

    /** The value of the {@code :topics:} attribute written into the assembly. */
    @Parameter(property = "ike.build.topicsAttribute", defaultValue = "{generated}/topics-asciidoc")
    String topicsAttribute;

    /** Report what would be placed and write nothing; the registry and render steps are then skipped. */
    @Parameter(property = "ike.build.dryRun", defaultValue = "false")
    boolean dryRun;

    /** Where {@code placed.yaml}, {@code topic-registry.yaml} and the child Maven logs are written. */
    @Parameter(property = "ike.ingest.outputDir", defaultValue = "${project.build.directory}/ingest")
    File outputDir;

    /** The steps to run, comma-separated, from {@code place}, {@code registry} and {@code render}; unset, all run in that order. */
    @Parameter(property = "ike.ingest.steps")
    String steps;

    /** The Maven command for the child runs, which may carry arguments; unset, the running Maven's own launcher. */
    @Parameter(property = "ike.build.mavenCommand")
    String mavenCommand;

    /** Extra arguments for both child Maven runs, for example {@code -o}; split on spaces. */
    @Parameter(property = "ike.build.mavenArgs")
    String mavenArgs;

    /** The plugin whose {@code topic-registry} goal the registry step runs; add {@code :VERSION} to pin it. */
    @Parameter(property = "ike.build.plugin", defaultValue = "network.ike.docs:ike-doc-maven-plugin")
    String plugin;

    /** The minutes each child Maven may run before it is killed. */
    @Parameter(property = "ike.build.timeoutMinutes", defaultValue = "30")
    int timeoutMinutes;

    /** The steps of this goal, in run order. */
    static final List<String> STEPS = List.of("place", "registry", "render");

    /** Creates this goal instance. */
    public IngestBuildMojo() {}

    /** What placing would do, worked out before anything is written. */
    private record Plan(List<Path> topicFiles, List<String> replaced, Path assemblySource, String assemblyBase,
                        String moduleName, Path moduleDir, boolean moduleExists, String parentGroup,
                        String parentArtifact) {
    }

    @Override
    public void execute() throws MojoException {
        List<String> toRun = stepsToRun(steps);
        Path project = projectDir == null ? null : projectDir.getAbsoluteFile().toPath();
        if (topicsDir == null && project != null) {
            topicsDir = project.resolve("topics/src/docs/asciidoc/topics/dex").toFile();
        }
        if (assembliesDir == null && project != null) {
            assembliesDir = project.resolve("assemblies").toFile();
        }
        String placedModule = null;
        for (String step : toRun) {
            log.info("idoc:ingest-build: step " + step);
            switch (step) {
                case "place" -> {
                    placedModule = place();
                    if (dryRun) {
                        log.info("idoc:ingest-build: dry run, so the registry and render steps are skipped");
                        return;
                    }
                }
                case "registry" -> registry(project);
                case "render" -> render(project, placedModule);
                default -> throw new MojoException("idoc:ingest-build: unknown step " + step);
            }
        }
    }

    /**
     * The steps to run for a {@code steps} value, in run order whatever order
     * they were named in.
     *
     * @param steps the requested steps, comma-separated, or null or blank for all
     * @return the steps in run order
     * @throws MojoException when a name is not a step
     */
    static List<String> stepsToRun(String steps) throws MojoException {
        if (steps == null || steps.isBlank()) {
            return STEPS;
        }
        List<String> named = new ArrayList<>();
        for (String part : steps.split(",")) {
            String step = part.strip().toLowerCase(java.util.Locale.ROOT);
            if (!STEPS.contains(step)) {
                throw new MojoException("idoc:ingest-build: ike.ingest.steps must name steps from " + STEPS
                        + ", not '" + part.strip() + "'");
            }
            named.add(step);
        }
        return STEPS.stream().filter(named::contains).toList();
    }

    /** The topic registry check: {@code idoc:topic-registry} in the project, as a child Maven. */
    private void registry(Path project) throws MojoException {
        requireProject(project, "registry");
        Path out = outputDir.getAbsoluteFile().toPath();
        List<String> argv = maven();
        argv.addAll(List.of("-B", plugin + ":topic-registry", "-pl", "topics",
                "-Dike.topic-registry.output=" + out.resolve("topic-registry.yaml")));
        argv.addAll(ToolRunner.split(mavenArgs));
        child("topic registry check", argv, project, out.resolve("build-registry.log"));
    }

    /** The render: {@code mvn clean verify} for the assembly module and what it needs, as a child Maven. */
    private void render(Path project, String placedModule) throws MojoException {
        requireProject(project, "render");
        String moduleName = placedModule != null ? placedModule : module != null ? module.strip() : "";
        if (!MODULE_NAME.matcher(moduleName).matches()) {
            throw new MojoException("idoc:ingest-build needs ike.build.module, the assembly module to render, "
                    + "when the render step runs without the place step");
        }
        List<String> argv = maven();
        argv.addAll(List.of("-B", "clean", "verify", "-pl", "assemblies/" + moduleName, "-am"));
        argv.addAll(ToolRunner.split(mavenArgs));
        child("render", argv, project, outputDir.getAbsoluteFile().toPath().resolve("build-render.log"));
    }

    private static void requireProject(Path project, String step) throws MojoException {
        if (project == null || !Files.isDirectory(project)) {
            throw new MojoException("idoc:ingest-build: the " + step + " step needs ike.build.project, the DeX "
                    + "project folder, and " + project + " is not a folder");
        }
    }

    /** The Maven command for a child run, as a list that can be added to. */
    private List<String> maven() {
        if (mavenCommand != null && !mavenCommand.isBlank()) {
            return new ArrayList<>(ToolRunner.split(mavenCommand));
        }
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "mvn.cmd" : "mvn";
        String home = System.getProperty("maven.home");
        if (home != null) {
            Path launcher = Path.of(home, "bin", exe);
            if (Files.isRegularFile(launcher)) {
                return new ArrayList<>(List.of(launcher.toString()));
            }
        }
        return new ArrayList<>(List.of(exe));
    }

    /** Run a child Maven in the project, keep its full output in a log, and fail with the end of it. */
    private void child(String what, List<String> argv, Path project, Path logFile) throws MojoException {
        if (timeoutMinutes < 1) {
            throw new MojoException("idoc:ingest-build: ike.build.timeoutMinutes must be at least 1, not "
                    + timeoutMinutes);
        }
        log.info("idoc:ingest-build: running " + String.join(" ", argv) + " in " + project);
        ToolRunner.Result result;
        try {
            result = ToolRunner.run(argv, project, Map.of(), java.time.Duration.ofMinutes(timeoutMinutes),
                    line -> log.info("  " + line));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-build cannot start Maven for the " + what + " ('" + argv.get(0)
                    + "'): " + e.getMessage() + "; set -Dike.build.mavenCommand", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("idoc:ingest-build was interrupted during the " + what, e);
        }
        try {
            IngestYaml.writeAtomic(logFile, result.output());
        } catch (IOException e) {
            log.warn("idoc:ingest-build could not write " + logFile + ": " + e.getMessage());
        }
        if (result.exitCode() != 0) {
            String[] lines = result.output().strip().split("\\R");
            String tail = String.join(" | ", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 3),
                    lines.length));
            throw new MojoException("idoc:ingest-build: the " + what + (result.timedOut()
                    ? " ran longer than " + timeoutMinutes + " minute(s) and was stopped"
                    : " failed with exit code " + result.exitCode()) + "; see " + logFile + "; last output: " + tail);
        }
    }

    /** The place step; returns the assembly module name, or null when the inputs were unusable. */
    private String place() throws MojoException {
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
        IngestReport report = new IngestReport("ingest-build", List.of(), details, findings, List.of());
        try {
            IngestYaml.writeAtomic(out.resolve("placed.yaml"), IngestYaml.reportToYaml(report));
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-build could not write placed.yaml under " + out + ": "
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
            throw new MojoException("idoc:ingest-build found " + errors + " error(s); nothing was placed, see "
                    + out.resolve("placed.yaml"));
        }
        if (plan != null) {
            log.info("idoc:ingest-build: " + (dryRun ? "would place " : "placed ") + plan.topicFiles().size()
                    + " topic(s) into " + topicsDir + " and assembly module " + plan.moduleName() + " under "
                    + assembliesDir);
        }
        return plan == null ? null : plan.moduleName();
    }

    /** Check everything that can stop placing and work out what to write; null when the inputs are unusable. */
    private Plan plan(List<Finding> findings, Map<String, Object> details) {
        if (fragments == null) {
            findings.add(Finding.error("fragments-missing", "ike.build.fragments",
                    "the place step needs ike.build.fragments, the output folder of idoc:ingest-split"));
            return null;
        }
        Path frag = fragments.getAbsoluteFile().toPath();
        Path reportFile = frag.resolve("fragments.yaml");
        if (!Files.isRegularFile(reportFile)) {
            findings.add(Finding.error("fragments-missing", reportFile.toString(),
                    "no fragments.yaml; run idoc:ingest-split first and point ike.build.fragments at its output"));
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
            throw new MojoException("idoc:ingest-build could not write the project: " + e.getMessage(), e);
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
                                "idoc:ingest-split recorded an error, so nothing is placed; fix it and run it again"));
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
                    "no pom.xml in the assemblies folder; set ike.build.project or ike.build.assembliesDir"));
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

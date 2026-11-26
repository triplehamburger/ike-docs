package network.ike.docs.plugin;

import network.ike.docs.ingest.DexRegistration;
import network.ike.docs.ingest.Finding;
import network.ike.docs.ingest.GitState;
import network.ike.docs.ingest.ImageReferences;
import network.ike.docs.ingest.IngestReport;
import network.ike.docs.ingest.IngestYaml;
import network.ike.docs.ingest.PathJail;
import network.ike.docs.ingest.PomModules;
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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 *       becomes a new assembly module and is registered as a
 *       {@code <subproject>} of {@code assemblies/pom.xml}. The module folder,
 *       its {@code artifactId}, its {@code .adoc} and
 *       {@code pdf.source.document} are all the assembly's name
 *       ({@code DeXRecord_K}). Images the assembly refers to are copied to the
 *       module's {@code images} folder, images a topic refers to to
 *       {@code topics/images/dex/K}; the references are rewritten in the
 *       placed copies only. Three more files are generated and rewritten on
 *       every run, so nobody edits a hand-owned file to register the record:
 *       {@code topics/dex/_includes-K.adoc}, the topics module's
 *       {@code index-dex-records.adoc} and the {@code DeXRecords} compendium
 *       module (listed as a {@code <subproject>} too). Nothing is placed when
 *       the split recorded an error, when the project has uncommitted changes
 *       (unless {@code ike.build.allowDirty=true}), when an image is missing
 *       or escapes its folder, when a fragment file name is already used by a
 *       topic with a different id, or when a fragment id is used by another
 *       topic file; every check is made before the first file is written. A
 *       topic with the same file name and id is replaced, which is how a
 *       re-ingest updates it. {@code placed.yaml} lists every file written, to
 *       commit. {@code ike.build.dryRun=true} reports what would be placed,
 *       writes nothing and skips the other steps.</li>
 *   <li>{@code registry}: {@code idoc:topic-registry} reads every topic header
 *       of the project and fails on findings. It runs as a child Maven in the
 *       project, and its registry is written under the ingest output
 *       directory, not under the project's {@code target}, so the clean of the
 *       next step cannot remove it. A second run, from
 *       {@code index-dex-records.adoc} with {@code rootFile}, must reach every
 *       topic of the record.</li>
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

    @org.apache.maven.api.di.Inject
    org.apache.maven.api.plugin.Log log;

    /** The output directory of {@code idoc:ingest-split}: holds {@code topics/dex}, {@code assembly} and {@code fragments.yaml}. */
    @Parameter(property = "ike.build.fragments")
    File fragments;

    /** The DeX project to place into and render; its root holds {@code topics} and {@code assemblies}. */
    @Parameter(property = "ike.build.project", defaultValue = "${project.basedir}")
    File projectDir;

    /** The topics module's AsciiDoc root, which holds {@code index.adoc} and {@code images}; unset, {@code topics/src/docs/asciidoc} in the project. */
    @Parameter(property = "ike.build.topicsRoot")
    File topicsRoot;

    /** The folder the topic files are copied into, under the topics root; unset, {@code topics/dex} there. */
    @Parameter(property = "ike.build.topicsDir")
    File topicsDir;

    /** The folder the image references in the fragments are relative to; unset, the parent of the fragments folder, where {@code idoc:ingest-convert} wrote the AsciiDoc and its {@code images}. */
    @Parameter(property = "ike.build.sourceDir")
    File sourceDir;

    /** Place into a project that has uncommitted changes; they are listed as a warning instead of stopping the run. */
    @Parameter(property = "ike.build.allowDirty", defaultValue = "false")
    boolean allowDirty;

    /** The folder holding {@code pom.xml} of the assemblies aggregator and one folder per assembly module; unset, {@code assemblies} in the project. */
    @Parameter(property = "ike.build.assembliesDir")
    File assembliesDir;

    /** The assembly module folder to create or update. It must equal the assembly file name, so the folder, artifactId, {@code .adoc} and {@code pdf.source.document} agree; unset, that name. */
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

    /** An image file to copy into the project. */
    private record ImageCopy(Path from, Path to) {
    }

    /** What placing would do, worked out before anything is written. */
    private record Plan(Map<String, String> topicTexts, List<String> replaced, String assemblyText,
                        String assemblyBase, String recordId, List<String> fragmentOrder, String moduleName,
                        Path moduleDir, boolean moduleExists, String parentGroup, String parentArtifact,
                        List<ImageCopy> images) {
    }

    @Override
    public void execute() throws MojoException {
        List<String> toRun = stepsToRun(steps);
        Path project = projectDir == null ? null : projectDir.getAbsoluteFile().toPath();
        if (topicsRoot == null && project != null) {
            topicsRoot = project.resolve("topics/src/docs/asciidoc").toFile();
        }
        if (topicsDir == null && topicsRoot != null) {
            topicsDir = topicsRoot.toPath().resolve("topics/dex").toFile();
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

    /**
     * The topic registry check: {@code idoc:topic-registry} in the project, as a
     * child Maven, then the same goal from {@code index-dex-records.adoc} with
     * {@code rootFile}, which must reach every topic of the record.
     */
    private void registry(Path project) throws MojoException {
        requireProject(project, "registry");
        Path out = outputDir.getAbsoluteFile().toPath();
        List<String> argv = maven();
        argv.addAll(List.of("-B", plugin + ":topic-registry", "-pl", "topics",
                "-Dike.topic-registry.output=" + out.resolve("topic-registry.yaml")));
        argv.addAll(ToolRunner.split(mavenArgs));
        child("topic registry check", argv, project, out.resolve("build-registry.log"));
        Path index = topicsRoot == null ? null : topicsRoot.toPath().resolve(DexRegistration.INDEX_FILE);
        if (index == null || !Files.isRegularFile(index)) {
            log.info("idoc:ingest-build: no " + DexRegistration.INDEX_FILE + ", so the reachability check is skipped");
            return;
        }
        Path reached = out.resolve("topic-registry-dex-records.yaml");
        List<String> rooted = maven();
        rooted.addAll(List.of("-B", plugin + ":topic-registry", "-pl", "topics",
                "-Dike.topic-registry.rootFile=" + index.toAbsolutePath(),
                "-Dike.topic-registry.output=" + reached));
        rooted.addAll(ToolRunner.split(mavenArgs));
        child("reachability check from " + DexRegistration.INDEX_FILE, rooted, project,
                out.resolve("build-registry-dex-records.log"));
        List<String> missing = unreachedTopics(reached);
        if (!missing.isEmpty()) {
            throw new MojoException("idoc:ingest-build: " + DexRegistration.INDEX_FILE + " does not reach "
                    + missing.size() + " topic(s): " + String.join(", ", missing)
                    + "; run the place step again to rewrite it");
        }
    }

    /** The ids of the record's topics that the registry run from the index did not list. */
    private List<String> unreachedTopics(Path registryFile) throws MojoException {
        Set<String> expected = new LinkedHashSet<>();
        Path placed = fragments == null ? null : fragments.toPath().resolve("topics").resolve("dex");
        List<Finding> ignored = new ArrayList<>();
        for (Path file : placed != null && Files.isDirectory(placed) ? list(placed, ignored)
                : includedTopicFiles()) {
            String id = topicId(file);
            if (id != null) {
                expected.add(id);
            }
        }
        Set<String> reached = new LinkedHashSet<>();
        try {
            Object loaded = new Yaml().load(Files.readString(registryFile, StandardCharsets.UTF_8));
            if (loaded instanceof Map<?, ?> model && model.get("domains") instanceof List<?> domains) {
                for (Object d : domains) {
                    if (d instanceof Map<?, ?> domain && domain.get("topics") instanceof List<?> topics) {
                        for (Object t : topics) {
                            if (t instanceof Map<?, ?> topic && topic.get("id") != null) {
                                reached.add(String.valueOf(topic.get("id")));
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new MojoException("idoc:ingest-build cannot read " + registryFile + ": " + e.getMessage(), e);
        }
        expected.removeAll(reached);
        return new ArrayList<>(expected);
    }

    /** The topic files every generated {@code _includes-K.adoc} lists, for a registry check without fragments. */
    private List<Path> includedTopicFiles() throws MojoException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(topicsDir.toPath())) {
            for (Path includes : s.filter(p -> DexRegistration.recordIdOf(p.getFileName().toString()) != null)
                    .sorted().toList()) {
                for (String line : Files.readAllLines(includes, StandardCharsets.UTF_8)) {
                    if (line.startsWith("include::") && line.contains(".adoc[")) {
                        files.add(topicsDir.toPath().resolve(line.substring("include::".length(),
                                line.indexOf('['))));
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-build cannot read the includes files in " + topicsDir + ": "
                    + e.getMessage(), e);
        }
        return files;
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
                apply(plan, findings, details);
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
            log.info("idoc:ingest-build: " + (dryRun ? "would place " : "placed ") + plan.topicTexts().size()
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
        String moduleName = module != null && !module.isBlank() ? module.strip() : base;
        if (!MODULE_NAME.matcher(moduleName).matches()) {
            findings.add(Finding.error("module-name-invalid", moduleName,
                    "an assembly module name may hold letters, digits, dots, hyphens and underscores only"));
            return null;
        }
        if (!moduleName.equals(base)) {
            findings.add(Finding.error("module-name-mismatch", moduleName,
                    "the module folder, its artifactId, its .adoc and pdf.source.document must be the same name, "
                            + base + ", the name of the assembly file; ike.build.module says " + moduleName));
            return null;
        }
        Path topicsRootPath = topicsRoot.getAbsoluteFile().toPath().normalize();
        Path topicsPath = topicsDir.getAbsoluteFile().toPath().normalize();
        if (!topicsPath.startsWith(topicsRootPath) || topicsPath.equals(topicsRootPath)) {
            findings.add(Finding.error("topics-dir-outside-root", topicsPath.toString(),
                    "the topics folder must be inside the topics root " + topicsRootPath));
            return null;
        }
        Path assembliesPath = assembliesDir.getAbsoluteFile().toPath();
        String[] parent = parentCoordinates(assembliesPath.resolve("pom.xml"), findings);
        if (parent == null) {
            return null;
        }
        checkProjectClean(findings, details);
        String recordId = DexRegistration.recordId(base);
        Path moduleDir = assembliesPath.resolve(moduleName);
        Path sourceRoot = sourceDir != null ? sourceDir.getAbsoluteFile().toPath()
                : frag.toAbsolutePath().getParent();
        List<ImageCopy> images = new ArrayList<>();
        Set<Path> usedImages = new LinkedHashSet<>();
        Path moduleImages = moduleDir.resolve("src").resolve("docs").resolve("asciidoc").resolve("images");
        String assemblyText = readText(assembly, findings);
        List<String> order = DexRegistration.fragmentOrder(assemblyText);
        assemblyText = placeImages(assemblyText, "the assembly", sourceRoot, moduleImages, "", images, usedImages,
                findings);
        Map<String, String> topicTexts = new TreeMap<>();
        Path topicImages = topicsRootPath.resolve("images").resolve("dex").resolve(recordId);
        for (Path file : topics) {
            String text = readText(file, findings);
            topicTexts.put(file.getFileName().toString(), placeImages(text, file.getFileName().toString(),
                    sourceRoot, topicImages, "dex/" + recordId + "/", images, usedImages, findings));
        }
        reportUnreferencedImages(sourceRoot, usedImages, findings);
        for (String included : order) {
            if (!topicTexts.containsKey(included)) {
                findings.add(Finding.warning("assembly-include-missing", included,
                        "the assembly includes this fragment but topics/dex has no such file"));
            }
        }
        for (String name : topicTexts.keySet()) {
            if (!order.contains(name)) {
                findings.add(Finding.warning("fragment-not-in-assembly", name,
                        "this fragment is not included by the assembly; it is listed last in _includes-" + recordId
                                + ".adoc"));
            }
        }
        List<String> replaced = new ArrayList<>();
        Map<String, String> existingIds = existingTopicIds(topicsRootPath, topicsPath, topicTexts.keySet());
        for (Path file : topics) {
            String name = file.getFileName().toString();
            String newId = topicId(file);
            Path target = topicsPath.resolve(name);
            if (Files.exists(target)) {
                String oldId = topicId(target);
                if (newId != null && newId.equals(oldId)) {
                    replaced.add(name);
                } else {
                    findings.add(Finding.error("file-name-clash", target.toString(),
                            "a topic with this file name exists with id " + oldId + ", not " + newId
                                    + "; placing would overwrite a different topic"));
                }
            }
            if (newId != null && existingIds.containsKey(newId)) {
                findings.add(Finding.error("topic-id-clash", name, "the id " + newId + " is already used by "
                        + existingIds.get(newId) + "; two topics must not share an id"));
            }
        }
        details.put("topics", topics.size());
        details.put("topics-replaced", replaced.size());
        details.put("images", images.size());
        details.put("module", moduleName);
        details.put("record", recordId);
        details.put("topics-dir", topicsPath.toString());
        details.put("assembly-module", moduleDir.toString());
        List<String> fullOrder = new ArrayList<>(order);
        topicTexts.keySet().stream().filter(n -> !order.contains(n)).forEach(fullOrder::add);
        fullOrder.removeIf(n -> !topicTexts.containsKey(n));
        return new Plan(topicTexts, replaced, assemblyText, base, recordId, fullOrder, moduleName, moduleDir,
                Files.isDirectory(moduleDir), parent[0], parent[1], images);
    }

    /** A DeX project with uncommitted changes makes "files added" meaningless, so it stops the run unless allowed. */
    private void checkProjectClean(List<Finding> findings, Map<String, Object> details) {
        if (projectDir == null) {
            return;
        }
        try {
            var dirty = GitState.dirtyPaths(projectDir.getAbsoluteFile().toPath());
            if (dirty.isEmpty()) {
                findings.add(Finding.info("git-unavailable", projectDir.toString(),
                        "git could not report the project's state, so a dirty start is not checked"));
            } else if (!dirty.get().isEmpty()) {
                details.put("dirty-before", dirty.get());
                String shown = String.join(", ", dirty.get().stream().limit(5).toList())
                        + (dirty.get().size() > 5 ? " and " + (dirty.get().size() - 5) + " more" : "");
                String message = dirty.get().size() + " path(s) in the project have uncommitted changes (" + shown
                        + "), so what this run adds cannot be told apart; commit or stash them"
                        + (allowDirty ? "" : ", or set ike.build.allowDirty=true");
                findings.add(allowDirty ? Finding.warning("project-dirty", projectDir.toString(), message)
                        : Finding.error("project-dirty", projectDir.toString(), message));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            findings.add(Finding.error("project-dirty", projectDir.toString(), "interrupted while checking git"));
        }
    }

    private static String readText(Path file, List<Finding> findings) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            findings.add(Finding.error("fragments-unreadable", file.toString(), "cannot read: " + e.getMessage()));
            return "";
        }
    }

    /**
     * Rewrite the image references of one text for its new home and queue the
     * files to copy. A reference becomes the path inside {@code destRoot}
     * with {@code refPrefix} in front; the placed file therefore finds it from
     * the images folder its document sets.
     */
    private static String placeImages(String text, String owner, Path sourceRoot, Path destRoot, String refPrefix,
                                      List<ImageCopy> copies, Set<Path> used, List<Finding> findings) {
        PathJail from = PathJail.enforcing(sourceRoot);
        PathJail to = PathJail.enforcing(destRoot);
        for (String target : ImageReferences.targets(text)) {
            String inside = ImageReferences.insideImages(target);
            try {
                Path source = from.resolve(sourceRoot.resolve(target), findings);
                Path dest = to.resolve(destRoot.resolve(inside), findings);
                if (!Files.isRegularFile(source)) {
                    findings.add(Finding.error("image-missing", owner, "refers to the image " + target
                            + " but " + source + " is not a file"));
                    continue;
                }
                used.add(source);
                if (copies.stream().noneMatch(c -> c.to().equals(dest))) {
                    copies.add(new ImageCopy(source, dest));
                }
            } catch (AccessDeniedException e) {
                findings.add(Finding.error("image-path-escapes", owner, "the image path " + target
                        + " leads outside its folder: " + e.getMessage()));
            } catch (IOException e) {
                findings.add(Finding.error("image-unreadable", owner, "cannot resolve the image " + target + ": "
                        + e.getMessage()));
            }
        }
        return ImageReferences.rewrite(text, target -> refPrefix + ImageReferences.insideImages(target));
    }

    private static void reportUnreferencedImages(Path sourceRoot, Set<Path> used, List<Finding> findings) {
        Path images = sourceRoot.resolve("images");
        if (!Files.isDirectory(images)) {
            return;
        }
        try (Stream<Path> s = Files.walk(images)) {
            for (Path file : s.filter(Files::isRegularFile).sorted().toList()) {
                if (!used.contains(realOrSelf(file))) {
                    findings.add(Finding.warning("image-unreferenced", images.relativize(file).toString(),
                            "the converter wrote this image but no fragment or the assembly refers to it"));
                }
            }
        } catch (IOException e) {
            findings.add(Finding.warning("image-unreadable", images.toString(), "cannot list: " + e.getMessage()));
        }
    }

    private static Path realOrSelf(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            return file.toAbsolutePath().normalize();
        }
    }

    /** The topic ids already in the topics module, with the file each is in; the files this run replaces are left out. */
    private static Map<String, String> existingTopicIds(Path root, Path topicsPath, Set<String> replacing) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return ids;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path file : s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".adoc"))
                    .sorted().toList()) {
                boolean replaced = file.getParent().normalize().equals(topicsPath)
                        && replacing.contains(file.getFileName().toString());
                String id = replaced ? null : topicId(file);
                if (id != null) {
                    ids.putIfAbsent(id, root.relativize(file).toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            // an unreadable tree is reported by the registry step; the id check then sees what it can
        }
        return ids;
    }

    private void apply(Plan plan, List<Finding> findings, Map<String, Object> details) throws MojoException {
        List<String> written = new ArrayList<>();
        Path projectPath = projectDir == null ? null : projectDir.getAbsoluteFile().toPath();
        try {
            Path topicsPath = topicsDir.getAbsoluteFile().toPath();
            Path rootPath = topicsRoot.getAbsoluteFile().toPath();
            for (Map.Entry<String, String> topic : plan.topicTexts().entrySet()) {
                write(topicsPath.resolve(topic.getKey()), topic.getValue(), written);
            }
            for (ImageCopy image : plan.images()) {
                Files.createDirectories(image.to().getParent());
                Files.copy(image.from(), image.to(), StandardCopyOption.REPLACE_EXISTING);
                written.add(image.to().toString());
            }
            Path adocDir = plan.moduleDir().resolve("src").resolve("docs").resolve("asciidoc");
            write(adocDir.resolve(plan.assemblyBase() + ".adoc"), withAttributes(plan.assemblyText()), written);
            Path pom = plan.moduleDir().resolve("pom.xml");
            if (!Files.exists(pom)) {
                write(pom, pomFor(plan.parentGroup(), plan.parentArtifact(), plan.assemblyBase(), plan.moduleName(),
                        "Assembly of the topics ingested for " + plan.moduleName() + "."), written);
            }
            write(topicsPath.resolve(DexRegistration.includesFileName(plan.recordId())),
                    DexRegistration.includesFile(plan.recordId(), plan.fragmentOrder()), written);
            List<String> records = new ArrayList<>();
            try (Stream<Path> s = Files.list(topicsPath)) {
                s.map(p -> DexRegistration.recordIdOf(p.getFileName().toString())).filter(java.util.Objects::nonNull)
                        .sorted().forEach(records::add);
            }
            String fromRoot = rootPath.relativize(topicsPath).toString().replace('\\', '/');
            write(rootPath.resolve(DexRegistration.INDEX_FILE), DexRegistration.indexFile(fromRoot, records), written);
            Path assembliesPath = assembliesDir.getAbsoluteFile().toPath();
            Path compendium = assembliesPath.resolve(DexRegistration.COMPENDIUM_MODULE);
            write(compendium.resolve("src").resolve("docs").resolve("asciidoc")
                            .resolve(DexRegistration.COMPENDIUM_MODULE + ".adoc"),
                    DexRegistration.compendiumFile(fromRoot, records), written);
            if (!Files.exists(compendium.resolve("pom.xml"))) {
                write(compendium.resolve("pom.xml"), pomFor(plan.parentGroup(), plan.parentArtifact(),
                        DexRegistration.COMPENDIUM_MODULE, DexRegistration.COMPENDIUM_MODULE,
                        "Generated: every ingested DeX record."), written);
            }
            register(List.of(plan.moduleName(), DexRegistration.COMPENDIUM_MODULE), assembliesPath.resolve("pom.xml"),
                    findings, written);
        } catch (IOException e) {
            throw new MojoException("idoc:ingest-build could not write the project: " + e.getMessage(), e);
        }
        List<String> shown = new ArrayList<>();
        for (String path : written) {
            shown.add(projectPath != null && Path.of(path).startsWith(projectPath)
                    ? projectPath.relativize(Path.of(path)).toString().replace('\\', '/') : path);
        }
        details.put("written", shown);
        if (!plan.replaced().isEmpty()) {
            findings.add(Finding.info("topics-replaced", "topics", plan.replaced().size()
                    + " topic(s) had the same file name and id and were replaced"));
        }
        if (plan.moduleExists()) {
            findings.add(Finding.info("assembly-module-exists", plan.moduleName(),
                    "the assembly module exists; its assembly .adoc was replaced and its pom.xml kept"));
        }
    }

    /** Write a file the stage owns, and note it in the list of what was written. */
    private static void write(Path file, String text, List<String> written) throws IOException {
        IngestYaml.writeAtomic(file, text);
        written.add(file.toString());
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
    private static String pomFor(String parentGroup, String parentArtifact, String artifactId, String name,
                                 String description) {
        String[] lines = {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<project xmlns=\"http://maven.apache.org/POM/4.1.0\"",
            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
            "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.1.0",
            "         http://maven.apache.org/xsd/maven-4.1.0.xsd\">",
            "    <modelVersion>4.1.0</modelVersion>",
            "",
            "    <parent>",
            "        <groupId>" + parentGroup + "</groupId>",
            "        <artifactId>" + parentArtifact + "</artifactId>",
            "    </parent>",
            "",
            "    <artifactId>" + artifactId + "</artifactId>",
            "    <packaging>pom</packaging>",
            "",
            "    <name>" + name + "</name>",
            "    <description>" + description + "</description>",
            "",
            "    <properties>",
            "        <pdf.source.document>" + artifactId + "</pdf.source.document>",
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

    /** Add each module to the aggregator's {@code <subprojects>} (or {@code <modules>}) when it is not listed. */
    static void register(List<String> moduleNames, Path assembliesPom, List<Finding> findings, List<String> written)
            throws IOException {
        String text = Files.readString(assembliesPom, StandardCharsets.UTF_8);
        boolean changed = false;
        for (String moduleName : moduleNames) {
            PomModules.Result result = PomModules.add(text, moduleName);
            if (result.element() == null) {
                findings.add(Finding.warning("module-not-registered", assembliesPom.toString(),
                        "no <subprojects> or <modules> element found; add <subproject>" + moduleName
                                + "</subproject> by hand"));
                return;
            }
            if (result.changed()) {
                text = result.text();
                changed = true;
                findings.add(Finding.info("module-registered", moduleName,
                        "added to <" + result.element() + "> of " + assembliesPom.getFileName()
                                + "; commit the change"));
            }
        }
        if (changed) {
            write(assembliesPom, text, written);
        }
    }
}

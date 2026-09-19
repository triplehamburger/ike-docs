package network.ike.docs.plugin.ledger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Times each phase of a ledger build on a real directory, so a change to the
 * reading strategy is judged by numbers. Not a test: run it by hand.
 *
 * <pre>
 *   java -cp target/classes:target/test-classes:&lt;snakeyaml.jar&gt; \
 *        network.ike.docs.plugin.ledger.LedgerBenchmark &lt;root&gt; [repetitions]
 * </pre>
 *
 * <p>Reports the cold first run and the median of the warm runs, per phase,
 * in milliseconds. Phases are cumulative variants of the same work so the
 * cost of each step is the difference between neighbours.
 */
public final class LedgerBenchmark {

    private LedgerBenchmark() {
    }

    /**
     * Entry point.
     *
     * @param args the root to scan and an optional repetition count (default 7)
     * @throws IOException if the root cannot be read
     */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toRealPath();
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        List<Path> files = walk(root);
        long bytes = 0;
        for (Path f : files) {
            bytes += Files.size(f);
        }
        System.out.printf("root=%s files=%d bytes=%d reps=%d%n", root, files.size(), bytes, reps);
        System.out.printf("%-34s %9s %9s%n", "phase", "cold ms", "warm ms");

        time("walk only", reps, () -> walk(root).size());
        time("walk + readString (whole files)", reps, () -> {
            long n = 0;
            for (Path f : walk(root)) {
                n += Files.readString(f, StandardCharsets.UTF_8).length();
            }
            return n;
        });
        time("walk + header-only read", reps, () -> {
            long n = 0;
            for (Path f : walk(root)) {
                n += headerOnly(f).size();
            }
            return n;
        });
        time("walk + toRealPath per file (removed)", reps, () -> {
            long n = 0;
            for (Path f : walk(root)) {
                n += f.toRealPath().getNameCount();
            }
            return n;
        });
        time("walk + parse (current TopicHeader)", reps, () -> {
            long n = 0;
            for (Path f : walk(root)) {
                n += TopicHeader.parse(f, root).attributes().size();
            }
            return n;
        });
        time("walk + parse, parallel stream", reps, () ->
                walk(root).parallelStream().mapToLong(f -> {
                    try {
                        return TopicHeader.parse(f, root).attributes().size();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).sum());
        time("Ledger.scan (all of the above)", reps, () -> Ledger.scan(root, 1_000_000).topics().size());
        time("scan + model", reps, () ->
                Ledger.model(List.of(Ledger.scan(root, 1_000_000)), root, Instant.now()).size());
        time("scan + model + yaml (string-built)", reps, () ->
                Ledger.yaml(Ledger.model(List.of(Ledger.scan(root, 1_000_000)), root, Instant.now())).length());

        Path ledger = Files.createTempFile("ledger-bench", ".yaml");
        Files.writeString(ledger, Ledger.yaml(Ledger.model(List.of(Ledger.scan(root, 1_000_000)), root, Instant.now())));
        Path one = files.get(files.size() / 2);
        System.out.printf("ledger yaml bytes=%d%n", Files.size(ledger));
        time("yaml load", reps, () -> Ledger.load(ledger).size());
        time("yaml load + add 1 file", reps, () -> {
            Map<String, Object> m = Ledger.load(ledger);
            return Ledger.add(m, root, List.of(one), Instant.now()).size();
        });
        time("yaml load + add 1 + yaml dump", reps, () -> {
            Map<String, Object> m = Ledger.load(ledger);
            Ledger.add(m, root, List.of(one), Instant.now());
            return Ledger.yaml(m).length();
        });
        Files.deleteIfExists(ledger);
    }

    private interface Work {
        long run() throws IOException;
    }

    private static void time(String label, int reps, Work work) throws IOException {
        long[] ms = new long[reps];
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            work.run();
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long cold = ms[0];
        long[] warm = Arrays.copyOfRange(ms, 1, reps);
        Arrays.sort(warm);
        System.out.printf("%-34s %9d %9d%n", label, cold, warm.length == 0 ? cold : warm[warm.length / 2]);
    }

    private static List<Path> walk(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && Ledger.SKIPPED_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".adoc")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Read lines until the heading and its contiguous attribute block are past: the header-only strategy. */
    private static List<String> headerOnly(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean pastHeading = false;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (pastHeading && !line.startsWith(":")) {
                    break;
                }
                if (line.startsWith("= ") || line.startsWith("== ")) {
                    pastHeading = true;
                }
            }
        }
        return lines;
    }
}

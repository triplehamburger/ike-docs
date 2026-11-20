package network.ike.docs.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs an external command for an ingestion stage: the Python converter, the
 * fidelity check or a child Maven.
 *
 * <p>A command is always an argument list, never a string handed to a shell, so
 * a file name cannot inject a command. The run has a time limit, after which the
 * process is killed, and what it printed is kept up to a size cap.
 */
final class ToolRunner {

    /** The most output characters kept from one run, the newest ones. */
    static final int OUTPUT_CAP = 200_000;

    /**
     * What a run did.
     *
     * @param exitCode the process exit code, or -1 when it was killed on timeout
     * @param output   what the process printed, standard error included, newest part if long
     * @param timedOut whether the process was killed for running too long
     */
    record Result(int exitCode, String output, boolean timedOut) {
    }

    private ToolRunner() {
    }

    /**
     * Run a command and wait for it.
     *
     * @param command  the program and its arguments
     * @param workDir  the working directory, or null for the current one
     * @param env      environment variables to add to the inherited ones
     * @param timeout  how long to wait before killing the process
     * @param lines    receives each output line as it is printed
     * @return what the run did
     * @throws IOException          when the program cannot be started
     * @throws InterruptedException when the wait is interrupted; the process is killed
     */
    static Result run(List<String> command, Path workDir, Map<String, String> env, Duration timeout,
                      Consumer<String> lines) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        builder.environment().putAll(env);
        Process process = builder.start();
        StringBuilder kept = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.accept(line);
                    synchronized (kept) {
                        kept.append(line).append('\n');
                        if (kept.length() > OUTPUT_CAP) {
                            kept.delete(0, kept.length() - OUTPUT_CAP);
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process was killed; whatever was read is kept
            }
        });
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        reader.join(5_000);
        synchronized (kept) {
            return new Result(finished ? process.exitValue() : -1, kept.toString(), !finished);
        }
    }

    /**
     * Split a command setting into program and arguments. Parts are separated by
     * white space; a part may be wrapped in double quotes to hold spaces. There
     * is no escape character, so a Windows path keeps its backslashes.
     *
     * @param setting the command setting, for example {@code python utilities/convert.py}
     * @return the parts, empty for a blank setting
     */
    static List<String> split(String setting) {
        List<String> parts = new ArrayList<>();
        if (setting == null) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < setting.length(); i++) {
            char c = setting.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    parts.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            parts.add(current.toString());
        }
        return parts;
    }
}

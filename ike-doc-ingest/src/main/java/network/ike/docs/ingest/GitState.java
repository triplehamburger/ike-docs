package network.ike.docs.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Whether a project folder has changes git has not committed, so a stage that
/// writes into it can report what it added and a dirty start is noticed.
public final class GitState {

    private GitState() {
    }

    /// The paths `git status --porcelain` reports in a folder: modified,
    /// staged and untracked files that `.gitignore` does not hide.
    ///
    /// @param project the project folder
    /// @return the changed paths as git prints them, empty when the tree is clean, or empty
    ///         optional when git cannot say (not a repository, or git is not installed)
    /// @throws InterruptedException when the wait for git is interrupted
    public static Optional<List<String>> dirtyPaths(Path project) throws InterruptedException {
        ToolRunner.Result result;
        try {
            result = ToolRunner.run(List.of("git", "-C", project.toString(), "status", "--porcelain",
                    "--untracked-files=normal", "--", "."), null, Map.of(), Duration.ofSeconds(60), line -> { });
        } catch (IOException e) {
            return Optional.empty();
        }
        if (result.exitCode() != 0 || result.timedOut()) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        for (String line : result.output().split("\\R")) {
            if (line.length() > 3) {
                paths.add(line.substring(3).strip());
            }
        }
        return Optional.of(paths);
    }
}

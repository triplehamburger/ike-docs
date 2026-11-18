package network.ike.docs.ingest;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The result of one ingest goal, written to `report.yaml`.
///
/// Everything in it is deterministic: sources and plan entries are sorted
/// by path, findings are sorted, and goal-specific `details` are written
/// with sorted keys. There are no timestamps and no absolute paths.
///
/// @param goal     the goal that wrote the report, e.g. `ingest-detect`
/// @param sources  the input files and their hashes
/// @param details  goal-specific data, e.g. the classification
/// @param findings every problem found; an error fails the goal
/// @param plan     the files a draft staged, with their content hashes
public record IngestReport(String goal, List<Source> sources, Map<String, Object> details,
                           List<Finding> findings, List<PlanEntry> plan) {

    /// An input file.
    ///
    /// @param path   module-relative path, forward slashes
    /// @param sha256 hex SHA-256 of the file's bytes
    public record Source(String path, String sha256) {
        /// Validate the source.
        ///
        /// @throws IllegalArgumentException if either field is blank
        public Source {
            requireText(path, "source path");
            requireText(sha256, "source sha256");
        }
    }

    /// A staged output file.
    ///
    /// @param path   path relative to both the staging root and the module root
    /// @param sha256 hex SHA-256 of the staged file's bytes
    public record PlanEntry(String path, String sha256) {
        /// Validate the entry.
        ///
        /// @throws IllegalArgumentException if either field is blank
        public PlanEntry {
            requireText(path, "plan path");
            requireText(sha256, "plan sha256");
        }
    }

    /// Validate and canonicalize: copy the collections and sort them.
    ///
    /// @throws IllegalArgumentException if the goal is blank
    public IngestReport {
        requireText(goal, "goal");
        sources = sources == null ? List.of()
                : sources.stream().sorted(Comparator.comparing(Source::path)).toList();
        details = details == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        findings = findings == null ? List.of()
                : findings.stream().sorted(Comparator
                        .comparing(Finding::component)
                        .thenComparing(Finding::check)
                        .thenComparing(Finding::message)
                        .thenComparing(Finding::severity)).toList();
        plan = plan == null ? List.of()
                : plan.stream().sorted(Comparator.comparing(PlanEntry::path)).toList();
    }

    /// Whether any finding is an error.
    ///
    /// @return true if the goal should fail
    public boolean hasErrors() {
        return findings.stream().anyMatch(Finding::isError);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

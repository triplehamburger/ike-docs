package network.ike.docs.ingest;

import java.util.List;

/// Decides whether an ingestion stage may run a tool.
///
/// The policy has tiers (`GOAL-TREE-PLAN.md` section 6). This is tier 0:
/// {@link #tier0()} allows every tool and enforces nothing; it only notes a
/// tool that could not be resolved to a file, so the manifest shows the gap.
/// Tier 1 (a configured path is required, a version range) and tier 2 (a
/// checked-in hash allowlist) are further implementations of this interface,
/// chosen by the caller, so the stages need no change when they arrive.
public interface ToolTrustPolicy {

    /// The outcome of a decision.
    ///
    /// @param allowed  whether the tool may run
    /// @param findings what the policy found, recorded in the manifest; an ERROR
    ///                 finding is expected with {@code allowed} false
    record Decision(boolean allowed, List<Finding> findings) {

        /// Copy the findings so the decision is immutable.
        public Decision {
            findings = List.copyOf(findings);
        }
    }

    /// Decide whether a tool may run.
    ///
    /// @param tool the tool as resolved by {@link ToolIdentity#resolve}
    /// @return the decision
    Decision decide(ToolIdentity tool);

    /// Tier 0: allow every tool; note one that was not resolved to a file.
    ///
    /// @return the policy
    static ToolTrustPolicy tier0() {
        return tool -> tool.programSha256() == null
                ? new Decision(true, List.of(Finding.info("tool-unresolved", tool.program(),
                        "the program was not found as a file, so no hash is recorded for it")))
                : new Decision(true, List.of());
    }
}

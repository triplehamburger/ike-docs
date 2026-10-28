package network.ike.docs.ingest;

import java.util.List;

/// A per-kind validation of an answer to a {@link NeedsAi} work order.
///
/// A goal registers one check for each kind it issues. The check lists
/// every problem it finds, so one rejected answer reports all its faults
/// at once. An empty list means the answer is acceptable.
@FunctionalInterface
public interface AnswerCheck {

    /// Validate an answer.
    ///
    /// @param item  the work order being answered
    /// @param value the answer text
    /// @return every problem found; empty if the answer is acceptable
    List<String> validate(NeedsAi item, String value);
}

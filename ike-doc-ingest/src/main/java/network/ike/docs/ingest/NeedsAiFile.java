package network.ike.docs.ingest;

import java.util.Comparator;
import java.util.List;

/// The AI-facing file `needs-ai.yaml`: instructions stated once, then the
/// open work orders. Kept small so an answerer reads nothing else.
///
/// @param instructions how to answer, stated once for every item
/// @param items        the open work orders, sorted by target then id
public record NeedsAiFile(String instructions, List<NeedsAi> items) {

    /// Validate and sort the items by target, then id, so the file is
    /// deterministic and page work orders come out in page order.
    ///
    /// @throws IllegalArgumentException if the instructions are blank or an id repeats
    public NeedsAiFile {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("needs-ai instructions must not be blank");
        }
        instructions = instructions.strip();
        items = items == null ? List.of()
                : items.stream().sorted(Comparator.comparing(NeedsAi::target).thenComparing(NeedsAi::id)).toList();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (NeedsAi item : items) {
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("duplicate needs-ai id: " + item.id());
            }
        }
    }
}

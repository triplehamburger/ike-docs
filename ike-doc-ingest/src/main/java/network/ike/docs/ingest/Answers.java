package network.ike.docs.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Validates and merges the answers to open work orders.
///
/// The rules are all-or-nothing per item and never throw. Malformed YAML
/// accepts nothing; a bad item is rejected with a specific finding and
/// stays open while the valid items are still accepted; an answer whose id
/// no longer matches any open item (its input changed) is ignored with a
/// warning; an id answered twice is rejected as ambiguous.
public final class Answers {

    private static final String FILE = "answers.yaml";

    private Answers() {
    }

    /// The outcome of resolving answers against the open work orders.
    ///
    /// @param accepted  accepted answer text by item id, sorted by id
    /// @param remaining work orders still open, in their original order
    /// @param findings  every problem found while resolving
    public record Resolution(Map<String, String> accepted, List<NeedsAi> remaining,
                             List<Finding> findings) {
    }

    /// Resolve an `answers.yaml` text against the open work orders.
    ///
    /// @param open     the open work orders
    /// @param yaml     the contents of `answers.yaml`; null or blank means no answers
    /// @param checks   per-kind validations, keyed by {@link NeedsAi#kind()}
    /// @return the accepted answers, the items still open, and the findings
    public static Resolution resolve(List<NeedsAi> open, String yaml, Map<String, AnswerCheck> checks) {
        List<Finding> findings = new ArrayList<>();
        Map<String, String> accepted = new LinkedHashMap<>();
        if (yaml == null || yaml.isBlank()) {
            return new Resolution(accepted, List.copyOf(open), findings);
        }

        List<Entry> entries = new ArrayList<>();
        try {
            Object root = IngestYaml.load(yaml);
            if (root != null) {
                Map<String, Object> map = IngestYaml.asMap(root, FILE);
                List<Object> list = IngestYaml.asList(map.get("answers"), "answers");
                for (int i = 0; i < list.size(); i++) {
                    parseEntry(list.get(i), i, entries, findings);
                }
            }
        } catch (IOException e) {
            findings.add(Finding.error("answers-malformed", FILE, e.getMessage()));
            return new Resolution(accepted, List.copyOf(open), findings);
        }

        Map<String, NeedsAi> byId = new LinkedHashMap<>();
        open.forEach(item -> byId.put(item.id(), item));

        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new HashSet<>();
        for (Entry entry : entries) {
            if (!seen.add(entry.id())) {
                duplicated.add(entry.id());
            }
        }
        duplicated.stream().sorted().forEach(id -> findings.add(Finding.error("answer-duplicate", id,
                "answered more than once; all answers for this id were rejected")));

        Map<String, String> candidate = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (duplicated.contains(entry.id())) {
                continue;
            }
            NeedsAi item = byId.get(entry.id());
            if (item == null) {
                findings.add(Finding.warning("answer-stale", entry.id(),
                        "no open work order has this id; its input may have changed, so the answer was ignored"));
                continue;
            }
            if (entry.value().isBlank()) {
                findings.add(Finding.error("answer-empty", entry.id(), "the answer is empty"));
                continue;
            }
            AnswerCheck check = checks == null ? null : checks.get(item.kind());
            List<String> problems = check == null ? List.of() : check.validate(item, entry.value());
            if (!problems.isEmpty()) {
                for (String problem : problems) {
                    findings.add(Finding.error("answer-invalid", entry.id(), problem));
                }
                continue;
            }
            candidate.put(entry.id(), entry.value());
        }
        candidate.keySet().stream().sorted().forEach(id -> accepted.put(id, candidate.get(id)));

        List<NeedsAi> remaining = open.stream().filter(item -> !accepted.containsKey(item.id())).toList();
        return new Resolution(accepted, remaining, findings);
    }

    private record Entry(String id, String value) {
    }

    private static void parseEntry(Object raw, int index, List<Entry> entries, List<Finding> findings) {
        String where = "answers[" + index + "]";
        if (!(raw instanceof Map<?, ?> map)) {
            findings.add(Finding.error("answer-entry", where, "each answer must be a mapping with id and value"));
            return;
        }
        Object id = map.get("id");
        Object value = map.get("value");
        if (!(id instanceof String s) || s.isBlank()) {
            findings.add(Finding.error("answer-entry", where, "the answer has no text id"));
        } else if (!(value instanceof String text)) {
            findings.add(Finding.error("answer-entry", where,
                    "the value must be text; use a | block for multi-line answers"));
        } else {
            entries.add(new Entry(s.strip(), text.replace("\r\n", "\n").stripTrailing()));
        }
    }
}

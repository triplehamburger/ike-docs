package network.ike.docs.ingest;

import java.util.regex.Pattern;

/// One bounded task a tool could not decide, handed to an AI or a human.
///
/// The work order carries the minimum context needed and a description of
/// the exact answer expected. The answer comes back through
/// `answers.yaml`, keyed by {@link #id()}, and is validated by the tool
/// before it is merged. The id is a hash of the kind, target and context,
/// so an answer to changed input no longer matches and is ignored.
///
/// @param id       stable id, `{kind}-{12 hex}`, from {@link #of}
/// @param kind     kebab-case task kind, e.g. `transcribe-page`
/// @param target   what the answer applies to: a page, file or topic id
/// @param blocking whether publish must refuse while this item is open
/// @param context  the minimum input the answerer needs
/// @param expect   the exact shape of the answer the tool will accept
public record NeedsAi(String id, String kind, String target, boolean blocking,
                      String context, String expect) {

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /// Validate the work order.
    ///
    /// @throws IllegalArgumentException if any field is blank or the kind is not kebab-case
    public NeedsAi {
        id = requireText(id, "id");
        kind = requireText(kind, "kind");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("needs-ai kind must be lowercase kebab-case: " + kind);
        }
        target = requireText(target, "target");
        context = requireText(context, "context");
        expect = requireText(expect, "expect");
    }

    /// Build a work order with its stable id.
    ///
    /// @param kind     the task kind
    /// @param target   what the answer applies to
    /// @param blocking whether publish must wait for the answer
    /// @param context  the minimum input the answerer needs
    /// @param expect   the exact shape of the answer expected
    /// @return the work order
    public static NeedsAi of(String kind, String target, boolean blocking,
                             String context, String expect) {
        String hash = IngestHash.sha256Hex(kind + "\n" + target + "\n" + context);
        return new NeedsAi(kind + "-" + hash.substring(0, 12), kind, target, blocking, context, expect);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("needs-ai " + name + " must not be blank");
        }
        return value.strip();
    }
}

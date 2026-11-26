package network.ike.docs.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The images an AsciiDoc text refers to, and a way to point those references
/// somewhere else.
///
/// A reference is the target of an `image:` or `image::` macro. A target that
/// is a URL, a `file:` path, a data URI or holds an attribute reference
/// (`{imagesdir}`) is not a local file this class can place, so it is left out
/// of {@link #targets} and left alone by {@link #rewrite}.
public final class ImageReferences {

    private static final Pattern MACRO = Pattern.compile("(image::?)([^\\[\\s]+)(\\[)");

    private ImageReferences() {
    }

    /// Whether a macro target is a local file path.
    ///
    /// @param target the text between `image::` and `[`
    /// @return true when it names a file relative to an images folder
    public static boolean isLocal(String target) {
        return !target.contains("://") && !target.startsWith("file:") && !target.startsWith("data:")
                && !target.contains("{");
    }

    /// The local image targets in a text, each once, in order of first use.
    ///
    /// @param adoc the AsciiDoc text
    /// @return the targets as written
    public static List<String> targets(String adoc) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = MACRO.matcher(adoc);
        while (m.find()) {
            if (isLocal(m.group(2))) {
                found.add(m.group(2));
            }
        }
        return new ArrayList<>(found);
    }

    /// The text with each local image target replaced.
    ///
    /// @param adoc   the AsciiDoc text
    /// @param mapper gives the new target for an old one, or null to keep it
    /// @return the changed text
    public static String rewrite(String adoc, UnaryOperator<String> mapper) {
        return MACRO.matcher(adoc).replaceAll(m -> {
            String target = m.group(2);
            String replaced = isLocal(target) ? mapper.apply(target) : null;
            return Matcher.quoteReplacement(m.group(1) + (replaced == null ? target : replaced) + m.group(3));
        });
    }

    /// A target without its leading `images/`, the part that is relative to
    /// the images folder.
    ///
    /// @param target a target as written, such as `images/k1-image-01.jpg`
    /// @return the part inside the images folder, such as `k1-image-01.jpg`
    public static String insideImages(String target) {
        String normal = target.replace('\\', '/');
        return normal.startsWith("images/") ? normal.substring("images/".length()) : normal;
    }
}

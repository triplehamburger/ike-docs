package network.ike.docs.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Lists a module in the `<subprojects>` (or `<modules>`) of an aggregator
/// pom by editing its text, so the rest of the file, comments and line endings
/// included, stays as it was.
///
/// Elements inside XML comments are ignored: a commented-out
/// `</subprojects>` is not the one to insert before, and a commented-out
/// `<subproject>` does not count as listed.
public final class PomModules {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private PomModules() {
    }

    /// What adding a module did.
    ///
    /// @param text    the pom text after the change; the same text when nothing changed
    /// @param changed whether a line was added
    /// @param element the element the module is, or would be, listed in (`subprojects` or
    ///                `modules`), or null when the pom has neither
    public record Result(String text, boolean changed, String element) {
    }

    /// Add a module to the pom text when it is not listed.
    ///
    /// @param pom    the pom text
    /// @param module the module directory name
    /// @return the result
    public static Result add(String pom, String module) {
        String masked = mask(pom);
        for (String[] tags : new String[][] {{"subprojects", "subproject"}, {"modules", "module"}}) {
            Matcher close = Pattern.compile("([ \\t]*)</" + tags[0] + ">").matcher(masked);
            if (!close.find()) {
                continue;
            }
            if (masked.contains("<" + tags[1] + ">" + module + "</" + tags[1] + ">")) {
                return new Result(pom, false, tags[0]);
            }
            String eol = pom.contains("\r\n") ? "\r\n" : "\n";
            String indent = close.group(1);
            String line = indent + "    <" + tags[1] + ">" + module + "</" + tags[1] + ">" + eol;
            return new Result(pom.substring(0, close.start()) + line + pom.substring(close.start()),
                    true, tags[0]);
        }
        return new Result(pom, false, null);
    }

    /// The text with every comment replaced by spaces of the same length, so
    /// positions in it are positions in the original.
    private static String mask(String text) {
        Matcher m = COMMENT.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n' && out.charAt(i) != '\r') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }
}

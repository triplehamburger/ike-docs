package network.ike.docs.ingest;

import java.util.Objects;

/// One problem, or note, found while ingesting.
///
/// Ingest goals never let a raw exception escape: every failure is
/// reported as a finding with a named check, so a run always ends with a
/// complete list of what is wrong. An {@link Severity#ERROR} fails the
/// goal; a {@link Severity#WARNING} or {@link Severity#INFO} does not.
///
/// @param severity how serious the finding is
/// @param check    the kebab-case name of the check that produced it
/// @param component what it is about: a file path, page, topic id or item id
/// @param message  the finding, stated for a reader who will act on it
public record Finding(Severity severity, String check, String component, String message) {

    /// Finding severities.
    public enum Severity {
        /// The run must not continue to publish.
        ERROR,
        /// Worth attention; does not block.
        WARNING,
        /// Context for the reader.
        INFO
    }

    /// Validate the finding and flatten whitespace in its text so it
    /// always renders on one line.
    ///
    /// @throws NullPointerException     if the severity is null
    /// @throws IllegalArgumentException if check, component or message is blank
    public Finding {
        Objects.requireNonNull(severity, "severity");
        check = requireText(check, "check");
        component = requireText(component, "component");
        message = requireText(message, "message");
    }

    /// An {@link Severity#ERROR} finding.
    ///
    /// @param check     the check name
    /// @param component what it is about
    /// @param message   the finding text
    /// @return the finding
    public static Finding error(String check, String component, String message) {
        return new Finding(Severity.ERROR, check, component, message);
    }

    /// A {@link Severity#WARNING} finding.
    ///
    /// @param check     the check name
    /// @param component what it is about
    /// @param message   the finding text
    /// @return the finding
    public static Finding warning(String check, String component, String message) {
        return new Finding(Severity.WARNING, check, component, message);
    }

    /// An {@link Severity#INFO} finding.
    ///
    /// @param check     the check name
    /// @param component what it is about
    /// @param message   the finding text
    /// @return the finding
    public static Finding info(String check, String component, String message) {
        return new Finding(Severity.INFO, check, component, message);
    }

    /// Whether this finding fails a goal.
    ///
    /// @return true for {@link Severity#ERROR}
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finding " + name + " must not be blank");
        }
        return value.replaceAll("\\s+", " ").strip();
    }
}

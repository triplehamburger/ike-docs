package network.ike.docs.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswersTest {

    private static final NeedsAi P1 = NeedsAi.of("transcribe-page", "page-001", true, "image 1", "plain text");
    private static final NeedsAi P2 = NeedsAi.of("transcribe-page", "page-002", true, "image 2", "plain text");
    private static final NeedsAi T1 = NeedsAi.of("index-terms", "dex-k230440-summary", false, "text", "terms");

    private static String answers(String... entries) {
        return "answers:\n" + String.join("", entries);
    }

    private static String entry(String id, String value) {
        return "  - id: " + id + "\n    value: |\n      " + value.replace("\n", "\n      ") + "\n";
    }

    private static List<String> checks(Answers.Resolution r) {
        return r.findings().stream().map(Finding::check).toList();
    }

    @Test
    void noAnswersLeavesEverythingOpen() {
        Answers.Resolution blank = Answers.resolve(List.of(P1, P2), "  \n", Map.of());
        Answers.Resolution none = Answers.resolve(List.of(P1, P2), null, Map.of());

        assertThat(blank.remaining()).containsExactly(P1, P2);
        assertThat(none.remaining()).containsExactly(P1, P2);
        assertThat(blank.findings()).isEmpty();
        assertThat(none.accepted()).isEmpty();
    }

    @Test
    void validAnswersAreAcceptedAndOthersStayOpen() {
        Answers.Resolution r = Answers.resolve(List.of(P1, P2, T1),
                answers(entry(P2.id(), "second page\ntext"), entry(P1.id(), "first page")), Map.of());

        assertThat(r.accepted()).containsOnlyKeys(P1.id(), P2.id());
        assertThat(r.accepted().get(P2.id())).isEqualTo("second page\ntext");
        assertThat(r.remaining()).containsExactly(T1);
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void malformedYamlAcceptsNothing() {
        Answers.Resolution r = Answers.resolve(List.of(P1), "answers: [unclosed", Map.of());

        assertThat(r.accepted()).isEmpty();
        assertThat(r.remaining()).containsExactly(P1);
        assertThat(checks(r)).containsExactly("answers-malformed");
    }

    @Test
    void anAnswerForChangedInputIsIgnoredWithAWarning() {
        Answers.Resolution r = Answers.resolve(List.of(P1), answers(entry("transcribe-page-000000000000", "x")),
                Map.of());

        assertThat(r.accepted()).isEmpty();
        assertThat(r.remaining()).containsExactly(P1);
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo("answer-stale");
            assertThat(f.isError()).isFalse();
        });
    }

    @Test
    void anIdAnsweredTwiceIsRejectedEntirely() {
        Answers.Resolution r = Answers.resolve(List.of(P1, P2),
                answers(entry(P1.id(), "one"), entry(P1.id(), "again"), entry(P2.id(), "fine")), Map.of());

        assertThat(r.accepted()).containsOnlyKeys(P2.id());
        assertThat(r.remaining()).containsExactly(P1);
        assertThat(checks(r)).containsExactly("answer-duplicate");
    }

    @Test
    void anEmptyAnswerIsRejected() {
        Answers.Resolution r = Answers.resolve(List.of(P1), "answers:\n  - id: " + P1.id() + "\n    value: \"  \"\n",
                Map.of());

        assertThat(r.accepted()).isEmpty();
        assertThat(checks(r)).containsExactly("answer-empty");
    }

    @Test
    void aKindCheckRejectsOneItemAndKeepsTheRest() {
        AnswerCheck needsThreeLines = (item, value) ->
                value.lines().count() < 3 ? List.of("needs at least 3 lines", "must mention page") : List.of();

        Answers.Resolution r = Answers.resolve(List.of(P1, T1),
                answers(entry(P1.id(), "only one line"), entry(T1.id(), "term")),
                Map.of("transcribe-page", needsThreeLines));

        assertThat(r.accepted()).containsOnlyKeys(T1.id());
        assertThat(r.remaining()).containsExactly(P1);
        assertThat(checks(r)).containsExactly("answer-invalid", "answer-invalid");
    }

    @Test
    void malformedEntriesAreReportedWithTheirPosition() {
        String yaml = "answers:\n  - just a string\n  - id: " + P1.id() + "\n    value: 42\n  - value: x\n";

        Answers.Resolution r = Answers.resolve(List.of(P1), yaml, Map.of());

        assertThat(r.accepted()).isEmpty();
        assertThat(r.findings()).extracting(Finding::component)
                .containsExactly("answers[0]", "answers[1]", "answers[2]");
        assertThat(checks(r)).containsOnly("answer-entry");
    }

    @Test
    void windowsLineEndingsInAnAnswerAreNormalized() {
        Answers.Resolution r = Answers.resolve(List.of(P1),
                "answers:\n  - id: " + P1.id() + "\n    value: \"a\\r\\nb\"\n", Map.of());

        assertThat(r.accepted().get(P1.id())).isEqualTo("a\nb");
    }
}

package dev.rabauer.ai.diary.insights;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyRulesTest {

    private static final Map<Long, String> ENTRIES = Map.of(
            1L, "I had a long meeting with Anna about the budget.\nI felt unsure   about the numbers, 100% unsure.",
            2L, "Walked along the river. “It’s quiet here”, I thought.");

    private static WeeklyLlmOutput out(String theme, String pattern, String question) {
        return new WeeklyLlmOutput(
                List.of(new WeeklyLlmOutput.Theme("Budget", theme, List.of(1L)),
                        new WeeklyLlmOutput.Theme("River walks", "Walks along the river were mentioned.", List.of(2L))),
                List.of(new WeeklyLlmOutput.Quote("I felt unsure about the numbers", 1L),
                        new WeeklyLlmOutput.Quote("It's quiet here", 2L)),
                List.of("Whether to talk to Anna again"),
                List.of(pattern),
                question);
    }

    private static List<String> problems(WeeklyLlmOutput o) {
        return WeeklyRules.check(o, ENTRIES);
    }

    private static final String OK_THEME = "The budget meeting with Anna was mentioned on Monday.";
    private static final String OK_PATTERN = "Entries were written on Monday and Wednesday.";
    private static final String OK_QUESTION = "What would you like to look at again next week?";

    @Test
    void acceptsAValidReflection() {
        assertThat(problems(out(OK_THEME, OK_PATTERN, OK_QUESTION))).isEmpty();
    }

    @Test
    void quotesMustBeVerbatimIgnoringWhitespaceCaseAndTypographicQuotes() {
        WeeklyLlmOutput o = new WeeklyLlmOutput(out(OK_THEME, OK_PATTERN, OK_QUESTION).recurringThemes(),
                List.of(new WeeklyLlmOutput.Quote("\"I FELT unsure\nabout the numbers\"", 1L),
                        new WeeklyLlmOutput.Quote("It’s quiet here", 2L)),
                List.of(), List.of(OK_PATTERN), OK_QUESTION);
        assertThat(problems(o)).isEmpty();
    }

    @Test
    void inventedQuoteAndQuoteFromAnotherEntryAreRejected() {
        WeeklyLlmOutput base = out(OK_THEME, OK_PATTERN, OK_QUESTION);
        WeeklyLlmOutput invented = new WeeklyLlmOutput(base.recurringThemes(),
                List.of(new WeeklyLlmOutput.Quote("I never wanted this budget", 1L), base.inYourOwnWords().get(1)),
                List.of(), List.of(OK_PATTERN), OK_QUESTION);
        assertThat(problems(invented)).singleElement().asString().contains("quote 1").contains("verbatim");

        WeeklyLlmOutput wrongEntry = new WeeklyLlmOutput(base.recurringThemes(),
                List.of(new WeeklyLlmOutput.Quote("I felt unsure about the numbers", 2L), base.inYourOwnWords().get(1)),
                List.of(), List.of(OK_PATTERN), OK_QUESTION);
        assertThat(problems(wrongEntry)).singleElement().asString().contains("quote 1");
    }

    @Test
    void foreignEntryIdsAreRejected() {
        WeeklyLlmOutput base = out(OK_THEME, OK_PATTERN, OK_QUESTION);
        WeeklyLlmOutput o = new WeeklyLlmOutput(
                List.of(new WeeklyLlmOutput.Theme("Budget", OK_THEME, List.of(1L, 99L)), base.recurringThemes().get(1)),
                List.of(new WeeklyLlmOutput.Quote("I felt unsure about the numbers", 77L), base.inYourOwnWords().get(1)),
                List.of(), List.of(OK_PATTERN), OK_QUESTION);
        assertThat(problems(o)).hasSize(2).anyMatch(p -> p.contains("99")).anyMatch(p -> p.contains("77"));
    }

    @Test
    void questionMustBeExactlyOneQuestion() {
        assertThat(problems(out(OK_THEME, OK_PATTERN, "What is next"))).anyMatch(p -> p.contains("question mark"));
        assertThat(problems(out(OK_THEME, OK_PATTERN, "What is next? And after that?"))).anyMatch(p -> p.contains("exactly one"));
        assertThat(problems(out(OK_THEME, OK_PATTERN, "Was möchtest du ansehen?"))).isEmpty();
    }

    @Test
    void scoresAndRatingsAreRejected() {
        for (String bad : List.of("Mood 7/10 on Monday.", "The week scored high.", "About 80% of the entries were about work.",
                "Stimmung: 8 am Montag.", "Die Woche bekam 5 Punkte.", "Entries about work, rated 4 stars.", "A score of ten.")) {
            assertThat(problems(out(OK_THEME, bad, OK_QUESTION))).as(bad).isNotEmpty();
        }
    }

    @Test
    void healthAndTherapyClaimsAreRejected() {
        for (String bad : List.of("This looks like burnout.", "Signs of depression appear.", "A possible diagnosis comes to mind.",
                "Du wirkst depressiv.", "You should start therapy.", "Du brauchst eine Therapie.", "You seem unhealthy.",
                "Your mental health matters here.", "Ein Anzeichen von Burn-out.")) {
            assertThat(problems(out(OK_THEME, bad, OK_QUESTION))).as(bad).isNotEmpty();
        }
    }

    @Test
    void neutralMentionsOfRelatedWordsAreNotFalsePositives() {
        for (String ok : List.of("A depressing film was mentioned.", "Therapy was not mentioned this week; a walk was.",
                "Healthy cooking came up on Wednesday.", "Der Hund war krank und ein Tierarzttermin stand an.",
                "Die Gesundheitskarte wurde erwähnt.", "The word points appears in a list of agenda points.")) {
            assertThat(problems(out(OK_THEME, ok, OK_QUESTION))).as(ok).isEmpty();
        }
    }

    @Test
    void genericPraiseAndAdviceAreRejected() {
        for (String bad : List.of("Great job staying consistent.", "Well done on Monday.", "Das war toll.", "Gut gemacht am Mittwoch.",
                "Wunderbar, wie du schreibst.", "Keep it up.")) {
            assertThat(problems(out(OK_THEME, bad, OK_QUESTION))).as(bad).isNotEmpty();
        }
        assertThat(problems(out(OK_THEME, "You should write more often.", OK_QUESTION))).anyMatch(p -> p.contains("advice"));
        assertThat(problems(out(OK_THEME, "Du solltest früher schlafen.", OK_QUESTION))).anyMatch(p -> p.contains("advice"));
    }

    @Test
    void quotesAreExemptFromContentChecksAndProblemsNeverEchoContent() {
        WeeklyLlmOutput base = out(OK_THEME, OK_PATTERN, OK_QUESTION);
        // the user's own words contain a percent sign: allowed, because quotes only have to be verbatim
        WeeklyLlmOutput o = new WeeklyLlmOutput(base.recurringThemes(),
                List.of(new WeeklyLlmOutput.Quote("numbers, 100% unsure", 1L), base.inYourOwnWords().get(1)),
                List.of(), List.of("Burnout 99%"), OK_QUESTION);
        List<String> problems = problems(o);
        assertThat(problems).isNotEmpty();
        assertThat(String.join(" ", problems)).doesNotContain("Burnout").doesNotContain("99%").doesNotContain("100%");
    }

    @Test
    void termsTheUserUsedMayBeRepeatedButNotIntroduced() {
        Map<Long, String> input = Map.of(1L, "I worry about Burn-out. 50 % of my days are meetings. It felt unsure about it.",
                2L, "Walked along the river. It is quiet here.");
        WeeklyLlmOutput base = out(OK_THEME, OK_PATTERN, OK_QUESTION);
        WeeklyLlmOutput reuse = new WeeklyLlmOutput(base.recurringThemes(),
                List.of(new WeeklyLlmOutput.Quote("I worry about Burn-out", 1L), new WeeklyLlmOutput.Quote("It is quiet here", 2L)),
                List.of("The burnout worry"), List.of("About 50% of the days are meetings."), OK_QUESTION);
        assertThat(WeeklyRules.check(reuse, input)).isEmpty();
        // a different number or word is still the model's own
        WeeklyLlmOutput other = new WeeklyLlmOutput(reuse.recurringThemes(), reuse.inYourOwnWords(),
                List.of("The depression worry"), List.of("About 60% of the days are meetings."), OK_QUESTION);
        assertThat(WeeklyRules.check(other, input)).hasSize(2);
        // coach lines count as input as well
        assertThat(WeeklyRules.check(other, input, "depressed and 60 percent: 60%")).isEmpty();
    }

    @Test
    void claimsStayRejectedEvenIfTheWordIsInTheInput() {
        Map<Long, String> input = Map.of(1L, "I worry about burnout and depression. My mood is low. I felt unsure about the numbers.",
                2L, "Walked along the river. It is quiet here.");
        for (String claim : List.of("Du hast ein Burnout.", "You are depressed.", "Your mood: 4/10.", "Du solltest eine Therapie machen.",
                "Stimmung bei 4.")) {
            assertThat(WeeklyRules.check(out(OK_THEME, claim, OK_QUESTION), input)).as(claim).isNotEmpty();
        }
    }
}

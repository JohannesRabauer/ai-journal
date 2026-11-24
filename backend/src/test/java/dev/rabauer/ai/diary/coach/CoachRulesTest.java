package dev.rabauer.ai.diary.coach;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.api.entry.EntryDto;
import dev.rabauer.ai.diary.api.entry.EntrySource;
import dev.rabauer.ai.diary.api.entry.EntryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests of the deterministic coach rules (no Quarkus). */
class CoachRulesTest {

    @Test
    void detectsGermanAndEnglishAndDefaultsToEnglish() {
        assertThat(CoachRules.detectLanguage("Heute war ich müde und habe nicht gut geschlafen, aber der Tag war schön.")).isEqualTo(Language.DE);
        assertThat(CoachRules.detectLanguage("Today I was tired and I did not sleep well, but the day was nice.")).isEqualTo(Language.EN);
        assertThat(CoachRules.detectLanguage("")).isEqualTo(Language.EN);
        assertThat(CoachRules.detectLanguage("1234 ...")).isEqualTo(Language.EN);
    }

    @Test
    void crisisKeywordsAreDetectedInBothLanguages() {
        assertThat(CoachRules.indicatesCrisis("Sometimes I think I want to kill myself.")).isTrue();
        assertThat(CoachRules.indicatesCrisis("I don’t want to live like this")).isTrue();
        assertThat(CoachRules.indicatesCrisis("Ich will mich umbringen.")).isTrue();
        assertThat(CoachRules.indicatesCrisis("Ich habe Suizidgedanken")).isTrue();
    }

    @Test
    void crisisListDoesNotFireOnOrdinaryText() {
        assertThat(CoachRules.indicatesCrisis("I killed time at the station and my feet hurt.")).isFalse();
        assertThat(CoachRules.indicatesCrisis("Ich will nicht mehr in dieser Stadt wohnen, das Leben dort ist teuer.")).isFalse();
        assertThat(CoachRules.indicatesCrisis("My die-hard friend and I talked about life.")).isFalse();
        assertThat(CoachRules.indicatesCrisis(null)).isFalse();
    }

    @Test
    void explicitRequestsAreRecognised() {
        for (String s : List.of("Can you explain why I feel this way?", "Please explain.", "What do you think about that?",
                "I would like your opinion.", "Any advice?", "Tell me why this happens.",
                "Kannst du mir erklären, warum das so ist?", "Erklär mir das bitte.", "Was denkst du darüber?",
                "Was meinst du dazu?", "Hast du einen Tipp?", "Kannst du mir das sagen?")) {
            assertThat(CoachRules.asksForExplanation(s)).as(s).isTrue();
        }
    }

    @Test
    void ordinaryAnswersAreNotExplicitRequests() {
        for (String s : List.of("I explained it to my sister yesterday.", "I can't explain it, I just felt tired.",
                "I think the meeting went badly.", "Ich habe ihm das gestern erklärt.", "Ich weiß nicht, was ich denken soll.")) {
            assertThat(CoachRules.asksForExplanation(s)).as(s).isFalse();
        }
    }

    @Test
    void doneSignals() {
        assertThat(CoachRules.signalsDone("That's all for now.")).isTrue();
        assertThat(CoachRules.signalsDone("Ich bin fertig, das war's.")).isTrue();
        assertThat(CoachRules.signalsDone("That's all I wanted to tell you")).isTrue();
        assertThat(CoachRules.signalsDone("I walked home and it was fine.")).isFalse();
    }

    @Test
    void genericPraiseIsDetected() {
        for (String s : List.of("Great insight! What made you say that?", "Well done. What happened next?",
                "That's wonderful. What did you do then?", "Toll! Was kam danach?", "Super, und was dann?",
                "Das ist wirklich großartig. Was hat dir dabei geholfen?", "Gut gemacht. Was fühlst du?",
                "Eine tolle Frage, aber was meinst du selbst?", "Ich bin stolz auf dich. Was nun?", "You're doing great. What next?")) {
            assertThat(CoachRules.containsPraise(s)).as(s).isTrue();
        }
    }

    @Test
    void quotingTheUsersOwnPositiveWordsIsNotPraise() {
        for (String s : List.of("What made the day wonderful for you?", "Was war am Ausblick so großartig?",
                "Du sagst, es ging dir sehr gut. Woran merkst du das?", "You called the talk great. What was great about it?",
                "Was ist für dich super an diesem Plan?", "Which part of the evening felt nice?")) {
            assertThat(CoachRules.containsPraise(s)).as(s).isFalse();
        }
    }

    @Test
    void diagnosisAndScoreWordingIsDetectedButTherapyMentionsAreNot() {
        assertThat(CoachRules.containsDiagnosisOrScore("This sounds like a diagnosis of burnout.")).isTrue();
        assertThat(CoachRules.containsDiagnosisOrScore("Du hast eine Depression.")).isTrue();
        assertThat(CoachRules.containsDiagnosisOrScore("Your score is 7 out of 10.")).isTrue();
        assertThat(CoachRules.containsDiagnosisOrScore("How was your therapy session on Tuesday?")).isFalse();
        assertThat(CoachRules.containsDiagnosisOrScore("Wie war das Spiel? Wer hat mehr Punkte gemacht?")).isFalse();
    }

    @Test
    void turnChecksEnforceQuestionsWordLimitAndKinds() {
        List<CoachTurnKind> followUpOnly = List.of(CoachTurnKind.FOLLOW_UP_QUESTION);
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.FOLLOW_UP_QUESTION, "What did you feel then?"), followUpOnly)).isEmpty();
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.FOLLOW_UP_QUESTION, "That sounds hard."), followUpOnly)).isNotEmpty();
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.FOLLOW_UP_QUESTION, "What happened? How did it feel?"), followUpOnly)).isNotEmpty();
        String tooLong = "word ".repeat(61) + "?";
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.FOLLOW_UP_QUESTION, tooLong), followUpOnly)).isNotEmpty();
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.EXPLANATION, "Because it matters. Does that fit?"), followUpOnly)).isNotEmpty();
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.CLOSING, "Thank you for sharing."), followUpOnly)).isNotEmpty();

        List<CoachTurnKind> withExplanation = List.of(CoachTurnKind.FOLLOW_UP_QUESTION, CoachTurnKind.EXPLANATION);
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.EXPLANATION, "Tiredness often follows stress. Does that match your week?"), withExplanation)).isEmpty();
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.EXPLANATION, "Tiredness often follows stress."), withExplanation))
                .contains("an explanation must end with a question");
    }

    @Test
    void openingChecksRequireDistinctSingleQuestions() {
        assertThat(CoachService.checkOpening(new OpeningQuestions(List.of("What stayed with you?", "What do you need?")))).isEmpty();
        assertThat(CoachService.checkOpening(new OpeningQuestions(List.of("What stayed with you?", "what stayed with you?")))).isNotEmpty();
        assertThat(CoachService.checkOpening(new OpeningQuestions(List.of("What stayed with you", "What do you need?")))).isNotEmpty();
        assertThat(CoachService.checkOpening(new OpeningQuestions(List.of("Why? And how?", "What do you need?")))).isNotEmpty();
    }

    @Test
    void explanationMustExplainBeforeAsking() {
        var only = java.util.List.of(CoachTurnKind.EXPLANATION);
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.EXPLANATION, "Wie kann der nächste Versuch anders laufen?"), only))
                .anyMatch(p -> p.contains("first explain"));
        assertThat(CoachService.checkTurn(new CoachTurn(CoachTurnKind.EXPLANATION,
                "Unklare Gespräche wirken oft lange nach, weil nichts abgeschlossen ist. Was fehlt dir noch zum Abschluss?"), only))
                .isEmpty();
    }

    @Test
    void allowedKindsNeverContainExplanationUnlessRequested() {
        assertThat(CoachService.allowedKinds(false, false, false)).containsExactly(CoachTurnKind.FOLLOW_UP_QUESTION);
        assertThat(CoachService.allowedKinds(false, false, true)).containsExactly(CoachTurnKind.FOLLOW_UP_QUESTION, CoachTurnKind.CLOSING);
        assertThat(CoachService.allowedKinds(false, true, false)).containsExactly(CoachTurnKind.CLOSING);
        assertThat(CoachService.allowedKinds(true, false, false)).as("an explicit request is answered").containsExactly(CoachTurnKind.EXPLANATION);
        assertThat(CoachService.allowedKinds(true, true, true)).containsExactly(CoachTurnKind.EXPLANATION);
    }

    @Test
    void retrievalFailureYieldsNoContextInsteadOfAnException() {
        CoachService service = new CoachService();
        service.similarEntries = 3;
        service.similarMinScore = 0.3;
        service.embeddings = new EmbeddingGateway() {
            @Override
            public float[] embed(String text) {
                throw new ProviderException("ollama", ProviderException.Kind.UNREACHABLE, "down");
            }

            @Override
            public String modelId() {
                return "x";
            }
        };
        service.vectors = null; // never reached
        EntryDto entry = new EntryDto(1, LocalDateTime.now(), EntryStatus.FINISHED, EntrySource.TEXT, Language.EN, "text", null, null,
                false, Instant.now(), Instant.now(), Instant.now());
        assertThat(service.similarEntries(entry, "text")).isEmpty();

        service.embeddings = new EmbeddingGateway() {
            @Override
            public float[] embed(String text) {
                return new float[]{1f};
            }

            @Override
            public String modelId() {
                return "x";
            }
        };
        service.vectors = new dev.rabauer.ai.diary.spi.fake.InMemoryVectorStore() {
            @Override
            public List<Match> search(String namespace, float[] query, String modelId, int limit, double minScore) {
                throw new IllegalStateException("store broken");
            }
        };
        assertThat(service.similarEntries(entry, "text")).isEmpty();
    }
}

package dev.rabauer.ai.diary.memory;

import dev.rabauer.ai.diary.api.memory.LessonDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Static memory-technique lessons (de/en, offline). Lesson n+1 unlocks by USAGE, never by scores:
 * <ol>
 *   <li>retrieval practice: always open</li>
 *   <li>spacing: after the first approved memory item</li>
 *   <li>elaboration: after 3 completed reviews</li>
 *   <li>chunking: after 8 completed reviews</li>
 *   <li>dual coding: after 15 completed reviews</li>
 *   <li>method of loci: after 25 completed reviews</li>
 * </ol>
 * A completed review is a graded review (any grade); an approved item is an approved candidate. Both counts only
 * grow, so a lesson never locks again.
 */
final class Lessons {

    /** @param minItems approved items needed; @param minReviews completed reviews needed */
    record Spec(String id, int minItems, int minReviews, String titleDe, String bodyDe, String titleEn, String bodyEn) {
    }

    static final List<Spec> ALL = List.of(
            new Spec("retrieval", 0, 0,
                    "Abrufen statt Nachlesen",
                    "Sich etwas aus dem Gedächtnis zu holen, festigt es stärker als erneutes Lesen. Darum zeigt jede Karte zuerst nur die Frage. "
                            + "Versuche eine Antwort, auch eine unvollständige, und tippe sie oder denke sie dir. Erst danach siehst du die Antwort. "
                            + "Ein Fehlversuch ist kein Rückschlag: Der Versuch selbst bereitet das Behalten vor.",
                    "Retrieval instead of rereading",
                    "Pulling something out of memory strengthens it more than reading it again. That is why every card shows only the question first. "
                            + "Try an answer, even an incomplete one, and type it or think it. Only then do you see the answer. "
                            + "A miss is not a setback: the attempt itself prepares the memory."),
            new Spec("spacing", 1, 0,
                    "Abstände wachsen lassen",
                    "Eine Karte kehrt nach 1, 3, 7, 14, 30 Tagen zurück (einstellbar) und danach in immer größeren Abständen. "
                            + "Wer kurz vor dem Vergessen wiederholt, behält mit weniger Aufwand. Wiederholungen an vielen Tagen sind besser als viele an einem Tag. "
                            + "Wenn du eine Karte vergessen hast, beginnt sie einfach wieder mit kurzen Abständen.",
                    "Let the gaps grow",
                    "A card returns after 1, 3, 7, 14, 30 days (adjustable) and then at ever longer gaps. "
                            + "Reviewing just before you would forget keeps things with less effort. A few minutes on many days beats a long session on one day. "
                            + "If you forgot a card, it simply starts again with short gaps."),
            new Spec("elaboration", 0, 3,
                    "Mit eigenen Worten erklären",
                    "Frage dich bei einer neuen Erkenntnis: Warum ist das so? Wo habe ich das selbst erlebt? Was wäre ein Gegenbeispiel? "
                            + "Wer etwas mit eigenem Wissen verknüpft, findet später mehr Wege dorthin zurück. "
                            + "Formuliere deine Karten deshalb in eigenen Worten, statt Sätze abzuschreiben.",
                    "Explain it in your own words",
                    "With a new insight, ask yourself: why is that so? Where did I experience this myself? What would be a counter-example? "
                            + "Linking something to what you already know gives you more ways back to it later. "
                            + "So write your cards in your own words instead of copying sentences."),
            new Spec("chunking", 0, 8,
                    "In Einheiten bündeln",
                    "Das Arbeitsgedächtnis fasst nur wenige Einheiten gleichzeitig. Bündle Zusammengehörendes zu einer Einheit mit einem Namen: "
                            + "drei Schritte werden zu einer Liste mit Titel. Eine Karte sollte eine einzige Sache abfragen. "
                            + "Ist die Antwort lang, teile sie in zwei Karten.",
                    "Bundle into chunks",
                    "Working memory holds only a few units at once. Bundle related things into one unit with a name: "
                            + "three steps become one list with a title. A card should ask for one single thing. "
                            + "If the answer is long, split it into two cards."),
            new Spec("dual-coding", 0, 15,
                    "Wort und Bild verbinden",
                    "Ein Gedanke, den du zusätzlich als einfaches Bild oder Skizze festhältst, hat zwei Spuren im Gedächtnis. "
                            + "Es muss nicht schön sein: ein Strichmännchen, ein Pfeil, ein Kasten genügt. "
                            + "Stelle dir beim Beantworten einer Karte kurz dieses Bild vor.",
                    "Join words and pictures",
                    "A thought that you also keep as a simple image or sketch leaves two traces in memory. "
                            + "It does not need to be pretty: a stick figure, an arrow, a box is enough. "
                            + "When you answer a card, picture it briefly."),
            new Spec("loci", 0, 25,
                    "Die Methode der Orte",
                    "Gehe in Gedanken einen vertrauten Weg, etwa durch deine Wohnung. Lege an jeder Station ein Bild für eine Sache ab, "
                            + "die du dir merken willst: Haustür, Garderobe, Küchentisch. Zum Abrufen gehst du den Weg wieder ab. "
                            + "Die Methode eignet sich für Listen und Reihenfolgen; probiere sie mit einer kleinen Liste aus.",
                    "The method of loci",
                    "Walk a familiar route in your mind, such as through your home. At each stop place an image for something "
                            + "you want to remember: front door, coat rack, kitchen table. To recall, walk the route again. "
                            + "It suits lists and sequences; try it with a short list."));

    private Lessons() {
    }

    /** @param approvedItems approved candidates so far; @param completedReviews graded reviews so far */
    static List<LessonDto> forLanguage(boolean german, long approvedItems, long completedReviews) {
        List<LessonDto> out = new ArrayList<>();
        int order = 1;
        for (Spec s : ALL) {
            boolean unlocked = approvedItems >= s.minItems && completedReviews >= s.minReviews;
            out.add(new LessonDto(s.id, order++,
                    german ? s.titleDe : s.titleEn,
                    unlocked ? (german ? s.bodyDe : s.bodyEn) : "",
                    unlocked,
                    unlocked ? null : hint(german, s)));
        }
        return out;
    }

    static String hint(boolean german, Spec s) {
        if (s.minReviews > 0) {
            return german ? "Öffnet sich nach deiner " + s.minReviews + ". Wiederholung."
                    : "Opens after your " + ordinal(s.minReviews) + " review.";
        }
        return german ? "Öffnet sich, sobald du die erste Karte übernommen hast."
                : "Opens once you have kept your first card.";
    }

    private static String ordinal(int n) {
        return switch (n % 100 >= 11 && n % 100 <= 13 ? 0 : n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}

package dev.rabauer.ai.diary.api.coach;

/** @param voice true if the text came from a transcribed voice answer */
public record AnswerRequest(String text, boolean voice) {
}

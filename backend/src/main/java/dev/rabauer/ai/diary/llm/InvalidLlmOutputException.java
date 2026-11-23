package dev.rabauer.ai.diary.llm;

import java.util.List;

/** LLM output failed validation after all attempts. {@link #problems()} never contains the output itself. */
public class InvalidLlmOutputException extends RuntimeException {

    private final int attempts;
    private final List<String> problems;

    public InvalidLlmOutputException(int attempts, List<String> problems) {
        super("LLM output invalid after " + attempts + " attempt(s): " + problems);
        this.attempts = attempts;
        this.problems = List.copyOf(problems);
    }

    public int attempts() {
        return attempts;
    }

    public List<String> problems() {
        return problems;
    }
}

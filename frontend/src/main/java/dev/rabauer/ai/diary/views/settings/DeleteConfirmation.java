package dev.rabauer.ai.diary.views.settings;

import dev.rabauer.ai.diary.api.privacy.DeleteAllRequest;

/** The delete-everything button is enabled only when the typed text equals the phrase exactly. */
public final class DeleteConfirmation {

    private DeleteConfirmation() {
    }

    public static boolean isConfirmed(String typed) {
        return DeleteAllRequest.CONFIRMATION_PHRASE.equals(typed);
    }

    public static DeleteAllRequest request(String typed) {
        if (!isConfirmed(typed)) {
            throw new IllegalArgumentException("confirmation phrase does not match");
        }
        return new DeleteAllRequest(typed);
    }
}

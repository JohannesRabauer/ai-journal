package dev.rabauer.ai.diary.web.ui;

import java.util.List;
import java.util.Locale;

/** Test adapter with the lookup shape the ported Vaadin presenter tests use, backed by the backend's {@link Messages}. */
final class MessageCatalog {

    private MessageCatalog() {
    }

    static MessageCatalog fromClasspath(ClassLoader ignored) {
        return new MessageCatalog();
    }

    String get(Locale locale, String key, Object... args) {
        Messages t = Messages.forAcceptable(List.of(locale));
        return switch (args.length) {
            case 0 -> t.get(key);
            case 1 -> t.get(key, args[0]);
            case 2 -> t.get(key, args[0], args[1]);
            default -> t.get(key, args[0], args[1], args[2]);
        };
    }
}

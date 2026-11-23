package dev.rabauer.ai.diary.shell;

import dev.rabauer.ai.diary.api.system.SystemInfoDto;

/** What the shell banner should show, derived from the system info call. Plain Java, no UI. */
public record ShellStatus(Kind kind, String cloudNotice) {

    public enum Kind { LOCAL, CLOUD, OFFLINE }

    public static ShellStatus of(SystemInfoDto info) {
        if (info == null) {
            return offline();
        }
        if (info.cloudMode()) {
            return new ShellStatus(Kind.CLOUD, info.cloudNotice() == null ? "" : info.cloudNotice());
        }
        return new ShellStatus(Kind.LOCAL, "");
    }

    public static ShellStatus offline() {
        return new ShellStatus(Kind.OFFLINE, "");
    }

    public boolean visible() {
        return kind != Kind.LOCAL;
    }
}

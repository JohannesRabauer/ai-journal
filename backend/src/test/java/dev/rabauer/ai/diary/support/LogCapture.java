package dev.rabauer.ai.diary.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures every log record (all categories, all levels) while open. */
public final class LogCapture implements AutoCloseable {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final Logger root = Logger.getLogger("");
    private final Level previous;
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            String msg = r.getMessage();
            Object[] params = r.getParameters();
            StringBuilder sb = new StringBuilder(String.valueOf(msg));
            if (params != null) {
                for (Object p : params) {
                    sb.append(' ').append(p);
                }
            }
            if (r.getThrown() != null) {
                sb.append(' ').append(r.getThrown());
            }
            lines.add(sb.toString());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    public LogCapture() {
        previous = root.getLevel();
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
    }

    public String all() {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }

    @Override
    public void close() {
        root.removeHandler(handler);
        root.setLevel(previous);
    }
}

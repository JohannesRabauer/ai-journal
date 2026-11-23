package dev.rabauer.ai.diary.privacy;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only owner that fails on demand (see PrivacyResourceTest). Harmless unless {@link #FAIL} is set. */
@ApplicationScoped
public class FailingTestOwner implements UserDataOwner {

    public static final AtomicBoolean FAIL = new AtomicBoolean();

    @Override
    public String name() {
        return "test-failing";
    }

    @Override
    public int deleteOrder() {
        return 500;
    }

    @Override
    public long deleteAll() {
        if (FAIL.get()) {
            throw new IllegalStateException("boom");
        }
        return 0;
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        if (FAIL.get()) {
            throw new IOException("boom");
        }
    }
}

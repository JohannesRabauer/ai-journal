package dev.rabauer.ai.diary.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-registered JUnit extension: every RestAssured request of the suite sends the write-guard header
 * ({@code X-AI-Diary: 1}) the backend requires for non-GET requests. Tests that check the guard itself use
 * {@link java.net.http.HttpClient} instead.
 * <p>
 * @QuarkusTest classes run in Quarkus' class loader and see their own copy of RestAssured, so the filter is
 * installed reflectively on whichever copy the current thread context class loader (and the plain one) resolves.
 */
public class ApiHeaderExtension implements BeforeAllCallback, BeforeEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        install(Thread.currentThread().getContextClassLoader());
        install(context.getRequiredTestClass().getClassLoader());
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        beforeAll(context);
    }

    private static void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> restAssured = Class.forName("io.restassured.RestAssured", true, cl);
            Class<?> filterClass = Class.forName("io.restassured.filter.Filter", true, cl);
            Object filter = Class.forName(ApiHeaderFilter.class.getName(), true, cl).getConstructor().newInstance();
            restAssured.getMethod("replaceFiltersWith", java.util.List.class).invoke(null, java.util.List.of(filter));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot install the default X-AI-Diary header", e);
        }
    }
}

package io.github.aindriub.dataprism.hazelcast;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Fails any test in which a Hazelcast member logs a serialisation-filter rejection ("is not allowed"). A class the
 * allowlist lacks does not surface as a refusal but as a lost reply, so the call hangs until a timeout; this turns
 * that into a prompt failure that names the class. The test thread is interrupted at the first rejection.
 * Registered by service loader; tests that reject on purpose carry {@link ExpectsFilterRejection}, and so does the
 * whole spike package, whose tests probe the filter.
 */
public final class FilterRejectionGuard implements BeforeEachCallback, AfterEachCallback {

    private static final List<String> REJECTIONS = new CopyOnWriteArrayList<>();
    private static volatile Thread testThread;
    private static volatile boolean armed;

    static {
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!armed) {
                    return;
                }
                StringBuilder text = new StringBuilder(String.valueOf(record.getMessage()));
                for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
                    text.append(' ').append(t.getMessage());
                }
                String all = text.toString();
                int at = all.indexOf("is not allowed");
                // Not the Management Center refusal, which also says "is not allowed".
                if (at >= 0 && all.lastIndexOf("Resolving class", at) >= 0) {
                    int from = all.lastIndexOf("Resolving class", at);
                    REJECTIONS.add(all.substring(from, at + "is not allowed".length()));
                    Thread thread = testThread;
                    if (thread != null) {
                        thread.interrupt();
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private static boolean expected(ExtensionContext context) {
        return context.getRequiredTestClass().getPackageName().endsWith(".spike")
                || context.getRequiredTestClass().isAnnotationPresent(ExpectsFilterRejection.class)
                || context.getRequiredTestMethod().isAnnotationPresent(ExpectsFilterRejection.class);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        REJECTIONS.clear();
        testThread = Thread.currentThread();
        armed = !expected(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        boolean wasArmed = armed;
        armed = false;
        testThread = null;
        Thread.interrupted();
        if (wasArmed && !REJECTIONS.isEmpty()) {
            List<String> seen = new ArrayList<>(REJECTIONS);
            REJECTIONS.clear();
            throw new AssertionError("a member's serialisation filter rejected a class: " + seen
                    + ". Add it to PrivacyCluster.ALLOWED_CLASSES or ALLOWED_PREFIXES, or mark the test "
                    + "@ExpectsFilterRejection if the rejection is the point.");
        }
    }
}

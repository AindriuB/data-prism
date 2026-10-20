package io.github.aindriub.dataprism.hazelcast;

import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for a list appender: no SLF4J provider is on this module's test classpath. */
final class RecordingLogger {

    /** One logging call: its level, its formatted message and whether it carried a throwable. */
    record Line(Level level, String message, boolean withThrowable) {
    }

    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
            new Class<?>[]{Logger.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("is") && name.endsWith("Enabled")) {
                    return true;
                }
                Level level = switch (name) {
                    case "error" -> Level.ERROR;
                    case "warn" -> Level.WARN;
                    case "info" -> Level.INFO;
                    case "debug" -> Level.DEBUG;
                    case "trace" -> Level.TRACE;
                    default -> null;
                };
                if (level == null || args == null || args.length == 0 || !(args[0] instanceof String format)) {
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
                Object[] rest = new Object[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                Object[] flattened = rest.length == 1 && rest[0] instanceof Object[] array ? array : rest;
                var formatted = MessageFormatter.arrayFormat(format, flattened);
                lines.add(new Line(level, formatted.getMessage(),
                        formatted.getThrowable() != null || hasThrowable(rest)));
                return null;
            });

    private static boolean hasThrowable(Object[] values) {
        for (Object value : values) {
            if (value instanceof Throwable) return true;
        }
        return false;
    }

    Logger logger() {
        return logger;
    }

    List<Line> lines() {
        return List.copyOf(lines);
    }
}

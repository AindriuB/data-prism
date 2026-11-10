package io.github.aindriub.dataprism.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;

/**
 * Decorates the engine's {@link McpJsonMapper} so that a read or a conversion that fails carries no
 * client-supplied content.
 *
 * <p>Jackson's messages quote the offending token and the SDK logs and replies with them, and the SDK's
 * own "cannot deserialize" message carries the whole inbound line. Here every failure of
 * {@code readValue} or {@code convertValue} is replaced by a fixed message with no cause, of the same
 * exception type the SDK already handles. Writes delegate unchanged, so every outbound byte still
 * passes through the wrapped mapper, which is the one the scrubbing engine is installed on
 * (docs/architecture.md boundary 1).
 *
 * <p>The SDK reads each inbound message as a {@code HashMap} and builds its own exception when none
 * of {@code method}, {@code result} or {@code error} is present. That check is mirrored here, ahead
 * of the SDK's, so its message is never built.
 */
final class ContentFreeMcpJsonMapper implements McpJsonMapper {

    static final String READ_FAILED = "Failed to read value";
    static final String NOT_JSON_RPC = "Not a JSON-RPC message";
    static final String CONVERT_FAILED = "Failed to convert value";

    private final McpJsonMapper delegate;

    ContentFreeMcpJsonMapper(McpJsonMapper delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /** The decorated mapper: the engine's own. */
    McpJsonMapper delegate() {
        return delegate;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        try {
            return delegate.readValue(content, type);
        } catch (RuntimeException | IOException e) {
            throw new IOException(READ_FAILED);
        }
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        try {
            return delegate.readValue(content, type);
        } catch (RuntimeException | IOException e) {
            throw new IOException(READ_FAILED);
        }
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        T value;
        try {
            value = delegate.readValue(content, type);
        } catch (RuntimeException | IOException e) {
            throw new IOException(READ_FAILED);
        }
        return checked(value, type);
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        T value;
        try {
            value = delegate.readValue(content, type);
        } catch (RuntimeException | IOException e) {
            throw new IOException(READ_FAILED);
        }
        return checked(value, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        try {
            return delegate.convertValue(value, type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(CONVERT_FAILED);
        }
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        try {
            return delegate.convertValue(value, type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(CONVERT_FAILED);
        }
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(value);
    }

    /** The SDK reads an inbound message as a {@code HashMap}; one with no routing key is refused here. */
    private static <T> T checked(T value, TypeRef<T> type) throws IOException {
        if (value instanceof HashMap<?, ?> map && isHashMapOfStringObject(type.getType())
                && !map.containsKey("method") && !map.containsKey("result") && !map.containsKey("error")) {
            throw new IOException(NOT_JSON_RPC);
        }
        return value;
    }

    private static boolean isHashMapOfStringObject(Type type) {
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() == HashMap.class
                && parameterized.getActualTypeArguments().length == 2
                && parameterized.getActualTypeArguments()[0] == String.class
                && parameterized.getActualTypeArguments()[1] == Object.class;
    }
}

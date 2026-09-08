package io.github.aindriub.dataprism.core;

/**
 * One external system.
 *
 * <p>Declared in core so orchestration and MCP can depend on the contract while
 * the connector modules stay leaves that nothing imports. That is what keeps the
 * MCP layer unable to reach a source directly.
 */
public interface DataSourceAdapter<T> {

    String sourceName();

    Class<T> responseType();

    T fetch(DataRequest request);
}

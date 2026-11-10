package io.github.aindriub.dataprism.core.link;

/**
 * Marks a {@link LinkAttemptLimiter} whose failed-attempt count is shared by every cluster member. Startup decides
 * from the declared type alone (the {@code @Bean} return type or the class), never by building the bean and never
 * by calling {@link #clusterWide()}, so an application that needs a shared limiter declares this type.
 */
public interface ClusterWideLinkAttemptLimiter extends LinkAttemptLimiter {

    /** Always {@code true}: the count is shared by every member. */
    @Override
    default boolean clusterWide() {
        return true;
    }
}

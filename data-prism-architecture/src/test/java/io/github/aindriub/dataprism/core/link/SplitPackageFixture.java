package io.github.aindriub.dataprism.core.link;

/**
 * Negative fixture for {@code LinkArchitectureTest}: a class in the {@code core.link} package that is compiled into
 * {@code data-prism-architecture}'s test classes, not {@code data-prism-core}'s main classes. The split-package rule
 * must fail on it. Imported explicitly, never by a package scan.
 */
public final class SplitPackageFixture {

    private SplitPackageFixture() {
    }
}

# Builds data-prism-server and the quickstart's reviewed adapter extension
# together from the repository root, then ships only the two resulting jars:
# the packaged standalone server (task 23's own module, unowned by this
# task and built exactly as ServerPackagingIT already proves it) and this
# task's data-prism-quickstart-extension, loaded into it through
# -Dloader.path at the bottom of this file — the same mechanism
# ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath
# proves in data-prism-server's own test suite.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
# data-prism-processor is named explicitly: data-prism-quickstart-extension
# uses it only as an annotationProcessorPath, never as a <dependency>, so
# Maven's -am ("also make") never discovers the edge and, in a fresh
# container with no local repository cache, fails to resolve it.
RUN mvn -B -q -pl data-prism-processor,data-prism-server,data-prism-quickstart-extension -am package -DskipTests
# The reactor version is embedded in the filenames `mvn package` produces
# (data-prism-server-<version>.jar, data-prism-quickstart-extension-<version>.jar),
# and the pom.xml is the only place that version is written (task 36) —
# rather than duplicating it here as an ARG default that silently goes
# stale the moment the reactor cuts a new version (see
# docs/plan/tasks/55-publish-quickstart-images.md), find the two repackaged
# jars Maven actually produced and give them fixed names. The
# spring-boot-maven-plugin repackage goal leaves the pre-repackage jar
# alongside data-prism-server's as *.jar.original — `-name '*.jar'` already
# excludes that file on its own (it does not end in .jar), so the trailing
# `! -name '*.original'` is redundant there, kept only as a defensive
# belt-and-braces; data-prism-quickstart-extension is not repackaged, so it
# has no such sibling to begin with.
RUN find data-prism-server/target -maxdepth 1 -name '*.jar' ! -name '*.original' \
      -exec cp {} /workspace/data-prism-server.jar \; \
    && find data-prism-quickstart-extension/target -maxdepth 1 -name '*.jar' ! -name '*.original' \
      -exec cp {} /workspace/data-prism-quickstart-extension.jar \;

FROM eclipse-temurin:21-jre
# Fixture-only: this image bundles a pre-loaded adapter extension and a demo
# configuration for the Compose quickstart, never a real backend, and is not
# for production use — see docker/distribution/Dockerfile for the image an
# operator deploys against their own data.
LABEL org.opencontainers.image.description="Data Prism quickstart server: a fixture-only demo build for the Compose quickstart, not for production use."
WORKDIR /app
COPY --from=build /workspace/data-prism-server.jar /app/data-prism-server.jar
COPY --from=build /workspace/data-prism-quickstart-extension.jar /app/data-prism-quickstart-extension.jar
# Spring Boot's own convention: a jar started with working directory /app
# auto-loads /app/config/application.yaml ahead of its own packaged defaults.
COPY docker/server/application.yaml /app/config/application.yaml
EXPOSE 8080
ENTRYPOINT ["java", "-Dloader.path=/app/data-prism-quickstart-extension.jar", "-jar", "/app/data-prism-server.jar", "--server.port=8080"]

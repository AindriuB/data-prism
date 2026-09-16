# The distributable data-prism-server image: `data-prism-server` alone, with
# no quickstart extension, fixture yaml or issuer bundled — unlike
# docker/server/Dockerfile, which is a demo of the whole quickstart journey
# (see that file's own header). This image is never runnable by itself:
# DataPrismContractValidator refuses to start a server with no
# DataSourceAdapter bean for every dataprism.sources.* entry, and this image
# ships none. A consumer supplies their own reviewed adapter jar (see
# LOADER_PATH below) and a deployment configuration (see CONFIG_DIR below).
# That refusal, not a bug, is the image's whole point: it cannot leak a
# working server with someone else's data behind it.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
# data-prism-server is a real Maven <dependency> of every module it needs
# (data-prism-spring-boot-autoconfigure, data-prism-core, ...), so -am
# ("also make") resolves the whole dependency edge on its own — unlike
# docker/server/Dockerfile, which also has to name data-prism-processor
# because data-prism-quickstart-extension uses it only as an
# annotationProcessorPath. This image never builds that extension.
RUN mvn -B -q -pl data-prism-server -am package -DskipTests

FROM eclipse-temurin:21-jre

# Single source of truth for the jar's version-embedded filename: bump this
# alongside the reactor's own <version> (task 36 owns the poms) and every
# reference below moves with it. ENV, not just ARG: ARG values are build-time
# only and do not persist into the running container, but the ENTRYPOINT
# below has to re-expand ${VERSION} in a runtime shell — see its own comment
# for why — so this also needs to still be set when the container starts.
ARG VERSION=0.1.0
ENV VERSION=${VERSION}

LABEL io.modelcontextprotocol.server.name="io.github.aindriub/data-prism"

# Non-root: the packaged jar and PropertiesLauncher never need root, and
# running as root in a container is a needless privilege to hand an operator's
# deployment.
RUN groupadd --system dataprism && useradd --system --gid dataprism --home-dir /app dataprism

WORKDIR /app

# LOADER_PATH: a directory, read by Spring Boot's PropertiesLauncher exactly as
# ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath
# proves for a single jar passed via -Dloader.path — PropertiesLauncher reads
# the same property from this environment variable (its own upper-case,
# underscore convention), and, given a directory rather than one jar, adds
# every *.jar placed directly inside it to the classpath. An operator mounts
# their own reviewed DataSourceAdapter jar here, e.g.:
#   docker run -v /path/to/my-adapter.jar:/app/adapters/my-adapter.jar ...
# Nothing is pre-populated: an empty directory is exactly what makes
# MISSING_SOURCE_ADAPTER the correct, fail-closed refusal without one.
RUN mkdir -p /app/adapters && chown -R dataprism:dataprism /app
ENV LOADER_PATH=/app/adapters

# CONFIG_DIR: Spring Boot's own convention — a jar started with working
# directory /app auto-loads /app/config/application.yaml ahead of its packaged
# defaults. Mount a deployment configuration satisfying the dataprism.*
# vocabulary (docs/configuration.md) here, e.g.:
#   docker run -v /path/to/application.yaml:/app/config/application.yaml ...
# This image ships no application.yaml of its own — a fixture or placeholder
# security configuration baked into the image is exactly what task 21's
# fail-closed contract and this task both refuse to ship.

COPY --from=build /workspace/data-prism-server/target/data-prism-server-${VERSION}.jar /app/data-prism-server-${VERSION}.jar
RUN chown dataprism:dataprism /app/data-prism-server-${VERSION}.jar

USER dataprism

EXPOSE 8080

# --server.port=8080 is a command-line argument, which Spring Boot ranks
# above a mounted /app/config/application.yaml's own server.port — an
# operator setting server.port there is silently overridden by this, not
# read. That matches docker/server/Dockerfile's own demo entrypoint; it is
# not changed here, only called out so it is not a surprise.
#
# ENTRYPOINT cannot be a plain JSON array here despite VERSION being a single
# ARG/ENV above: Docker only expands ${VAR} at build time for a fixed list of
# instructions (COPY, RUN's own shell, ENV, LABEL, ...), never for a JSON-array
# ENTRYPOINT — confirmed empirically before writing this, not from memory: a
# JSON-array ENTRYPOINT keeps the literal text "${VERSION}" in the image's
# metadata instead of substituting it. The `sh -c` below is only there to
# expand VERSION at container start; `exec` immediately replaces that shell
# with the java process, so the JVM still ends up as PID 1 and signal
# handling is unaffected.
ENTRYPOINT ["sh", "-c", "exec java -jar /app/data-prism-server-${VERSION}.jar --server.port=8080"]

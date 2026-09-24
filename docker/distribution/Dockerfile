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

LABEL io.modelcontextprotocol.server.name="io.github.AindriuB/data-prism"
LABEL org.opencontainers.image.title="Data Prism"
LABEL org.opencontainers.image.description="Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients."
LABEL org.opencontainers.image.source="https://github.com/AindriuB/data-prism"
LABEL org.opencontainers.image.documentation="https://aindriub.github.io/data-prism/"
LABEL org.opencontainers.image.licenses="Apache-2.0"

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
# --8<-- [start:loader-path]
ENV LOADER_PATH=/app/adapters
# --8<-- [end:loader-path]

# CONFIG_DIR: Spring Boot's own convention — a jar started with working
# directory /app auto-loads /app/config/application.yaml ahead of its packaged
# defaults. Mount a deployment configuration satisfying the dataprism.*
# vocabulary (docs/configuration.md) here, e.g.:
#   docker run -v /path/to/application.yaml:/app/config/application.yaml ...
# This image ships no application.yaml of its own — a fixture or placeholder
# security configuration baked into the image is exactly what task 21's
# fail-closed contract and this task both refuse to ship.

# VERSION only ever names the build stage's *source* jar below: the reactor
# version (task 36 owns the poms) is embedded in the filename `mvn package`
# actually produces, so COPY needs it once to find that file — and fails the
# build loudly if this default ever drifts from the reactor's own version
# without both being bumped together, rather than silently packaging nothing.
# The image's own copy of the jar is renamed to a fixed, version-agnostic
# path below (matching docker/server/Dockerfile's own convention) precisely
# so nothing past this COPY step, ENTRYPOINT included, ever needs to know
# this ARG's value.
ARG VERSION=0.3.0
COPY --from=build /workspace/data-prism-server/target/data-prism-server-${VERSION}.jar /app/data-prism-server.jar
RUN chown dataprism:dataprism /app/data-prism-server.jar

USER dataprism

EXPOSE 8080

# --server.port=8080 is a command-line argument, which Spring Boot ranks
# above a mounted /app/config/application.yaml's own server.port — an
# operator setting server.port there is silently overridden by this, not
# read. That matches docker/server/Dockerfile's own demo entrypoint; it is
# not changed here, only called out so it is not a surprise.
#
# Plain JSON array, deliberately: anything appended after the image name on
# `docker run` is appended to this array and reaches the JVM directly (e.g.
# `docker run <image> --dataprism.security.jwt.issuer=https://idp.example`),
# which is how an operator supplies the rest of the dataprism.* configuration
# docs/configuration.md describes without a config file. An earlier version
# of this line read `["sh", "-c", "exec java ... ${VERSION} ..."]` to expand
# a runtime VERSION placeholder; that shell became argv0 for every appended
# argument instead of passing them through to java, so an operator's flags
# were silently swallowed and the container refused as though nothing had
# been passed. Renaming the jar to a fixed path above removes the need for
# any runtime substitution here, so this can go back to a plain array and
# `docker run` arguments reach the JVM again.
ENTRYPOINT ["java", "-jar", "/app/data-prism-server.jar", "--server.port=8080"]

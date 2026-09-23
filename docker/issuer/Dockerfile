# Builds data-prism-quickstart-issuer from the repository root so the Maven
# reactor resolves its data-prism parent and sibling modules, then ships
# only the resulting executable Spring Boot jar.
#
# fixture-only evaluation issuer: this image mints tokens for the Compose
# quickstart's own synthetic audience/issuer only (see
# data-prism-quickstart-issuer's own application.yaml) — it is never a
# general-purpose identity provider, and nothing about it is meant to run
# outside `docker compose up` for this quickstart.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q -pl data-prism-quickstart-issuer -am package -DskipTests
# The reactor version is embedded in the filename `mvn package` produces
# (data-prism-quickstart-issuer-<version>.jar), and the pom.xml is the only
# place that version is written (task 36) — rather than duplicating it here
# as an ARG default that silently goes stale the moment the reactor cuts a
# new version (see docs/plan/tasks/55-publish-quickstart-images.md), find
# the one repackaged jar Maven actually produced and give it a fixed name.
# The spring-boot-maven-plugin repackage goal leaves the pre-repackage jar
# alongside it as *.jar.original, so that suffix is excluded explicitly.
RUN find data-prism-quickstart-issuer/target -maxdepth 1 -name '*.jar' ! -name '*.original' \
      -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
# The GHCR package page is the one place a user can encounter this image
# without ever reading this Dockerfile or the repository it lives in — so
# the evaluation-only status above is repeated here as the one field GHCR
# actually surfaces as the package's own summary.
LABEL org.opencontainers.image.description="Fixture-only evaluation JWT issuer for the Data Prism Compose quickstart. Mints tokens for the quickstart's own synthetic issuer/audience only — never a general-purpose identity provider, never for production use."
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8544
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

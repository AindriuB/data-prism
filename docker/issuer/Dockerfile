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

FROM eclipse-temurin:21-jre
WORKDIR /app
# VERSION only ever names the build stage's *source* jar: the reactor
# version is embedded in the filename `mvn package` produces, so COPY needs
# it once to find that file — matching docker/distribution/Dockerfile's own
# convention. Nothing past this COPY step needs to know this ARG's value.
ARG VERSION=0.2.0
COPY --from=build /workspace/data-prism-quickstart-issuer/target/data-prism-quickstart-issuer-${VERSION}.jar /app/app.jar
EXPOSE 8544
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

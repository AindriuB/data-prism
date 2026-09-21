# Builds data-prism-quickstart-fixtures from the repository root so the
# Maven reactor resolves its data-prism parent and sibling modules, then
# ships only the resulting executable Spring Boot jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q -pl data-prism-quickstart-fixtures -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# VERSION only ever names the build stage's *source* jar: the reactor
# version is embedded in the filename `mvn package` produces, so COPY needs
# it once to find that file — matching docker/distribution/Dockerfile's own
# convention. Nothing past this COPY step needs to know this ARG's value.
ARG VERSION=0.2.0
COPY --from=build /workspace/data-prism-quickstart-fixtures/target/data-prism-quickstart-fixtures-${VERSION}.jar /app/app.jar
EXPOSE 8543
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

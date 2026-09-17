# Builds data-prism-quickstart-fixtures from the repository root so the
# Maven reactor resolves its data-prism parent and sibling modules, then
# ships only the resulting executable Spring Boot jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q -pl data-prism-quickstart-fixtures -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/data-prism-quickstart-fixtures/target/data-prism-quickstart-fixtures-0.2.0.jar /app/app.jar
EXPOSE 8543
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

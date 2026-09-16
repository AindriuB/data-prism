# Builds data-prism-quickstart-issuer from the repository root so the Maven
# reactor resolves its data-prism parent and sibling modules, then ships
# only the resulting executable Spring Boot jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q -pl data-prism-quickstart-issuer -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/data-prism-quickstart-issuer/target/data-prism-quickstart-issuer-0.1.0.jar /app/app.jar
EXPOSE 8544
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

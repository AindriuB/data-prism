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

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/data-prism-server/target/data-prism-server-0.1.0.jar /app/data-prism-server.jar
COPY --from=build /workspace/data-prism-quickstart-extension/target/data-prism-quickstart-extension-0.1.0.jar /app/data-prism-quickstart-extension.jar
# Spring Boot's own convention: a jar started with working directory /app
# auto-loads /app/config/application.yaml ahead of its own packaged defaults.
COPY docker/server/application.yaml /app/config/application.yaml
EXPOSE 8080
ENTRYPOINT ["java", "-Dloader.path=/app/data-prism-quickstart-extension.jar", "-jar", "/app/data-prism-server.jar", "--server.port=8080"]

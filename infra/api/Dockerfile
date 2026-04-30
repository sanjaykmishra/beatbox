# Build context: repository root.
#   docker build -f infra/api/Dockerfile -t beat-api .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY api/gradlew api/gradlew
COPY api/gradle api/gradle
COPY api/settings.gradle api/build.gradle ./api/
RUN cd api && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY api/src api/src
COPY migrations migrations
RUN cd api && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

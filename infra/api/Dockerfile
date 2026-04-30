# Build context: repository root.
#   docker build -f infra/api/Dockerfile -t beat-api .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY api/gradlew api/gradlew
COPY api/gradle api/gradle
# Defensive against Windows checkouts: strip CRLF and ensure executable bit.
RUN sed -i 's/\r$//' api/gradlew && chmod +x api/gradlew
COPY api/settings.gradle api/build.gradle ./api/
RUN cd api && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY api/src api/src
COPY migrations migrations
COPY prompts prompts
RUN cd api && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends wget \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/api/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

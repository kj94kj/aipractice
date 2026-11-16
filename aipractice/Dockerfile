FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 spring
COPY --from=builder /workspace/build/libs/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

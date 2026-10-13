# syntax=docker/dockerfile:1

# ==============================================================================
# Stage 1: Build & Extract Spring Boot Layered JAR (Temurin JDK 21)
# ==============================================================================
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
COPY config config

RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package && \
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ==============================================================================
# Stage 2: Minimal Hardened Runtime Image (Temurin JRE 21, non-root, fontconfig)
# ==============================================================================
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system --gid 10001 appgroup && \
    useradd --system --uid 10001 --gid appgroup --no-create-home appuser && \
    chown -R appuser:appgroup /app

COPY --from=build --chown=appuser:appgroup /workspace/extracted/dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=appuser:appgroup /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=appuser:appgroup /workspace/extracted/application/ ./

USER appuser:appgroup

ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=5 \
    CMD curl -fsS http://localhost:${PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

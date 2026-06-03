# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Builder
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy POM first for layer caching.
# Dependencies are only re-downloaded when POM changes, not when source changes.
COPY pom.xml .

# Download all dependencies offline.
# This layer is cached until pom.xml changes.
RUN mvn dependency:go-offline -q

# Copy source code
COPY src ./src

# Build the JAR. Skip tests — tests run in CI, not inside Docker build.
RUN mvn clean package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security hardening: run as non-root user
RUN addgroup -S scalekit && adduser -S scalekit -G scalekit

WORKDIR /app

# Copy only the fat JAR from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Ensure the non-root user owns the JAR
RUN chown scalekit:scalekit app.jar

# Switch to non-root user
USER scalekit

# Expose application port
EXPOSE 8080

# Container-level health check (Kubernetes also uses its own probes)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1

# JVM tuning for containerised environments:
#   UseContainerSupport  — respect cgroup memory/cpu limits
#   MaxRAMPercentage     — use up to 75% of container memory for heap
#   InitialRAMPercentage — start heap at 50% to reduce startup time
#   ExitOnOutOfMemoryError — fail fast; let K8s restart the pod
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:InitialRAMPercentage=50.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Dspring.profiles.active=prod", \
    "-jar", "app.jar"]
